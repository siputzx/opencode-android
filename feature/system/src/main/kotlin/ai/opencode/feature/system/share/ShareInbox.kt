package ai.opencode.feature.system.share

import ai.opencode.feature.system.hasSameDirectoryMetadata
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.files.ComposerFileStaging
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's side of the share target handoff (ShareHandoff.kt). [ShareTargetsPublication] is built
 * by the drawer (`HomeFeature`).
 */
class ShareInboxClient(
    /** Removes and returns every pending share, oldest first. */
    val consume: suspend () -> List<ReceivedShare>,
    /** Publishes the sessions the share sheet offers. */
    val publishTargets: (ShareTargets) -> Unit,
    /** Shares the current server's credentials with the share sheet, so it can send. */
    val publishCredentials: (credentials: ShareCredentials) -> Unit,
    /** Forgets published sessions and credentials for a server that is no longer saved. */
    val removeTargets: (serverID: String) -> Unit,
    /** Offers a session in the share sheet's suggestions ([ShareSuggestion]). */
    val donateSuggestion: (serverID: String, sessionID: String, title: String) -> Unit = { _, _, _ -> },
    /** Takes deleted sessions out of the suggestions. */
    val removeSuggestions: (serverID: String, sessionIDs: List<String>) -> Unit = { _, _ -> },
) {
    companion object Key : DependencyKey<ShareInboxClient> {
        override val liveValue: ShareInboxClient by lazy { live(ShareCredentialStore.live) }

        override val testValue: ShareInboxClient = ShareInboxClient(
            consume = { emptyList() },
            publishTargets = {},
            publishCredentials = {},
            removeTargets = {},
        )

        /** One thread for target writes and removals, so they land in order. */
        private val targetsQueue = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ai.opencode.share-targets").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }

        /** Suggestions are system calls: off the caller's thread, in order. */
        private val suggestionsQueue = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ai.opencode.share-suggestions").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }

        fun live(credentials: ShareCredentialStore): ShareInboxClient {
            val context = dependency(AppContext).applicationContext
            return ShareInboxClient(
                consume = {
                    withContext(Dispatchers.IO) {
                        ShareHandoffStore.live?.consumeAll(
                            now = Instant.now(),
                            filesDirectory = ComposerFileStaging.directory,
                        ) ?: emptyList()
                    }
                },
                publishTargets = { targets ->
                    targetsQueue.execute {
                        if (!targets.write(File(context.filesDir, "share-targets.json"))) {
                            AppLog.app.error("Couldn’t write share targets")
                        }
                    }
                },
                publishCredentials = { value -> ShareCredentials.publish(value, credentials) },
                removeTargets = { serverID ->
                    if (credentials.load()?.serverID == serverID) credentials.remove()
                    targetsQueue.execute {
                        val file = File(context.filesDir, "share-targets.json")
                        if (ShareTargets.load(file)?.serverID == serverID) ShareTargets.remove(file)
                    }
                    suggestionsQueue.execute { ShareSuggestion.removeAll(context, ShareConversation.serverKey(serverID)) }
                },
                donateSuggestion = { serverID, sessionID, _ ->
                    suggestionsQueue.execute {
                        ShareSuggestion.donate(context, ShareConversation.serverKey(serverID), sessionID)
                    }
                },
                removeSuggestions = { serverID, sessionIDs ->
                    suggestionsQueue.execute {
                        ShareSuggestion.remove(context, ShareConversation.serverKey(serverID), sessionIDs)
                    }
                },
            )
        }
    }
}

/** Writes [credentials] unless the item already holds them. */
fun ShareCredentials.Companion.publish(credentials: ShareCredentials, store: ShareCredentialStore) {
    if (store.load() == credentials) return
    store.save(credentials)
}

/**
 * Where a share lands in the app: its session, or, for a New Session or a session since deleted, a
 * New Session draft ([sessionID] null) in [projectID]. The session area maps it onto its
 * `SharedDraftTarget`.
 */
data class ResolvedShareTarget(val sessionID: String?, val projectID: String?)

