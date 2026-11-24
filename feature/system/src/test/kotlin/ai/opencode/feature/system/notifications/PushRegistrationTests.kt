package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodePushBell
import ai.opencode.server.model.OpenCodePushDevice
import ai.opencode.server.model.OpenCodePushLevel
import ai.opencode.server.model.OpenCodePushState
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.days

/** Registration once per server: one token request, a callback reused until three days are left. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushRegistrationTests {
    @get:Rule val isolation = TestIsolationRule()

    private val start = Instant.parse("2026-11-01T00:00:00Z")
    private val relay = SessionNotifications.RELAY_URL.trimEnd('/')

    @Test
    fun registrationReusesTheCallbackUntilItNeedsRenewing() = runBlocking {
        val tokenRequests = AtomicInteger(0)
        val mints = AtomicInteger(0)
        val established = mutableListOf<String>()
        val notifications = SessionNotifications(
            tokens = PushTokenProvider { onToken, _ ->
                tokenRequests.incrementAndGet()
                onToken("token-1")
            },
            mintCallback = { _, _ ->
                val count = mints.incrementAndGet()
                SessionNotifications.Minted("$relay/v2/notify/sealed$count", start.plusMillis(30.days.inWholeMilliseconds))
            },
        )
        val fixture = WireFixture { request ->
            if (request.method == "PUT") Reply.Json(200, """{"publicKey":"server-key"}""") else Reply.status(404)
        }
        var now = start
        try {
            withDependenciesSuspending({
                this[SharedStore] = SharedStore.inMemory()
                this[AppContext] = RuntimeEnvironment.getApplication()
                this[DateClient] = DateClient { now }
                this[OpenCodeClient] = OpenCodeClient.liveValue
                this[NotificationKeysClient] = NotificationKeysClient(
                    prepare = { "phone-key" },
                    establish = { _, key -> established += key },
                    remove = {},
                    verify = { null },
                )
            }) {
                // Four callers at once ask the push channel for one token.
                val results = coroutineScope {
                    val calls = (1..4).map { async { notifications.register(fixture.server, OpenCodePushLevel.all) } }
                    calls.awaitAll()
                }
                assertEquals(listOf(true, true, true, true), results)
                assertEquals(1, tokenRequests.get())
                assertEquals(1, mints.get())

                // A later connect keeps the callback while more than three days are left.
                now = start.plusMillis(26.days.inWholeMilliseconds)
                assertTrue(notifications.register(fixture.server, OpenCodePushLevel.none))
                assertEquals(1, mints.get())

                now = start.plusMillis(28.days.inWholeMilliseconds)
                assertTrue(notifications.register(fixture.server, OpenCodePushLevel.none))
                assertEquals(2, mints.get())

                // A new token needs a new callback.
                notifications.didChangeToken("token-2")
                assertTrue(notifications.register(fixture.server, OpenCodePushLevel.none))
                assertEquals(3, mints.get())
            }
            val puts = fixture.requests.filter { it.method == "PUT" }
            assertEquals(7, puts.size)
            val path = puts.first().path
            assertTrue(path.startsWith("/api/experimental/push/device/"))
            for (put in puts) assertEquals(path, put.path)
            val last = puts.last().bodyText
            assertTrue(last, last.contains("\"callbackURL\":\"$relay/v2/notify/sealed3\""))
            assertTrue(last, last.contains("\"settings\":{\"default\":\"none\"}"))
            assertEquals(7, established.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aFailedRegistrationReportsFalse() = runBlocking {
        val notifications = SessionNotifications(
            tokens = PushTokenProvider { onToken, _ -> onToken("token-1") },
            mintCallback = { _, _ -> null },
        )
        withDependenciesSuspending({
            this[SharedStore] = SharedStore.inMemory()
            this[AppContext] = RuntimeEnvironment.getApplication()
            this[NotificationKeysClient] = NotificationKeysClient(prepare = { "phone-key" }, establish = { _, _ -> }, remove = {}, verify = { null })
        }) {
            val server = OpenCodeServer(url = "https://unused.invalid", username = "", password = "")
            assertFalse(notifications.register(server, OpenCodePushLevel.all))
        }
    }
}

/** What the bell shows: the server's pause, this phone's pause, its own bell, the shared bell, its default. */
class PushBellTests {
    private val device = OpenCodePushDevice(
        id = "ref-1", name = "Pixel", platform = "android", expiresAt = 1,
        defaultLevel = OpenCodePushLevel.attention, paused = false, sessions = emptyMap(),
    )

