package ai.opencode.app

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.system.SystemIntegrationClient
import ai.opencode.feature.system.notifications.AppIconBadgeClient
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.feature.system.share.ShareConversation
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeHomeSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppIconBadgeTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()

    private val savedServer get() = shared.run { shared(ServerSharedKeys.savedServer).value }
    private val savedServers get() = shared.run { shared(ServerSharedKeys.savedServers).value }

    class Forgotten {
        val badges = Locked<List<String>>(emptyList())
        val notifications = Locked<List<String>>(emptyList())
        val unregistered = Locked<List<String>>(emptyList())
        val system = Locked<List<String>>(emptyList())
    }

    private fun DependencyValues.Builder.forgetting(forgotten: Forgotten) {
        this[AppIconBadgeClient] = AppIconBadgeClient(publish = { _, _ -> }, forget = { id -> forgotten.badges.update { it + id } })
        val notifications = SessionNotificationsClient.testValue
        this[SessionNotificationsClient] = SessionNotificationsClient(
            isAvailable = notifications.isAvailable,
            forget = { id, server ->
                forgotten.notifications.update { it + id }
                if (server != null) forgotten.unregistered.update { it + server.persistenceID }
            },
        )
        val system = SystemIntegrationClient.testValue
        this[SystemIntegrationClient] = SystemIntegrationClient(
            actions = system.actions,
            publish = system.publish,
            removeServer = { key -> forgotten.system.update { it + key } },
        )
    }

    @Test
    fun removingTheServerForgetsItsBadge() = runTest {
        val saved = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "")
        val forgotten = Forgotten()
        shared.run { shared(ServerSharedKeys.savedServers).set(listOf(saved)) }
        val store = shared.appStore(shared.appState(), this) { forgetting(forgotten) }
        store.exhaustive = false
        store.send(AppFeature.Action.ServerDeleted(saved))
        store.finish()
        assertEquals(listOf(saved.persistenceID), forgotten.badges.value)
        // Removing a server also removes this phone from its notifications.
        assertEquals(listOf(saved.persistenceID), forgotten.unregistered.value)
        assertEquals(listOf(saved.persistenceID), forgotten.notifications.value)
    }

    @Test
    fun rePairingForgetsTheOldIdentityEvenWhenItsSessionsFailToLoad() = runTest {
        val old = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "old")
        val repaired = OpenCodeServer(url = old.url, username = "kit", password = "new")
        val forgotten = Forgotten()
        val initialState = shared.appState().load(repaired)
        shared.run { shared(ServerSharedKeys.savedServers).set(listOf(old)) }
        val store = shared.appStore(initialState, this) {
            forgetting(forgotten)
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                health = {},
                homeSnapshot = { throw OpenCodeClientError("Sessions unavailable") },
            )
        }
        store.exhaustive = false
        store.send(AppFeature.Action.ConnectButtonTapped)
        store.receiveCase<AppFeature.Action.HealthResponse>()
        store.receiveCase<AppFeature.Action.HomeResponse>()
        store.finish()
        assertEquals(listOf(repaired), savedServers)
        assertEquals(listOf(old.persistenceID), forgotten.badges.value)
        assertEquals(listOf(old.persistenceID), forgotten.notifications.value)
        assertEquals(listOf(old.persistenceID), forgotten.unregistered.value)
        assertEquals(listOf(ShareConversation.serverKey(old.persistenceID)), forgotten.system.value)
    }

    private fun kotlinx.coroutines.test.TestScope.makeEditedServerStore(
        original: OpenCodeServer,
        forgotten: Forgotten,
        homeSnapshot: suspend (OpenCodeServer) -> OpenCodeHomeSnapshot,
    ) = run {
        val state = shared.appState()
        shared.run {
            shared(ServerSharedKeys.savedServer).set(original)
            shared(ServerSharedKeys.savedServers).set(listOf(original))
        }
        shared.appStore(state, this) {
            forgetting(forgotten)
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(health = {}, homeSnapshot = homeSnapshot)
        }.also { it.exhaustive = false }
    }

    @Test
    fun anEditSavedUnderANewIdentityForgetsTheOldOne() = runTest {
        val original = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "secret")
        val forgotten = Forgotten()
        val store = makeEditedServerStore(original, forgotten) { emptySnapshot }
        store.send(AppFeature.Action.EditServerTapped(original))
        store.send(AppFeature.Action.ServerURLChanged("https://box.tailnet.ts.net:4096"))
        store.send(AppFeature.Action.ConnectButtonTapped)
        store.receiveCase<AppFeature.Action.HealthResponse>()
        store.receiveCase<AppFeature.Action.HomeResponse>()
        store.skipReceivedActions()
        eventually { forgotten.notifications.value.isNotEmpty() }
        assertNotEquals(original.persistenceID, savedServer?.persistenceID)
        assertEquals(listOf(original.persistenceID), forgotten.badges.value)
        assertEquals(listOf(original.persistenceID), forgotten.notifications.value)
        assertEquals(listOf(original.persistenceID), forgotten.unregistered.value)
    }

    @Test
    fun anEditWhoseSessionsFailKeepsTheOriginalsState() = runTest {
        val original = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "secret")
        val forgotten = Forgotten()
        val store = makeEditedServerStore(original, forgotten) { throw OpenCodeClientError("Sessions unavailable") }
        store.send(AppFeature.Action.EditServerTapped(original))
        store.send(AppFeature.Action.ServerURLChanged("https://box.tailnet.ts.net:4096"))
        store.send(AppFeature.Action.ConnectButtonTapped)
        store.receiveCase<AppFeature.Action.HealthResponse>()
        store.receiveCase<AppFeature.Action.HomeResponse>()
        store.finish()
        // The original is still the saved server under its own identity.
        assertEquals(original, savedServer)
        assertTrue(forgotten.badges.value.isEmpty())
        assertTrue(forgotten.notifications.value.isEmpty())
    }
}
