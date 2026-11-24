package ai.opencode.feature.drawer

import ai.opencode.core.strings.localized
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.notifications.NotificationPermissionClient
import ai.opencode.feature.system.notifications.PushChoice
import ai.opencode.feature.system.notifications.PushServer
import ai.opencode.feature.system.notifications.PushSharedKeys
import ai.opencode.feature.system.notifications.PushStatus
import ai.opencode.feature.system.notifications.PushSync
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodePushState

/**
 * This phone's notification permission, and push on the current server: registering on every
 * connect and when the push token changes, and replacing the server's copy
 * ([PushSharedKeys.servers]) whenever it says something changed. Home supplies the current server
 * and keeps [Status] as `notificationState`.
 */
class HomeNotificationsFeature : Reducer<HomeNotificationsFeature.State, HomeNotificationsFeature.Action> {
    data class Status(
        /** Null until read, and while this build offers no notifications. */
        val notificationPermission: NotificationPermission? = null,
        val isRequestingNotifications: Boolean = false,
        val notificationSetupError: String? = null,
    )

    data class State(
        val server: OpenCodeServer,
        val status: Status = Status(),
    )

    sealed interface Action {
        data object NotificationSettingsRequested : Action
        data class NotificationPermissionLoaded(val permission: NotificationPermission) : Action
        data object EnableNotificationsTapped : Action
        data class NotificationPermissionRequested(val permission: NotificationPermission) : Action
        data class NotificationSettingsOpened(val opened: Boolean) : Action
        /** Home started: registers again whenever the push token changes. */
        data object Task : Action
        /** Registers this phone with the current server (or removes it) and fetches the server's copy. */
        data object PushSyncRequested : Action
        data class PushSynced(val serverID: String, val result: PushServer) : Action
        /** The server said push changed (`push.updated`). */
        data object PushFetchRequested : Action
        data class PushFetched(val serverID: String, val state: OpenCodePushState?) : Action
    }

    private enum class CancelID { tokenChanges, sync, fetch }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val status = state.status
        return when (action) {
            Action.NotificationSettingsRequested -> {
                val sessionNotifications = dependency(SessionNotificationsClient)
                if (!sessionNotifications.isAvailable) return Reduced(state)
                val notificationPermission = dependency(NotificationPermissionClient)
                Reduced(state, Effect.run { send -> send(Action.NotificationPermissionLoaded(notificationPermission.status())) })
            }

            is Action.NotificationPermissionLoaded -> {
                val next = state.copy(status = status.copy(notificationPermission = action.permission))
                val changed = status.notificationPermission != action.permission
                if (!changed || action.permission != NotificationPermission.Enabled) return Reduced(next)
                Reduced(next, Effect.send(Action.PushSyncRequested))
            }

            Action.EnableNotificationsTapped -> {
                if (status.isRequestingNotifications) return Reduced(state)
                val next = state.copy(status = status.copy(notificationSetupError = null, isRequestingNotifications = true))
                val notificationPermission = dependency(NotificationPermissionClient)
                val permission = status.notificationPermission
                if (permission != null && permission != NotificationPermission.NotDetermined) {
                    // Disabled or enabled: only the system's settings can change it.
                    return Reduced(next, Effect.run { send -> send(Action.NotificationSettingsOpened(notificationPermission.openSettings())) })
                }
                Reduced(next, Effect.run { send -> send(Action.NotificationPermissionRequested(notificationPermission.request())) })
            }

            is Action.NotificationPermissionRequested -> {
                val error = if (action.permission == NotificationPermission.NotDetermined) {
                    localized(R.string.drawer_notifications_request_error)
                } else {
                    status.notificationSetupError
                }
                Reduced(
                    state.copy(status = status.copy(isRequestingNotifications = false, notificationSetupError = error)),
                    Effect.send(Action.NotificationPermissionLoaded(action.permission)),
                )
            }

            is Action.NotificationSettingsOpened -> {
                val error = if (!action.opened) {
                    localized(R.string.drawer_notifications_open_settings_error)
                } else {
                    status.notificationSetupError
                }
                Reduced(state.copy(status = status.copy(isRequestingNotifications = false, notificationSetupError = error)))
            }

            Action.Task -> {
                val sessionNotifications = dependency(SessionNotificationsClient)
                if (!sessionNotifications.isAvailable) return Reduced(state)
                Reduced(
                    state,
                    Effect.run<Action> { send ->
                        sessionNotifications.tokenChanges.collect { send(Action.PushSyncRequested) }
                    }.cancellable(CancelID.tokenChanges, cancelInFlight = true),
                )
            }

            Action.PushSyncRequested -> {
                val server = state.server
                val serverID = server.persistenceID
                val choice = shared(PushSharedKeys.choices).value[serverID] ?: PushChoice()
                Reduced(
                    state,
                    Effect.run<Action> { send -> send(Action.PushSynced(serverID, PushSync.sync(server, choice))) }
                        .cancellable(CancelID.sync, cancelInFlight = true),
                )
            }

            is Action.PushSynced -> {
                val servers = shared(PushSharedKeys.servers)
                if (servers.value[action.serverID] != action.result) {
                    servers.update { it + (action.serverID to action.result) }
                }
                Reduced(state)
            }

            Action.PushFetchRequested -> {
                val server = state.server
                val serverID = server.persistenceID
                val current = shared(PushSharedKeys.servers).value[serverID]
                if (current?.status != PushStatus.Ready) return Reduced(state)
                Reduced(
                    state,
                    Effect.run<Action> { send -> send(Action.PushFetched(serverID, PushSync.fetchState(server))) }
                        .cancellable(CancelID.fetch, cancelInFlight = true),
                )
            }

            is Action.PushFetched -> {
                val fetched = action.state ?: return Reduced(state)
                val servers = shared(PushSharedKeys.servers)
                val current = servers.value[action.serverID]
                if (current?.status != PushStatus.Ready || current.state == fetched) return Reduced(state)
                servers.update { it + (action.serverID to current.copy(state = fetched)) }
                Reduced(state)
            }
        }
    }
}
