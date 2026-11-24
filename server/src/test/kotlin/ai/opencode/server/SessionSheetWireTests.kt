package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.live.decodeContextUsage
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeContextUsage
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.model
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class SessionSheetWireTests {
    @get:Rule val isolation = TestIsolationRule()

    private val model = OpenCodeModelRef(id = "fixture-model", providerID = "fixture")
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test fun contextDecodingMatchesTheTUI() {
        fun reading(messages: String, next: Boolean = false): OpenCodeContextReading {
            val cursor = if (next) ""","cursor":{"next":"c1"}""" else ""
            return decodeContextUsage("""{"data":[$messages]$cursor}""".toByteArray())
        }
        val tokens = """"tokens":{"input":1000,"output":200,"reasoning":50,"cache":{"read":3000,"write":10}}"""
        val model = """"model":{"id":"fixture-model","providerID":"fixture"}"""
        // Newest first: a reply still streaming has no usage yet; the one before counts.
        assertEquals(
            OpenCodeContextReading.Measured(OpenCodeContextUsage(tokens = 4_260, model = this.model)),
            reading("""{"type":"assistant",$model},{"type":"user"},{"type":"assistant",$model,$tokens}"""),
        )
        // A completed compaction resets the window; a failed one does not.
        assertEquals(OpenCodeContextReading.Empty, reading("""{"type":"compaction","status":"completed"},{"type":"assistant",$model,$tokens}"""))
        assertEquals(
            OpenCodeContextReading.Measured(OpenCodeContextUsage(tokens = 4_260, model = this.model)),
            reading("""{"type":"compaction","status":"failed"},{"type":"assistant",$model,$tokens}"""),
        )
        // Zero usage reads as nothing, like the TUI's `tokens <= 0`.
        assertEquals(
            OpenCodeContextReading.Empty,
            reading("""{"type":"assistant",$model,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}"""),
        )
        // A window of user messages says nothing unless it reaches the start.
        assertEquals(OpenCodeContextReading.Inconclusive, reading("""{"type":"user"},{"type":"user"}""", next = true))
        assertEquals(OpenCodeContextReading.Empty, reading("""{"type":"user"}"""))
        try {
            decodeContextUsage("[]".toByteArray())
            fail("Expected an OpenCodeClientError")
        } catch (_: OpenCodeClientError) {
        }
    }

    @Test fun sheetRoutesMatchTheV2Contract() = runTest {
        val info = """{"id":"ses_fork","projectID":"prj","title":"Forked","cost":1.5,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/work/app"}}"""
        val fixture = WireFixture { request ->
            when (request.route) {
                "PATCH /api/session/ses_1" -> Reply.status(204)
                "POST /api/session/ses_1/compact" -> Reply.Json(200, """{"data":{"id":"msg_c","sessionID":"ses_1","time":{"created":1},"type":"compaction","payload":{},"delivery":"steer"}}""")
                "POST /api/session/ses_1/fork" -> Reply.Json(200, """{"data":$info}""")
                "GET /api/experimental/session/ses_1/export" -> Reply.Json(200, TRANSFER)
                "GET /api/session/ses_1/message" -> Reply.Json(200, """{"data":[{"type":"assistant","model":{"id":"m","providerID":"p"},"tokens":{"input":5,"output":1,"reasoning":0,"cache":{"read":4,"write":0}}}]}""")
                else -> Reply.status(404)
            }
        }
        try {
            val client = OpenCodeClient.liveValue
            client.renameSession(fixture.server, "ses_1", "New title")
            client.compactSession(fixture.server, "ses_1")
            val forked = client.forkSession(fixture.server, "ses_1")
            val exported = client.exportSession(fixture.server, "ses_1")
            val context = client.contextUsage(fixture.server, "ses_1")

            assertTrue(forked.id == "ses_fork" && forked.cost == 1.5 && forked.directory == "/work/app")
            assertArrayEquals(TRANSFER.toByteArray(), exported)
            assertEquals(OpenCodeContextReading.Measured(OpenCodeContextUsage(tokens = 10, model = OpenCodeModelRef(id = "m", providerID = "p"))), context)
            val requests = fixture.requests
            assertEquals(
                listOf(
                    "PATCH /api/session/ses_1", "POST /api/session/ses_1/compact", "POST /api/session/ses_1/fork",
                    "GET /api/experimental/session/ses_1/export", "GET /api/session/ses_1/message",
                ),
                requests.map { it.route },
            )
            assertEquals(json("""{"title":"New title"}"""), requests[0].json())
            assertEquals(json("{}"), requests[1].json())
            assertEquals(json("{}"), requests[2].json())
            assertEquals("desc", requests[4].queryValue("order"))
            assertEquals("8", requests[4].queryValue("limit"))
        } finally {
            fixture.close()
        }
    }

    @Test fun modelCatalogCarriesTheContextWindow() = runTest {
        val fixture = WireFixture { request ->
            when (request.path) {
                "/api/model" -> Reply.Json(
                    200,
                    """{"data":[{"id":"alias","modelID":"api-model","providerID":"p","name":"Model","enabled":true,"status":"active","limit":{"context":400000,"output":32000}},{"id":"same","modelID":"same","providerID":"p","name":"Same","enabled":true,"status":"active","limit":{"context":0,"output":1}}]}""",
                )
                else -> Reply.Json(200, """{"data":null}""")
            }
        }
        try {
            val catalog = OpenCodeClient.liveValue.modelCatalog(fixture.server, "/work/app")
            assertEquals(listOf(400_000, null), catalog.models.map { it.contextLimit })
            assertEquals(listOf("alias", null), catalog.models.map { it.catalogID })
            assertTrue(catalog.models[0].matches(OpenCodeModelRef(id = "alias", providerID = "p")))
            assertFalse(catalog.models[0].matches(OpenCodeModelRef(id = "api-model", providerID = "p")))
            assertNull(catalog.models.model(OpenCodeModelRef(id = "api-model", providerID = "p")))
            assertEquals(catalog.models[0], catalog.models.model(OpenCodeModelRef(id = "alias", providerID = "p")))
            assertEquals(400_000, catalog.models.model(OpenCodeModelRef(id = "alias", providerID = "p"))?.contextLimit)
            assertEquals(OpenCodeModelRef(id = "alias", providerID = "p"), catalog.models[0].ref)
            assertFalse(catalog.models[0].matches(OpenCodeModelRef(id = "alias", providerID = "other")))
        } finally {
            fixture.close()
        }
    }

    private companion object {
        val TRANSFER = """
            {"data":{"info":{"id":"ses_sheet","title":"Export me","time":{"created":1000,"updated":2000}},"messages":[
              {"id":"msg_1","type":"user","text":"Please summarize"},
              {"id":"msg_2","type":"assistant","content":[
                {"type":"reasoning","text":"private reasoning"},
                {"type":"text","text":"Here it is."},
                {"type":"tool","name":"read","state":{"status":"completed","input":{"path":"a.swift"},"content":[{"type":"text","text":"ok"}]}}
              ]}
            ]}}
        """.trimIndent()
    }
}
