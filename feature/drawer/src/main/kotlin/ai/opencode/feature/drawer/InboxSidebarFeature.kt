package ai.opencode.feature.drawer

import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.strings.localized
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.log.logSummary
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.feature.drawer.icons.resolved
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Inbox Sidebar (experimental): the drawer as an inbox, one item per session, sorted Needs your
 * input, then unread, then newest. Rows come from Home's state
 * ([HomeFeature.State.inboxSidebarRows]); this reducer owns Archive and Unarchive, the actions Home
 * doesn't already have. Android: archived sessions can be restored, as on desktop.
 */
class InboxSidebarFeature : Reducer<InboxSidebarFeature.State, InboxSidebarFeature.Action> {
    data class Status(
        /**
         * Archives in flight: hidden at once. Once the server confirms, Home stamps the session's
         * `time.archived` and the ID goes, so snapshots decide from then on (a session restored on
         * desktop comes back).
         */
        val archivedIDs: Set<String> = emptySet(),
        val archiveError: DrawerFailure? = null,
        /** The server's archived sessions, newest first (the Archived page). */
        val archived: List<OpenCodeSession> = emptyList(),
        /** The Archived page's search, and the server's matches once they land (null while it's empty). */
        val archivedSearch: String = "",
        val archivedMatches: List<OpenCodeSession>? = null,
        /** Unarchives in flight: hidden from the Archived section at once. */
        val unarchivingIDs: Set<String> = emptySet(),
    )

    data class State(val server: OpenCodeServer, val status: Status = Status())

    sealed interface Action {
        data class ArchiveTapped(val sessionID: String, val title: String) : Action
        data class ArchiveResponse(val sessionID: String, val title: String, val error: OpenCodeClientError?) : Action
        data object ArchiveErrorCleared : Action
        data object ArchivedListRequested : Action
        data class ArchivedListResponse(val sessions: List<OpenCodeSession>) : Action
        data class ArchivedSearchChanged(val text: String) : Action
        data class ArchivedSearchResponse(val search: String, val sessions: List<OpenCodeSession>) : Action
        /** Home lists it (it isn't in the session list) for the tap or delete that follows. */
        data class ArchivedRowTapped(val sessionID: String) : Action
        data class UnarchiveTapped(val sessionID: String, val title: String) : Action
        data class UnarchiveResponse(val sessionID: String, val title: String, val error: OpenCodeClientError?) : Action
    }

    sealed interface CancelID {
        data class Archive(val sessionID: String) : CancelID
        data object ArchiveError : CancelID
        data object ArchivedList : CancelID
        data object ArchivedSearch : CancelID
        data class Unarchive(val sessionID: String) : CancelID
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.ArchiveTapped -> {
            val sessionID = action.sessionID
            if (sessionID in state.status.archivedIDs) {
                Reduced(state)
            } else {
                dependency(HapticsClient).play(Haptic.tap)
                val client = dependency(OpenCodeClient)
                val server = state.server
                val title = action.title
                Reduced(
                    state.copy(status = state.status.copy(archivedIDs = state.status.archivedIDs + sessionID, archiveError = null)),
                    Effect.merge(
                        Effect.cancel(CancelID.ArchiveError),
                        Effect.run<Action> { send ->
                            try {
                                client.archiveSession(server, sessionID)
                                send(Action.ArchiveResponse(sessionID, title, null))
                            } catch (error: OpenCodeClientError) {
                                send(Action.ArchiveResponse(sessionID, title, error))
                            }
                        }.cancellable(CancelID.Archive(sessionID), cancelInFlight = true),
                    ),
                )
            }
        }

        is Action.ArchiveResponse -> {
            val next = state.copy(status = state.status.copy(archivedIDs = state.status.archivedIDs - action.sessionID))
            val error = action.error
            if (error == null) {
                Reduced(next)
            } else {
                AppLog.network.notice { "Archive failed: ${error.logSummary}" }
                failed(next, DrawerFailure(DrawerFailure.Action.archive, action.title, error.message))
            }
        }

        Action.ArchiveErrorCleared -> Reduced(state.copy(status = state.status.copy(archiveError = null)))

        Action.ArchivedListRequested -> {
            val client = dependency(OpenCodeClient)
            val server = state.server
            Reduced(
                state,
                Effect.run<Action> { send ->
                    try {
                        send(Action.ArchivedListResponse(client.archivedSessions(server, "", ARCHIVED_LIMIT)))
                    } catch (error: OpenCodeClientError) {
                        AppLog.network.notice { "Archived list failed: ${error.logSummary}" }
                    }
                }.cancellable(CancelID.ArchivedList, cancelInFlight = true),
            )
        }

        is Action.ArchivedListResponse -> Reduced(state.copy(status = state.status.copy(archived = action.sessions)))

