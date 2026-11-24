package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.live.ServerInfoPayload
import ai.opencode.server.live.request
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class AppFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private suspend fun expectClientError(block: suspend () -> Unit): OpenCodeClientError {
        try {
            block()
        } catch (error: OpenCodeClientError) {
            return error
        }
        throw AssertionError("Expected an OpenCodeClientError")
    }

    @Test
    fun homeCacheWrittenByEarlierBuildsStillDecodes() {
        val legacy = """{"serverID":"kit|https://saved.example","version":"1.2.3","snapshot":{"projects":[],"sessions":[],"activeSessionIDs":[]}}"""
        val cache = json.decodeFromString(CachedHomeSnapshot.serializer(), legacy)
        assertEquals(
            CachedHomeSnapshot(
                serverID = "kit|https://saved.example",
                snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()),
            ),
            cache,
        )

        val cached = """{"serverID":"kit|https://saved.example","snapshot":{"projects":[],"sessions":[{"id":"ses_1","title":"Release","projectID":"global","directory":"/tmp","updatedAt":"2026-05-09T01:46:40Z","cost":0.5}],"activeSessionIDs":[]}}"""
        val session = json.decodeFromString(CachedHomeSnapshot.serializer(), cached).snapshot.sessions.first()
        assertEquals(
            OpenCodeSession(
                id = "ses_1", serverTitle = "Release", projectID = "global", directory = "/tmp",
                updatedAt = Instant.parse("2026-05-09T01:46:40Z"), cost = 0.5,
            ),
            session,
        )
    }

    @Test
    fun v2PairingLinkDecodesOriginAndOneTimeCode() {
        assertEquals(
            OpenCodePairingLink(origin = "http://10.0.10.26:4196", code = "Ab3_x-9Q"),
            OpenCodePairingLink.decode("  http://10.0.10.26:4196/auth/connect/Ab3_x-9Q\n"),
        )
        assertEquals("https://box.example", OpenCodePairingLink.decode("https://box.example/auth/connect/code")?.origin)
        assertEquals(true, OpenCodePairingLink.decode("http://127.0.0.1:4096/auth/connect/code")?.isLoopback)
    }

    /** Pairing links as people copy them. */
    @Test
    fun copiedPairingLinksDecode() {
        val link = "https://abcdefghijklmnop.qrstuvwxyz12.opentunnel.xyz/auth/connect/Ab3_x-9QAb3_x-9QAb3_x-"
        val expected = OpenCodePairingLink.decode(link)!!
        val line = "  $link"
        for (copy in listOf(
            line.take(80) + "\n" + line.drop(80), // an 80-column terminal's wrap, inside the code
            link.chunked(29).joinToString("\n  "), // the TUI's /pair box
            link.take(50) + "\r\n" + link.drop(50),
            "\n  Open a link to connect. Links work once and expire in 5 minutes.\n\n$line\n\n  Over SSH? Forward the port.\n",
            "Pair with: $link (expires in 5 min)",
            "$link.",
            "<$link>",
            "\"$link\"",
            "[link]($link)",
        )) {
            assertEquals(copy, expected, OpenCodePairingLink.decodeCopied(copy))
        }
        // The TUI lists one link per address: one link with every address; loopback is never redeemed.
        val listed = OpenCodePairingLink.decodeCopied("Links\nhttp://127.0.0.1:4096/auth/connect/Ab3_x-9QAb3_x-9QAb3_x-\n$link")
        assertEquals(listOf("http://127.0.0.1:4096", expected.origin), listed?.candidateOrigins)
        assertEquals(listOf(expected.origin), listed?.redeemOrigins)
        for (text in listOf(
            "hello",
            "see https://example.com/docs.",
            "https://box.example/auth/connect/code?next=/",
            "https://evil.example/?u=https://box.example/auth/connect/code",
        )) {
            assertNull(text, OpenCodePairingLink.decodeCopied(text))
        }
    }

    @Test
    fun rejectsMalformedPairingLinks() {
        for (link in listOf(
            "ftp://box.example/auth/connect/code",
            "https://box.example/auth/connect/",
            "https://box.example/auth/connect/code/extra",
            "https://box.example/connect/code",
            "https://box.example/auth/connect/code?next=/",
            "https://box.example/auth/connect/code#frag",
            "https://user:pass@box.example/auth/connect/code",
            "https://box.example/auth/connect/co%2Fde",
        )) {
            assertNull(link, OpenCodePairingLink.decode(link))
        }
    }

    @Test
    fun pairingPayloadListsEveryAddressInOrder() {
        assertEquals(
            OpenCodePairingLink(origin = "http://192.168.1.10:4096", code = "Ab3_x-9Q", alternateOrigins = listOf("http://100.64.0.3:4096")),
            OpenCodePairingLink.decode("""{"code":"Ab3_x-9Q","urls":["http://192.168.1.10:4096","http://100.64.0.3:4096"]}"""),
        )
        val duplicated = OpenCodePairingLink.decode(
            """{"code":"abc","urls":["HTTP://192.168.1.10:4096","https://box.example","http://192.168.1.10:4096/"]}""",
        )
        assertEquals("http://192.168.1.10:4096", duplicated?.origin)
        assertEquals(listOf("http://192.168.1.10:4096", "https://box.example"), duplicated?.candidateOrigins)
        assertEquals(true, OpenCodePairingLink.decode("""{"code":"abc","urls":["http://127.0.0.1:4096"]}""")?.isLoopback)
        assertEquals(false, OpenCodePairingLink.decode("""{"code":"abc","urls":["http://127.0.0.1:4096","http://10.0.0.2:4096"]}""")?.isLoopback)
    }

    @Test
    fun rejectsMalformedPairingPayloads() {
        for (payload in listOf(
            """{"code":"ab/c","urls":["http://192.168.1.10:4096"]}""",
            """{"code":"åbc","urls":["http://192.168.1.10:4096"]}""",
            """{"code":"","urls":["http://192.168.1.10:4096"]}""",
            """{"code":"abc","urls":[]}""",
            """{"code":"abc"}""",
            """{"urls":["http://192.168.1.10:4096"]}""",
            """{"code":"abc","urls":["ftp://192.168.1.10:4096"]}""",
            """{"code":"abc","urls":["http://192.168.1.10:4096","not a url"]}""",
            """{"code":"abc","urls":["http://192.168.1.10:4096","https://box.example/api"]}""",
            """{"code":"abc","urls":["http://user:pass@192.168.1.10:4096"]}""",
            """{"code":"abc","urls":["http://192.168.1.10:4096?x=1"]}""",
            """{"code":"abc","urls":"http://192.168.1.10:4096"}""",
            """{"code":"abc","urls":["http://192.168.1.10:4096"]""",
        )) {
            assertNull(payload, OpenCodePairingLink.decode(payload))
        }
    }

    @Test
    fun customSchemePairingDeepLinksDecodeEveryReachableAddress() {
        val single = OpenCodePairingLink.decode("opencode://pair?code=Ab3_x-9Q&url=https://mini-trial.taila8e7c7.ts.net:4196")
        assertEquals(OpenCodePairingLink(origin = "https://mini-trial.taila8e7c7.ts.net:4196", code = "Ab3_x-9Q"), single)

        val multiple = OpenCodePairingLink.decode(
            "opencode-ios://pair?code=Ab3_x-9Q&url=http://192.168.1.10:4196&url=https://mini-trial.taila8e7c7.ts.net:4196/",
        )
        assertEquals(
            OpenCodePairingLink(
                origin = "http://192.168.1.10:4196", code = "Ab3_x-9Q",
                alternateOrigins = listOf("https://mini-trial.taila8e7c7.ts.net:4196"),
            ),
            multiple,
        )

        val pathForm = OpenCodePairingLink.decode(
            "opencode-ios://connect/Ab3_x-9Q?urls=%5B%22https%3A%2F%2Fmini-trial.taila8e7c7.ts.net%3A4196%22%5D",
        )
        assertEquals(OpenCodePairingLink(origin = "https://mini-trial.taila8e7c7.ts.net:4196", code = "Ab3_x-9Q"), pathForm)

        val roundTrip = multiple!!.deepLinkURL
        assertEquals("opencode-ios", URI(roundTrip).scheme)
        assertEquals(multiple, OpenCodePairingLink.decode(roundTrip))
        assertTrue(OpenCodePairingLink.shouldHandleOpenURL(roundTrip))
    }

    @Test
    fun rejectsCredentialBearingOrMalformedPairingLinksButStillInterceptsEndpoint() {
        for (link in listOf(
            "opencode-ios://connect?url=https://mini-trial.taila8e7c7.ts.net:4196&username=opencode&password=synthetic-pw",
            "opencode://pair?code=Ab3_x-9Q&url=https://mini-trial.taila8e7c7.ts.net:4196&password=synthetic-pw",
            "opencode://pair?code=Ab3_x-9Q&url=https://mini-trial.taila8e7c7.ts.net:4196&token=synthetic-token",
            "opencode://user:pass@pair?code=Ab3_x-9Q&url=https://mini-trial.taila8e7c7.ts.net:4196",
            "opencode://pair:4196?code=Ab3_x-9Q&url=https://mini-trial.taila8e7c7.ts.net:4196",
            "opencode://pair/other?code=Ab3_x-9Q&url=https://mini-trial.taila8e7c7.ts.net:4196",
            "opencode://pair?code=Ab3_x-9Q&url=ftp://mini-trial.taila8e7c7.ts.net:4196",
            "opencode://pair?code=Ab3_x-9Q&url=https://mini-trial.taila8e7c7.ts.net:4196#frag",
            "opencode://pair?code=Ab3_x-9Q",
            "https://opencode.abc123.opentunnel.xyz/auth/connect/Ab3_x-9Q#frag",
            "https://opencode.abc123.opentunnel.xyz/auth/connect/Ab3_x-9Q?extra=1",
            "https://opencode.abc123.opentunnel.xyz/auth/connect/bad!code",
        )) {
            assertNull(link, OpenCodePairingLink.decode(link))
            assertTrue(link, OpenCodePairingLink.isPairingEndpoint(link))
            // The string checks apply to every link here.
            assertTrue(link, OpenCodePairingLink.shouldHandleOpenURL(link))
            assertNull(link, OpenCodePairingLink.shareableURL(link))
        }
    }

    @Test
    fun anUnusableAddressInAPairingCodeIsNamedWithoutItsSecrets() {
        val code = "Ab3_x-9QAb3_x-9QAb3_x-"
        // `opencode pair --url` behind a reverse proxy at a path: the one address can't work.
        val withPath = """{"code":"$code","urls":["http://192.168.1.10:4096","https://box.example/opencode"]}"""
        assertNull(OpenCodePairingLink.decode(withPath))
        assertEquals(
            OpenCodePairingLink.UnusableAddress("https://box.example/opencode", "has a path, and the server must be at the root of its address"),
            OpenCodePairingLink.unusableAddress(withPath),
        )
        val withSecrets = "opencode://pair?code=$code&url=" + java.net.URLEncoder.encode("https://kit:hunter2@box.example/?token=abc", "UTF-8")
        assertNull(OpenCodePairingLink.decode(withSecrets))
        assertEquals(
            OpenCodePairingLink.UnusableAddress("https://box.example/", "includes a user name or password"),
            OpenCodePairingLink.unusableAddress(withSecrets),
        )
        assertEquals("doesn’t start with http:// or https://", OpenCodePairingLink.unusableAddress("""{"code":"$code","urls":["ftp://box.example"]}""")?.reason)
        assertEquals("has a port outside 1–65535", OpenCodePairingLink.unusableAddress("""{"code":"$code","urls":["http://box.example:70000"]}""")?.reason)
        // Not a pairing code at all, a bad code, or nothing wrong with the addresses: no address to name.
        assertNull(OpenCodePairingLink.unusableAddress("https://box.example/opencode"))
        assertNull(OpenCodePairingLink.unusableAddress("""{"code":"bad!code","urls":["https://box.example/opencode"]}"""))
        assertNull(OpenCodePairingLink.unusableAddress("""{"code":"$code","urls":["https://box.example"]}"""))
    }

    @Test
    fun alreadyUsedPairingCodeReportsClearErrorWithoutRetrying() = runTest {
        val consumed = AtomicBoolean(false)
        val fixture = WireFixture { request ->
            if (request.route != "GET /auth/connect/synth_code_1") return@WireFixture Reply.status(404)
            if (consumed.getAndSet(true)) {
                Reply.Json(401, """{"error":"invalid or expired code"}""")
            } else {
                Reply.Json(200, """{"token":"1790000000.synthetic_token_signature"}""")
            }
        }
        try {
            val link = OpenCodePairingLink.decode("opencode://pair?code=synth_code_1&url=${fixture.server.url}")
            assertNotNull(link)
            // First redemption consumes the single-use code.
            val firstServer = OpenCodeClient.liveValue.redeemPairing(link!!)
            assertEquals("1790000000.synthetic_token_signature", firstServer.password)
            assertEquals(1, fixture.requests.count { it.path == "/auth/connect/synth_code_1" })

            // A second attempt (e.g. after a browser preview consumed the code) fails once with 401 and no retry.
            val error = expectClientError { OpenCodeClient.liveValue.redeemPairing(link) }
            assertEquals(401, error.statusCode)
            assertEquals("This pairing code expired or was already used. Run `opencode pair --remote` again.", error.message)
            assertEquals(2, fixture.requests.count { it.path == "/auth/connect/synth_code_1" })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun pairingRedemptionPrefersTheCodesAddressesAndKeepsReportedOnesAsFallbacks() = runTest {
        val tunnel = "https://k7f3m2qz9x.opentunnel.dev"
        val fixture = WireFixture { request ->
            when (request.route) {
                "GET /auth/connect/synth_code_2" -> Reply.Json(200, """{"token":"1790000000.synthetic_token_signature"}""")
                "GET /api/info" -> Reply.Json(
                    200,
                    """{"version":"2.0.0","pid":1,"urls":["http://127.0.0.1:4096","http://192.168.1.10:4096","$tunnel/"],"paths":{"tmp":"/tmp"}}""",
                )
                else -> Reply.status(404)
            }
        }
        try {
            // `opencode pair --url <address>`: the code lists only the address the person chose.
            val link = OpenCodePairingLink.decode("""{"code":"synth_code_2","urls":["${fixture.server.url}"]}""")
            val server = OpenCodeClient.liveValue.redeemPairing(link!!)
            assertEquals(fixture.server.url, server.url)
            assertEquals(listOf(fixture.server.url), server.preferredURLs)
            assertEquals("Reported addresses stay as fallbacks, without loopback", listOf("http://192.168.1.10:4096", tunnel), server.alternateURLs)
            // The fixture's name is an internet address, so the LAN one isn't held back; the tunnel is.
            assertEquals(listOf(fixture.server.url, "http://192.168.1.10:4096"), server.firstAttemptURLs)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun pairingRedemptionRejectsRedirectsWhileOrdinaryRequestsFollowRedirectsAsBefore() = runTest {
        val redirectTarget = WireFixture(scheme = "https") { Reply.Json(200, """{"token":"1790000000.redirected_token_signature"}""") }
        val targetURL = redirectTarget.server.url
        val redirectSource = WireFixture(scheme = "https") { request ->
            when (request.route) {
                "GET /auth/connect/synth_redirect" ->
                    Reply.Json(302, "", headers = mapOf("Location" to "$targetURL/auth/connect/synth_redirect"))
                else -> Reply.status(404)
            }
        }
        val ordinaryTarget = WireFixture(scheme = "http") {
            Reply.Json(200, """{"version":"2.0.0","pid":1,"urls":[],"paths":{"tmp":"/tmp"}}""")
        }
        val ordinaryTargetURL = ordinaryTarget.server.url
        val ordinaryRedirectSource = WireFixture(scheme = "https") {
            Reply.Json(302, "", headers = mapOf("Location" to "$ordinaryTargetURL/api/info"))
        }
        try {
            val link = OpenCodePairingLink.decode("opencode-ios://pair?code=synth_redirect&url=${redirectSource.server.url}")!!
            val error = expectClientError { OpenCodeClient.liveValue.redeemPairing(link) }
            assertEquals(302, error.statusCode)
            assertEquals(1, redirectSource.requests.size)
            assertTrue("Single-use pairing redemption must never follow a redirect", redirectTarget.requests.isEmpty())

            // Ordinary requests keep the default redirect handling.
            request<ServerInfoPayload>("/api/info", server = ordinaryRedirectSource.server)
            assertEquals(1, ordinaryRedirectSource.requests.size)
            assertEquals(1, ordinaryTarget.requests.size)

            // An address probe doesn't: a redirect is a captive portal, not the server.
            expectClientError { OpenCodeClient.liveValue.health(ordinaryRedirectSource.server) }
            assertEquals(2, ordinaryRedirectSource.requests.size)
            assertEquals(1, ordinaryTarget.requests.size)
        } finally {
            redirectTarget.close()
            redirectSource.close()
            ordinaryTarget.close()
            ordinaryRedirectSource.close()
        }
    }

    @Test
    fun pairedServersAreRecognisedByTheirSessionToken() {
        assertTrue(OpenCodeServer(url = "http://a", username = "opencode", password = "1792565443.Xr2dC8pLq0Z_vY4mN7bK1sA9eT3uW6jH5fG").usesPairingToken)
        assertFalse(OpenCodeServer(url = "http://a", username = "opencode", password = "onboarding-test").usesPairingToken)
    }

    @Test
    fun reachabilityRanksPublicThenTailscaleThenLANThenLoopback() {
        val urls = listOf(
            "http://127.0.0.1:4102", "http://10.0.10.15:4102", "http://192.168.139.3:4102", "http://172.28.0.1:4102",
            "http://100.84.220.87:4102", "https://box.ts.net:4102", "http://203.0.113.9:4102",
        )
        val ranked = urls.withIndex()
            .sortedWith(compareBy({ OpenCodeServer.reachabilityRank(it.value) }, { it.index }))
            .map { it.value }
        assertEquals(
            listOf(
                "http://203.0.113.9:4102",
                "https://box.ts.net:4102",
                "http://100.84.220.87:4102",
                "http://10.0.10.15:4102",
                "http://192.168.139.3:4102",
                "http://172.28.0.1:4102",
                "http://127.0.0.1:4102",
            ),
            ranked,
        )
    }
}
