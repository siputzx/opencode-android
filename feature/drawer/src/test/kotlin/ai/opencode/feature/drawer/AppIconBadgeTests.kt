package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.system.notifications.AppBadgeLedger
import ai.opencode.feature.system.notifications.AppIconBadgeClient
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.share.ShareConversation
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Collections

class AppIconBadgeTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun session(id: String, idle: Long = 1_000, viewed: Long = 1_000) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "p", directory = "/project",
        updatedAt = Instant.ofEpochSecond(10), idleMilliseconds = idle, viewedMilliseconds = viewed,
    )

    /**
     * `unread` has news, `waiting` will need input once the attention read lands,
     * and `read` has nothing new. The list has loaded; its attention read hasn't.
     */
    private fun home(server: OpenCodeServer = this.server): HomeFeature.State {
        val sessions = listOf(session("unread", idle = 2_000), session("read"), session("waiting"))
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(emptyList(), sessions, emptySet()))
        state = state.copy(
            connection = state.connection.copy(hasConnected = true),
            noticeReadiness = HomeFeature.State.NoticeReadiness.listed,
        )
        state = state.openingDraft()
        return state.copy(sessionDetail = state.sessionDetail?.copy(isVisible = true))
    }

    private fun CoroutineScope.store(
        state: HomeFeature.State,
        published: MutableList<AppBadgeLedger>,
        attention: () -> Set<String> = { setOf("waiting") },
    ): TestStore<HomeFeature.State, Action> {
        val listed = state.sessions
        val store = TestStore(state, HomeFeature(), this) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[AppIconBadgeClient] = AppIconBadgeClient(
                publish = { ledger, persistenceID ->
                    assertEquals(state.server.persistenceID, persistenceID)
                    published += ledger
                },
                forget = {},
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                viewSession = { _, _, _ -> },
                transcript = { _, _, _ -> emptyList() },
                homeAttention = { _, _, _ -> attention() },
                homeSnapshot = { OpenCodeHomeSnapshot(emptyList(), listed, emptySet()) },
            )
        }
        store.exhaustive = false
        return store
    }

    private fun published() = Collections.synchronizedList(mutableListOf<AppBadgeLedger>())

    @Test fun countsWhatCatchUpHoldsOnceTheAttentionReadLands() = runTest {
        val published = published()
        val store = store(home(), published)
        assertNull("Not before the attention read", store.state.appBadgeLedger)

        store.send(Action.AttentionRefreshed(setOf("waiting")))
        val ledger = published.lastOrNull()
        assertEquals(listOf("unread", "waiting"), ledger?.sessionIDs)
        assertEquals(store.state.catchUpSessions.size, ledger?.count)
        // A hash of the server's identity, never its username or address.
        assertEquals(ShareConversation.serverKey(server.persistenceID), ledger?.serverKey)
        assertTrue(ledger?.let { AppBadgeLedger.isHashedKey(it.serverKey) } == true)
        assertFalse(ledger?.serverKey?.contains("localhost") ?: true)
        store.skipInFlightEffects()
    }

    @Test fun markAllAsReadLeavesWaitingSessionsThenAnsweringClearsTheBadge() = runTest {
        val published = published()
        val store = store(home(), published)
        store.send(Action.AttentionRefreshed(setOf("waiting")))

        store.send(Action.catchUpMarkAllReadTapped)
        assertEquals(listOf("waiting"), published.last().sessionIDs)

        store.send(Action.AttentionRefreshed(emptySet()))
        assertEquals("Everything read clears the badge", 0, published.last().count)
        assertTrue(store.state.catchUpSessions.isEmpty())
        store.skipInFlightEffects()
    }

    @Test fun openingASessionReadsItAndMarksItAsOnScreen() = runTest {
        val published = published()
        val state = home().copy(transcriptCache = mapOf("unread" to emptyList()))
        val store = store(state, published)
        store.send(Action.AttentionRefreshed(setOf("waiting")))

        store.send(Action.SessionTapped("unread"))
        assertEquals(listOf("waiting"), published.last().sessionIDs)
        assertEquals(listOf("unread"), published.last().viewingSessionIDs)
        store.skipInFlightEffects()
    }

    @Test fun anOutageKeepsTheLastCountUntilTheServerAnswersAgain() = runTest {
        val published = published()
        var attention = setOf("waiting")
        val store = store(home(), published, attention = { attention })
        store.send(Action.AttentionRefreshed(setOf("waiting")))
        val before = published.size

        store.send(Action.ConnectionLost)
        store.send(Action.ConnectionLost)
        // Offline once the grace has passed with two failed attempts.
        store.receive({ it is Action.OfflineGraceElapsed }, "offlineGraceElapsed")
        assertEquals(HomeConnectionFeature.State.ConnectionStatus.disconnected, store.state.connectionStatus)
        assertNull(store.state.appBadgeLedger)
        assertEquals("Disconnected sessions read as none; the badge holds", before, published.size)

        // Back: the waiting session was answered elsewhere meanwhile.
        attention = emptySet()
        store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(emptyList(), store.state.sessions, emptySet())))
        store.receive({ it is Action.AttentionRefreshed }, "attentionRefreshed")
        assertTrue(published.size > before)
        assertEquals(listOf("unread"), published.last().sessionIDs)
        store.skipInFlightEffects()
    }

    @Test fun leavingTheAppPublishesWithNothingOnScreen() = runTest {
        val published = published()
        val state = home().copy(transcriptCache = mapOf("read" to emptyList()))
        val store = store(state, published)
        store.send(Action.AttentionRefreshed(setOf("waiting")))
        store.send(Action.SessionTapped("read"))
        assertEquals(listOf("read"), published.last().viewingSessionIDs)

        store.send(Action.detail(SessionFeature.Action.VisibilityChanged(false)))
        store.send(Action.SceneEnteredBackground)
        assertEquals(emptyList<String>(), published.last().viewingSessionIDs)
        assertEquals(listOf("unread", "waiting"), published.last().sessionIDs)
        store.skipInFlightEffects()
    }

    @Test fun eachServerCountsOnlyItsOwnSessions() = runTest {
        val other = OpenCodeServer(url = "http://localhost:10", username = "", password = "")
        val published = published()
        val base = home(server = other)
        val store = store(base, published)
        store.send(Action.AttentionRefreshed(setOf("waiting")))
        val ledger = published.lastOrNull()
        assertEquals(ShareConversation.serverKey(other.persistenceID), ledger?.serverKey)
        assertNotEquals(ShareConversation.serverKey(server.persistenceID), ledger?.serverKey)
        assertEquals(listOf("unread", "waiting"), ledger?.sessionIDs)
        store.skipInFlightEffects()
    }

    @Test fun allowingNotificationsShowsTheCountAlreadyPublished() = runTest {
        val published = published()
        val base = home()
        val state = base.copy(notificationState = base.notificationState.copy(notificationPermission = NotificationPermission.NotDetermined))
        val store = store(state, published)
        store.send(Action.AttentionRefreshed(setOf("waiting")))
        val before = published.size

        store.send(Action.NotificationPermissionLoaded(NotificationPermission.Enabled))
        assertEquals(before + 1, published.size)
        assertEquals(listOf("unread", "waiting"), published.last().sessionIDs)

        // Reading the same permission again publishes nothing.
        store.send(Action.NotificationPermissionLoaded(NotificationPermission.Enabled))
        assertEquals(before + 1, published.size)
        store.skipInFlightEffects()
    }
}