        // As desktop's: the server searches every archived session, not just the 50 listed.
        is Action.ArchivedSearchChanged -> {
            val search = action.text.trim()
            val status = state.status.copy(archivedSearch = action.text)
            if (search.isEmpty()) {
                Reduced(state.copy(status = status.copy(archivedMatches = null)), Effect.cancel(CancelID.ArchivedSearch))
            } else {
                val client = dependency(OpenCodeClient)
                val clock = dependency(Clock)
                val server = state.server
                Reduced(
                    state.copy(status = status),
                    Effect.run<Action> { send ->
                        clock.sleep(HomeFeature.sessionSearchDebounce)
                        try {
                            send(Action.ArchivedSearchResponse(search, client.archivedSessions(server, search, ARCHIVED_LIMIT)))
                        } catch (error: OpenCodeClientError) {
                            AppLog.network.notice { "Archived search failed: ${error.logSummary}" }
                        }
                    }.cancellable(CancelID.ArchivedSearch, cancelInFlight = true),
                )
            }
        }

        is Action.ArchivedSearchResponse ->
            if (action.search != state.status.archivedSearch.trim()) Reduced(state)
            else Reduced(state.copy(status = state.status.copy(archivedMatches = action.sessions)))

        is Action.ArchivedRowTapped -> Reduced(state)

        is Action.UnarchiveTapped -> {
            val sessionID = action.sessionID
            if (sessionID in state.status.unarchivingIDs) {
                Reduced(state)
            } else {
                dependency(HapticsClient).play(Haptic.tap)
                val client = dependency(OpenCodeClient)
                val server = state.server
                val title = action.title
                Reduced(
                    state.copy(status = state.status.copy(unarchivingIDs = state.status.unarchivingIDs + sessionID, archiveError = null)),
                    Effect.merge(
                        Effect.cancel(CancelID.ArchiveError),
                        Effect.run<Action> { send ->
                            try {
                                client.unarchiveSession(server, sessionID)
                                send(Action.UnarchiveResponse(sessionID, title, null))
                            } catch (error: OpenCodeClientError) {
                                send(Action.UnarchiveResponse(sessionID, title, error))
                            }
                        }.cancellable(CancelID.Unarchive(sessionID), cancelInFlight = true),
                    ),
                )
            }
        }

        is Action.UnarchiveResponse -> {
            val status = state.status.copy(unarchivingIDs = state.status.unarchivingIDs - action.sessionID)
            val error = action.error
            if (error == null) {
                Reduced(
                    state.copy(
                        status = status.copy(
                            archived = status.archived.filter { it.id != action.sessionID },
                            archivedMatches = status.archivedMatches?.filter { it.id != action.sessionID },
                        ),
                    ),
                )
            } else {
                AppLog.network.notice { "Unarchive failed: ${error.logSummary}" }
                failed(state.copy(status = status), DrawerFailure(DrawerFailure.Action.unarchive, action.title, error.message))
            }
        }
    }

    /** Shows [failure] above New Session for a few seconds. */
    private fun failed(state: State, failure: DrawerFailure): Reduced<State, Action> {
        dependency(HapticsClient).play(Haptic.error)
        val clock = dependency(Clock)
        return Reduced(
            state.copy(status = state.status.copy(archiveError = failure.joining(state.status.archiveError))),
            Effect.run<Action> { send ->
                clock.sleep(5.seconds)
                send(Action.ArchiveErrorCleared)
            }.cancellable(CancelID.ArchiveError, cancelInFlight = true),
        )
    }

    companion object {
        /** The Inbox Sidebar switch. */
        val experiment: ExperimentalFeature get() = ExperimentalFeature.inboxSidebar

        /** As many archived sessions as desktop's Archived list shows. */
        const val ARCHIVED_LIMIT = 50
    }
}

/** One session as the Inbox Sidebar shows it. */
data class InboxSidebarRow(
    val id: String,
    val title: String,
    val project: String?,
    val line: Line,
    val unread: Boolean,
    val updatedAt: Instant,
) {
    sealed interface Line {
        data object NeedsInput : Line
        data object Working : Line
        /** The latest reply's opening prose, on one line. */
        data class Reply(val text: String) : Line
        data object None : Line
    }

    companion object {
        /** The newest reply's first prose block, flattened to one line. */
        fun replySnippet(items: List<OpenCodeTranscriptItem>): String? {
            for (item in items.asReversed()) {
                if (item !is OpenCodeTranscriptItem.AssistantMarkdown) continue
                for (block in OpenCodeMarkdownBlock.blocks(item.text, inlineCache = null)) {
                    val text = when (val kind = block.kind) {
                        is OpenCodeMarkdownBlock.Kind.Paragraph -> kind.text.text
                        is OpenCodeMarkdownBlock.Kind.ListItem -> kind.text.text
                        is OpenCodeMarkdownBlock.Kind.NumberedItem -> kind.text.text
                        is OpenCodeMarkdownBlock.Kind.Blockquote -> kind.text.text
                        is OpenCodeMarkdownBlock.Kind.Heading -> kind.text.text
                        else -> continue
                    }
                    val line = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                    if (line.isNotEmpty()) return line.take(200)
                }
            }
            return null
        }

        /** A short age: “now”, “5m”, “3h”, “2d”, then the date. */
        fun age(date: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = appFormattingLocale()): String {
            val seconds = maxOf(Duration.between(date, now).toMillis() / 1_000.0, 0.0)
            return when {
                seconds < 60 -> localized(R.string.drawer_age_now)
                seconds < 3_600 -> {
                    val minutes = (seconds / 60).toInt()
                    localizedPlural(R.plurals.drawer_age_minutes, minutes, minutes)
                }
                seconds < 86_400 -> {
                    val hours = (seconds / 3_600).toInt()
                    localizedPlural(R.plurals.drawer_age_hours, hours, hours)
                }
                seconds < 604_800 -> {
                    val days = (seconds / 86_400).toInt()
                    localizedPlural(R.plurals.drawer_age_days, days, days)
                }
                else -> DateTimeFormatter.ofPattern("MMM d", locale).withZone(zone).format(date)
            }
        }
    }
}

