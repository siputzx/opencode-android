package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.session.FailedSendsSupport.finishLikeTCA
import ai.opencode.feature.session.FailedSendsSupport.sentID
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.AuthoritativeRefresh
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.core.testing.eventually
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.feature.system.notifications.PushChoice
import ai.opencode.feature.system.notifications.PushMode
import ai.opencode.feature.system.notifications.PushServer
import ai.opencode.feature.system.notifications.PushSharedKeys
import ai.opencode.feature.system.notifications.PushStatus
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.TranscriptRowIdentityPlan
import ai.opencode.feature.transcript.rows.ToolGroupLabel
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeTranscriptPage
import ai.opencode.server.model.OpenCodePushBell
import ai.opencode.server.model.OpenCodePushDevice
import ai.opencode.server.model.OpenCodePushLevel
import ai.opencode.server.model.OpenCodePushState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Parameterized tests loop over their arguments. */
class SessionFeatureTestsInbox {
    private fun markdown(id: String, text: String) = OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = text)
    private fun user(id: String, text: String) = OpenCodeTranscriptItem.UserText(id = id, text = text)

    private val noticeReply = markdown("msg_0-text-0", "I started a subagent on the small fixes.")
    private val pendingNotice = OpenCodeTranscriptItem.Notice(id = "msg_notice", text = "General finished · Small fixes: labels, rename test, awake")

    private fun noticeTool(id: String, messageID: String = "msg_0") = OpenCodeTranscriptItem.Tool(
        id = "$messageID-tool-$id",
        call = OpenCodeToolCall(
            callID = id, messageID = messageID, name = "shell", status = OpenCodeToolCall.Status.Completed,
            input = OpenCodeJSONValue.Object(mapOf("command" to OpenCodeJSONValue.Str("swift build"))), output = emptyList(), error = null, metadata = emptyMap(),
        ),
    )

    /** A parent busy on its own step, with a subagent's completion in its inbox. */
    private fun Harness.noticeStore(items: List<OpenCodeTranscriptItem>, configure: (State) -> State = { it }): TestStore<State, Action> {
        val session = testSession(id = "ses_parent", title = "Parent")
        val state = configure(makeState(session = session, cachedItems = items, isRunning = true))
        val store = store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(transcript = { _, _, _ -> emptyList() })
        }
        store.exhaustive = false
        return store
    }

    private suspend fun inbox(store: TestStore<State, Action>, inputs: List<OpenCodePendingInput>) =
        store.send(Action.PendingInputsResponse(sessionID = "ses_parent", inputs = inputs))

    private suspend fun history(store: TestStore<State, Action>, items: List<OpenCodeTranscriptItem>) =
        store.send(Action.TranscriptResponse(sessionID = "ses_parent", result = Result.success(items)))

    private val pendingNoticeInput get() = OpenCodePendingInput(id = "msg_notice", text = "", notice = pendingNotice)

    private fun State.withQueuedDelivery(index: Int, delivery: OpenCodeDelivery) =
        copy(queuedInputs = queuedInputs.mapIndexed { i, input -> if (i == index) input.copy(delivery = delivery) else input })

    private suspend fun until(condition: () -> Boolean) {
        repeat(500) {
            if (condition()) return
            delay(2)
        }
    }

    @Test fun failedTranscriptDoesNotAcknowledgeViewedState() = sessionTest { h ->
        val session = testSession(id = "ses_view", title = "Viewed", updatedAt = distantPast, idleMilliseconds = 1234)
        val store = h.store(h.makeState(session = session, cachedItems = listOf(user("stale", "Stale")))) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> throw OpenCodeClientError("Offline") },
                sessionFamily = { _, _ -> throw OpenCodeClientError("Offline") },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> throw OpenCodeClientError("Offline") },
                pendingInputs = { _, _ -> emptyList() },
                viewSession = { _, _, _ -> throw AssertionError("Failed transcript must not be marked viewed") },
            )
        }
        store.exhaustive = false
        store.send(Action.VisibilityChanged(true)) { it.copy(isVisible = true) }
        store.send(Action.FirstContentFrameRendered)
        store.finishLikeTCA()
        assertTrue(store.state.viewedIdle.isEmpty())
    }

    @Test fun deliveredPromptOutsideNewestWindowLeavesQueueWithoutMovingToTail() = sessionTest { h ->
        val session = testSession(directory = "/tmp/demo")
        val latest = listOf<OpenCodeTranscriptItem>(user("msg_newer", "Sent later"))
        val state = h.makeState(session = session, cachedItems = latest).copy(
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_old", text = "Sent first")),
            promptStates = mapOf("msg_old" to State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted)),
            optimisticPromptIDs = setOf("msg_old"),
        )
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                message = { _, sessionID, messageID ->
                    assertEquals("ses_1", sessionID)
                    assertEquals("msg_old", messageID)
                    listOf(user("msg_old", "Sent first"))
                },
            )
        }
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = emptyList()))
        store.receive(Action.PromptEchoResponse(sessionID = session.id, messageID = "msg_old", requestEpoch = 1, items = listOf(user("msg_old", "Sent first")))) {
            it.copy(
                queuedInputs = emptyList(),
                promptStates = emptyMap(),
                optimisticPromptIDs = emptySet(),
                promotedPromptIDs = listOf("msg_old"),
                runWorkGeneration = 1,
            )
        }
        assertEquals(latest, store.state.items)
        // A late inbox response must not resurrect the already-delivered row.
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(OpenCodePendingInput(id = "msg_old", text = "Sent first"))))
        store.finish()
    }

    /**
     * A subagent's completion that waited in the parent's inbox rides at the end of the turn it
     * arrived in, like the desktop's compact timeline: in a quiet group of its own after a reply,
     * inside the Used N group after tools, never as its own row.
     */
    @Test fun pendingCompletionNoticeFoldsIntoTheTurn() {
        for (tail in listOf("afterReply", "afterTools")) pendingCompletionNoticeFoldsIntoTheTurn(tail)
    }

    private fun pendingCompletionNoticeFoldsIntoTheTurn(tail: String) = sessionTest { h ->
        val items = if (tail == "afterReply") listOf<OpenCodeTranscriptItem>(noticeReply) else listOf(noticeReply, noticeTool("1"))
        val store = h.noticeStore(items)
        val steer = OpenCodePendingInput(id = "msg_steer", text = "Also check the header", delivery = OpenCodeDelivery.Steer)

        inbox(store, listOf(pendingNoticeInput, steer))

        assertEquals(tail, items + pendingNotice, store.state.items)
        assertEquals("Only the steering message is a pending row", listOf("msg_steer"), store.state.queuedInputRows.map { it.id })
        val entries = TranscriptEntry.build(store.state.visibleItems, isRunning = true)
        assertTrue("The agent's reply still shows", entries.contains(TranscriptEntry.Single(noticeReply)))
        assertFalse("The notice never stands alone", entries.contains(TranscriptEntry.Single(pendingNotice)))
        if (tail == "afterReply") {
            assertEquals(TranscriptEntry.ToolGroup(groupID = "msg_notice", tools = listOf(pendingNotice)), entries.last())
            assertEquals("A lone notice says what it was", "General finished", ToolGroupLabel.of(listOf(pendingNotice)).first)
        } else {
            assertEquals(TranscriptEntry.ToolGroup(groupID = "msg_0-tool-1", tools = listOf(noticeTool("1"), pendingNotice)), entries.last())
            assertEquals("Notices don't inflate the count", "Used 1 tool", ToolGroupLabel.of(listOf(noticeTool("1"), pendingNotice)).first)
        }
    }

    /**
     * The inbox read after delivery can land before the history refresh.
     * The notice stays where it is until history has it, under the same ID.
     */
    @Test fun deliveredNoticeStaysWhenTheInboxEmptiesBeforeHistory() = sessionTest { h ->
        val store = h.noticeStore(listOf(noticeReply))
        inbox(store, listOf(pendingNoticeInput))
        val shown = store.state.items

        inbox(store, emptyList())
        assertEquals("An empty inbox alone doesn't remove it", shown, store.state.items)

        val followUp = markdown("msg_1-text-0", "The fixes are in.")
        history(store, listOf(noticeReply, pendingNotice, followUp))
        assertEquals(listOf(noticeReply, pendingNotice, followUp), store.state.items)
        assertTrue(store.state.pendingNoticeIDs.isEmpty())
    }

    /**
     * History can deliver the notice first; an inbox read that started
     * before delivery must not add it again at the tail.
     */
    @Test fun staleInboxNeverReaddsADeliveredNotice() {
        for (wasPending in listOf(true, false)) staleInboxNeverReaddsADeliveredNotice(wasPending)
    }

    private fun staleInboxNeverReaddsADeliveredNotice(wasPending: Boolean) = sessionTest { h ->
        val store = h.noticeStore(listOf(noticeReply))
        if (wasPending) inbox(store, listOf(pendingNoticeInput))
        val followUp = markdown("msg_1-text-0", "The fixes are in.")
        history(store, listOf(noticeReply, pendingNotice, followUp))

        inbox(store, listOf(pendingNoticeInput))

        assertEquals("wasPending=$wasPending", listOf(noticeReply, pendingNotice, followUp), store.state.items)
        assertTrue(store.state.pendingNoticeIDs.isEmpty())
    }

    /**
     * The next step can stream over SSE before REST has the delivered
     * notice. The notice stays above the new reply through a page captured
     * before delivery, and through the page that delivers it.
     */
    @Test fun nextStepStreamedBeforeRESTStaysBelowTheNotice() = sessionTest { h ->
        val store = h.noticeStore(listOf(noticeReply))
        inbox(store, listOf(pendingNoticeInput))
        inbox(store, emptyList())
        store.send(
            Action.ContentBatch(
                sessionID = "ses_parent",
                events = listOf(ai.opencode.server.OpenCodeLiveContentEvent.TextDelta(messageID = "msg_1", ordinal = 0, delta = "The fixes are in.")),
            ),
        )
        val streamed = markdown("msg_1-text-0", "The fixes are in.").copy(inProgress = true)
        assertEquals(listOf(noticeReply, pendingNotice, streamed), store.state.items)

        history(store, listOf(noticeReply))
        assertEquals("A page from before delivery keeps the order", listOf(noticeReply, pendingNotice, streamed), store.state.items)

        history(store, listOf(noticeReply, pendingNotice, streamed))
        assertEquals(listOf(noticeReply, pendingNotice, streamed), store.state.items)
        val entries = TranscriptEntry.build(store.state.visibleItems, isRunning = true)
        assertEquals(listOf("msg_0-text-0", "group-msg_notice", "msg_1-text-0"), entries.map { it.id })
    }

    /**
     * When a tool joins the open Updates group ahead of the notice, the
     * group's entry ID changes but its row identity, which keys disclosure,
     * doesn't, so the reader's open group stays open.
     */
    @Test fun updatesGroupKeepsItsRowIdentityWhenAToolJoinsAhead() = sessionTest { h ->
        val store = h.noticeStore(listOf(noticeReply))
        inbox(store, listOf(pendingNoticeInput))
        val before = TranscriptEntry.build(store.state.visibleItems, isRunning = true)
        assertEquals("group-msg_notice", before.last().id)

        history(store, listOf(noticeReply, noticeTool("1"), pendingNotice))
        val after = TranscriptEntry.build(store.state.visibleItems, isRunning = true)
        assertEquals(TranscriptEntry.ToolGroup(groupID = "msg_0-tool-1", tools = listOf(noticeTool("1"), pendingNotice)), after.last())
        val plan = TranscriptRowIdentityPlan.make(
            previousEntries = before, previousQueued = emptyList(),
            previousEntryStableIDs = before.associate { it.id to it.id }, previousQueuedStableIDs = emptyMap(),
            entries = after, queued = emptyList(),
        )
        assertEquals("group-msg_notice", plan.entryStableIDs["group-msg_0-tool-1"])
    }

    /**
     * A shell's notice arrives after a read and the next paragraph streams
     * below it. When the read's message settles, the notice stays in the
     * read's Used N group: it never drops below the paragraph as its own
     * Updates row, which left again a moment later when the page read put it
     * ahead of the read.
     */
    @Test fun noticeInsideAToolGroupStaysThereWhenItsMessageSettles() {
        for (delivered in listOf(false, true)) noticeInsideAToolGroupStaysThereWhenItsMessageSettles(delivered)
    }

    private fun noticeInsideAToolGroupStaysThereWhenItsMessageSettles(delivered: Boolean) = sessionTest { h ->
        val read = noticeTool("read", messageID = "msg_1")
        val store = h.noticeStore(listOf(noticeReply, read)) {
            // The read arrived over SSE; its message's text will end it.
            it.copy(projectedMessageIDs = setOf("msg_1"), refresh = State.Refresh(id = 1, reason = RefreshReason.Settlement(messageID = "msg_1")))
        }
        inbox(store, listOf(pendingNoticeInput))
        assertEquals(listOf(noticeReply, read, pendingNotice), store.state.items)
        if (delivered) {
            inbox(store, emptyList())
            history(store, listOf(noticeReply, pendingNotice))
            assertTrue(store.state.pendingNoticeIDs.isEmpty())
        }
        store.send(
            Action.ContentBatch(
                sessionID = "ses_parent",
                events = listOf(ai.opencode.server.OpenCodeLiveContentEvent.TextDelta(messageID = "msg_1", ordinal = 0, delta = "The screenshot matches.")),
            ),
        )
        val text = markdown("msg_1-text-0", "The screenshot matches.").copy(inProgress = true)
        assertEquals("delivered=$delivered", listOf(noticeReply, read, pendingNotice, text), store.state.items)
        val shown = TranscriptEntry.build(store.state.visibleItems, isRunning = true)
        assertEquals(listOf("msg_0-text-0", "group-msg_1-tool-read", "msg_1-text-0"), shown.map { it.id })

        store.send(
            Action.RefreshResponse(
                sessionID = "ses_parent", requestID = 1,
                result = Result.success(
                    AuthoritativeRefresh(observed = testSession(id = "ses_parent", title = "Parent"), items = listOf(read, text), scope = AuthoritativeRefresh.Scope.Message("msg_1")),
                ),
            ),
        )
        assertEquals(listOf(noticeReply, read, pendingNotice, text), store.state.items)
        val entries = TranscriptEntry.build(store.state.visibleItems, isRunning = true)
        assertEquals("No Updates row of its own", listOf("msg_0-text-0", "group-msg_1-tool-read", "msg_1-text-0"), entries.map { it.id })
        assertEquals(TranscriptEntry.ToolGroup(groupID = "msg_1-tool-read", tools = listOf(read, pendingNotice)), entries[1])
        assertEquals("Notices don't inflate the count", "Used 1 tool", ToolGroupLabel.of(listOf(read, pendingNotice)).first)
    }

    @Test fun cancelledPendingNoticeLeavesTheTranscript() = sessionTest { h ->
        val store = h.noticeStore(listOf(noticeReply))
        inbox(store, listOf(pendingNoticeInput))
        store.send(Action.InputCancelled("msg_notice"))
        assertEquals(listOf<OpenCodeTranscriptItem>(noticeReply), store.state.items)
        assertTrue(store.state.pendingNoticeIDs.isEmpty())
    }

    @Test fun pendingFailureNoticeKeepsItsCueWhileCollapsed() = sessionTest { h ->
        val store = h.noticeStore(listOf(noticeReply))
        val failed = OpenCodeTranscriptItem.Notice(id = "msg_failed", text = "Subagent failed · Review", isError = true)
        inbox(store, listOf(OpenCodePendingInput(id = "msg_failed", text = "", notice = failed)))
        val group = TranscriptEntry.build(store.state.visibleItems, isRunning = true).lastOrNull() as? TranscriptEntry.ToolGroup
            ?: return@sessionTest fail("Expected the failure in a group")
        assertEquals("Subagent failed", ToolGroupLabel.failedNotices(group.tools))
    }

    @Test fun pendingPromotionSurvivesInboxBeforeHistory() {
        for (source in listOf("typed", "sendNow", "voice")) pendingPromotionSurvivesInboxBeforeHistory(source)
    }

    private fun pendingPromotionSurvivesInboxBeforeHistory(source: String) = sessionTest { h ->
        val session = testSession(id = "ses_promotion", title = "Promotion")
        val first = user("msg_first", "First")
        val id = "msg_11111111111111111111111111111111"
        val text = if (source == "voice") {
            "Voice delegation. Instruction is the cleaned interpretation of the user's request.\n{\"instruction\":\"Check the build\",\"transcript_delta\":[\"user: check it\"]}"
        } else {
            "Check the build"
        }
        val input = OpenCodePendingInput(id = id, text = text, delivery = if (source == "sendNow") OpenCodeDelivery.Queue else OpenCodeDelivery.Steer)
        var state = h.makeState(session = session, cachedItems = listOf(first)).copy(isExternallyActive = true)
        if (source != "voice") {
            state = state.copy(promptStates = state.promptStates + (id to State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted)))
        }
        val gate = CompletableDeferred<Unit>()
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                message = { _, _, messageID ->
                    assertEquals(id, messageID)
                    gate.await()
                    listOf(user(id, text))
                },
                setPendingInputDelivery = { _, _, _, _ -> },
                pendingInputs = { _, _ -> listOf(OpenCodePendingInput(id = id, text = text, delivery = OpenCodeDelivery.Steer)) },
            )
        }
        store.exhaustive = false
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(input)))
        if (source == "sendNow") {
            store.send(Action.PendingInputSendNowTapped(id))
            store.receive(Action.PendingInputsResponse::class)
        }
        val pending = store.state.queuedInputRows
        assertEquals(source, listOf(id), pending.map { it.id })
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = emptyList()))
        assertEquals("An inbox omission is not a rendered promotion", pending, store.state.queuedInputRows)
        gate.complete(Unit)
        store.finish()
        store.skipReceivedActions()
        assertEquals(source, listOf(first, user(id, text)), store.state.items)
        assertTrue(store.state.queuedInputs.isEmpty())
        val entries = TranscriptEntry.build(store.state.items, isRunning = true)
        val plan = TranscriptRowIdentityPlan.make(
            previousEntries = TranscriptEntry.build(listOf(first)), previousQueued = pending,
            previousEntryStableIDs = mapOf("user-msg_first" to "user-msg_first"), previousQueuedStableIDs = mapOf(id to id),
            entries = entries, queued = store.state.queuedInputRows,
        )
        assertEquals(id, plan.entryStableIDs["user-$id"])
        assertEquals(setOf("user-$id"), plan.promotedEntryIDs)
        // A page read captured before delivery must not erase the targeted echo.
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(listOf(first))))
        assertEquals(listOf(first, user(id, text)), store.state.items)
        // A newer page can omit it too, but it must stay before that page, never
        // stick below later replies as if it were still an undelivered prompt.
        val later = markdown("msg_later-text-0", "")
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(listOf(later))))
        assertTrue(source, store.state.items.indexOfFirst { it.id == id } < store.state.items.indexOfFirst { it.id == later.id })
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(listOf(first, user(id, text)))))
        assertTrue(store.state.optimisticPromptIDs.isEmpty())
        assertTrue(store.state.echoedPromptIDs.isEmpty())
        store.finish()
    }

    /**
     * Move to Queue on a waiting steering message: it leaves the transcript
     * for the queued pill at once, the server is asked to queue it, and a
     * failure puts it back as steering.
     */
    @Test fun steeringMessageMovesToTheQueue() {
        for (fails in listOf(false, true)) steeringMessageMovesToTheQueue(fails)
    }

    private fun steeringMessageMovesToTheQueue(fails: Boolean) = sessionTest { h ->
        val session = testSession(id = "ses_queue", title = "Queue")
        val steering = OpenCodePendingInput(id = "msg_steer", text = "Use the staging bucket", delivery = OpenCodeDelivery.Steer)
        val later = OpenCodePendingInput(id = "msg_later", text = "And keep retries at 3", delivery = OpenCodeDelivery.Steer)
        val state = h.makeState(session = session, cachedItems = listOf(user("msg_first", "First"))).copy(
            isExternallyActive = true,
            queuedInputs = listOf(steering, later),
            pendingPromptPositions = mapOf(
                steering.id to State.PendingPromptPosition(precedingPromptIDs = setOf("msg_first"), precedingMessageIDs = emptySet(), requestEpoch = 1),
            ),
        )
        val calls = LockIsolated(listOf<Pair<String, OpenCodeDelivery>>())
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                setPendingInputDelivery = { _, sessionID, id, delivery ->
                    assertEquals(session.id, sessionID)
                    calls.append(id to delivery)
                    if (fails) throw OpenCodeClientError("The server returned HTTP 409.", statusCode = 409)
                },
                pendingInputs = { _, _ ->
                    if (fails) listOf(steering, later) else listOf(steering.copy(delivery = OpenCodeDelivery.Queue), later)
                },
            )
        }
        store.exhaustive = false
        store.send(Action.PendingInputMoveToQueueTapped(steering.id)) { it.withQueuedDelivery(0, OpenCodeDelivery.Queue) }
        assertEquals("It waits in the pill now", true, store.state.queuedInputRows.firstOrNull()?.waitsInQueuedPill)
        assertEquals(
            "Queued, the steering message sent after it may overtake it",
            true, store.state.pendingPromptPositions[steering.id]?.precedingPromptIDs?.contains(later.id),
        )
        if (fails) {
            store.receive(Action.PendingInputUpdateFailed::class) { it.withQueuedDelivery(0, OpenCodeDelivery.Steer) }
        }
        store.receive(Action.PendingInputsRequested::class)
        store.receive(Action.PendingInputsResponse::class)
        assertEquals(listOf(steering.id), calls.value.map { it.first })
        assertEquals(listOf(OpenCodeDelivery.Queue), calls.value.map { it.second })
        assertEquals(listOf(if (fails) OpenCodeDelivery.Steer else OpenCodeDelivery.Queue, OpenCodeDelivery.Steer), store.state.queuedInputs.map { it.delivery })
        assertEquals("fails=$fails", fails, store.state.items.any { it is OpenCodeTranscriptItem.Info })

        // Only a waiting steering message moves: a queued one is already there.
        if (!fails) {
            store.send(Action.PendingInputMoveToQueueTapped(steering.id))
            assertEquals(1, calls.value.size)
        }
        store.finish()
    }

    /**
     * An inbox read that started before Move to Queue (or Send Now) lists the
     * old delivery; it must not move the message back while the switch is on
     * its way, and the server's confirmation retires the override.
     */
    @Test fun anInboxReadFromBeforeADeliveryChangeDoesNotBounceIt() {
        for (from in listOf(OpenCodeDelivery.Steer, OpenCodeDelivery.Queue)) anInboxReadFromBeforeADeliveryChangeDoesNotBounceIt(from)
    }

    private fun anInboxReadFromBeforeADeliveryChangeDoesNotBounceIt(from: OpenCodeDelivery) = sessionTest { h ->
        val session = testSession(id = "ses_bounce", title = "Bounce")
        val to = if (from == OpenCodeDelivery.Steer) OpenCodeDelivery.Queue else OpenCodeDelivery.Steer
        val input = OpenCodePendingInput(id = "msg_bounce", text = "Check the logs", delivery = from)
        val state = h.makeState(session = session, cachedItems = listOf(user("msg_first", "First"))).copy(isExternallyActive = true, queuedInputs = listOf(input))
        val gate = CompletableDeferred<Unit>()
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                setPendingInputDelivery = { _, _, _, _ -> gate.await() },
                pendingInputs = { _, _ -> listOf(input.copy(delivery = to)) },
            )
        }
        store.exhaustive = false
        store.send(if (from == OpenCodeDelivery.Steer) Action.PendingInputMoveToQueueTapped(input.id) else Action.PendingInputSendNowTapped(input.id))
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(input)))
        assertEquals("The stale read leaves it where it was moved", listOf(to), store.state.queuedInputs.map { it.delivery })
        assertEquals(to, store.state.deliveryOverrides[input.id])
        gate.complete(Unit)
        store.receive(Action.PendingInputsRequested::class)
        store.receive(Action.PendingInputsResponse::class)
        assertEquals(listOf(to), store.state.queuedInputs.map { it.delivery })
        assertTrue("Confirmed by the server", store.state.deliveryOverrides.isEmpty())
        store.finish()
    }

    /**
     * Move to Queue and Delete while the message's send is still in flight:
     * the row changes at once, an inbox read from before the change doesn't
     * move it back, and the server hears about it once the send is admitted.
     */
    @Test fun changesWhileTheSendIsInFlightReachTheServerOnAdmission() {
        for (change in listOf("moveToQueue", "delete", "deleteThenFails")) changesWhileTheSendIsInFlightReachTheServerOnAdmission(change)
    }

    private fun changesWhileTheSendIsInFlightReachTheServerOnAdmission(change: String) = sessionTest { h ->
        val session = testSession(id = "ses_flight", title = "Flight")
        val id = "msg_flight"
        val steering = OpenCodePendingInput(id = id, text = "Use the staging bucket", delivery = OpenCodeDelivery.Steer)
        val state = h.makeState(session = session, cachedItems = listOf(user("msg_first", "First"))).copy(
            isExternallyActive = true,
            isAwaitingReply = true,
            queuedInputs = listOf(steering),
            promptStates = mapOf(id to State.PromptState(requestEpoch = 1)),
            sentDeliveries = mapOf(id to OpenCodeDelivery.Steer),
            optimisticPromptIDs = setOf(id),
        )
        val deliveries = LockIsolated(listOf<Pair<String, OpenCodeDelivery>>())
        val cancelled = LockIsolated(listOf<String>())
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                setPendingInputDelivery = { _, _, _, delivery -> deliveries.append(id to delivery) },
                cancelPendingInput = { _, _, id -> cancelled.append(id) },
                pendingInputs = { _, _ ->
                    if (change == "moveToQueue") listOf(OpenCodePendingInput(id = id, text = steering.text, delivery = OpenCodeDelivery.Queue)) else emptyList()
                },
                message = { _, _, _ -> throw OpenCodeClientError("Missing", statusCode = 404) },
            )
        }
        store.exhaustive = false

        if (change == "moveToQueue") {
            store.send(Action.PendingInputMoveToQueueTapped(id)) {
                it.withQueuedDelivery(0, OpenCodeDelivery.Queue).copy(deliveryOverrides = it.deliveryOverrides + (id to OpenCodeDelivery.Queue))
            }
            // The server still lists it as steering until the deferred change.
            store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(steering)))
            assertEquals("An older read doesn't bounce it back", listOf(OpenCodeDelivery.Queue), store.state.queuedInputs.map { it.delivery })
        } else {
            store.send(Action.PendingInputCancelTapped(id)) {
                it.copy(queuedInputs = emptyList(), cancelledInputIDs = setOf(id), optimisticPromptIDs = emptySet())
            }
            assertEquals("Kept until the send settles", State.PromptState.Admission.sent, store.state.promptStates[id]?.admission)
            store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(steering)))
            assertTrue(store.state.queuedInputs.isEmpty())
        }
        assertTrue("Nothing reaches the server before admission", deliveries.value.isEmpty() && cancelled.value.isEmpty())

        if (change == "deleteThenFails") {
            val pending = PendingPrompt(
                messageID = id, text = steering.text, attachments = emptyList(), wasRunning = true, delivery = OpenCodeDelivery.Steer, requestEpoch = 1,
            )
            store.send(Action.PromptFailed(sessionID = session.id, error = OpenCodeClientError("The server returned HTTP 502.", statusCode = 502), pending = pending))
            assertTrue("A deleted message's failed send isn't offered for retry", store.state.failedSends.isEmpty())
            assertEquals(listOf<OpenCodeTranscriptItem>(user("msg_first", "First")), store.state.items)
            assertNull(store.state.promptStates[id])
        } else {
            store.send(Action.PromptAdmitted(sessionID = session.id, messageID = id, requestEpoch = 1))
            store.receive(Action.PendingInputsRequested::class)
            if (change == "moveToQueue") {
                assertEquals(listOf(id), deliveries.value.map { it.first })
                assertEquals(listOf(OpenCodeDelivery.Queue), deliveries.value.map { it.second })
                assertTrue(cancelled.value.isEmpty())
            } else {
                assertEquals(listOf(id), cancelled.value)
                assertTrue(deliveries.value.isEmpty())
                assertNull(store.state.promptStates[id])
            }
            assertNull(change, store.state.sentDeliveries[id])
        }
        store.finish()
    }

    /**
     * Delete on a waiting steering message cancels it on the server and
     * takes its row out at once, like Cancel in the queued sheet.
     */
    @Test fun steeringMessageDeletes() = sessionTest { h ->
        val session = testSession(id = "ses_delete", title = "Delete")
        val steering = OpenCodePendingInput(id = "msg_steer", text = "Never mind", delivery = OpenCodeDelivery.Steer)
        val state = h.makeState(session = session, cachedItems = listOf(user("msg_first", "First"))).copy(isExternallyActive = true, queuedInputs = listOf(steering))
        val cancelled = LockIsolated(listOf<String>())
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                cancelPendingInput = { _, sessionID, id ->
                    assertEquals(session.id, sessionID)
                    cancelled.append(id)
                },
                pendingInputs = { _, _ -> listOf(steering) },
            )
        }
        store.exhaustive = false
        store.send(Action.PendingInputCancelTapped(steering.id)) {
            it.copy(queuedInputs = emptyList(), cancelledInputIDs = setOf(steering.id))
        }
        store.receive(Action.PendingInputsRequested::class)
        store.receive(Action.PendingInputsResponse::class)
        assertEquals(listOf(steering.id), cancelled.value)
        assertTrue("A read from before the cancel doesn't bring it back", store.state.queuedInputs.isEmpty())
        store.finish()
    }

    @Test fun cancelledPromotionRejectsItsLateEcho() {
        for (remote in listOf(false, true)) cancelledPromotionRejectsItsLateEcho(remote)
    }

    private fun cancelledPromotionRejectsItsLateEcho(remote: Boolean) = sessionTest { h ->
        val session = testSession(id = "ses_cancel", title = "Cancel")
        val first = user("first", "First")
        val input = OpenCodePendingInput(id = "msg_cancel", text = "Cancel me")
        var state = h.makeState(session = session, cachedItems = listOf(first))
        if (!remote) state = state.copy(promptStates = mapOf(input.id to State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted)))
        val gate = CompletableDeferred<Unit>()
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                message = { _, _, _ ->
                    gate.await()
                    listOf(user(input.id, input.text))
                },
                cancelPendingInput = { _, _, _ -> },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(input)))
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = emptyList()))
        assertEquals(listOf(input), store.state.queuedInputs)
        store.send(if (remote) Action.InputCancelled(input.id) else Action.PendingInputCancelTapped(input.id))
        gate.complete(Unit)
        store.finish()
        store.skipReceivedActions()
        assertTrue("remote=$remote", store.state.queuedInputs.isEmpty())
        assertTrue(store.state.pendingPromptPositions.isEmpty())
        assertEquals(listOf<OpenCodeTranscriptItem>(first), store.state.items)
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(input)))
        assertTrue(store.state.queuedInputs.isEmpty())
    }

    @Test fun remotePromptLookupOnlyRetiresAuthoritativeAbsence() {
        for (status in listOf(404, 500)) remotePromptLookupOnlyRetiresAuthoritativeAbsence(status)
    }

    private fun remotePromptLookupOnlyRetiresAuthoritativeAbsence(status: Int) = sessionTest { h ->
        val session = testSession(id = "ses_lookup", title = "Lookup")
        val input = OpenCodePendingInput(id = "msg_remote", text = "From voice")
        val store = h.store(h.makeState(session = session, cachedItems = emptyList())) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(message = { _, _, _ -> throw OpenCodeClientError("Read failed", statusCode = status) })
        }
        store.exhaustive = false
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(input)))
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = emptyList()))
        store.finish()
        store.skipReceivedActions()
        assertEquals("status=$status", if (status == 404) emptyList() else listOf(input), store.state.queuedInputs)
        assertFalse("A 404 is not an explicit cancellation", store.state.cancelledInputIDs.contains(input.id))
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(input)))
        assertEquals("An authoritative inbox read can restore a remote prompt", listOf(input), store.state.queuedInputs)
    }

    @Test fun promotionDoesNotDependOnAnUndeliveredPredecessor() {
        for (path in listOf("steer", "sendNow", "cancelFirst", "voice")) promotionDoesNotDependOnAnUndeliveredPredecessor(path)
    }

    private fun promotionDoesNotDependOnAnUndeliveredPredecessor(path: String) = sessionTest { h ->
        val session = testSession(id = "ses_order", title = "Order")
        val first = user("msg_first", "First")
        val queued = OpenCodePendingInput(id = "msg_queued", text = "Later", delivery = OpenCodeDelivery.Queue)
        val second = OpenCodePendingInput(
            id = "msg_second", text = if (path == "voice") "Voice delegation" else "Do this now",
            delivery = if (path == "sendNow") OpenCodeDelivery.Queue else OpenCodeDelivery.Steer,
        )
        val state = h.makeState(session = session, cachedItems = listOf(first)).copy(isExternallyActive = true)
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                setPendingInputDelivery = { _, _, _, _ -> },
                pendingInputs = { _, _ -> listOf(queued, second.copy(delivery = OpenCodeDelivery.Steer)) },
            )
        }
        store.exhaustive = false
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(queued)))
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(queued, second)))
        if (path == "sendNow") {
            store.send(Action.PendingInputSendNowTapped(second.id))
            store.receive(Action.PendingInputsResponse::class)
        }
        if (path == "cancelFirst") store.send(Action.InputCancelled(queued.id))
        val epoch = store.state.pendingPromptPositions[second.id]!!.requestEpoch
        store.send(Action.PromptEchoResponse(sessionID = session.id, messageID = second.id, requestEpoch = epoch, items = listOf(user(second.id, second.text))))
        assertEquals(path, listOf(first.id, second.id), store.state.items.map { it.id })
        assertFalse(store.state.queuedInputs.any { it.id == second.id })
        if (path != "cancelFirst") {
            val queuedEpoch = store.state.pendingPromptPositions[queued.id]!!.requestEpoch
            store.send(Action.PromptEchoResponse(sessionID = session.id, messageID = queued.id, requestEpoch = queuedEpoch, items = listOf(user(queued.id, queued.text))))
            assertEquals("The queued prompt delivers after the steering prompt", listOf(first.id, second.id, queued.id), store.state.items.map { it.id })
        }
        store.finish()
    }

    @Test fun promotionSurvivesATransientLastRow() {
        for (kind in listOf("info", "notice", "projection")) promotionSurvivesATransientLastRow(kind)
    }

    private fun promotionSurvivesATransientLastRow(kind: String) = sessionTest { h ->
        val session = testSession(id = "ses_transient", title = "Transient")
        val first = user("msg_first", "First")
        val transient: OpenCodeTranscriptItem = when (kind) {
            "info" -> OpenCodeTranscriptItem.Info(id = "transient", text = "Temporary")
            "notice" -> OpenCodeTranscriptItem.Notice(id = "transient", text = "Temporary")
            else -> markdown("msg_reply-text-0", "")
        }
        val initial = if (kind == "projection") listOf(transient) else listOf(first, transient)
        val refreshed: List<OpenCodeTranscriptItem> = if (kind == "projection") listOf(markdown("msg_reply-text-1", "")) else listOf(first)
        val store = h.store(h.makeState(session = session, cachedItems = initial)) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                message = { _, _, _ -> refreshed },
            )
        }
        store.exhaustive = false
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(OpenCodePendingInput(id = "msg_next", text = "Next"))))
        if (kind == "projection") {
            store.send(Action.RefreshRequested(RefreshReason.Settlement(messageID = "msg_reply")))
            store.receive(Action.RefreshResponse::class)
        } else {
            store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(refreshed)))
        }
        store.send(Action.PromptEchoResponse(sessionID = session.id, messageID = "msg_next", requestEpoch = 1, items = listOf(user("msg_next", "Next"))))
        assertEquals(kind, refreshed.map { it.id } + "msg_next", store.state.items.map { it.id })
        store.finish()
    }

    @Test fun newerInboxFetchWinsOverALateEmptyRead() = sessionTest { h ->
        val session = testSession(id = "ses_inbox", title = "Inbox")
        val input = OpenCodePendingInput(id = "msg_voice", text = "From voice")
        val old = Channel<List<OpenCodePendingInput>>(Channel.UNLIMITED)
        val new = Channel<List<OpenCodePendingInput>>(Channel.UNLIMITED)
        val starts = Channel<Int>(Channel.UNLIMITED)
        val count = LockIsolated(0)
        val lookups = LockIsolated(0)
        val store = h.store(h.makeState(session = session, cachedItems = emptyList())) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                pendingInputs = { _, _ ->
                    val index = count.increment()
                    starts.trySend(index)
                    (if (index == 1) old else new).receiveCatching().getOrNull() ?: emptyList()
                },
                message = { _, _, _ ->
                    lookups.increment()
                    throw OpenCodeClientError("Not delivered", statusCode = 404)
                },
            )
        }
        store.exhaustive = false
        store.send(Action.InputEnqueued(null))
        assertEquals(1, withTimeout(5.seconds) { starts.receive() })
        store.send(Action.InputEnqueued(null))
        assertEquals(2, withTimeout(5.seconds) { starts.receive() })
        new.trySend(listOf(input))
        new.close()
        store.receive(Action.PendingInputsResponse::class)
        old.trySend(emptyList())
        old.close()
        store.finish()
        store.skipReceivedActions()
        assertEquals("The cancelled older inbox read must not trigger a message lookup", 0, lookups.value)
        assertEquals(listOf(input), store.state.queuedInputs)
        assertTrue(store.state.cancelledInputIDs.isEmpty())
    }

    /** Only Sessions I Use Here: a send turns this phone's own bell on for that session, once. */
    @Test fun composerSendTurnsThisPhonesBellOnWhenOnlySessionsUsedHereNotify() = sessionTest { h ->
        val session = testSession(id = "ses_notify", title = "Notify")
        val state = h.makeState(session = session, cachedItems = emptyList()).withDraft("Fix the bug")
        val device = OpenCodePushDevice(
            id = "ref-1", name = "Pixel", platform = "android", expiresAt = 1L,
            defaultLevel = OpenCodePushLevel.none, paused = false, sessions = emptyMap(),
        )
        h.sharedStore.reference(PushSharedKeys.choices).set(mapOf(testServer.persistenceID to PushChoice(mode = PushMode.fromThisPhone)))
        h.sharedStore.reference(PushSharedKeys.servers).set(
            mapOf(testServer.persistenceID to PushServer(PushStatus.Ready, OpenCodePushLevel.entries, "ref-1", OpenCodePushState(devices = listOf(device)))),
        )
        val bells = LockIsolated(listOf<Pair<String, OpenCodePushBell?>>())
        val failures = LockIsolated(1)
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> awaitCancellation() },
                setPushSessionBell = { _, id, sessionID, bell ->
                    assertEquals("ref-1", id)
                    if (failures.value > 0) {
                        failures.setValue(0)
                        throw OpenCodeClientError("Busy", statusCode = 503)
                    }
                    bells.update { it + (sessionID to bell) }
                },
            )
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        store.receive(Action.PromptAdmitted::class)
        assertTrue(eventually(timeout = 5.seconds, wallClock = true) { bells.value.isNotEmpty() })
        h.skipInFlightEffects()
        assertEquals(listOf("ses_notify" to OpenCodePushBell(OpenCodePushLevel.all)), bells.value)
    }

    /**
     * A send from a reader browsing a long session lets go of the rows they
     * were reading in the send's own update, so the list applies one
     * snapshot rather than trimming them a frame into its glide to the end.
     */
    @Test fun sendingWhileBrowsingALongSessionReturnsTheWindowToTheEndAtOnce() = sessionTest { h ->
        val session = testSession(id = "ses_long", title = "Long")
        val items = (0 until 30).map { user("msg_$it", "Message $it") }
        val state = h.makeState(session = session, cachedItems = items).withDraft("Back to the end")
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> awaitCancellation() },
            )
        }
        store.exhaustive = false
        store.send(Action.TranscriptBrowsingChanged(true))
        val readingStart = store.state.visibleItems.firstOrNull()?.id
        assertEquals("msg_10", readingStart)
        assertEquals(readingStart, store.state.browsingWindowStartID)
        store.send(Action.SendTapped) { it.copy(browsingWindowStartID = null) }
        assertEquals(store.state.visibleLimit, store.state.visibleItems.size)
        assertEquals(store.state.lastSentItemID, store.state.visibleItems.lastOrNull()?.id)
        h.skipInFlightEffects()
    }

    @Test fun typedSteeringOvertakesAQueuedPrompt() = sessionTest { h ->
        val session = testSession(id = "ses_typed", title = "Typed")
        val queued = OpenCodePendingInput(id = "msg_queued", text = "Later", delivery = OpenCodeDelivery.Queue)
        val id = sentID
        val state = h.makeState(session = session, cachedItems = listOf(user("msg_first", "First"))).copy(isExternallyActive = true).withDraft("Now")
        val wait = CompletableDeferred<Unit>()
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, messageID, delivery ->
                    assertEquals(id, messageID)
                    assertEquals(OpenCodeDelivery.Steer, delivery)
                },
                pendingInputs = { _, _ -> listOf(queued, OpenCodePendingInput(id = id, text = "Now")) },
                waitForIdle = { _, _ ->
                    wait.await()
                    throw OpenCodeClientError("Disconnected")
                },
            )
        }
        store.exhaustive = false
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(queued)))
        store.send(Action.SendTapped)
        store.receive(Action.PromptAdmitted::class)
        store.receive(Action.PendingInputsResponse::class)
        val epoch = store.state.promptStates[id]!!.requestEpoch
        store.send(Action.PromptEchoResponse(sessionID = session.id, messageID = id, requestEpoch = epoch, items = listOf(user(id, "Now"))))
        assertEquals(listOf("msg_first", id), store.state.items.map { it.id })
        store.send(Action.PromptEchoResponse(sessionID = session.id, messageID = queued.id, requestEpoch = 1, items = listOf(user(queued.id, queued.text))))
        assertEquals(listOf("msg_first", id, queued.id), store.state.items.map { it.id })
        wait.complete(Unit)
        store.finish()
    }

    @Test fun deliveredPromptBehindANewerAssistantWindowDoesNotBecomeTheTail() = sessionTest { h ->
        val session = testSession(id = "ses_window", title = "Window")
        val first = user("msg_first", "First")
        val later = markdown("msg_later-text-0", "")
        val store = h.store(h.makeState(session = session, cachedItems = listOf(first)))
        store.exhaustive = false
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(OpenCodePendingInput(id = "msg_pending", text = "Earlier"))))
        // The newest page has moved beyond the prompt, even though it contains
        // only assistant messages and therefore adds no new prompt IDs.
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(listOf(later))))
        store.send(Action.PromptEchoResponse(sessionID = session.id, messageID = "msg_pending", requestEpoch = 1, items = listOf(user("msg_pending", "Earlier"))))
        assertEquals(listOf(first, later), store.state.items)
        assertTrue(store.state.queuedInputs.isEmpty())
        store.finish()
    }

    @Test fun lateFirstPromptEchoRespectsInboxOrder() {
        for (firstIsQueued in listOf(false, true)) lateFirstPromptEchoRespectsInboxOrder(firstIsQueued)
    }

    private fun lateFirstPromptEchoRespectsInboxOrder(firstIsQueued: Boolean) = sessionTest { h ->
        val session = testSession(id = "ses_order", title = "Order")
        val first = user("msg_a", "First")
        val second = user("msg_b", "Second")
        val inputs = listOf(
            OpenCodePendingInput(id = first.id, text = "First", delivery = if (firstIsQueued) OpenCodeDelivery.Queue else OpenCodeDelivery.Steer),
            OpenCodePendingInput(id = second.id, text = "Second", delivery = OpenCodeDelivery.Steer),
        )
        val state = h.makeState(session = session, cachedItems = emptyList()).copy(earlierHistory = State.EarlierHistory.Loading(cursor = null, reachedLoadedItems = false))
        val store = h.store(state)
        store.exhaustive = false
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = inputs))
        // Repeated reads must preserve the same predecessor ordering too.
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = inputs))
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(listOf(second))))
        store.send(Action.PromptEchoResponse(sessionID = session.id, messageID = first.id, requestEpoch = 1, items = listOf(first)))
        assertTrue(store.state.queuedInputs.isEmpty())
        if (firstIsQueued) {
            assertEquals("Steering can overtake a queued prompt", listOf<OpenCodeTranscriptItem>(second, first), store.state.items)
        } else {
            assertEquals("The earlier steer must not append after the later one", listOf<OpenCodeTranscriptItem>(second), store.state.items)
            store.send(
                Action.EarlierHistoryResponse(
                    sessionID = session.id, generation = 0, cursor = null,
                    result = Result.success(OpenCodeTranscriptPage(items = listOf(first, second), next = null)),
                ),
            )
            assertEquals("Paged history restores the earlier steer before its successor", listOf<OpenCodeTranscriptItem>(first, second), store.state.items)
        }
        store.finish()
    }

    @Test fun oneMissingPendingPromptDoesNotReorderItsSiblings() = sessionTest { h ->
        val session = testSession(id = "ses_order", title = "Order")
        val inputs = listOf(OpenCodePendingInput(id = "msg_a", text = "First"), OpenCodePendingInput(id = "msg_b", text = "Second"))
        val store = h.store(h.makeState(session = session, cachedItems = emptyList())) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(message = { _, _, _ -> throw OpenCodeClientError("Offline") })
        }
        store.exhaustive = false
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = inputs))
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(inputs[1])))
        assertEquals(inputs, store.state.queuedInputs)
        store.finish()
        store.send(Action.PromptEchoResponse(sessionID = session.id, messageID = "msg_a", requestEpoch = 1, items = listOf(user("msg_a", "First"))))
        store.send(Action.PromptEchoResponse(sessionID = session.id, messageID = "msg_b", requestEpoch = 2, items = listOf(user("msg_b", "Second"))))
        assertEquals(listOf("msg_a", "msg_b"), store.state.items.map { it.id })
        assertTrue(store.state.queuedInputs.isEmpty())
    }

    @Test fun failedPromptLookupKeepsQueueAndLateInboxCannotDuplicateRemotePrompt() = sessionTest { h ->
        val session = testSession(directory = "/tmp/demo")
        val state = h.makeState(session = session, cachedItems = listOf(user("msg_remote", "Other client"))).copy(
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_pending", text = "Still pending")),
            promptStates = mapOf("msg_pending" to State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted)),
        )
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(message = { _, _, _ -> throw OpenCodeClientError("Offline") })
        }
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(OpenCodePendingInput(id = "msg_remote", text = "Other client"))))
        store.finish()
        assertEquals(listOf("msg_pending"), store.state.queuedInputs.map { it.id })
    }

    @Test fun switchingSessionsCancelsPromptLookupAndRejectsItsLateEcho() = sessionTest { h ->
        val root = testSession(id = "ses_root", title = "Root")
        val child = testSession(id = "ses_child", title = "Child", parentID = root.id)
        val state = h.makeState(session = root, cachedItems = emptyList()).copy(
            familySessions = listOf(root, child),
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_pending", text = "Root prompt")),
            promptStates = mapOf("msg_pending" to State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted)),
        )
        val responses = Channel<List<OpenCodeTranscriptItem>>(Channel.UNLIMITED)
        val cancelled = LockIsolated(false)
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                message = { _, _, _ ->
                    try {
                        responses.receive()
                    } catch (cancellation: CancellationException) {
                        cancelled.setValue(true)
                        throw cancellation
                    }
                },
                getSession = { _, id -> if (id == child.id) child else root },
                transcript = { _, _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(Action.PendingInputsResponse(sessionID = root.id, inputs = emptyList()))
        store.send(Action.FamilyPicked(child.id))
        until { cancelled.value }
        assertTrue(cancelled.value)
        store.send(Action.PromptEchoResponse(sessionID = root.id, messageID = "msg_pending", requestEpoch = 1, items = listOf(user("msg_pending", "Root prompt"))))
        assertTrue(store.state.items.isEmpty())
        assertTrue(store.state.promotedPromptIDs.isEmpty())
        h.skipInFlightEffects()
        responses.close()
    }
}
