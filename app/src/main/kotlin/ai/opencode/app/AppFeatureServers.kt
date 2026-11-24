package ai.opencode.app

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.system.SystemIntegrationClient
import ai.opencode.feature.system.notifications.AppIconBadgeClient
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.feature.system.share.ShareInboxClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.rememberName

/** Renames a saved server in place, without reconnecting; Home takes the name too. */
internal fun renameServer(server: OpenCodeServer, name: String, state: AppFeature.State): AppFeature.State {
    val trimmed = name.trim()
    val renamed = (state.knownServers.firstOrNull { it.persistenceID == server.persistenceID } ?: server)
        .copy(name = trimmed.ifEmpty { null })
    var next = state
    val home = next.home
    if (home != null && home.server.persistenceID == renamed.persistenceID) {
        next = next.copy(home = home.copy(connection = home.connection.copy(server = home.server.copy(name = renamed.name))))
    }
    renamed.rememberName()
    return next
}

internal fun AppFeature.selectServer(
    server: OpenCodeServer,
    state: AppFeature.State,
    preservingPending: Boolean = false,
    keepingHome: Boolean = false,
): Reduced<AppFeature.State, AppFeature.Action> = connect(
    state.copy(isScanningPairingCode = false, isEditingServer = false, editingSavedServer = null).load(server),
    preservingPending = preservingPending,
    keepingHome = keepingHome,
)

/** The form opens over Home; Home stays until the edit connects. */
internal fun editServer(server: OpenCodeServer, state: AppFeature.State): AppFeature.State {
    val saved = state.knownServers.firstOrNull { it.persistenceID == server.persistenceID } ?: server
    return state.copy(editingSavedServer = saved, manualEntryDraft = null, isEditingServer = true).load(saved)
}

/** The server in use isn't offered for removal; switch away first. */
internal fun confirmRemoval(server: OpenCodeServer, state: AppFeature.State): Effect<AppFeature.Action> =
    if (state.home?.server?.persistenceID == server.persistenceID) Effect.none()
    else Effect.send(AppFeature.Action.ServerDeleted(server))

/** Pairing's own screen: scan a code, or paste the link `opencode pair` prints. */
internal fun beginScanningPairingCode(state: AppFeature.State): AppFeature.State =
    state.copy(editingSavedServer = null, isScanningPairingCode = true)

/** Opens the form for a new server: the last failed entry, or a blank one. */
internal fun beginNewServer(state: AppFeature.State): AppFeature.State {
    val next = state.copy(isEditingServer = true, editingSavedServer = null)
    val draft = next.manualEntryDraft
    return if (draft != null) next.load(draft) else next.copy(
        serverURL = "",
        username = "opencode",
        password = "",
        alternateURLs = emptyList(),
        preferredURLs = emptyList(),
        serverName = "",
        headers = emptyList(),
    )
}

/** Leaving Home to pair. */
internal fun leaveHome(state: AppFeature.State): AppFeature.State {
    val current = state.homeServer ?: return state.withoutHome()
    return state.copy(returnServer = current).withoutHome()
}

/** An empty or wrong clipboard is an ordinary mistake: say so beside Paste, not in an error report. */
internal fun pastePairingCode(state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    val text = dependency(PasteboardClient).string().orEmpty()
    val problem = pasteProblem(text)
    if (problem != null) {
        dependency(HapticsClient).play(Haptic.error)
        return cancelConnection(state.copy(pasteError = problem))
    }
    return Reduced(state, Effect.send(AppFeature.Action.PairingCodeScanned(text)))
}

/** Why pasted text can't pair, or null when it can. */
internal fun pasteProblem(text: String): String? {
    if (text.isBlank()) return AppFeature.EMPTY_CLIPBOARD_MESSAGE
    val error = validatePairing(text).exceptionOrNull() ?: return null
    return pairingProblem(error.message.orEmpty())
}

internal fun cancelConnection(
    state: AppFeature.State,
    connection: AppFeature.State.Connection = AppFeature.State.Connection.Picking,
): Reduced<AppFeature.State, AppFeature.Action> = Reduced(
    state.copy(connection = connection, connectionAttempt = null, pendingNotification = null, pendingSystemAction = null),
    Effect.cancel(
        listOf(
            AppFeature.CancelID.Health, AppFeature.CancelID.Home, AppFeature.CancelID.Notification,
            AppFeature.CancelID.SystemSession, AppFeature.CancelID.SwitchTimeout,
        ),
    ),
)

internal fun serverDeleted(state: AppFeature.State, server: OpenCodeServer): Reduced<AppFeature.State, AppFeature.Action> {
    var next = state
    if (next.returnServer?.persistenceID == server.persistenceID) next = next.copy(returnServer = null)
    val wasEditingServer = next.editingSavedServer?.persistenceID == server.persistenceID
    if (wasEditingServer) next = next.copy(editingSavedServer = null, isEditingServer = false)
    next.cachedHomeSnapshots.update { it - server.persistenceID }
    next.savedServers.update { servers -> servers.filterNot { it.persistenceID == server.persistenceID } }
    if (next.savedServer.value?.persistenceID == server.persistenceID) next.savedServer.set(null)
    discardStagedFiles(next.pendingShares.filter { it.handoff.serverID == server.persistenceID })
    next = next.copy(pendingShares = next.pendingShares.filterNot { it.handoff.serverID == server.persistenceID })
    dependency(ShareInboxClient).removeTargets(server.persistenceID)
    val forgotten = forgetIdentity(server.persistenceID, next, removedServer = server)
    next = forgotten.state
    if (wasEditingServer || next.connectingServerID == server.id) {
        val cancelled = cancelConnection(next)
        return Reduced(cancelled.state, Effect.merge(cancelled.effect, forgotten.effect))
    }
    return forgotten
}

/**
 * Forgets what the system, notifications, the icon's badge and the composer keep for a server
 * identity that is no longer saved. A [removedServer] is also asked to stop sending this phone notifications.
 */
internal fun forgetIdentity(
    persistenceID: String,
    state: AppFeature.State,
    removedServer: OpenCodeServer? = null,
): Reduced<AppFeature.State, AppFeature.Action> {
    dependency(ComposerPersistence).removeServer(persistenceID)
    val transcripts = ServerSharedKeys.cachedTranscriptSnapshots()
    if (transcripts.value.entries.any { it.serverID == persistenceID }) transcripts.update { it.removeEntries(persistenceID) }
    dependency(SystemIntegrationClient).removeServer(SessionDirectory.serverKey(persistenceID))
    val sessionNotifications = dependency(SessionNotificationsClient)
    val appIconBadge = dependency(AppIconBadgeClient)
    return Reduced(
        state,
        Effect.run {
            appIconBadge.forget(persistenceID)
            sessionNotifications.forget(persistenceID, removedServer)
        },
    )
}
