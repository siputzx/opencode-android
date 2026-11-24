package ai.opencode.feature.drawer.notices

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.experiments.InAppNoticeMode
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeConnectionFeature
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.openingDraft
import ai.opencode.feature.session.*
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * In-App Notices through `HomeFeature` (modes, suppression, coalescing, repeats) and the delivered
 * notifications Home clears.
 */
class InAppNoticeTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun session(id: String, title: String, idle: Long = 1_000, viewed: Long = 1_000, parent: String? = null) = OpenCodeSession(
        id = id, serverTitle = title, projectID = "p", directory = "/project",
        updatedAt = Instant.ofEpochSecond(10), idleMilliseconds = idle, viewedMilliseconds = viewed, parentID = parent,
    )

    /** Two read sessions and a subagent, live, with a draft on screen. */
    private fun home(): HomeFeature.State {
        val sessions = listOf(
            session("upload", "Upload tests"),
            session("login", "Fix login flow"),
            session("sub", "UI checks", parent = "login"),
        )
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(emptyList(), sessions, emptySet()))
        state = state.copy(
            connection = state.connection.copy(hasConnected = true),
            noticeReadiness = HomeFeature.State.NoticeReadiness.settled,
        )
        state = state.openingDraft()
        return state.copy(sessionDetail = state.sessionDetail?.copy(isVisible = true))
    }

    private class Recorded {
        val haptics: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val announcements: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val cleared: MutableList<Set<String>> = Collections.synchronizedList(mutableListOf())
        val clearedNeedsInput: MutableList<Set<String>> = Collections.synchronizedList(mutableListOf())
    }

    private fun CoroutineScope.store(
        state: HomeFeature.State,
        mode: InAppNoticeMode,
        clock: TestClock = TestClock(),
        recorded: Recorded = Recorded(),
        attention: Set<String> = emptySet(),
        snapshot: () -> OpenCodeHomeSnapshot? = { null },
    ): TestStore<HomeFeature.State, Action> {
        val store = TestStore(state, HomeFeature(), this) {
            this[Clock] = clock
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[InAppNoticeSettings] = InAppNoticeSettings.constant(mode)
            this[HapticsClient] = HapticsClient(play = { recorded.haptics += it.name })
            this[AccessibilityAnnouncer] = AccessibilityAnnouncer { text, urgent ->
                recorded.announcements += if (urgent) "urgent: $text" else text
            }
            this[DeliveredNotificationsClient] = DeliveredNotificationsClient(
                removeAll = { ids -> recorded.cleared += ids },
                removeNeedsInput = { ids -> recorded.clearedNeedsInput += ids },
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                viewSession = { _, _, _ -> },
                transcript = { _, _, _ -> emptyList() },
                homeAttention = { _, _, _ -> attention },
                homeSnapshot = { snapshot() ?: throw OpenCodeClientError("offline") },
            )
        }
        store.exhaustive = false
        return store
    }

    /** The run in [id] ended unread: a newer idle watermark nobody has read. */
    private suspend fun finish(id: String, store: TestStore<HomeFeature.State, Action>, idle: Long = 2_000) {
        val sessions = store.state.sessions.map { if (it.id == id) it.copy(idleMilliseconds = idle) else it }
        store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(emptyList(), sessions, emptySet())))
    }

    // Modes

    @Test fun offShowsNothing() = runTest {
        val recorded = Recorded()
        val store = store(home(), InAppNoticeMode.off, recorded = recorded)
        store.send(Action.AttentionRefreshed(setOf("upload")))
        finish("login", store)
        assertNull(store.state.inAppNotice)
        assertTrue(recorded.haptics.isEmpty())
        assertTrue(recorded.announcements.isEmpty())
        store.skipInFlightEffects()
    }

    @Test fun needsYourInputOnlyShowsRequestsWithOneLightHapticAndAnAnnouncement() = runTest {
        val recorded = Recorded()
        val store = store(home(), InAppNoticeMode.needsInput, recorded = recorded)
        finish("login", store)
        assertNull("Done is quiet in this mode", store.state.inAppNotice)

        store.send(Action.AttentionRefreshed(setOf("upload")))
        val notice = store.state.inAppNotice
        assertEquals("Upload tests", notice?.title)
        assertEquals("Needs your input", notice?.status)
        assertEquals(InAppNotice.Target.Session("upload"), notice?.target)
        assertEquals(6.seconds, notice?.hold)
        assertEquals(listOf("attention"), recorded.haptics.toList())
        assertEquals(listOf("urgent: Upload tests, Needs your input"), recorded.announcements.toList())
        store.skipInFlightEffects()
    }

    @Test fun allUpdatesShowsDoneQuietlyForFourSecondsThenFoldsBack() = runTest {
        val clock = TestClock()
        val recorded = Recorded()
        val store = store(home(), InAppNoticeMode.all, clock = clock, recorded = recorded)
        finish("login", store)
        assertEquals("Fix login flow", store.state.inAppNotice?.title)
        assertEquals("Done", store.state.inAppNotice?.status)
        assertEquals(4.seconds, store.state.inAppNotice?.hold)
        assertTrue(recorded.haptics.isEmpty())
        assertEquals(listOf("Fix login flow, Done"), recorded.announcements.toList())

        clock.advance(3.9.seconds)
        assertNotNull(store.state.inAppNotice)
        clock.advance(0.2.seconds)
        store.receive({ it is Action.InAppNoticeExpired }, "inAppNoticeExpired")
        assertNull(store.state.inAppNotice)
        store.skipInFlightEffects()
    }

    // Suppression

    @Test fun nothingForTheSessionOnScreen() = runTest {
        val state = home().copy(transcriptCache = mapOf("upload" to emptyList()))
        val store = store(state, InAppNoticeMode.all)
        store.send(Action.SessionTapped("upload"))
        assertEquals(true, store.state.sessionDetail?.isVisible)
        store.send(Action.AttentionRefreshed(setOf("upload")))
        assertNull(store.state.inAppNotice)
        // Leaving it doesn't bring the request back late.
        store.send(Action.NewSessionTapped)
        store.send(Action.AttentionRefreshed(setOf("upload")))
        assertNull(store.state.inAppNotice)
        store.skipInFlightEffects()
    }

    @Test fun nothingForSubagents() = runTest {
        val store = store(home(), InAppNoticeMode.all)
        store.send(Action.AttentionRefreshed(setOf("sub")))
        finish("sub", store)
        assertNull(store.state.inAppNotice)
        store.skipInFlightEffects()
    }

    @Test fun nothingWhileTheDrawerOrSettingsIsUpOrTheAppIsAway() = runTest {
        for (cover in listOf("drawer", "settings", "background")) {
            var state = home()
            state = when (cover) {
                "drawer" -> state.copy(isDrawerOpen = true)
                "settings" -> state.copy(settings = SettingsFeature.State(server = state.server))
                else -> state.copy(sessionDetail = state.sessionDetail?.copy(isVisible = false))
            }
            val store = store(state, InAppNoticeMode.all)
            store.send(Action.AttentionRefreshed(setOf("upload")))
            finish("login", store)
            assertNull(cover, store.state.inAppNotice)
            store.skipInFlightEffects()
        }
    }

    @Test fun theDrawerOpeningFoldsTheLabelAway() = runTest {
        val store = store(home(), InAppNoticeMode.all)
        store.send(Action.AttentionRefreshed(setOf("upload")))
        assertNotNull(store.state.inAppNotice)
        store.send(Action.SessionDrawerArrived(isOpen = true))
        assertNull(store.state.inAppNotice)
        store.skipInFlightEffects()
    }

    @Test fun nothingForARunAlreadyReadElsewhere() = runTest {
        val store = store(home(), InAppNoticeMode.all)
        val sessions = store.state.sessions.toMutableList()
        sessions[1] = sessions[1].copy(idleMilliseconds = 2_000, viewedMilliseconds = 2_000)
        store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(emptyList(), sessions, emptySet())))
        assertNull(store.state.inAppNotice)
        store.skipInFlightEffects()
    }

    /**
     * Upload tests already waits and Fix login flow already finished unread, as the server tells
     * it after a launch, a reconnect, or a return.
     */
    private fun waitingAlready(sessions: List<OpenCodeSession>): List<OpenCodeSession> =
        sessions.toMutableList().also { it[1] = it[1].copy(idleMilliseconds = 2_000) }

    @Test fun nothingFromAReconnectOrAColdList() = runTest {
        for (how in listOf("cold launch", "reconnect")) {
            val clock = TestClock()
            val recorded = Recorded()
            var state = home()
            if (how == "cold launch") state = state.copy(noticeReadiness = HomeFeature.State.NoticeReadiness.unsettled)
            val store = store(state, InAppNoticeMode.all, clock = clock, recorded = recorded, attention = setOf("upload"))
            if (how == "reconnect") {
                store.send(Action.ConnectionLost)
                assertEquals(HomeConnectionFeature.State.ConnectionStatus.reconnecting, store.state.connectionStatus)
            }
            // The list lands first; the attention read it starts lands 150 ms later.
            store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(emptyList(), waitingAlready(store.state.sessions), emptySet())))
            assertEquals(how, HomeFeature.State.NoticeReadiness.listed, store.state.noticeReadiness)
            clock.advance(200.milliseconds)
            store.receive({ it is Action.AttentionRefreshed }, "attentionRefreshed")
            assertEquals(how, HomeFeature.State.NoticeReadiness.settled, store.state.noticeReadiness)
            assertEquals(setOf("upload"), store.state.attentionSessionIDs)
            assertNull(how, store.state.inAppNotice)
            assertTrue(recorded.haptics.isEmpty())
            assertTrue(recorded.announcements.isEmpty())

            // From the settled baseline, a new run is news.
            finish("login", store, idle = 3_000)
            assertEquals(how, listOf("login"), store.state.inAppNotice?.events?.map { it.sessionID })
            store.skipInFlightEffects()
        }
    }

    @Test fun returningToTheAppReadsWhatEndedWhileAwayQuietly() = runTest {
        val clock = TestClock()
        val recorded = Recorded()
        var snapshot: OpenCodeHomeSnapshot? = null
        val store = store(home(), InAppNoticeMode.all, clock = clock, recorded = recorded, attention = setOf("upload"), snapshot = { snapshot })
        // While away, Upload tests asked and Fix login flow finished.
        snapshot = OpenCodeHomeSnapshot(emptyList(), waitingAlready(store.state.sessions), emptySet())
        store.send(Action.RetryConnectionTapped)
        assertEquals(HomeFeature.State.NoticeReadiness.unsettled, store.state.noticeReadiness)
        clock.advance(250.milliseconds)
        store.receive({ it is Action.SnapshotRefreshed }, "snapshotRefreshed")
        clock.advance(200.milliseconds)
        store.receive({ it is Action.AttentionRefreshed }, "attentionRefreshed")
        assertEquals(HomeFeature.State.NoticeReadiness.settled, store.state.noticeReadiness)
        assertNull(store.state.inAppNotice)
        assertTrue(recorded.haptics.isEmpty())
        assertTrue(recorded.announcements.isEmpty())
        store.skipInFlightEffects()
    }

    @Test fun nothingBesideAnIPadSidebar() = runTest {
        val state = home().copy(showsDrawerLabel = false)
        val recorded = Recorded()
        val store = store(state, InAppNoticeMode.all, recorded = recorded)
        store.send(Action.AttentionRefreshed(setOf("upload")))
        finish("login", store)
        assertNull(store.state.inAppNotice)
        assertTrue(recorded.haptics.isEmpty())
        assertTrue(recorded.announcements.isEmpty())
        assertEquals(InAppNoticeMode.off, InAppNoticeMode.all.effective(showsDrawerLabel = false))
        assertEquals(InAppNoticeMode.needsInput, InAppNoticeMode.needsInput.effective(showsDrawerLabel = true))
        store.skipInFlightEffects()
    }

    // Coalescing and repeats

    @Test fun aSecondEventUpdatesTheLabelInPlace() = runTest {
        val recorded = Recorded()
        val store = store(home(), InAppNoticeMode.all, recorded = recorded)
        finish("login", store)
        val id = store.state.inAppNotice?.id
        store.send(Action.AttentionRefreshed(setOf("upload")))
        val notice = store.state.inAppNotice
        assertEquals(id, notice?.id)
        assertEquals("2 sessions", notice?.title)
        assertEquals("1 needs your input", notice?.status)
        assertEquals(InAppNotice.Target.Drawer, notice?.target)
        assertEquals(6.seconds, notice?.hold)
        assertEquals(listOf("attention"), recorded.haptics.toList())

        store.send(Action.InAppNoticeTapped)
        assertNull(store.state.inAppNotice)
        assertEquals("several open the drawer, which the view does", true, store.state.sessionDetail?.isDraft)
        store.skipInFlightEffects()
    }

    @Test fun repeatsNeverShowTwice() = runTest {
        val clock = TestClock()
        val recorded = Recorded()
        val store = store(home(), InAppNoticeMode.all, clock = clock, recorded = recorded, attention = setOf("upload"))
        store.send(Action.AttentionRefreshed(setOf("upload")))
        store.send(Action.ServerEvent(OpenCodeServerEvent.AttentionChanged(askedSessionID = "upload")))
        finish("login", store)
        clock.advance(7.seconds)
        store.receive({ it is Action.InAppNoticeExpired }, "inAppNoticeExpired")
        assertNull(store.state.inAppNotice)

        // The same request, re-read, and the same run, read back by a stale list then the current
        // one, are old news.
        store.send(Action.AttentionRefreshed(setOf("upload")))
        finish("login", store, idle = 1_500)
        finish("login", store, idle = 2_000)
        assertNull(store.state.inAppNotice)
        assertEquals(2, recorded.announcements.size)

        // A new run is news again.
        finish("login", store, idle = 3_000)
        assertEquals(listOf("login"), store.state.inAppNotice?.events?.map { it.sessionID })
        store.skipInFlightEffects()
    }

    @Test fun answeredOrReadElsewhereLeavesTheLabel() = runTest {
        val store = store(home(), InAppNoticeMode.all)
        store.send(Action.AttentionRefreshed(setOf("upload")))
        finish("login", store)
        assertEquals(2, store.state.inAppNotice?.events?.size)
        store.send(Action.AttentionRefreshed(emptySet()))
        assertEquals(listOf("login"), store.state.inAppNotice?.events?.map { it.sessionID })
        val sessions = store.state.sessions.toMutableList()
        sessions[1] = sessions[1].copy(viewedMilliseconds = 2_000)
        store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(emptyList(), sessions, emptySet())))
        assertNull(store.state.inAppNotice)
        store.skipInFlightEffects()
    }

    @Test fun aNoticeThatGoesTakesItsTimerWithIt() = runTest {
        for (how in listOf("answered", "read", "opened")) {
            val clock = TestClock()
            val state = home().copy(transcriptCache = mapOf("upload" to emptyList(), "login" to emptyList()))
            val store = store(state, InAppNoticeMode.all, clock = clock)
            if (how == "read") {
                finish("login", store)
                clock.advance(200.milliseconds)
                val sessions = store.state.sessions.toMutableList()
                sessions[1] = sessions[1].copy(viewedMilliseconds = 2_000)
                store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(emptyList(), sessions, emptySet())))
            } else {
                store.send(Action.AttentionRefreshed(setOf("upload")))
                if (how == "answered") {
                    store.send(Action.AttentionRefreshed(emptySet()))
                } else {
                    store.send(Action.SessionTapped("upload"))
                }
            }
            assertNull(how, store.state.inAppNotice)
            // Only the short attention read is left; a 4 or 6 s timer would hold `finish` past its
            // timeout.
            clock.advance(200.milliseconds)
            store.skipReceivedActions()
            store.exhaustive = true
            store.finish()
        }
    }

    @Test fun tappingOpensTheSession() = runTest {
        val state = home().copy(transcriptCache = mapOf("upload" to emptyList()))
        val store = store(state, InAppNoticeMode.needsInput)
        store.send(Action.AttentionRefreshed(setOf("upload")))
        store.send(Action.InAppNoticeTapped)
        store.receive({ it is Action.SessionTapped }, "sessionTapped")
        assertNull(store.state.inAppNotice)
        assertEquals("upload", store.state.sessionDetail?.session?.id)
        store.skipInFlightEffects()
    }

    // Notification Center

    @Test fun viewingASessionClearsItsDeliveredNotifications() = runTest {
        val state = home().copy(transcriptCache = mapOf("login" to emptyList()))
        val recorded = Recorded()
        val store = store(state, InAppNoticeMode.off, recorded = recorded)
        store.send(Action.SessionTapped("login"))
        store.finish()
        assertEquals(setOf("login"), recorded.cleared.lastOrNull())
    }

    @Test fun answeringARequestClearsItsNeedsInputNotifications() = runTest {
        val state = home().copy(attentionSessionIDs = setOf("upload", "login"))
        val recorded = Recorded()
        val store = store(state, InAppNoticeMode.off, recorded = recorded)
        store.send(Action.AttentionRefreshed(setOf("login")))
        store.finish()
        assertEquals(listOf(setOf("upload")), recorded.clearedNeedsInput.toList())
    }
}
