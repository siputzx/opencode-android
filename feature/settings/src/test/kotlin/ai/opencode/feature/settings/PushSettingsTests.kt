package ai.opencode.feature.settings

import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.system.notifications.PushChoice
import ai.opencode.feature.system.notifications.PushServer
import ai.opencode.feature.system.notifications.PushSharedKeys
import ai.opencode.feature.system.notifications.PushStatus
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodePushCapability
import ai.opencode.server.model.OpenCodePushLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Settings › Notifications: a change is kept only when the server takes it. */
class PushSettingsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val failure = PushSettingsFeature.Failure("Couldn’t Change Notifications", "The server didn’t take the change.")

    @Test fun turningOffIsKeptOnlyWhenTheServerLetsThePhoneGo() = runTest {
        val shared = SettingsTestShared()
        val haptics = HapticsRecorder()
        val unregistered = Locked(false)
        val store = TestStore(PushSettingsFeature.State(server = server), PushSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[HapticsClient] = haptics.client
            this[SessionNotificationsClient] = SessionNotificationsClient(
                isAvailable = true,
                relay = "https://relay.example",
                unregister = { unregistered.value },
            )
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                pushCapability = { OpenCodePushCapability(1, listOf("https://relay.example"), OpenCodePushLevel.entries) },
            )
        }
        val off = PushChoice(enabled = false)

        store.send(PushSettingsFeature.Action.EnabledChanged(false)) { it.copy(pendingChoice = off) }
        store.receive(PushSettingsFeature.Action.ChoiceSynced(off, PushServer(PushStatus.Failed, OpenCodePushLevel.entries))) {
            it.copy(pendingChoice = null, failure = failure)
        }
        assertEquals(emptyMap<String, PushChoice>(), shared.get(PushSharedKeys.choices))
        assertEquals(listOf("error"), haptics.value)

        unregistered.set(true)
        store.send(PushSettingsFeature.Action.FailureDismissed) { it.copy(failure = null) }
        store.send(PushSettingsFeature.Action.EnabledChanged(false)) { it.copy(pendingChoice = off) }
        val result = PushServer(PushStatus.Off, OpenCodePushLevel.entries)
        store.receive(PushSettingsFeature.Action.ChoiceSynced(off, result)) { it.copy(pendingChoice = null) }
        assertEquals(mapOf(server.persistenceID to off), shared.get(PushSharedKeys.choices))
        assertEquals(mapOf(server.persistenceID to result), shared.get(PushSharedKeys.servers))
        store.finish()
    }
}
