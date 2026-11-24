package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.sheet.asClientError
import ai.opencode.feature.session.sheet.clientError
import ai.opencode.feature.session.sheet.clientResult
import ai.opencode.feature.system.notifications.PushDuration
import ai.opencode.feature.system.notifications.PushServer
import ai.opencode.feature.system.notifications.PushSharedKeys
import ai.opencode.feature.system.notifications.PushSync
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeExportFormat
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePushBell
import ai.opencode.server.model.OpenCodePushState
import ai.opencode.server.model.OpenCodePushLevel
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.SessionTitle
import kotlinx.coroutines.CancellationException
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Session sheet's own work (rename, compact, fork, export, and the confirmations around them).
 * What the sheet shows (title, model, context, cost) is read from the session; list-level actions
 * are delegated to Home.
 */
class SessionSheetFeature : Reducer<SessionSheetFeature.State, SessionSheetFeature.Action> {
    data class State(
        val server: OpenCodeServer,
        val sessionID: String,
        val renameDraft: String = "",
        /** The title the rename prompt opened with: submitting it unchanged sends nothing. */
        val renameOriginalTitle: String = "",
        val isRenaming: Boolean = false,
        val operation: Operation? = null,
        val confirmsDeletion: Boolean = false,
        val exportedFile: ExportedFile? = null,
        val failure: Failure? = null,
        /** Auto Rename failed: the title stays and says so briefly, no alert. */
        val autoRenameFailed: Boolean = false,
        /** The composer's pick waiting for the next send: compaction applies it first, as the TUI does. */
        val pendingModel: OpenCodeModelRef? = null,
    )

    sealed interface Operation {
        data object Renaming : Operation
        data object Compacting : Operation
        data object Forking : Operation
        /** The server is writing a title; the title breathes until it arrives. */
        data object AutoRenaming : Operation
        data class Exporting(val format: OpenCodeExportFormat) : Operation
    }

    /** A written export, by absolute path. */
    data class ExportedFile(val path: String) {
        val id: String get() = path
    }

    data class Failure(val title: String, val message: String)

    sealed interface Action {
        // Bindings.
        data class RenameDraftChanged(val draft: String) : Action
        /** The rename prompt was dismissed (Cancel, back, outside tap). */
        data class IsRenamingChanged(val isRenaming: Boolean) : Action
        data class ConfirmsDeletionChanged(val confirmsDeletion: Boolean) : Action
        data class PendingModelChanged(val model: OpenCodeModelRef?) : Action

        data class RenameTapped(val currentTitle: String) : Action
        data object RenameSubmitted : Action
        /** The server writes a title from the conversation, as for a new session. */
        data object AutoRenameTapped : Action
        data class RenameFinished(val title: String, val error: OpenCodeClientError?, val sessionID: String) : Action
        data object AutoRenameFailureCleared : Action
        data object CompactTapped : Action
        data class CompactFinished(val error: OpenCodeClientError?, val model: OpenCodeModelRef?) : Action
        data object ForkTapped : Action
        data class ForkFinished(val result: Result<OpenCodeSession>) : Action
        data class ExportTapped(val format: OpenCodeExportFormat, val title: String) : Action
        data class ExportFinished(val result: Result<String>) : Action
        /** The share sheet closed: the written file is removed. */
        data object ExportDismissed : Action
        data object CopySessionIDTapped : Action
        data object PinTapped : Action
        data object ReadTapped : Action
        data object MuteTapped : Action
        /** This phone's own bell: a level for a while, or null to follow the phone's choice. */
        data class BellChosen(val level: OpenCodePushLevel?, val duration: PushDuration) : Action
        /** The server refused the bell shown as [after]; [fetched] is its copy, read again. */
        data class BellRefused(val before: PushServer, val after: PushServer, val fetched: OpenCodePushState?) : Action
        data object DeleteTapped : Action
        data object DeleteConfirmed : Action
        data object FailureDismissed : Action
        data class Delegate(val delegate: SessionSheetFeature.Delegate) : Action
    }

    sealed interface Delegate {
        data class Renamed(val title: String) : Delegate
        /** With the waiting pick it applied first, if any. */
        data class Compacted(val model: OpenCodeModelRef?) : Delegate
        data class Forked(val session: OpenCodeSession) : Delegate
        data object TogglePin : Delegate
        data object ToggleRead : Delegate
        data object ToggleMute : Delegate
        data object DeleteSession : Delegate
    }

