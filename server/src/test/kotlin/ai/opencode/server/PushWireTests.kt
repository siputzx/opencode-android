package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.model.OpenCodePushBell
import ai.opencode.server.model.OpenCodePushCapability
import ai.opencode.server.model.OpenCodePushDevice
import ai.opencode.server.model.OpenCodePushLevel
import ai.opencode.server.model.OpenCodePushRegistration
import ai.opencode.server.model.OpenCodePushState
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The push routes' exact shapes: capability, registration, the server's copy and its writes. */
class PushWireTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test fun capabilityIsReadFromInfoAndMissingOnAnOlderServer() = runTest {
        val bodies = listOf(
            """{"urls":[],"capabilities":{"push":{"version":1,"relays":["https://relay.example"],"levels":["all","attention","none","later"]}}}""",
            """{"urls":[]}""",
        )
        var answered = 0
        val fixture = WireFixture { request ->
            if (request.route != "GET /api/info") return@WireFixture Reply.status(404)
            answered += 1
            Reply.Json(200, bodies[answered - 1])
        }
        try {
            val client = OpenCodeClient.liveValue
            assertEquals(
                OpenCodePushCapability(1, listOf("https://relay.example"), listOf(OpenCodePushLevel.all, OpenCodePushLevel.attention, OpenCodePushLevel.none)),
                client.pushCapability(fixture.server),
            )
            assertNull(client.pushCapability(fixture.server))
        } finally {
            fixture.close()
        }
    }

    @Test fun registrationPutsTheDeviceAndReadsTheServersKey() = runTest {
        val fixture = WireFixture { request ->
            if (request.route == "PUT /api/experimental/push/device/ref-1") Reply.Json(200, """{"publicKey":"server-key","extra":1}""") else Reply.status(404)
        }
        try {
            val registration = OpenCodePushRegistration(
                callbackURL = "https://relay.example/v2/notify/sealed",
                expiresAt = 1_800_000_000_000,
                publicKey = "phone-key",
                name = "Pixel 8",
                defaultLevel = OpenCodePushLevel.none,
            )
            assertEquals("server-key", OpenCodeClient.liveValue.registerPushDevice(fixture.server, "ref-1", registration))
            assertEquals(
                json("""{"callbackURL":"https://relay.example/v2/notify/sealed","expiresAt":1800000000000,"publicKey":"phone-key","platform":"android","name":"Pixel 8","settings":{"default":"none"}}"""),
                json(fixture.requests.single().bodyText),
            )
        } finally {
            fixture.close()
        }
    }

    @Test fun stateDecodesDevicesAndBellsAndSkipsUnknownLevels() = runTest {
        val body = """{
            "settings":{"paused":true,"until":5000},
            "devices":[{"id":"ref-1","name":"Pixel","platform":"android","expiresAt":9000,"settings":{"default":"attention"},"paused":false,
                "sessions":{"ses_a":{"level":"none","until":7000},"ses_b":{"level":"loud"}},"time":{"created":1,"updated":2}}],
            "sessions":{"ses_c":{"level":"all"}}
        }"""
        val fixture = WireFixture { request ->
            if (request.route == "GET /api/experimental/push") Reply.Json(200, body) else Reply.status(404)
        }
        try {
            val device = OpenCodePushDevice(
                id = "ref-1", name = "Pixel", platform = "android", expiresAt = 9000,
                defaultLevel = OpenCodePushLevel.attention, paused = false,
                sessions = mapOf("ses_a" to OpenCodePushBell(OpenCodePushLevel.none, 7000)),
            )
            assertEquals(
                OpenCodePushState(
                    paused = true, pausedUntil = 5000, devices = listOf(device),
                    sessions = mapOf("ses_c" to OpenCodePushBell(OpenCodePushLevel.all)),
                ),
                OpenCodeClient.liveValue.pushState(fixture.server),
            )
        } finally {
            fixture.close()
        }
    }

    @Test fun writesUseTheContractsRoutesAndBodies() = runTest {
        val fixture = WireFixture { Reply.Json(200, "{}") }
        try {
            val client = OpenCodeClient.liveValue
            client.setPushSessionBell(fixture.server, "ref-1", "ses_a", OpenCodePushBell(OpenCodePushLevel.none, 3_600_000))
            client.setPushSessionBell(fixture.server, "ref-1", "ses_a", OpenCodePushBell(OpenCodePushLevel.all))
            client.setPushSessionBell(fixture.server, "ref-1", "ses_a", null)
            client.setPushDevicePaused(fixture.server, "ref-2", true)
            client.setPushPaused(fixture.server, true, 9_000)
            client.setPushPaused(fixture.server, false, null)
            client.removePushDevice(fixture.server, "ref-2")

            val sent = mutableListOf<Pair<String, JsonElement?>>()
            for (request in fixture.requests) {
                val text = request.bodyText
                sent += request.route to (if (text.isEmpty()) null else json(text))
            }
            assertEquals(
                listOf(
                    "PUT /api/experimental/push/device/ref-1/session/ses_a" to json("""{"level":"none","until":3600000}"""),
                    "PUT /api/experimental/push/device/ref-1/session/ses_a" to json("""{"level":"all"}"""),
                    "DELETE /api/experimental/push/device/ref-1/session/ses_a" to null,
                    "PATCH /api/experimental/push/device/ref-2" to json("""{"paused":true}"""),
                    "PUT /api/experimental/push/settings" to json("""{"paused":true,"until":9000}"""),
                    "PUT /api/experimental/push/settings" to json("""{"paused":false}"""),
                    "DELETE /api/experimental/push/device/ref-2" to null,
                ),
                sent,
            )
        } finally {
            fixture.close()
        }
    }
}