    private fun server(state: OpenCodePushState) = PushServer(PushStatus.Ready, OpenCodePushLevel.entries, "ref-1", state)

    @Test
    fun theFirstRuleThatHoldsWins() {
        val now = 1_000L
        var state = OpenCodePushState(devices = listOf(device))
        assertEquals(PushBellState(OpenCodePushLevel.attention, null, PushBellSource.PhoneDefault), PushBells.effective(server(state), "ses_a", now))

        state = state.copy(sessions = mapOf("ses_a" to OpenCodePushBell(OpenCodePushLevel.none, until = 5_000)))
        assertEquals(PushBellState(OpenCodePushLevel.none, 5_000, PushBellSource.Shared), PushBells.effective(server(state), "ses_a", now))

        val own = device.copy(sessions = mapOf("ses_a" to OpenCodePushBell(OpenCodePushLevel.all)))
        state = state.copy(devices = listOf(own))
        assertEquals(PushBellState(OpenCodePushLevel.all, null, PushBellSource.ThisPhone), PushBells.effective(server(state), "ses_a", now))

        state = state.copy(devices = listOf(own.copy(paused = true)))
        assertEquals(PushBellSource.PhonePaused, PushBells.effective(server(state), "ses_a", now)?.source)

        state = state.copy(paused = true, pausedUntil = 9_000)
        assertEquals(PushBellState(OpenCodePushLevel.none, 9_000, PushBellSource.ServerPaused), PushBells.effective(server(state), "ses_a", now))
    }

    @Test
    fun anEndedTimeNoLongerCounts() {
        val own = device.copy(sessions = mapOf("ses_a" to OpenCodePushBell(OpenCodePushLevel.none, until = 500)))
        val state = OpenCodePushState(paused = true, pausedUntil = 500, devices = listOf(own))
        assertEquals(PushBellSource.PhoneDefault, PushBells.effective(server(state), "ses_a", 1_000)?.source)
    }

    @Test
    fun nothingIsShownUntilTheServerKnowsThisPhone() {
        val state = OpenCodePushState(devices = listOf(device.copy(id = "ref-other")))
        assertEquals(null, PushBells.effective(server(state), "ses_a", 0))
    }

    @Test
    fun theRelayIsMatchedByItsOrigin() {
        val relays = listOf("https://relay.example", "http://localhost:8787")
        assertTrue(PushSync.isListed("https://relay.example/", relays))
        assertTrue(PushSync.isListed("https://relay.example:443", relays))
        assertTrue(PushSync.isListed("http://localhost:8787", relays))
        assertFalse(PushSync.isListed("https://relay.example:8443", relays))
        assertFalse(PushSync.isListed("https://other.example", relays))
    }

    @Test
    fun aSendTurnsThisPhonesBellOnUnlessItHasOneThatStillHolds() = runBlocking {
        val testServer = OpenCodeServer(url = "https://unused.invalid", username = "", password = "")
        val sessions = mapOf(
            "ses_own" to OpenCodePushBell(OpenCodePushLevel.none),
            "ses_ended" to OpenCodePushBell(OpenCodePushLevel.none, until = 500),
        )
        val written = mutableListOf<String>()
        withDependenciesSuspending({
            this[SharedStore] = SharedStore.inMemory()
            this[DateClient] = DateClient { Instant.ofEpochMilli(1_000) }
            this[SessionNotificationsClient] = SessionNotificationsClient(isAvailable = true, ref = { "ref-1" })
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                pushState = { OpenCodePushState(devices = listOf(device.copy(sessions = sessions))) },
                setPushSessionBell = { _, _, sessionID, bell ->
                    assertEquals(OpenCodePushBell(OpenCodePushLevel.all), bell)
                    written += sessionID
                },
            )
        }) {
            PushSync.bellAfterSend(testServer, "ses_new")
            assertEquals(emptyList<String>(), written)

            val choices = shared(PushSharedKeys.choices)
            choices.set(mapOf(testServer.persistenceID to PushChoice(mode = PushMode.fromThisPhone)))
            for (sessionID in listOf("ses_new", "ses_own", "ses_ended")) PushSync.bellAfterSend(testServer, sessionID)
            assertEquals(listOf("ses_new", "ses_ended"), written)
        }
    }
}