    private enum class CancelID { AutoRenameFailure, Bell }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val haptics = dependency(HapticsClient)
        return when (action) {
            is Action.Delegate -> Reduced(state)
            is Action.RenameDraftChanged -> Reduced(state.copy(renameDraft = action.draft))
            is Action.IsRenamingChanged -> Reduced(state.copy(isRenaming = action.isRenaming))
            is Action.ConfirmsDeletionChanged -> Reduced(state.copy(confirmsDeletion = action.confirmsDeletion))
            is Action.PendingModelChanged -> Reduced(state.copy(pendingModel = action.model))

            is Action.RenameTapped -> {
                if (state.operation != null) return Reduced(state)
                Reduced(state.copy(renameDraft = action.currentTitle, renameOriginalTitle = action.currentTitle, isRenaming = true))
            }

            Action.RenameSubmitted -> {
                val next = state.copy(isRenaming = false)
                val title = next.renameDraft.trim()
                if (next.operation != null || title.isEmpty() || title == next.renameOriginalTitle.trim()) return Reduced(next)
                val client = dependency(OpenCodeClient)
                val (server, sessionID) = next.server to next.sessionID
                Reduced(
                    next.copy(operation = Operation.Renaming),
                    Effect.run { send ->
                        val error = clientError { client.renameSession(server, sessionID, title) }
                        send(Action.RenameFinished(title, error, sessionID))
                    },
                )
            }

            Action.AutoRenameTapped -> {
                val next = state.copy(isRenaming = false)
                if (next.operation != null) return Reduced(next)
                val client = dependency(OpenCodeClient)
                val (server, sessionID) = next.server to next.sessionID
                Reduced(
                    next.copy(operation = Operation.AutoRenaming, autoRenameFailed = false),
                    Effect.run { send ->
                        var title = ""
                        // An empty title asks the server to generate one; it answers once the new title is saved.
                        val error = clientError {
                            client.renameSession(server, sessionID, "")
                            title = client.getSession(server, sessionID).serverTitle
                        }
                        send(Action.RenameFinished(if (error == null) title else "", error, sessionID))
                    },
                )
            }

            is Action.RenameFinished -> {
                // A rename for a session this sheet no longer shows isn't its business.
                if (action.sessionID != state.sessionID) return Reduced(state)
                val wasAuto = state.operation == Operation.AutoRenaming
                var next = state.copy(operation = null)
                // The server answers Auto Rename the same way whether or not it wrote a title: when
                // its title model fails, it keeps the old one and still succeeds. A title that comes
                // back unchanged, or still unnamed (shown as its first prompt), failed too.
                val autoRenameChangedNothing = wasAuto && action.error == null &&
                    (action.title == state.renameOriginalTitle || SessionTitle.isPlaceholder(action.title))
                if (wasAuto && (action.error != null || autoRenameChangedNothing)) {
                    // The old title stays; the line under it says so for a moment.
                    next = next.copy(autoRenameFailed = true)
                    haptics.play(Haptic.error)
                    val clock = dependency(Clock)
                    return Reduced(
                        next,
                        Effect.run<Action> { send ->
                            clock.sleep(2200.milliseconds)
                            send(Action.AutoRenameFailureCleared)
                        }.cancellable(CancelID.AutoRenameFailure, cancelInFlight = true),
                    )
                }
                action.error?.let { return fail(next, localized(R.string.session_sheet_rename_failed_title), it, haptics) }
                Reduced(next, Effect.send(Action.Delegate(Delegate.Renamed(action.title))))
            }

            Action.AutoRenameFailureCleared -> Reduced(state.copy(autoRenameFailed = false))

            Action.CompactTapped -> {
                if (state.operation != null) return Reduced(state)
                val client = dependency(OpenCodeClient)
                val (server, sessionID, model) = Triple(state.server, state.sessionID, state.pendingModel)
                Reduced(
                    state.copy(operation = Operation.Compacting),
                    Effect.run { send ->
                        val error = clientError {
                            if (model != null) client.switchModel(server, sessionID, model)
                            client.compactSession(server, sessionID)
                        }
                        send(Action.CompactFinished(error, if (error == null) model else null))
                    },
                )
            }

            is Action.CompactFinished -> {
                val next = state.copy(operation = null)
                action.error?.let { return fail(next, localized(R.string.session_sheet_compact_failed_title), it, haptics) }
                haptics.play(Haptic.success)
                Reduced(next, Effect.send(Action.Delegate(Delegate.Compacted(action.model))))
            }

            Action.ForkTapped -> {
                if (state.operation != null) return Reduced(state)
                val client = dependency(OpenCodeClient)
                val (server, sessionID) = state.server to state.sessionID
                Reduced(
                    state.copy(operation = Operation.Forking),
                    Effect.run { send -> send(Action.ForkFinished(clientResult { client.forkSession(server, sessionID) })) },
                )
            }

            is Action.ForkFinished -> action.result.fold(
                onSuccess = { Reduced(state.copy(operation = null), Effect.send(Action.Delegate(Delegate.Forked(it)))) },
                onFailure = { fail(state, localized(R.string.session_sheet_fork_failed_title), it.asClientError(), haptics) },
            )

            is Action.ExportTapped -> {
                if (state.operation != null) return Reduced(state)
                val client = dependency(OpenCodeClient)
                val sessionExport = dependency(SessionExportClient)
                val exportID = dependency(UuidClient).next()
                val (server, sessionID) = state.server to state.sessionID
                val (format, title) = action.format to action.title
                Reduced(
                    state.copy(operation = Operation.Exporting(format)),
                    Effect.run { send ->
                        val result = clientResult {
                            val body = client.exportSession(server, sessionID)
                            try {
                                sessionExport.write(body, format, title, exportID)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                throw OpenCodeClientError(localized(R.string.session_sheet_save_export_failed))
                            }
                        }
                        send(Action.ExportFinished(result))
                    },
                )
            }

            is Action.ExportFinished -> action.result.fold(
                onSuccess = { path ->
                    val sessionExport = dependency(SessionExportClient)
                    // Android: the system share sheet is started by the client (an Intent), never by a composable.
                    Reduced(
                        state.copy(operation = null, exportedFile = ExportedFile(path)),
                        Effect.run { sessionExport.share(path) },
                    )
                },
                onFailure = { fail(state, localized(R.string.session_sheet_export_failed_title), it.asClientError(), haptics) },
            )

            Action.ExportDismissed -> {
                val file = state.exportedFile ?: return Reduced(state)
                val sessionExport = dependency(SessionExportClient)
                Reduced(state.copy(exportedFile = null), Effect.run { sessionExport.remove(file.path) })
            }

            Action.CopySessionIDTapped -> {
                haptics.play(Haptic.tap)
                val sessionID = state.sessionID
                val pasteboard = dependency(PasteboardClient)
                Reduced(state, Effect.run { pasteboard.setString(sessionID) })
            }

            Action.PinTapped -> Reduced(state, Effect.send(Action.Delegate(Delegate.TogglePin)))
            Action.ReadTapped -> Reduced(state, Effect.send(Action.Delegate(Delegate.ToggleRead)))
            Action.MuteTapped -> Reduced(state, Effect.send(Action.Delegate(Delegate.ToggleMute)))

            is Action.BellChosen -> {
                val servers = shared(PushSharedKeys.servers)
                val serverID = state.server.persistenceID
                val current = servers.value[serverID] ?: return Reduced(state)
                val device = current.device ?: return Reduced(state)
                val previous = device.sessions[state.sessionID]
                var bell: OpenCodePushBell? = null
                if (action.level != null) {
                    val until = action.duration.until(dependency(DateClient).now(), ZoneId.systemDefault())
                    bell = OpenCodePushBell(action.level, until)
                }
                if (bell == previous) return Reduced(state)
                val after = current.withOwnBell(state.sessionID, bell)
                servers.update { it + (serverID to after) }
                val (server, sessionID) = state.server to state.sessionID
                Reduced(
                    state,
                    Effect.run<Action> { send ->
                        val error = clientError { PushSync.setOwnBell(server, device.id, sessionID, bell) }
                        if (error != null) send(Action.BellRefused(current, after, PushSync.fetchState(server)))
                    }.cancellable(CancelID.Bell, cancelInFlight = true),
                )
            }

            is Action.BellRefused -> {
                val servers = shared(PushSharedKeys.servers)
                val serverID = state.server.persistenceID
                val current = servers.value[serverID]
                if (current != null) {
                    val restored = PushSync.afterRefusal(current, action.before, action.after, action.fetched)
                    if (restored != current) servers.update { it + (serverID to restored) }
                }
                haptics.play(Haptic.error)
                val failure = Failure(localized(R.string.session_sheet_bell_failed_title), localized(R.string.session_sheet_bell_failed_message))
                Reduced(state.copy(failure = failure))
            }

            Action.DeleteTapped -> Reduced(state.copy(confirmsDeletion = true))
            Action.DeleteConfirmed -> Reduced(state.copy(confirmsDeletion = false), Effect.send(Action.Delegate(Delegate.DeleteSession)))
            Action.FailureDismissed -> Reduced(state.copy(failure = null))
        }
    }

    private fun fail(state: State, title: String, error: OpenCodeClientError, haptics: HapticsClient): Reduced<State, Action> {
        haptics.play(Haptic.error)
        return Reduced(state.copy(operation = null, failure = Failure(title, error.message)))
    }
}
