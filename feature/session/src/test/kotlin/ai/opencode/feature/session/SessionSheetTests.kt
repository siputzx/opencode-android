package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.header.SessionContextRing
import ai.opencode.feature.session.header.SessionContextText
import ai.opencode.feature.system.notifications.PushDuration
import ai.opencode.feature.system.notifications.PushServer
import ai.opencode.feature.system.notifications.PushSharedKeys
import ai.opencode.feature.system.notifications.PushStatus
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodePushBell
import ai.opencode.server.model.OpenCodePushDevice
import ai.opencode.server.model.OpenCodePushLevel
import ai.opencode.server.model.OpenCodePushState
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.ModelVariant
import ai.opencode.server.model.OpenCodeCompaction
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeContextUsage
import ai.opencode.server.model.OpenCodeExportFormat
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.testing.WireFixture
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import ai.opencode.core.testing.eventually

/**
 * `contextFractionUsesTheReplyModelsWindow`'s compact `"32% of 400K"` line runs under Robolectric
 * in [SessionSheetFrameworkTests] (`SessionContextText` formats with `android.icu`).
 */
class SessionSheetTests {
    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val model = OpenCodeModelRef(id = "fixture-model", providerID = "fixture")

    private fun session(id: String = "ses_sheet", title: String = "Sheet", parentID: String? = null, cost: Double? = null) = OpenCodeSession(
        id = id, serverTitle = title, projectID = "prj", directory = "/work/app",
        updatedAt = Instant.ofEpochSecond(100), parentID = parentID, cost = cost,
    )

    private fun catalogModel(limit: Int?) = OpenCodeModel(modelID = model.id, providerID = model.providerID, name = "Fixture", contextLimit = limit)

    private fun Harness.sheetState(session: OpenCodeSession? = null, cachedItems: List<OpenCodeTranscriptItem> = emptyList()): State =
        state { State.session(server, session ?: session(), cachedItems = cachedItems) }

    private fun Harness.sheetStore(
        session: OpenCodeSession? = null,
        cachedItems: List<OpenCodeTranscriptItem> = emptyList(),
        configure: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<State, Action> = store(sheetState(session, cachedItems), configure)

    private fun DependencyValues.Builder.stubIdleRefresh(session: OpenCodeSession) {
        this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            getSession = { _, _ -> session },
            transcript = { _, _, _ -> emptyList() },
            sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
            runningShells = { _, _ -> emptyList() },
            modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
            familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            pendingInputs = { _, _ -> emptyList() },
        )
    }

    private fun DependencyValues.Builder.client(update: OpenCodeClient.() -> OpenCodeClient) {
        this[OpenCodeClient] = this[OpenCodeClient].update()
    }

    private fun sheet(action: SessionSheetFeature.Action): Action = Action.SessionSheet(action)
    private fun sheetDelegate(delegate: SessionSheetFeature.Delegate): Action = sheet(SessionSheetFeature.Action.Delegate(delegate))
    private fun State.updatingSheet(update: SessionSheetFeature.State.() -> SessionSheetFeature.State) = copy(sessionSheet = sessionSheet?.update())
    private fun State.renamed(title: String) = copy(
        session = session?.copy(serverTitle = title),
        familySessions = familySessions.mapIndexed { index, s -> if (index == 0) s.copy(serverTitle = title) else s },
    )

    // region Context usage

    @Test fun contextFractionUsesTheReplyModelsWindow() = sessionTest { h ->
        var state = h.sheetState()
        assertNull(state.contextFraction)
        state = state.copy(contextUsage = OpenCodeContextUsage(tokens = 128_000, model = model))
        assertNull("No catalog yet: hide the ring", state.contextFraction)
        state = state.copy(models = listOf(catalogModel(limit = 400_000)))
        assertEquals(400_000, state.contextLimit)
        assertEquals(0.32, state.contextFraction)
        assertEquals("53,200 tokens", SessionContextText.summary(tokens = 53_200, limit = null, locale = java.util.Locale.US))

        // Message refs name the catalog key, which can differ from the API ID.
        state = state.copy(models = listOf(OpenCodeModel(modelID = "api-name", providerID = "fixture", name = "Fixture", contextLimit = 200_000, catalogID = model.id)))
        assertEquals(0.64, state.contextFraction)
    }

    @Test fun contextRingTintTracksTheThresholds() {
        val primary = Color.Black
        val red = Color.Red
        for ((fraction, expected) in listOf(0.3 to "neutral", 0.79 to "neutral", 0.85 to "orange", 0.97 to "red", 1.2 to "red")) {
            val tint = SessionContextRing.tint(fraction, isDark = false, primary = primary, red = red)
            val name = if (tint == red) "red" else if (tint == primary) "neutral" else "orange"
            assertEquals("fraction $fraction", expected, name)
        }
    }

    @Test fun pageRefreshReadsContextAndInconclusiveReadsKeepIt() = sessionTest { h ->
        val session = session()
        val readings = LockIsolated(
            listOf(OpenCodeContextReading.Measured(OpenCodeContextUsage(tokens = 64_000, model = model)), OpenCodeContextReading.Inconclusive, OpenCodeContextReading.Empty),
        )
        val store = h.sheetStore(session = session) {
            stubIdleRefresh(session)
            client { copy(contextUsage = { _, _ -> readings.withValue { it.drop(1) to it.first() } }) }
        }
        store.exhaustive = false

        store.send(Action.RefreshRequested(RefreshReason.Open))
        store.receive(Action.RefreshResponse::class) { it.copy(contextUsage = OpenCodeContextUsage(tokens = 64_000, model = model)) }
        store.send(Action.RefreshRequested(RefreshReason.RunState))
        store.receive(Action.RefreshResponse::class)
        assertEquals(64_000, store.state.contextUsage?.tokens)
        store.send(Action.RefreshRequested(RefreshReason.Invalidation))
        store.receive(Action.RefreshResponse::class) { it.copy(contextUsage = null) }
    }

