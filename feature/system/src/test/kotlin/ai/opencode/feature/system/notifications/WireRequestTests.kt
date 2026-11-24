package ai.opencode.feature.system.notifications

import ai.opencode.core.testing.TestIsolationRule
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class WireRequestTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun aCallbackIsReusedOnlyForTheSameTokenAndKeyWithThreeDaysLeft() {
        val relay = "https://relay.example".toHttpUrl()
        val now = Instant.ofEpochSecond(100)
        val cached = SessionNotifications.Callback(
            url = "https://relay.example/v2/notify/test", expiresAt = now.plusSeconds(30L * 86400),
            token = "device", environment = SessionNotifications.FCM_ENVIRONMENT, phonePublicKey = "old",
        )
        assertTrue(cached.isReusable("device", "old", relay, now))
        assertFalse(cached.isReusable("device", "new", relay, now))
        assertFalse(cached.isReusable("other-device", "old", relay, now))
        assertFalse(cached.isReusable("device", "old", relay, now.plusSeconds(27L * 86400)))
    }

    @Test
    fun oldOrForeignNotificationCapabilitiesNeverReachAPrompt() {
        val relay = "https://relay.example".toHttpUrl()
        assertTrue(SessionNotifications.isEncryptedCallback("https://relay.example/v2/notify/a_B-3", relay))
        for (address in listOf(
            "https://relay.example/v1/notify/abc", "http://relay.example/v2/notify/abc",
            "https://relay.example:444/v2/notify/abc", "https://other.example/v2/notify/abc",
            "https://user@relay.example/v2/notify/abc", "https://relay.example/v2/notify/a%2Fb",
            "https://relay.example/v2/notify/abc?extra=1", "https://relay.example/v2/notify/abc#extra",
        )) {
            assertFalse(address, SessionNotifications.isEncryptedCallback(address, relay))
        }
    }
}
