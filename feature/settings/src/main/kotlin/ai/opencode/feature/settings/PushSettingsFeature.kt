package ai.opencode.feature.settings

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.strings.localized
import ai.opencode.feature.system.notifications.PushChoice
import ai.opencode.feature.system.notifications.PushMode
import ai.opencode.feature.system.notifications.PushServer
import ai.opencode.feature.system.notifications.PushSharedKeys
import ai.opencode.feature.system.notifications.PushStatus
import ai.opencode.feature.system.notifications.PushSync
import ai.opencode.server.OpenCodeServer

/**
 * Settings › Notifications' server controls. A change shows at once and is kept only when the
 * server takes it; otherwise an alert says so.
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
                    dependency(HapticsClient).play(Haptic.error)
                    val failure = Failure(
                        localized(R.string.settings_notifications_change_failed_title),
                        localized(R.string.settings_notifications_change_failed_message),
                    )
                    return Reduced(next.copy(failure = failure))
                }
                if (choices.value[serverID] != action.choice) choices.update { it + (serverID to action.choice) }
                if (servers.value[serverID] != action.result) servers.update { it + (serverID to action.result) }
                return Reduced(next)
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
}