    @Test fun onlyAnEndedStepRereadsContextWithItsSettlement() = sessionTest { h ->
        val session = session()
        val reads = LockIsolated(0)
        val reply = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply-text-0", text = "Done"))
        val store = h.sheetStore(session = session, cachedItems = reply) {
            this[Clock] = ImmediateClock()
            client {
                copy(
                    getSession = { _, _ -> session },
                    message = { _, _, _ -> reply },
                    contextUsage = { _, _ ->
                        reads.update { it + 1 }
                        OpenCodeContextReading.Measured(OpenCodeContextUsage(tokens = 90_000, model = model))
                    },
                )
            }
        }
        store.exhaustive = false

        store.send(Action.ContentBatch(session.id, listOf(OpenCodeLiveContentEvent.TextEnded(messageID = "msg_reply", ordinal = 0, text = "Done"))))
        store.receive(Action.RefreshResponse::class)
        assertEquals("A text boundary does not change usage", 0, reads.value)

        store.send(
            Action.ContentBatch(
                session.id,
                listOf(
                    OpenCodeLiveContentEvent.TextEnded(messageID = "msg_reply", ordinal = 0, text = "Done"),
                    OpenCodeLiveContentEvent.StepEnded(messageID = "msg_reply"),
                ),
            ),
        ) { it.copy(contextNeedsRead = true) }
        store.receive(Action.RefreshResponse::class) {
            it.copy(contextUsage = OpenCodeContextUsage(tokens = 90_000, model = model), contextNeedsRead = false)
        }
        assertEquals(1, reads.value)
    }

