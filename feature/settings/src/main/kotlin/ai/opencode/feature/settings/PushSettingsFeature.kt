package ai.opencode.feature.settings

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.strings.localized
import ai.opencode.feature.system.notifications.PushChoice
import ai.opencode.feature.system.notifications.PushDuration
import ai.opencode.feature.system.notifications.PushMode
import ai.opencode.feature.system.notifications.PushServer
import ai.opencode.feature.system.notifications.PushSharedKeys
import ai.opencode.feature.system.notifications.PushStatus
import ai.opencode.feature.system.notifications.PushSync
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodePushDevice
import ai.opencode.server.model.OpenCodePushState
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/**
 * Settings › Notifications' server controls. A change shows at once and is sent once; when the
 * server refuses it, the server's copy is read again and an alert says so. Nothing is queued.
 */
class PushSettingsFeature : Reducer<PushSettingsFeature.State, PushSettingsFeature.Action> {
    data class State(
        val server: OpenCodeServer? = null,
        val failure: Failure? = null,
        /** A new choice for the server, shown while the server takes it. */
        val pendingChoice: PushChoice? = null,
    )

    data class Failure(val title: String, val message: String)

    sealed interface Action {
        data class EnabledChanged(val enabled: Boolean) : Action
        data class ModeChanged(val mode: PushMode) : Action
        data class ChoiceSynced(val choice: PushChoice, val result: PushServer) : Action
        /** Pause everything on the server for [duration], or resume with null. */
        data class PauseChosen(val duration: PushDuration?) : Action
        data class DevicePausedChanged(val deviceID: String, val paused: Boolean) : Action
        data class DeviceRemoved(val device: OpenCodePushDevice) : Action
        /** The server refused a change shown as [after]; [fetched] is its copy, read again. */
        data class Refused(
            val before: PushServer,
            val after: PushServer,
            val fetched: OpenCodePushState?,
            @param:StringRes val title: Int,
        ) : Action
        data object FailureDismissed : Action
    }

    private enum class CancelID { choice }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val server = state.server ?: return Reduced(state)
        val serverID = server.persistenceID
        val servers = shared(PushSharedKeys.servers)
        val choices = shared(PushSharedKeys.choices)
        when (action) {
            is Action.EnabledChanged -> {
                val choice = state.pendingChoice ?: choices.value[serverID] ?: PushChoice()
                return changeChoice(state, server, choice.copy(enabled = action.enabled))
            }

            is Action.ModeChanged -> {
                val choice = state.pendingChoice ?: choices.value[serverID] ?: PushChoice()
                return changeChoice(state, server, choice.copy(mode = action.mode))
            }

            is Action.ChoiceSynced -> {
                val next = state.copy(pendingChoice = null)
                if (action.result.status == PushStatus.Failed) {
                    return fail(next, R.string.settings_notifications_change_failed_title)
                }
                if (choices.value[serverID] != action.choice) choices.update { it + (serverID to action.choice) }
                if (servers.value[serverID] != action.result) servers.update { it + (serverID to action.result) }
                return Reduced(next)
            }

            is Action.PauseChosen -> {
                val before = servers.value[serverID] ?: return Reduced(state)
                if (before.state == null) return Reduced(state)
                val paused = action.duration != null
                var until: Long? = null
                if (action.duration != null) until = action.duration.until(dependency(DateClient).now(), ZoneId.systemDefault())
                val after = before.withPaused(paused, until)
                servers.update { it + (serverID to after) }
                val client = dependency(OpenCodeClient)
                return Reduced(
                    state,
                    send(server, before, after, R.string.settings_notifications_pause_failed_title) {
                        client.setPushPaused(server, paused, until)
                    },
                )
            }

            is Action.DevicePausedChanged -> {
                val before = servers.value[serverID] ?: return Reduced(state)
                val after = before.withDevicePaused(action.deviceID, action.paused)
                servers.update { it + (serverID to after) }
                val deviceID = action.deviceID
                val paused = action.paused
                return Reduced(
                    state,
                    send(server, before, after, R.string.settings_notifications_device_failed_title) {
                        PushSync.setDevicePaused(server, deviceID, paused)
                    },
                )
            }

            is Action.DeviceRemoved -> {
                val before = servers.value[serverID] ?: return Reduced(state)
                val after = before.withoutDevice(action.device.id)
                servers.update { it + (serverID to after) }
                val client = dependency(OpenCodeClient)
                val deviceID = action.device.id
                return Reduced(
                    state,
                    send(server, before, after, R.string.settings_notifications_device_remove_failed_title) {
                        client.removePushDevice(server, deviceID)
                    },
                )
            }

            is Action.Refused -> {
                val current = servers.value[serverID]
                if (current != null) {
                    val restored = PushSync.afterRefusal(current, action.before, action.after, action.fetched)
                    if (restored != current) servers.update { it + (serverID to restored) }
                }
                return fail(state, action.title)
            }

            Action.FailureDismissed -> return Reduced(state.copy(failure = null))
        }
    }

    private fun changeChoice(state: State, server: OpenCodeServer, choice: PushChoice): Reduced<State, Action> {
        val saved = shared(PushSharedKeys.choices).value[server.persistenceID] ?: PushChoice()
        if ((state.pendingChoice ?: saved) == choice) return Reduced(state)
        return Reduced(
            state.copy(pendingChoice = choice),
            Effect.run<Action> { send -> send(Action.ChoiceSynced(choice, PushSync.sync(server, choice))) }
                .cancellable(CancelID.choice, cancelInFlight = true),
        )
    }

    private fun send(
        server: OpenCodeServer,
        before: PushServer,
        after: PushServer,
        @StringRes title: Int,
        write: suspend () -> Unit,
    ): Effect<Action> = Effect.run { send ->
        try {
            write()
            return@run
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppLog.app.error { "Push change refused: ${PushSync.describe(error)}" }
        }
        send(Action.Refused(before, after, PushSync.fetchState(server), title))
    }

    private fun fail(state: State, @StringRes title: Int): Reduced<State, Action> {
        dependency(HapticsClient).play(Haptic.error)
        val failure = Failure(localized(title), localized(R.string.settings_notifications_change_failed_message))
        return Reduced(state.copy(failure = failure))
    }
}