/**
 * The server archived it: mirror `time.archived` until the next snapshot replaces it, read it, so it
 * leaves Catch Up and the unread count, and list it first under Archived.
 */
internal fun HomeFeature.archiveConfirmed(state: HomeFeature.State, sessionID: String): Reduced<HomeFeature.State, HomeFeature.Action> {
    val index = state.sessions.indexOfFirst { it.id == sessionID }
    if (index < 0) return Reduced(state)
    val session = state.sessions[index].copy(archivedMilliseconds = dependency(DateClient).now().toEpochMilli())
    val sessions = state.sessions.toMutableList().also { it[index] = session }
    val status = state.inboxSidebarStatus
    val archived = listOf(session) + status.archived.filter { it.id != sessionID }
    val next = state.copy(sessions = sessions, inboxSidebarStatus = status.copy(archived = archived))
        .markingReadLocally(session).clearingUnseenBaseline(sessionID)
    return Reduced(next, acknowledgeView(session, next.server))
}

val HomeFeature.State.inboxSidebar: InboxSidebarFeature.State
    get() = InboxSidebarFeature.State(server = server, status = inboxSidebarStatus)

/** Every listed session except archived ones: Needs your input first, then unread, then most recent. */
fun HomeFeature.State.inboxSidebarRows(): List<InboxSidebarRow> {
    val status = statusSnapshot()
    val archived = inboxSidebarStatus.archivedIDs
    val rows = visibleSessions.mapNotNull { session ->
        if (session.archivedMilliseconds != null || session.id in archived) return@mapNotNull null
        val line = when (status.indicator(session)) {
            HomeFeature.State.Indicator.attention -> InboxSidebarRow.Line.NeedsInput
            HomeFeature.State.Indicator.running -> InboxSidebarRow.Line.Working
            HomeFeature.State.Indicator.unread, HomeFeature.State.Indicator.none ->
                cachedTranscriptItems(session)?.let(InboxSidebarRow::replySnippet)?.let(InboxSidebarRow.Line::Reply)
                    ?: InboxSidebarRow.Line.None
        }
        val project = OpenCodeProject.resolved(session, projects).name
        InboxSidebarRow(
            id = session.id,
            title = session.title,
            project = project.ifEmpty { null },
            line = line,
            unread = status.isUnread(session),
            updatedAt = session.updatedAt,
        )
    }
    return rows.sortedWith { a, b ->
        val aInput = a.line == InboxSidebarRow.Line.NeedsInput
        val bInput = b.line == InboxSidebarRow.Line.NeedsInput
        when {
            aInput != bInput -> if (aInput) -1 else 1
            a.unread != b.unread -> if (a.unread) -1 else 1
            else -> b.updatedAt.compareTo(a.updatedAt)
        }
    }
}

/**
 * The Archived page: the server's archived sessions (or its search's matches), minus any being
 * restored or deleted, or already back in the list.
 */
fun HomeFeature.State.inboxSidebarArchivedRows(): List<InboxSidebarRow> {
    val status = inboxSidebarStatus
    val listed = HashSet<String>()
    for (session in visibleSessions) {
        listed.add(session.id)
    }
    val rows = mutableListOf<InboxSidebarRow>()
    for (session in status.archivedMatches ?: status.archived) {
        if (session.id in status.unarchivingIDs || session.id in listed || session.id in recentlyDeletedIDs) continue
        rows.add(
            InboxSidebarRow(
                id = session.id,
                title = session.title,
                project = OpenCodeProject.resolved(session, projects).name.ifEmpty { null },
                line = InboxSidebarRow.Line.None,
                unread = false,
                updatedAt = session.updatedAt,
            ),
        )
    }
    return rows
}

/**
 * An archived session joins the session list (still archived, so no list shows it) for the tap
 * that follows to open it, as a tapped session link does.
 */
internal fun HomeFeature.State.listingArchivedSession(sessionID: String): HomeFeature.State {
    if (sessions.any { it.id == sessionID }) return this
    val status = inboxSidebarStatus
    val session = (status.archivedMatches.orEmpty() + status.archived).firstOrNull { it.id == sessionID } ?: return this
    return copy(sessions = listOf(session) + sessions)
}