    @Test fun authoritativeRefreshDerivesContextUsageFromSingleTranscriptAndMessageReadsWithoutDuplicateMessageFetch() = sessionTest { h ->
        val session = session()
        val sessionJSON = """{"data":{"id":"ses_sheet","projectID":"prj","title":"Sheet","time":{"created":1,"updated":100000},"location":{"directory":"/work/app"}}}"""
        val modelJSON = """"model":{"id":"fixture-model","providerID":"fixture"}"""
        val eightUsersThenPos9AssistantJSON = (
            (1..8).map { """{"id":"msg_u_$it","type":"user","text":"User $it"}""" } + listOf(
                """{"id":"msg_a_pos9","type":"assistant",$modelJSON,"tokens":{"input":9000,"output":900,"reasoning":90,"cache":{"read":9,"write":0}},"content":[{"type":"text","text":"Ninth reply"}]}""",
                """{"id":"msg_a2","type":"assistant",$modelJSON,"tokens":{"input":3000,"output":500,"reasoning":100,"cache":{"read":600,"write":0}},"content":[{"type":"text","text":"After compaction"}]}""",
                """{"id":"msg_c_done","type":"compaction","status":"completed"}""",
                """{"id":"msg_a1","type":"assistant",$modelJSON,"tokens":{"input":1000,"output":200,"reasoning":50,"cache":{"read":300,"write":10}},"content":[{"type":"text","text":"First reply"}]}""",
                """{"id":"msg_u1","type":"user","text":"Hello"}""",
            )
            ).joinToString(",")
        val pageBodies = LockIsolated(
            listOf(
                """{"data":[{"id":"msg_c_pending","type":"compaction","status":"running"},{"id":"msg_a1","type":"assistant",$modelJSON,"tokens":{"input":1000,"output":200,"reasoning":50,"cache":{"read":300,"write":10}},"content":[{"type":"text","text":"First reply"}]},{"id":"msg_u1","type":"user","text":"Hello"}]}""",
                """{"data":[{"id":"msg_c_done","type":"compaction","status":"completed"},{"id":"msg_a1","type":"assistant",$modelJSON,"tokens":{"input":1000,"output":200,"reasoning":50,"cache":{"read":300,"write":10}},"content":[{"type":"text","text":"First reply"}]}]}""",
                """{"data":[{"id":"msg_b2","type":"assistant",$modelJSON,"tokens":{"input":6000,"output":800,"reasoning":200,"cache":{"read":500,"write":0}},"content":[{"type":"reasoning","text":"Deep thought"}]},{"id":"msg_a2","type":"assistant",$modelJSON,"tokens":{"input":3000,"output":500,"reasoning":100,"cache":{"read":600,"write":0}},"content":[{"type":"text","text":"After compaction"}]}]}""",
                """{"data":[$eightUsersThenPos9AssistantJSON]}""",
            ),
        )
        val settledMessageJSON = """{"data":{"id":"msg_a2","type":"assistant",$modelJSON,"tokens":{"input":3000,"output":500,"reasoning":100,"cache":{"read":600,"write":0}},"content":[{"type":"text","text":"After compaction"}]}}"""

        val fixture = WireFixture { request ->
            when (request.route) {
                "GET /api/session/ses_sheet" -> WireFixture.Reply.Json(200, sessionJSON)
                "GET /api/session/ses_sheet/message" -> WireFixture.Reply.Json(200, pageBodies.withValue { it.drop(1) to it.first() })
                "GET /api/session/ses_sheet/message/msg_a2" -> WireFixture.Reply.Json(200, settledMessageJSON)
                else -> WireFixture.Reply.status(404)
            }
        }
        try {
            val store = h.store(h.state { State.session(fixture.server, session, cachedItems = emptyList()) }) {
                this[Clock] = ImmediateClock()
                this[OpenCodeClient] = OpenCodeClient.liveValue
            }

            val runningCompaction = OpenCodeCompaction(status = OpenCodeCompaction.Status.Running, isProvider = false)
            val completedCompaction = OpenCodeCompaction(status = OpenCodeCompaction.Status.Completed, isProvider = false)
            val openItems = listOf(
                OpenCodeTranscriptItem.UserText(id = "msg_u1", text = "Hello"),
                OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_a1-text-0", text = "First reply"),
                OpenCodeTranscriptItem.Compaction(id = "msg_c_pending", info = runningCompaction),
            )
            val compactedItems = listOf(
                OpenCodeTranscriptItem.UserText(id = "msg_u1", text = "Hello"),
                OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_a1-text-0", text = "First reply"),
                OpenCodeTranscriptItem.Compaction(id = "msg_c_done", info = completedCompaction),
            )
            val settledItems = listOf(
                OpenCodeTranscriptItem.UserText(id = "msg_u1", text = "Hello"),
                OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_a1-text-0", text = "First reply"),
                OpenCodeTranscriptItem.Compaction(id = "msg_c_done", info = completedCompaction),
                OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_a2-text-0", text = "After compaction"),
            )
            val inconclusiveItems = settledItems +
                listOf(OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_a_pos9-text-0", text = "Ninth reply")) +
                (8 downTo 1).map { OpenCodeTranscriptItem.UserText(id = "msg_u_$it", text = "User $it") }

            // 1. Page refresh derives context usage from the single `initialFetchLimit`
            // transcript read (ignoring an unfinished compaction above the last
            // assistant message) and never issues a parallel `limit=8` message read.
            store.send(Action.RefreshRequested(RefreshReason.Open)) {
                it.copy(nextRefreshID = 1, refresh = State.Refresh(id = 1, reason = RefreshReason.Open))
            }
            store.receive(Action.RefreshResponse::class) {
                it.copy(
                    refresh = null,
                    initialTranscriptIsFresh = true,
                    items = openItems,
                    earlierHistory = State.EarlierHistory.Exhausted,
                    contextUsage = OpenCodeContextUsage(tokens = 1_560, model = model),
                ).adoptServerModel(null)
            }
            store.receive(Action.AcceptedTranscriptUpdated(session.id, openItems))
            val openMessageReads = fixture.requests.filter { it.path == "/api/session/ses_sheet/message" }
            assertEquals(1, openMessageReads.size)
            assertTrue(openMessageReads.first().query?.contains("limit=${SessionFeature.initialFetchLimit}") == true)
            assertTrue(openMessageReads.first().query?.contains("limit=8") == false)

            // 2. A completed compaction newer than the assistant message clears context
            // usage from the single page refresh response.
            store.send(Action.RefreshRequested(RefreshReason.Invalidation)) {
                it.copy(nextRefreshID = 2, refresh = State.Refresh(id = 2, reason = RefreshReason.Invalidation))
            }
            store.receive(Action.RefreshResponse::class) { it.copy(refresh = null, items = compactedItems, contextUsage = null) }
            store.receive(Action.AcceptedTranscriptUpdated(session.id, compactedItems))
            val afterCompactionReads = fixture.requests.filter { it.path == "/api/session/ses_sheet/message" }
            assertEquals(2, afterCompactionReads.size)
            assertTrue(afterCompactionReads.all { it.query?.contains("limit=${SessionFeature.initialFetchLimit}") == true })

            // 3. Settlement reads `/message/{id}` and `contextUsage` (`limit=8`), so a
            // newer reasoning-only step (`msg_b2`, 7,500 tokens) above the settled text
            // message (`msg_a2`, 4,200 tokens) is never missed.
            store.send(
                Action.ContentBatch(
                    session.id,
                    listOf(
                        OpenCodeLiveContentEvent.TextEnded(messageID = "msg_a2", ordinal = 0, text = "After compaction"),
                        OpenCodeLiveContentEvent.StepEnded(messageID = "msg_b2"),
                    ),
                ),
            ) {
                it.copy(
                    streamIsHealthy = true,
                    streamSupportsProjection = true,
                    isExternallyActive = true,
                    workingRun = WorkingRun(
                        counter = 1,
                        startID = 1,
                        finale = null,
                        key = "ses_sheet",
                        sawStart = true,
                        startTurn = "msg_u1",
                        baseline = "msg_a2-text-0",
                        trailing = false,
                    ),
                    projectedMessageIDs = setOf("msg_a2"),
                    lastProjectedMessageID = "msg_a2",
                    contextNeedsRead = true,
                    items = settledItems,
                )
            }
            store.receive(Action.RefreshRequested(RefreshReason.Settlement(messageID = "msg_a2"))) {
                it.copy(nextRefreshID = 3, refresh = State.Refresh(id = 3, reason = RefreshReason.Settlement(messageID = "msg_a2")))
            }
            store.receive(Action.RefreshResponse::class) {
                it.copy(refresh = null, contextUsage = OpenCodeContextUsage(tokens = 7_500, model = model), contextNeedsRead = false)
            }
            store.receive(Action.AcceptedTranscriptUpdated(session.id, settledItems))
            assertEquals(1, fixture.requests.count { it.path == "/api/session/ses_sheet/message/msg_a2" })
            val contextReads = fixture.requests.filter { it.path == "/api/session/ses_sheet/message" }
            assertEquals(3, contextReads.size)
            assertTrue(contextReads.last().query?.contains("limit=8") == true)

            // 4. Only the first 8 messages are inspected for context usage: 8 user messages
            // followed by an assistant message with usage at position 9 yields `.inconclusive`,
            // ignoring position 9 (9,999 tokens) and keeping the 7,500-token reading.
            store.send(Action.RefreshRequested(RefreshReason.RunState)) {
                it.copy(nextRefreshID = 4, refresh = State.Refresh(id = 4, reason = RefreshReason.RunState))
            }
            store.receive(Action.RefreshResponse::class) { it.copy(refresh = null, items = inconclusiveItems) }
            store.receive(Action.AcceptedTranscriptUpdated(session.id, inconclusiveItems))
            assertEquals(OpenCodeContextUsage(tokens = 7_500, model = model), store.state.contextUsage)
        } finally {
            fixture.close()
        }
    }

    @Test fun switchingFamilyMemberForgetsTheOtherSessionsContext() = sessionTest { h ->
        val root = session("ses_root")
        val child = session("ses_child", parentID = "ses_root")
        val state = h.state { State.session(server, root, cachedItems = emptyList()) }.copy(
            familySessions = listOf(root, child),
            contextUsage = OpenCodeContextUsage(tokens = 1, model = model),
            sessionSheet = SessionSheetFeature.State(server = server, sessionID = root.id),
        )
        val store = h.store(state) {
            client {
                copy(
                    getSession = { _, id -> if (id == child.id) child else root },
                    transcript = { _, _, _ -> awaitCancellation() },
                    sessionFamily = { _, observedRoot -> OpenCodeSessionFamily(sessions = listOf(observedRoot), activeSessionIDs = emptySet()) },
                    runningShells = { _, _ -> emptyList() },
                    modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                    familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                    pendingInputs = { _, _ -> emptyList() },
                )
            }
        }
        store.exhaustive = false
        store.send(Action.FamilyPicked(child.id))
        assertNull(store.state.contextUsage)
        assertNull("The sheet's in-flight work stays with the old session", store.state.sessionSheet)
    }

    @Test fun rootCostIncludesSubagentsAndChildCostIsItsOwn() = sessionTest { h ->
        var state = h.state { State.session(server, session("ses_root", cost = 0.5)) }
        state = state.copy(familySessions = listOf(session("ses_root", cost = 0.5), session("ses_child", parentID = "ses_root", cost = 0.25)))
        assertEquals(0.75, state.sessionCost)
        state = state.copy(session = session("ses_child", parentID = "ses_root", cost = 0.25))
        assertEquals(0.25, state.sessionCost)
        state = state.copy(session = session("ses_free"), familySessions = emptyList())
        assertNull(state.sessionCost)
    }

    // endregion

    // region Sheet actions

    private suspend fun openSheet(store: TestStore<State, Action>, sessionID: String = "ses_sheet") {
        store.send(Action.SessionInfoTapped) {
            it.copy(sessionSheet = SessionSheetFeature.State(server = server, sessionID = sessionID), activePicker = State.ActivePicker.session)
        }
    }

    private suspend fun beginRename(store: TestStore<State, Action>, currentTitle: String = "Sheet", updatesOriginal: Boolean = true) {
        store.send(sheet(SessionSheetFeature.Action.RenameTapped(currentTitle = currentTitle))) {
            it.updatingSheet {
                copy(renameDraft = currentTitle, renameOriginalTitle = if (updatesOriginal) currentTitle else renameOriginalTitle, isRenaming = true)
            }
        }
    }

    private fun assertAutoRenameFailsBriefly(renameSession: suspend (OpenCodeServer, String, String) -> Unit) = sessionTest { h ->
        val clock = TestClock()
        val session = session()
        val store = h.sheetStore(session = session) {
            this[Clock] = clock
            client { copy(renameSession = renameSession, getSession = { _, _ -> session }) }
        }
        openSheet(store)
        beginRename(store, currentTitle = session.title)
        store.send(sheet(SessionSheetFeature.Action.AutoRenameTapped)) {
            it.updatingSheet { copy(isRenaming = false, operation = SessionSheetFeature.Operation.AutoRenaming) }
        }
        store.receive(SessionSheetActionMatcher<SessionSheetFeature.Action.RenameFinished>()) {
            it.updatingSheet { copy(operation = null, autoRenameFailed = true) }
        }
        assertNull(store.state.sessionSheet?.failure)
        assertEquals("Sheet", store.state.session?.title)
        settle() // The failure's timer starts sleeping before time moves.
        clock.advance(2_200.milliseconds)
        store.receive(sheet(SessionSheetFeature.Action.AutoRenameFailureCleared)) { it.updatingSheet { copy(autoRenameFailed = false) } }
        store.finish()
    }

    @Test fun renameTrimsPatchesAndUpdatesTheTitle() = sessionTest { h ->
        val session = session()
        val patched = LockIsolated(listOf<String>())
        val store = h.sheetStore(session = session) {
            client {
                copy(
                    renameSession = { _, id, title ->
                        assertEquals(session.id, id)
                        patched.append(title)
                    },
                )
            }
        }
        openSheet(store)
        beginRename(store)
        store.send(sheet(SessionSheetFeature.Action.RenameDraftChanged("  Ship the sheet \n"))) {
            it.updatingSheet { copy(renameDraft = "  Ship the sheet \n") }
        }
        store.send(sheet(SessionSheetFeature.Action.RenameSubmitted)) {
            it.updatingSheet { copy(isRenaming = false, operation = SessionSheetFeature.Operation.Renaming) }
        }
        store.receive(SessionSheetActionMatcher<SessionSheetFeature.Action.RenameFinished>()) { it.updatingSheet { copy(operation = null) } }
        store.receive(sheetDelegate(SessionSheetFeature.Delegate.Renamed("Ship the sheet")))
        store.receive(Action.FamilySessionRenamed::class) { it.renamed("Ship the sheet") }
        assertEquals(listOf("Ship the sheet"), patched.value)

        // A blank title is not sent.
        beginRename(store, currentTitle = "Ship the sheet")
        store.send(sheet(SessionSheetFeature.Action.RenameDraftChanged("   "))) { it.updatingSheet { copy(renameDraft = "   ") } }
        store.send(sheet(SessionSheetFeature.Action.RenameSubmitted)) { it.updatingSheet { copy(isRenaming = false) } }
        assertEquals(1, patched.value.size)

        // Nor is the title it already has, padding aside.
        beginRename(store, currentTitle = "Ship the sheet", updatesOriginal = false)
        store.send(sheet(SessionSheetFeature.Action.RenameDraftChanged("Ship the sheet "))) { it.updatingSheet { copy(renameDraft = "Ship the sheet ") } }
        store.send(sheet(SessionSheetFeature.Action.RenameSubmitted)) { it.updatingSheet { copy(isRenaming = false) } }
        assertEquals(1, patched.value.size)
        store.finish()
    }

    @Test fun autoRenameAsksTheServerForATitleAndShowsIt() = sessionTest { h ->
        val session = session()
        val patched = LockIsolated(listOf<String>())
        val store = h.sheetStore(session = session) {
            client {
                copy(
                    renameSession = { _, _, title -> patched.append(title) },
                    getSession = { _, _ -> session.copy(serverTitle = "Plan the boys’ dinner") },
                )
            }
        }
        openSheet(store)
        beginRename(store)
        store.send(sheet(SessionSheetFeature.Action.AutoRenameTapped)) {
            it.updatingSheet { copy(isRenaming = false, operation = SessionSheetFeature.Operation.AutoRenaming) }
        }
        store.receive(SessionSheetActionMatcher<SessionSheetFeature.Action.RenameFinished>()) { it.updatingSheet { copy(operation = null) } }
        store.receive(sheetDelegate(SessionSheetFeature.Delegate.Renamed("Plan the boys’ dinner")))
        store.receive(Action.FamilySessionRenamed::class) { it.renamed("Plan the boys’ dinner") }
        // The empty title is the server's cue to generate one.
        assertEquals(listOf(""), patched.value)
        store.finish()
    }

    /**
     * The server keeps the old title and still answers 204 when its title
     * model fails, so an unchanged title reads as a failed Auto Rename.
     */
    @Test fun autoRenameThatChangesNothingSaysSoBriefly() = assertAutoRenameFailsBriefly { _, _, _ -> }

    @Test fun failedRenameKeepsTheTitleAndExplains() = sessionTest { h ->
        val store = h.sheetStore {
            client { copy(renameSession = { _, _, _ -> throw OpenCodeClientError(message = "Offline") }) }
        }
        openSheet(store)
        beginRename(store)
        store.send(sheet(SessionSheetFeature.Action.RenameDraftChanged("New"))) { it.updatingSheet { copy(renameDraft = "New") } }
        store.send(sheet(SessionSheetFeature.Action.RenameSubmitted)) {
            it.updatingSheet { copy(isRenaming = false, operation = SessionSheetFeature.Operation.Renaming) }
        }
        store.receive(SessionSheetActionMatcher<SessionSheetFeature.Action.RenameFinished>()) {
            it.updatingSheet { copy(operation = null, failure = SessionSheetFeature.Failure(title = "Couldn’t Rename Session", message = "Offline")) }
        }
        assertEquals("Sheet", store.state.session?.title)
        store.send(sheet(SessionSheetFeature.Action.FailureDismissed)) { it.updatingSheet { copy(failure = null) } }
        store.finish()
    }

    @Test fun failedAutoRenameKeepsTheTitleAndSaysSoBriefly() = assertAutoRenameFailsBriefly { _, _, _ ->
        throw OpenCodeClientError(message = "Offline")
    }

    @Test fun aRenameForAnotherSessionIsIgnored() = sessionTest { h ->
        val store = h.sheetStore()
        openSheet(store)
        // Finished after the sheet moved to another session: it must not rename this one.
        store.send(sheet(SessionSheetFeature.Action.RenameFinished(title = "Somebody else's title", error = null, sessionID = "ses_other")))
        assertEquals("Sheet", store.state.session?.title)
        store.finish()
    }

    @Test fun compactQueuesThenClosesTheSheetToShowTheCompactionRow() = sessionTest { h ->
        val session = session()
        val compacted = LockIsolated(0)
        val store = h.sheetStore(session = session) {
            stubIdleRefresh(session)
            client {
                copy(
                    compactSession = { _, id ->
                        assertEquals(session.id, id)
                        compacted.update { it + 1 }
                    },
                    pendingInputs = { _, _ -> listOf(OpenCodePendingInput(id = "msg_compact", text = "", isCompaction = true)) },
                )
            }
        }
        openSheet(store)
        store.send(sheet(SessionSheetFeature.Action.CompactTapped)) { it.updatingSheet { copy(operation = SessionSheetFeature.Operation.Compacting) } }
        store.exhaustive = false
        store.receive(SessionSheetActionMatcher<SessionSheetFeature.Action.CompactFinished>()) { it.updatingSheet { copy(operation = null) } }
        store.receive(SessionSheetActionMatcher<SessionSheetFeature.Action.Delegate> { it.delegate is SessionSheetFeature.Delegate.Compacted }) {
            it.copy(activePicker = null)
        }
        store.receive(Action.InputQueueChanged::class)
        store.receive(Action.PendingInputsResponse::class)
        assertEquals(1, compacted.value)
        assertEquals(listOf("msg_compact"), store.state.queuedInputs.map { it.id })
    }

    /** Like the TUI, compaction applies a pick still waiting for the next send. */
    @Test fun compactionAppliesAWaitingPickFirst() = sessionTest { h ->
        val session = session()
        val fast = OpenCodeModel(modelID = "fast-frank", providerID = "opencode", name = "Fast Frank")
        val calls = LockIsolated(listOf<String>())
        val store = h.sheetStore(session = session) {
            stubIdleRefresh(session)
            client {
                copy(
                    switchModel = { _, id, ref -> calls.append("model $id ${ref.id}") },
                    compactSession = { _, id -> calls.append("compact $id") },
                )
            }
        }
        store.exhaustive = false
        store.send(Action.ModelPicked(fast))
        store.send(Action.SessionInfoTapped)
        assertEquals(fast.ref, store.state.sessionSheet?.pendingModel)
        store.send(sheet(SessionSheetFeature.Action.CompactTapped))
        store.receive(SessionSheetActionMatcher<SessionSheetFeature.Action.Delegate> { it.delegate is SessionSheetFeature.Delegate.Compacted })
        assertEquals(listOf("model ${session.id} fast-frank", "compact ${session.id}"), calls.value)
        assertNull(store.state.pendingModel)
        assertNull(store.state.sessionSheet?.pendingModel)
    }

    @Test fun failedCompactionStaysOpenWithAnError() = sessionTest { h ->
        val store = h.sheetStore {
            client { copy(compactSession = { _, _ -> throw OpenCodeClientError(message = "Nothing to compact", statusCode = 400) }) }
        }
        openSheet(store)
        store.send(sheet(SessionSheetFeature.Action.CompactTapped)) { it.updatingSheet { copy(operation = SessionSheetFeature.Operation.Compacting) } }
        store.receive(SessionSheetActionMatcher<SessionSheetFeature.Action.CompactFinished>()) {
            it.updatingSheet { copy(operation = null, failure = SessionSheetFeature.Failure(title = "Couldn’t Compact Session", message = "Nothing to compact")) }
        }
        assertEquals(State.ActivePicker.session, store.state.activePicker)
        store.finish()
    }

    @Test fun forkOpensTheNewSession() = sessionTest { h ->
        val forked = session("ses_fork", title = "Sheet (fork)")
        val store = h.sheetStore {
            client {
                copy(
                    forkSession = { _, id ->
                        assertEquals("ses_sheet", id)
                        forked
                    },
                )
            }
        }
        openSheet(store)
        store.send(sheet(SessionSheetFeature.Action.ForkTapped)) { it.updatingSheet { copy(operation = SessionSheetFeature.Operation.Forking) } }
        store.receive(SessionSheetActionMatcher<SessionSheetFeature.Action.ForkFinished>()) { it.updatingSheet { copy(operation = null) } }
        store.receive(sheetDelegate(SessionSheetFeature.Delegate.Forked(forked))) { it.copy(activePicker = null) }
        store.receive(Action.Delegate(SessionFeature.Delegate.OpenSession(forked)))
        store.finish()
    }

    @Test fun failedForkStaysPut() = sessionTest { h ->
        val store = h.sheetStore {
            client { copy(forkSession = { _, _ -> throw OpenCodeClientError(message = "Session not found", statusCode = 404) }) }
        }
        openSheet(store)
        store.send(sheet(SessionSheetFeature.Action.ForkTapped)) { it.updatingSheet { copy(operation = SessionSheetFeature.Operation.Forking) } }
        store.receive(SessionSheetActionMatcher<SessionSheetFeature.Action.ForkFinished>()) {
            it.updatingSheet { copy(operation = null, failure = SessionSheetFeature.Failure(title = "Couldn’t Fork Session", message = "Session not found")) }
        }
        store.finish()
    }

    @Test fun exportWritesAShareableFile() {
        for (format in OpenCodeExportFormat.entries) exportWritesAShareableFile(format)
    }

    private fun exportWritesAShareableFile(format: OpenCodeExportFormat) = sessionTest { h ->
        val store = h.sheetStore {
            this[UuidClient] = UuidClient.incrementing()
            client { copy(exportSession = { _, _ -> transfer.toByteArray() }) }
        }
        openSheet(store)
        store.send(sheet(SessionSheetFeature.Action.ExportTapped(format, title = "Fix: the / sheet"))) {
            it.updatingSheet { copy(operation = SessionSheetFeature.Operation.Exporting(format)) }
        }
        val path = File(File(System.getProperty("java.io.tmpdir"), "Exports/00000000-0000-0000-0000-000000000000"), "Fix the sheet.${format.fileExtension}").absolutePath
        store.receive(SessionSheetActionMatcher<SessionSheetFeature.Action.ExportFinished>()) {
            it.updatingSheet { copy(operation = null, exportedFile = SessionSheetFeature.ExportedFile(path)) }
        }
        val content = File(path).readText()
        when (format) {
            OpenCodeExportFormat.Markdown -> {
                assertTrue(content.startsWith("# Export me\n\n**Session ID:** ses_sheet"))
                assertTrue(content.contains("## User\n\nPlease summarize"))
                assertTrue(content.contains("## Assistant\n\nHere it is.\n\n**Tool: read**"))
                assertFalse(content.contains("private reasoning"))
            }
            OpenCodeExportFormat.Json -> {
                val obj = Json.parseToJsonElement(content).jsonObject
                assertEquals("ses_sheet", obj["info"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
                assertEquals(2, obj["messages"]?.jsonArray?.size)
            }
        }
        store.send(sheet(SessionSheetFeature.Action.ExportDismissed)) { it.updatingSheet { copy(exportedFile = null) } }
        store.finish()
        assertFalse(File(path).exists())
    }

    @Test fun exportFailuresExplain() = sessionTest { h ->
        val bodies = LockIsolated(listOf(Result.failure(OpenCodeClientError(message = "Offline")), Result.success("{}".toByteArray())))
        val store = h.sheetStore {
            this[UuidClient] = UuidClient.incrementing()
            client { copy(exportSession = { _, _ -> bodies.withValue { it.drop(1) to it.first() }.getOrThrow() }) }
        }
        openSheet(store)
        for (message in listOf("Offline", "Couldn’t save export")) {
            store.send(sheet(SessionSheetFeature.Action.ExportTapped(OpenCodeExportFormat.Markdown, title = "Sheet"))) {
                it.updatingSheet { copy(operation = SessionSheetFeature.Operation.Exporting(OpenCodeExportFormat.Markdown)) }
            }
            store.receive(SessionSheetActionMatcher<SessionSheetFeature.Action.ExportFinished>()) {
                it.updatingSheet { copy(operation = null, failure = SessionSheetFeature.Failure(title = "Couldn’t Export Session", message = message)) }
            }
            store.send(sheet(SessionSheetFeature.Action.FailureDismissed)) { it.updatingSheet { copy(failure = null) } }
        }
        store.finish()
    }

    @Test fun listActionsAndDeletionAreDelegatedToHome() = sessionTest { h ->
        val store = h.sheetStore()
        openSheet(store)
        store.send(sheet(SessionSheetFeature.Action.PinTapped))
        store.receive(sheetDelegate(SessionSheetFeature.Delegate.TogglePin))
        store.receive(Action.Delegate(SessionFeature.Delegate.TogglePin("ses_sheet")))
        store.send(sheet(SessionSheetFeature.Action.ReadTapped))
        store.receive(sheetDelegate(SessionSheetFeature.Delegate.ToggleRead))
        store.receive(Action.Delegate(SessionFeature.Delegate.ToggleRead("ses_sheet")))
        store.send(sheet(SessionSheetFeature.Action.DeleteTapped)) { it.updatingSheet { copy(confirmsDeletion = true) } }
        store.send(sheet(SessionSheetFeature.Action.DeleteConfirmed)) { it.updatingSheet { copy(confirmsDeletion = false) } }
        store.receive(sheetDelegate(SessionSheetFeature.Delegate.DeleteSession)) { it.copy(activePicker = null) }
        store.receive(Action.Delegate(SessionFeature.Delegate.DeleteSession("ses_sheet")))
        store.finish()
    }

    @Test fun chooseModelSwapsTheSheetForThePicker() = sessionTest { h ->
        val store = h.sheetStore {
            client { copy(modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) }) }
        }
        store.exhaustive = false
        openSheet(store)
        store.send(Action.ModelButtonTapped) { it.copy(activePicker = State.ActivePicker.model) }
    }

    @Test fun headerProjectSkipsTheGlobalProject() {
        val projects = listOf(
            OpenCodeProject(id = "global", name = "Global", directory = "/"),
            OpenCodeProject(id = "prj", name = "opencode-ios", directory = "/work/app"),
        )
        assertEquals("opencode-ios", SessionListContext.project(session(), projects)?.name)
        val global = session().copy(projectID = "global")
        assertNull(SessionListContext.project(global, projects))
        assertNull(SessionListContext.project(null, projects))
    }

    private fun pushServer(sessions: Map<String, OpenCodePushBell> = emptyMap()): PushServer {
        val device = OpenCodePushDevice(
            id = "ref-1", name = "Pixel", platform = "android", expiresAt = 1L,
            defaultLevel = OpenCodePushLevel.all, paused = false, sessions = sessions,
        )
        return PushServer(PushStatus.Ready, OpenCodePushLevel.entries, "ref-1", OpenCodePushState(devices = listOf(device)))
    }

    @Test fun bellShowsTheChoiceAtOnceAndTakesTheServersCopyWhenRefused() = sessionTest { h ->
        val servers = h.sharedStore.reference(PushSharedKeys.servers)
        servers.set(mapOf(testServer.persistenceID to pushServer()))
        val written = LockIsolated(listOf<OpenCodePushBell?>())
        val refuse = LockIsolated(false)
        val muted = OpenCodePushBell(OpenCodePushLevel.none, until = 3_600_000L)
        val store = h.sheetStore {
            client {
                copy(
                    setPushSessionBell = { _, id, sessionID, bell ->
                        assertEquals("ref-1", id)
                        assertEquals("ses_sheet", sessionID)
                        if (refuse.value) throw OpenCodeClientError("Server error", statusCode = 500)
                        written.append(bell)
                    },
                    pushState = { _ -> pushServer(mapOf("ses_sheet" to muted)).state!! },
                )
            }
        }
        openSheet(store)

        store.send(sheet(SessionSheetFeature.Action.BellChosen(OpenCodePushLevel.none, PushDuration.hour)))
        assertEquals(muted, servers.value[testServer.persistenceID]?.device?.sessions?.get("ses_sheet"))
        assertTrue(eventually { written.value == listOf<OpenCodePushBell?>(muted) })

        refuse.setValue(true)
        store.send(sheet(SessionSheetFeature.Action.BellChosen(null, PushDuration.forever)))
        assertNull(servers.value[testServer.persistenceID]?.device?.sessions?.get("ses_sheet"))
        store.receive(SessionSheetActionMatcher<SessionSheetFeature.Action.BellRefused>()) {
            it.updatingSheet {
                copy(failure = SessionSheetFeature.Failure(title = "Couldn’t Change Notifications", message = "The server didn’t take the change."))
            }
        }
        assertEquals(muted, servers.value[testServer.persistenceID]?.device?.sessions?.get("ses_sheet"))
        store.finish()
    }

    @Test fun bellRegistersAgainWhenTheServerDoesNotKnowThePhone() = sessionTest { h ->
        h.sharedStore.reference(PushSharedKeys.servers).set(mapOf(testServer.persistenceID to pushServer()))
        val registered = LockIsolated(listOf<OpenCodePushLevel>())
        val calls = LockIsolated(0)
        val store = h.sheetStore {
            client {
                copy(
                    setPushSessionBell = { _, _, _, _ ->
                        calls.increment()
                        if (registered.value.isEmpty()) throw OpenCodeClientError("Not found", statusCode = 404)
                    },
                )
            }
            this[SessionNotificationsClient] = SessionNotificationsClient(
                isAvailable = true,
                register = { _, level ->
                    registered.append(level)
                    true
                },
            )
        }
        openSheet(store)

        store.send(sheet(SessionSheetFeature.Action.BellChosen(OpenCodePushLevel.attention, PushDuration.forever)))
        assertTrue(eventually { calls.value == 2 })
        assertEquals(listOf(OpenCodePushLevel.all), registered.value)
        store.finish()
    }

    @Test fun copySessionIDMenuActionWritesToPasteboard() = sessionTest { h ->
        val copied = LockIsolated(listOf<String>())
        val store = h.sheetStore(session = session("ses_copy_target")) {
            this[PasteboardClient] = PasteboardClient(setURL = {}, setString = { copied.append(it) })
        }
        store.send(Action.SessionMenu(SessionSheetFeature.Action.CopySessionIDTapped)) {
            it.copy(sessionSheet = SessionSheetFeature.State(server = server, sessionID = "ses_copy_target"))
        }
        store.receive(sheet(SessionSheetFeature.Action.CopySessionIDTapped))
        store.finish()
        assertEquals(listOf("ses_copy_target"), copied.value)
    }

    // endregion

    // region What the sheet shows

    @Test fun homeDirectoriesReadAsTilde() {
        for ((directory, shown) in listOf(
            "/Users/kit/code/opencode-ios" to "~/code/opencode-ios",
            "/home/dev/app" to "~/app",
            "/root/work" to "~/work",
            "/Users/kit" to "~",
            "/fixture/approval-playground" to "/fixture/approval-playground",
            "/Users" to "/Users",
        )) {
            assertEquals(shown, SessionSheetFormat.displayPath(directory))
        }
    }

    @Test fun levelBarsFillUpToTheChosenLevel() {
        val variants = ModelVariant.sorted(listOf("high", "low", "max", "medium"))
        assertEquals(3, ModelLevelBars.filled(variants = variants, level = "high"))
        assertEquals(4, ModelLevelBars.filled(variants = variants, level = "max"))
        assertEquals(0, ModelLevelBars.filled(variants = variants, level = "unknown"))
        assertEquals(5.0 / 13, ModelLevelBars.heights(count = 4).first(), 0.0)
        assertEquals(1.0, ModelLevelBars.heights(count = 4).last(), 0.0)
        assertEquals(listOf(5.0 / 13), ModelLevelBars.heights(count = 1))
    }

    /**
     * The orb before the title: Idle at rest, Waiting for Input while a
     * request waits, the live states while the session runs.
     */
    @Test fun statusOrbRestsIdleUnlessWaiting() {
        assertEquals(AgentActivity.idle, SubagentActivity(sessionID = "ses_sheet", phase = SubagentActivity.Phase.idle).activity(0.0))
        assertEquals(AgentActivity.waiting, SubagentActivity(sessionID = "ses_sheet", phase = SubagentActivity.Phase.idle, needsInput = true).activity(0.0))
        assertEquals(AgentActivity.waiting, SubagentActivity(sessionID = "ses_sheet", phase = SubagentActivity.Phase.running, needsInput = true).activity(0.0))
    }

    // endregion

    companion object {
        val transfer = """
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

/** A `.sessionSheet(...)` action of child case [T]. */
@Suppress("FunctionName")
private inline fun <reified T : SessionSheetFeature.Action> SessionSheetActionMatcher(
    crossinline where: (T) -> Boolean = { true },
): (Action) -> Boolean = { action -> ((action as? Action.SessionSheet)?.action as? T)?.let(where) == true }

/**
 * `SessionContextText` compacts the window with `CompactDecimalFormat`, which needs Android's ICU
 * (Robolectric).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class SessionSheetFrameworkTests {
    @Test fun contextFractionUsesTheReplyModelsWindow() {
        assertEquals("32% of 400K", SessionContextText.summary(tokens = 128_000, limit = 400_000, locale = java.util.Locale.US))
        assertEquals("53,200 tokens", SessionContextText.summary(tokens = 53_200, limit = null, locale = java.util.Locale.US))
        // English in the user's region.
        val german = ai.opencode.core.ui.appFormattingLocale(java.util.Locale.GERMANY)
        assertEquals("53.200 tokens", SessionContextText.summary(tokens = 53_200, limit = null, locale = german))
        assertEquals("32% used of 400.000 tokens", SessionContextText.accessibilityValue(tokens = 128_000, limit = 400_000, locale = german))
        val arabic = ai.opencode.core.ui.appFormattingLocale(java.util.Locale.forLanguageTag("ar"))
        assertEquals("53,200 tokens", SessionContextText.summary(tokens = 53_200, limit = null, locale = arabic))
    }
}
