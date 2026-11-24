package ai.opencode.server.live

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.R
import ai.opencode.server.SelfViewReceipts
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionPage
import ai.opencode.server.model.OpenCodeSessionPageRequest
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.withRetryPolicy
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable

internal val WAIT_POLL_TIMEOUT = 25.seconds

/**
 * A long poll re-armed until the run settles. Each `/wait` and each active probe is sent
 * once: this loop is the wait's retry layer. A poll that runs out while the session is
 * active re-arms at once; a fast failure waits the shared session-wait backoff, for at most
 * 12 fast failures. A probe that fails ends the wait. A caller that owns the retries
 * (`NetworkRetry.policy == .once`) gets a fast failure back after its one probe.
 */
internal suspend fun requestWaitForIdle(server: OpenCodeServer, sessionID: String, clock: Clock) {
    val callerOwnsRetries = NetworkRetry.policy.current() == RetryPolicy.once
    val maxPasses = 12
    var passes = 0
    while (currentCoroutineContext().isActive) {
        val started = clock.now()
        try {
            perform(
                "/api/experimental/session/$sessionID/wait",
                method = "POST",
                timeout = WAIT_POLL_TIMEOUT,
                idempotency = RequestIdempotency.Idempotent,
                retry = RetryPolicy.once,
                longPoll = true,
                server = server,
            )
            return
        } catch (waitError: OpenCodeClientError) {
            val fast = clock.now() - started < 20.seconds
            if (fast) passes += 1
            if (passes >= maxPasses || !waitError.isRetryableWaitError || !currentCoroutineContext().isActive) throw waitError
            val isStillActive = try {
                withRetryPolicy(RetryPolicy.once) { requestIsSessionActive(server, sessionID) }
            } catch (_: OpenCodeClientError) {
                throw waitError
            }
            if (!isStillActive) return
            // A long poll that ran out on a live run re-arms: that's waiting.
            if (!fast) continue
            if (callerOwnsRetries) throw waitError
            val delay = RetryPolicy.sessionWait.delay(passes, NetworkRetry.environment.current().random())
            clock.sleep(delay)
        }
    }
}

internal suspend fun requestIsSessionActive(server: OpenCodeServer, sessionID: String): Boolean {
    val active = request<DataPayload<Map<String, ActiveSessionPayload>>>("/api/session/active", server = server)
    return active.data[sessionID] != null
}

internal suspend fun requestInterrupt(server: OpenCodeServer, sessionID: String): Boolean =
    request<InterruptPayload>(
        "/api/session/$sessionID/interrupt",
        query = listOf("resume" to "true"),
        method = "POST",
        server = server,
    ).interrupted

internal suspend fun requestSearchSessions(server: OpenCodeServer, query: String, limit: Int): List<OpenCodeSession> =
    requestRootSessions(server, search = query, includeEmptySearch = true, limit = limit)

/**
 * The drawer's search and its older pages. A cursor carries the filters of the read it came
 * from, so a next page sends only the cursor.
 */
internal suspend fun requestSessionPage(
    server: OpenCodeServer,
    request: OpenCodeSessionPageRequest,
    limit: Int,
): OpenCodeSessionPage {
    val query = mutableListOf("limit" to limit.toString())
    when (request) {
        is OpenCodeSessionPageRequest.Search -> {
            query += "parentID" to "null"
            query += "archived" to "false"
            query += "order" to "desc"
            query += "search" to request.text
        }
        is OpenCodeSessionPageRequest.After -> query += "cursor" to request.cursor
    }
    val payload = request<SessionsPayload>("/api/session", query = query, server = server)
    // A server that ignores `archived=false` still lists them.
    val sessions = mutableListOf<OpenCodeSession>()
    for (item in payload.data) {
        val session = session(item)
        if (!session.isArchived && session.parentID == null) sessions += session
    }
    // An empty page ends the list even if the server offers another cursor.
    val next = if (payload.data.isEmpty()) null else payload.cursor?.next
    return OpenCodeSessionPage(sessions, next)
}

/** Desktop's mention list: root sessions that aren't archived, newest first. */
internal suspend fun requestMentionSessions(server: OpenCodeServer, search: String, limit: Int): List<OpenCodeSession> =
    requestRootSessions(server, search = search, includeEmptySearch = false, limit = limit)

private suspend fun requestRootSessions(
    server: OpenCodeServer,
    search: String,
    includeEmptySearch: Boolean,
    limit: Int,
): List<OpenCodeSession> {
    val query = mutableListOf("parentID" to "null", "archived" to "false", "order" to "desc", "limit" to limit.toString())
    if (includeEmptySearch || search.isNotEmpty()) query += "search" to search
    return request<DataPayload<List<SessionPayload>>>("/api/session", query = query, server = server)
        .data.map(::session).filterNot { it.isArchived }
}

/** Desktop's Archived list and its search: archived root sessions, newest first. */
internal suspend fun requestArchivedSessions(server: OpenCodeServer, search: String, limit: Int): List<OpenCodeSession> {
    val query = mutableListOf("parentID" to "null", "archived" to "true", "order" to "desc", "limit" to limit.toString())
    if (search.isNotEmpty()) query += "search" to search
    return request<DataPayload<List<SessionPayload>>>("/api/session", query = query, server = server).data.map(::session)
}

internal suspend fun requestGetSession(server: OpenCodeServer, sessionID: String): OpenCodeSession =
    session(request<DataPayload<SessionPayload>>("/api/session/$sessionID", server = server).data)