/** A deleted session's items start a new session in the same project. */
fun resolvedShareTarget(target: ShareHandoff.Target, sessions: List<OpenCodeSession>): ResolvedShareTarget =
    when (target) {
        is ShareHandoff.Target.Session ->
            if (sessions.any { it.id == target.id }) ResolvedShareTarget(target.id, null)
            else ResolvedShareTarget(null, target.projectID)
        is ShareHandoff.Target.NewSession -> ResolvedShareTarget(null, target.projectID)
    }

/**
 * What the share targets are built from, with a cheap equality for change detection that ignores
 * Catch Up's `viewedMilliseconds` / `idleMilliseconds` mutations. The drawer
 * (`HomeFeature.State.shareTargetsPublication`) builds it.
 */
class ShareTargetsPublication(
    val hasConnected: Boolean,
    /** The server itself, not its derived ID and name: both are built from its addresses. */
    val server: OpenCodeServer,
    val sessions: List<OpenCodeSession>,
    val projects: List<OpenCodeProject>,
    val pinnedSessionKeys: List<String>,
    val lastViewedSessionID: String?,
    val draftProject: OpenCodeProject?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShareTargetsPublication) return false
        return hasConnected == other.hasConnected &&
            (server == other.server ||
                (server.persistenceID == other.server.persistenceID && server.displayName == other.server.displayName)) &&
            lastViewedSessionID == other.lastViewedSessionID &&
            draftProject == other.draftProject &&
            pinnedSessionKeys == other.pinnedSessionKeys &&
            projects == other.projects &&
            sessions.hasSameDirectoryMetadata(other.sessions)
    }

    override fun hashCode(): Int = (hasConnected.hashCode() * 31 + server.persistenceID.hashCode()) * 31 + sessions.size

    fun targets(): ShareTargets {
        val projectsByID = HashMap<String, OpenCodeProject>()
        for (project in projects) projectsByID.putIfAbsent(project.id, project)
        fun target(session: OpenCodeSession) = ShareTargets.Session(
            id = session.id,
            title = session.title,
            projectID = session.projectID,
            projectName = projectsByID[session.projectID]?.name ?: File(session.directory).name,
            directory = session.directory,
        )
        val serverID = server.persistenceID
        fun sessionKey(id: String) = "$serverID|$id"
        val sessions = sessions.filterNot { it.isArchived }
        val keys = pinnedSessionKeys
        val pinnedSet = keys.toSet()
        val indexed = if (pinnedSet.isEmpty()) emptyMap() else {
            val map = HashMap<String, OpenCodeSession>()
            for (session in sessions) map.putIfAbsent(sessionKey(session.id), session)
            map
        }
        val pinned = keys.mapNotNull { indexed[it] }
        val recents = (if (pinnedSet.isEmpty()) sessions else sessions.filter { sessionKey(it.id) !in pinnedSet })
            .sortedByDescending { it.updatedAt }
            .take(ShareTargets.MAXIMUM_RECENTS)
        val sortedProjects = if (projects.size <= 1) projects else {
            val lastUsed = HashMap<String, Instant>(projects.size)
            for (session in sessions) {
                val existing = lastUsed[session.projectID]
                if (existing != null && existing >= session.updatedAt) continue
                lastUsed[session.projectID] = session.updatedAt
            }
            projects.sortedByDescending { lastUsed[it.id] ?: Instant.MIN }
        }
        val offered = mutableListOf<ShareTargets.Project>()
        for (project in listOfNotNull(draftProject) + sortedProjects) {
            if (offered.any { it.id == project.id } || offered.size >= ShareTargets.MAXIMUM_PROJECTS) continue
            offered.add(ShareTargets.Project(project.id, project.name, project.directory))
        }
        return ShareTargets(
            serverID = serverID,
            serverName = server.displayName,
            lastViewedSessionID = lastViewedSessionID,
            newSessionProject = offered.firstOrNull(),
            pinned = pinned.map(::target),
            recents = recents.map(::target),
            projects = offered,
        )
    }
}
