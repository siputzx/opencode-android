package ai.opencode.feature.settings

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.strings.localized
import ai.opencode.feature.system.notifications.PushChoice
import ai.opencode.feature.system.notifications.PushDuration
import ai.opencode.feature.system.notifications.PushMode
import ai.opencode.feature.system.notifications.PushSharedKeys
import ai.opencode.feature.system.notifications.PushSync
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodePushDevice
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/**
 * Settings › Notifications' server controls. A change shows at once, and is put back with an alert
 * when the server doesn't take it; nothing is queued.
 */
class PushSettingsFeature : Reducer<PushSettingsFeature.State, PushSettingsFeature.Action> {
    data class State(
        val server: OpenCodeServer? = null,
        val failure: Failure? = null,
    )

    data class Failure(val title: String, val message: String)

    sealed interface Action {
        data class EnabledChanged(val enabled: Boolean) : Action
        data class ModeChanged(val mode: PushMode) : Action
        /** Pause everything on the server for [duration], or resume with null. */
        data class PauseChosen(val duration: PushDuration?) : Action
        data class DevicePausedChanged(val deviceID: String, val paused: Boolean) : Action
        data class DeviceRemoved(val device: OpenCodePushDevice) : Action
        data class PauseFailed(val paused: Boolean, val until: Long?, val message: String) : Action
        data class DevicePauseFailed(val deviceID: String, val paused: Boolean, val message: String) : Action
        data class DeviceRemoveFailed(val device: OpenCodePushDevice, val index: Int, val message: String) : Action
        data object FailureDismissed : Action
        /** The phone's choice changed: Home registers again with it. */
        data object ChoiceSaved : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val server = state.server ?: return Reduced(state)
        val serverID = server.persistenceID
        val servers = shared(PushSharedKeys.servers)
        when (action) {
            is Action.EnabledChanged -> {
                val choices = shared(PushSharedKeys.choices)
                val choice = choices.value[serverID] ?: PushChoice()
                if (choice.enabled == action.enabled) return Reduced(state)
                choices.update { it + (serverID to choice.copy(enabled = action.enabled)) }
                return Reduced(state, Effect.send(Action.ChoiceSaved))
            }

            is Action.ModeChanged -> {
                val choices = shared(PushSharedKeys.choices)
                val choice = choices.value[serverID] ?: PushChoice()
                if (choice.mode == action.mode) return Reduced(state)
                choices.update { it + (serverID to choice.copy(mode = action.mode)) }
                return Reduced(state, Effect.send(Action.ChoiceSaved))
            }

            is Action.PauseChosen -> {
                val current = servers.value[serverID] ?: return Reduced(state)
                val pushState = current.state ?: return Reduced(state)
                val paused = action.duration != null
                var until: Long? = null
                if (action.duration != null) until = action.duration.until(dependency(DateClient).now(), ZoneId.systemDefault())
                servers.update { it + (serverID to current.withPaused(paused, until)) }
                val client = dependency(OpenCodeClient)
                val (wasPaused, wasUntil) = pushState.paused to pushState.pausedUntil
                return Reduced(
                    state,
                    Effect.run { send ->
                        val message = failureMessage { client.setPushPaused(server, paused, until) }
                        if (message != null) send(Action.PauseFailed(wasPaused, wasUntil, message))
                    },
                )
            }

            is Action.DevicePausedChanged -> {
                val current = servers.value[serverID] ?: return Reduced(state)
                servers.update { it + (serverID to current.withDevicePaused(action.deviceID, action.paused)) }
                val (deviceID, paused) = action.deviceID to action.paused
                return Reduced(
                    state,
                    Effect.run { send ->
                        val message = failureMessage { PushSync.setDevicePaused(server, deviceID, paused) }
                        if (message != null) send(Action.DevicePauseFailed(deviceID, !paused, message))
                    },
                )
            }

            is Action.DeviceRemoved -> {
                val current = servers.value[serverID] ?: return Reduced(state)
                val index = current.state?.devices?.indexOf(action.device) ?: return Reduced(state)
                if (index < 0) return Reduced(state)
                servers.update { it + (serverID to current.withoutDevice(action.device.id)) }
                val client = dependency(OpenCodeClient)
                val device = action.device
                return Reduced(
                    state,
                    Effect.run { send ->
                        val message = failureMessage { client.removePushDevice(server, device.id) }
                        if (message != null) send(Action.DeviceRemoveFailed(device, index, message))
                    },
                )
            }

            is Action.PauseFailed -> {
                val current = servers.value[serverID]
                if (current != null) servers.update { it + (serverID to current.withPaused(action.paused, action.until)) }
                return fail(state, localized(R.string.settings_notifications_pause_failed_title), action.message)
            }

            is Action.DevicePauseFailed -> {
                val current = servers.value[serverID]
                if (current != null) servers.update { it + (serverID to current.withDevicePaused(action.deviceID, action.paused)) }
                return fail(state, localized(R.string.settings_notifications_device_failed_title), action.message)
            }

            is Action.DeviceRemoveFailed -> {
                val current = servers.value[serverID]
                val pushState = current?.state
                if (current != null && pushState != null) {
                    val devices = pushState.devices.toMutableList()
                    devices.add(minOf(action.index, devices.size), action.device)
                    servers.update { it + (serverID to current.copy(state = pushState.copy(devices = devices))) }
                }
                return fail(state, localized(R.string.settings_notifications_device_remove_failed_title), action.message)
            }

            Action.FailureDismissed -> return Reduced(state.copy(failure = null))
            Action.ChoiceSaved -> return Reduced(state)
        }
    }

    private fun fail(state: State, title: String, message: String): Reduced<State, Action> {
        dependency(HapticsClient).play(Haptic.error)
        return Reduced(state.copy(failure = Failure(title, message)))
    }

    private suspend fun failureMessage(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        error.message ?: localized(R.string.settings_notifications_change_failed_message)
    }
}