@Serializable private data class TitleBody(val title: String)
@Serializable private data class DirectoryBody(val directory: String)
@Serializable private data class ArchiveBody(val archived: Boolean)
@Serializable private data class IdleBody(val idle: Long)
@Serializable private data class MessageIDBody(val messageID: String)
@Serializable private data class CreateBody(val id: String? = null, val location: Location) {
    @Serializable data class Location(val directory: String)
}

internal suspend fun requestRenameSession(server: OpenCodeServer, sessionID: String, title: String) {
    // An empty title has the server generate one before it answers: running the title model
    // again (and billing it again) isn't safe to repeat. A typed title is just set.
    perform(
        "/api/session/$sessionID",
        method = "PATCH",
        body = encodedBody(TitleBody(title)),
        timeout = if (title.isEmpty()) 60.seconds else 10.seconds,
        idempotency = if (title.isEmpty()) RequestIdempotency.Unsafe else RequestIdempotency.Idempotent,
        server = server,
    )
}

internal suspend fun requestMoveSession(server: OpenCodeServer, sessionID: String, directory: String) {
    // A session that's already there gets another move queued, so a repeat isn't harmless: it goes
    // again only when it provably never left.
    perform(
        "/api/session/$sessionID/move",
        method = "POST",
        body = encodedBody(DirectoryBody(directory)),
        timeout = 15.seconds,
        idempotency = RequestIdempotency.Unsafe,
        server = server,
    )
}

internal suspend fun requestArchiveSession(server: OpenCodeServer, sessionID: String) {
    requestSetArchived(server, sessionID, archived = true)
    // Servers before 2.1.0 answer 204 and leave the session unarchived.
    val session = try {
        request<DataPayload<SessionPayload>>("/api/session/$sessionID", server = server).data
    } catch (error: OpenCodeClientError) {
        return
    }
    if (session.time.archived == null) throw OpenCodeClientError(localized(R.string.server_error_archiving_unsupported))
}

internal suspend fun requestUnarchiveSession(server: OpenCodeServer, sessionID: String) =
    requestSetArchived(server, sessionID, archived = false)

private suspend fun requestSetArchived(server: OpenCodeServer, sessionID: String, archived: Boolean) {
    // Setting it again changes nothing, so a repeat is safe.
    perform(
        "/api/session/$sessionID",
        method = "PATCH",
        body = encodedBody(ArchiveBody(archived = archived)),
        timeout = 10.seconds,
        idempotency = RequestIdempotency.Idempotent,
        server = server,
    )
}

internal suspend fun requestCompactSession(server: OpenCodeServer, sessionID: String) {
    perform("/api/session/$sessionID/compact", method = "POST", body = EMPTY_JSON_OBJECT, server = server)
}

internal suspend fun requestStageRevert(server: OpenCodeServer, sessionID: String, messageID: String) {
    // Staging the same boundary again restores the same snapshot, but a repeat behind a newer
    // one would move it: sent again only if it never left.
    perform("/api/session/$sessionID/revert/stage", method = "POST", body = encodedBody(MessageIDBody(messageID)), server = server)
}

internal suspend fun requestClearRevert(server: OpenCodeServer, sessionID: String) {
    // With nothing staged, clearing again changes nothing.
    perform("/api/session/$sessionID/revert", method = "DELETE", idempotency = RequestIdempotency.Idempotent, server = server)
}

internal suspend fun requestForkSession(server: OpenCodeServer, sessionID: String): OpenCodeSession =
    session(request<DataPayload<SessionPayload>>("/api/session/$sessionID/fork", method = "POST", body = EMPTY_JSON_OBJECT, server = server).data)

internal suspend fun requestExportSession(server: OpenCodeServer, sessionID: String): ByteArray =
    perform("/api/experimental/session/$sessionID/export", timeout = 60.seconds, server = server).data

internal suspend fun requestViewSession(server: OpenCodeServer, sessionID: String, idle: Long) {
    val receipts = dependency(SelfViewReceipts)
    val receiptID = receipts.begin(server.persistenceID, sessionID, idle)
    try {
        // A read receipt: the server keeps the newest watermark, so it repeats safely.
        perform(
            "/api/session/$sessionID/view", method = "POST", body = encodedBody(IdleBody(idle)),
            idempotency = RequestIdempotency.Idempotent, server = server,
        )
        receipts.answered(receiptID)
    } catch (error: Throwable) {
        // Cancelled, it may still have landed: its echo is still this client's.
        if (error is CancellationException || !currentCoroutineContext().isActive) {
            receipts.answered(receiptID)
        } else {
            receipts.failed(receiptID)
        }
        throw error
    }
}

internal suspend fun requestCreateSession(server: OpenCodeServer, directory: String, id: String?): OpenCodeSession {
    // With a client-made ID the server returns the existing session on a repeat; without one a retry could create a second.
    val payload = request<DataPayload<SessionPayload>>(
        "/api/session",
        method = "POST",
        body = encodedBody(CreateBody(id, CreateBody.Location(directory))),
        idempotency = if (id == null) RequestIdempotency.Unsafe else RequestIdempotency.Idempotent,
        server = server,
    )
    return session(payload.data)
}

internal suspend fun requestDeleteSession(server: OpenCodeServer, sessionID: String) {
    perform("/api/session/$sessionID", method = "DELETE", server = server)
}

internal suspend fun requestMoveToBackground(server: OpenCodeServer, sessionID: String) {
    perform("/api/session/$sessionID/background", method = "POST", server = server)
}
