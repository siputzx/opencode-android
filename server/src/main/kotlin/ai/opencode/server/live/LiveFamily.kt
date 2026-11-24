package ai.opencode.server.live

import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.R
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.SessionTitle
import ai.opencode.server.model.instantFromMillis
import ai.opencode.server.model.nonEmpty
import ai.opencode.server.model.trimmedWhitespace
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.withRetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Concurrent `/api/session/{id}` metadata reads while walking unknown active ancestry. */
internal const val ACTIVE_ANCESTRY_CONCURRENCY = 2

/** Lookups the family walk spends finding running descendants it missed. */
private const val ACTIVE_ANCESTRY_LIMIT = 24

private typealias ActivePayload = DataPayload<Map<String, ActiveSessionPayload>>

internal suspend fun requestHomeSnapshot(server: OpenCodeServer): OpenCodeHomeSnapshot {
    try {
        return coroutineScope {
            // Failures are values until awaited, in order: a projects error wins over a sessions
            // error that came back first.
            val projects = async { capturedRequest<List<ProjectPayload>>("/api/project", server = server) }
            val sessions = async {
                capturedRequest<SessionsPayload>(
                    "/api/session",
                    query = listOf("parentID" to "null", "archived" to "false", "order" to "desc", "limit" to "50"),
                    server = server,
                )
            }
            val active = async { capturedRequest<ActivePayload>("/api/session/active", server = server) }
            val projectPayloads = projects.await().getOrThrow()
            val sessionPayload = sessions.await().getOrThrow()
            // A server that ignores `archived=false` still lists them.
            val roots = sessionPayload.data.map(::session).filterNot { it.isArchived }
            val activeIDs = active.await().getOrThrow().data.keys
            val parents = cachedActiveSessionParents(server, activeIDs, roots.map { it.id })
            OpenCodeHomeSnapshot(
                projects = projectPayloads.map { it.project() },
                sessions = roots,
                activeSessionIDs = activeIDs,
                activeSessionParents = parents.ifEmpty { null },
                nextCursor = if (sessionPayload.data.isEmpty()) null else sessionPayload.cursor?.next,
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: OpenCodeClientError) {
        throw error
    } catch (_: Exception) {
        throw OpenCodeClientError(localized(R.string.server_error_load_projects_and_sessions))
    }
}

private fun cachedActiveSessionParents(server: OpenCodeServer, activeIDs: Set<String>, roots: List<String>): Map<String, String> {
    val key = server.persistenceID
    val cache = OpenCodeReadCache.shared
    cache.noteActiveSnapshot(key)
    val known = cache.sessionParents(key).toMutableMap()
    for (root in roots) {
        // Cached when running, so a family read's ancestry pass skips it.
        if (root in activeIDs && known[root] == null) cache.storeSessionParent(null, root, key)
        known[root] = KnownParent(null)
    }
    val parents = LinkedHashMap<String, String>()
    for (start in activeIDs) {
        var id = start
        val seen = HashSet<String>()
        while (seen.add(id)) {
            val parent = known[id]?.parent ?: break
            parents[id] = parent
            id = parent
        }
    }
    return parents
}

internal suspend fun lookupSession(server: OpenCodeServer, id: String): OpenCodeSession? {
    val key = server.persistenceID
    val cache = OpenCodeReadCache.shared
    return NetworkRetry.run(
        NetworkRetry.currentPolicy(),
        idempotency = RequestIdempotency.Idempotent,
        label = "GET /api/session/$id",
    ) {
        currentCoroutineContext().ensureActive()
        if (cache.isSessionMissing(id, key)) return@run null
        val result = cache.lookupSession(key, id) operation@{
            if (cache.isSessionMissing(id, key)) return@operation Result.success(null)
            cache.acquireLookupPermit(key, ACTIVE_ANCESTRY_CONCURRENCY)
            try {
                val response = withRetryPolicy(RetryPolicy.once) {
                    request<DataPayload<SessionPayload>>("/api/session/$id", server = server)
                }
                cache.storeSessionParent(response.data.parentID, id, key)
                Result.success(session(response.data))
            } catch (error: OpenCodeClientError) {
                if (error.statusCode == 404) {
                    cache.storeMissingSession(id, key)
                    Result.success(null)
                } else {
                    Result.failure(error)
                }
            } finally {
                cache.releaseLookupPermit(key)
            }
        }
        currentCoroutineContext().ensureActive()
        result.getOrElse { throw it }
    }
}

/**
 * Metadata-only lookups, bounded to active ancestry: never location-scoped reads or a crawl
 * of historic families. Parents never change, so only unseen sessions are fetched, and each
 * answer is kept as it lands.
 */
internal suspend fun requestActiveSessionParents(server: OpenCodeServer, activeIDs: Set<String>, roots: List<String>): Map<String, String> {
    val key = server.persistenceID
    val cache = OpenCodeReadCache.shared
    val known = cache.sessionParents(key).toMutableMap()
    for (root in roots) {
        if (root in activeIDs && known[root] == null) cache.storeSessionParent(null, root, key)
        known[root] = KnownParent(null)
    }
    val fetched = HashSet<String>()
    while (true) {
        val parents = LinkedHashMap<String, String>()
        val unknown = HashSet<String>()
        for (start in activeIDs) {
            var id = start
            val seen = HashSet<String>()
            while (seen.add(id)) {
                val entry = known[id]
                if (entry == null) {
                    if (id !in fetched) unknown += id
                    break
                }
                val parent = entry.parent ?: break
                parents[id] = parent
                id = parent
            }
        }
        if (unknown.isEmpty()) return parents
        val batch = unknown.sorted().take(6)
        fetched += batch
        val results = try {
            coroutineScope {
                batch.map { id ->
                    async {
                        cache.sessionParent(id, key)?.let { return@async id to it }
                        val session = lookupSession(server, id) ?: return@async id to null
                        id to KnownParent(session.parentID)
                    }
                }.awaitAll()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: OpenCodeClientError) {
            throw error
        } catch (_: Exception) {
            throw OpenCodeReadCache.ancestryError
        }
        // A deleted session ends its chain here.
        for ((id, parent) in results) known[id] = parent ?: KnownParent(null)
    }
}

internal suspend fun requestSessionFamily(server: OpenCodeServer, root: OpenCodeSession): OpenCodeSessionFamily {
    try {
        return coroutineScope {
            // Captured: an active-status failure surfaces after the walk (whose own error wins),
            // instead of cancelling it.
            val active = async { capturedRequest<ActivePayload>("/api/session/active", server = server) }
            val sessions = mutableListOf(root)
            val queue = mutableListOf(root.id)
            var queueIndex = 0
            val visited = hashSetOf(root.id)
            while (queueIndex < queue.size && sessions.size < 100) {
                // Bound child discovery independently of the single active-status request.
                // Commit responses in queue order, not completion order.
                val end = minOf(queueIndex + 6, queue.size)
                val parents = queue.subList(queueIndex, end).toList()
                queueIndex = end
                coroutineScope {
                    val pages = parents.map { parentID ->
                        async {
                            capturedRequest<DataPayload<List<SessionPayload>>>(
                                "/api/session",
                                query = listOf("parentID" to parentID, "order" to "desc", "limit" to "100"),
                                server = server,
                            )
                        }
                    }
                    for (page in pages) {
                        // Match the serial traversal: keep the whole last response, even past 100,
                        // but consume no other parent's result (or error) after the cap.
                        if (sessions.size >= 100) break
                        val payload = page.await().getOrThrow()
                        for (child in payload.data.map(::session)) {
                            if (visited.add(child.id)) {
                                sessions += child
                                queue += child.id
                            }
                        }
                    }
                    if (sessions.size >= 100) coroutineContext.cancelChildren()
                }
            }
            val activeIDs = active.await().getOrThrow().data.keys
            val running = activeDescendants(server, sessions, activeIDs)
            OpenCodeSessionFamily(sessions + running, activeIDs)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: OpenCodeClientError) {
        throw error
    } catch (_: Exception) {
        throw OpenCodeClientError(localized(R.string.server_error_load_session_family))
    }
}

/**
 * Running descendants the capped walk missed: past a root's 100th subagent, or under a
 * parent it never listed. Each running session's parents are followed until they meet the
 * family (kept, ancestors first) or another root (skipped). A failed lookup ends the search.
 */
private suspend fun activeDescendants(server: OpenCodeServer, family: List<OpenCodeSession>, activeIDs: Set<String>): List<OpenCodeSession> {
    val key = server.persistenceID
    val parents = OpenCodeReadCache.shared.sessionParents(key).toMutableMap()
    for (member in family) parents[member.id] = KnownParent(member.parentID)
    val members = family.map { it.id }.toMutableSet()
    val fetched = HashMap<String, OpenCodeSession>()
    val attempted = HashSet<String>()
    val found = mutableListOf<OpenCodeSession>()
    while (true) {
        val unknown = HashSet<String>()
        for (start in activeIDs.sorted()) {
            if (start in members) continue
            val chain = mutableListOf<String>()
            var id = start
            var reachesFamily = false
            while (id !in chain) {
                if (id in members) {
                    reachesFamily = true
                    break
                }
                chain += id
                val entry = parents[id]
                if (entry == null) {
                    if (id !in attempted) unknown += id
                    break
                }
                id = entry.parent ?: break
            }
            if (!reachesFamily) continue
            val missing = chain.filter { fetched[it] == null }
            if (missing.isNotEmpty()) {
                unknown += missing.filter { it !in attempted }
                continue
            }
            for (member in chain.asReversed()) if (members.add(member)) fetched[member]?.let { found += it }
        }
        if (unknown.isEmpty() || attempted.size >= ACTIVE_ANCESTRY_LIMIT) return found
        val batch = unknown.sorted().take(minOf(6, ACTIVE_ANCESTRY_LIMIT - attempted.size))
        attempted += batch
        try {
            val results = coroutineScope { batch.map { id -> async { id to lookupSession(server, id) } }.awaitAll() }
            for ((id, session) in results) {
                if (session == null) {
                    parents[id] = KnownParent(null)
                    continue
                }
                parents[id] = KnownParent(session.parentID)
                fetched[id] = session
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val summary = (error as? OpenCodeClientError)?.logSummary ?: "client error"
            AppLog.network.notice { "Family active ancestry stopped after ${attempted.size} lookups: $summary" }
            return found
        }
    }
}

internal fun session(payload: SessionPayload) = OpenCodeSession(
    id = payload.id,
    serverTitle = payload.title?.trimmedWhitespace()?.nonEmpty ?: SessionTitle.placeholder,
    projectID = payload.projectID,
    directory = payload.location.directory,
    updatedAt = instantFromMillis(payload.time.updated),
    idleMilliseconds = payload.time.idle,
    viewedMilliseconds = payload.time.viewed,
    model = payload.model,
    parentID = payload.parentID,
    agent = payload.agent,
    cost = payload.cost,
    revertMessageID = payload.revert?.messageID,
    archivedMilliseconds = payload.time.archived?.toLong(),
)
