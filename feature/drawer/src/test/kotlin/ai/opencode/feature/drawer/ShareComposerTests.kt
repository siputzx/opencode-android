package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.testing.eventually
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.SharedDraftTarget
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.withDraft
import ai.opencode.feature.system.share.ReceivedShare
import ai.opencode.feature.system.share.ShareCredentials
import ai.opencode.feature.system.share.ShareHandoff
import ai.opencode.feature.system.share.ShareInboxClient
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.TranscriptCachePersistence
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShareComposerAppTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(
        url = "http://studio.tail1234.ts.net:4096", username = "opencode", password = "secret",
        alternateURLs = listOf("http://192.168.1.20:4096"), headers = listOf(OpenCodeServer.Header(name = "CF-Access-Client-Id", value = "abc")),
    )
    private val project = OpenCodeProject(id = "proj", name = "Work", directory = "/work")
    private val current = OpenCodeSession(
        id = "ses_current", serverTitle = "Current", projectID = "proj", directory = "/work", updatedAt = Instant.ofEpochSecond(30),
    )
    private val target = OpenCodeSession(
        id = "ses_target", serverTitle = "Target", projectID = "proj", directory = "/work", updatedAt = Instant.ofEpochSecond(20),
    )

    private val context = HomeTestContext()

    private fun share(delivery: ShareHandoff.Delivery, to: String, text: String, messageID: String? = null) = ReceivedShare(
        handoff = ShareHandoff(
            id = UUID.randomUUID(), createdAt = Instant.ofEpochSecond(100), serverID = server.persistenceID,
            target = ShareHandoff.Target.Session(id = to, projectID = "proj"), text = text, delivery = delivery, messageID = messageID,
        ),
        images = emptyList(),
    )

    private fun home(typed: String = ""): HomeFeature.State {
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(current, target), activeSessionIDs = emptySet()))
        state = state.copy(transcriptCache = mapOf(current.id to emptyList(), target.id to emptyList()))
        state = state.openingDetail(current, cachedItems = emptyList())
        return state.copy(sessionDetail = state.sessionDetail!!.withDraft(typed))
    }

    private val sessionTapped = { action: HomeFeature.Action -> action is HomeFeature.Action.SessionTapped }

    private fun shareInbox(
        publishCredentials: (ShareCredentials) -> Unit = {},
        donateSuggestion: (String, String, String) -> Unit = { _, _, _ -> },
    ) = ShareInboxClient(
        consume = { emptyList() },
        publishTargets = {},
        publishCredentials = publishCredentials,
        removeTargets = {},
        donateSuggestion = donateSuggestion,
    )

    @Test
    fun sendLaterSendsWhenItsComposerHasItUnderTheTriedID() = runTest {
        val sent = Collections.synchronizedList(mutableListOf<Triple<String, String, String>>())
        val store = context.store(backgroundScope, { home() }) {
            this[UuidClient] = UuidClient.incrementing()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            // The outbox delivers on the test's scheduler (live: its own background scope).
            this[PromptOutbox] = PromptOutbox(scope = backgroundScope)
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                viewSession = { _, _, _ -> },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                sendPrompt = { _, sessionID, text, _, _, messageID, _ -> sent += Triple(sessionID, text, messageID) },
                waitForIdle = { _, _ -> awaitCancellation() },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.SharesReceived(listOf(share(ShareHandoff.Delivery.send, to = target.id, text = "Ship it", messageID = "msg_tried"))))
        store.receive(sessionTapped, "sessionTapped")
        store.receive(HomeFeature.Action.detail(SessionFeature.Action.SharedSendRequested))
        store.receive({ it.detailAction is SessionFeature.Action.PromptAdmitted }, "sessionDetail.promptAdmitted")
        assertEquals(listOf(target.id), sent.map { it.first })
        assertEquals(listOf("Ship it"), sent.map { it.second })
        assertEquals(listOf("msg_tried"), sent.map { it.third })
        assertEquals("", store.state.sessionDetail?.draft)
        assertNull(store.state.sessionDetail?.sharedMessageID)
    }

    @Test
    fun sendLaterIntoAComposerWithWordsStaysADraft() = runTest {
        val store = context.store(backgroundScope, { home(typed = "Half a thought") }) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(viewSession = { _, _, _ -> })
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.SharesReceived(listOf(share(ShareHandoff.Delivery.send, to = current.id, text = "the share"))))
        assertEquals("Half a thought\n\nthe share", store.state.sessionDetail?.draft)
        assertNull(store.state.sessionDetail?.sharedSend)
        assertEquals(1, store.state.sessionDetail?.composerFocusRequest)
    }

    @Test
    fun continueOpensTheSessionWithTheDraftAndTheKeyboard() = runTest {
        val store = context.store(backgroundScope, { home() }) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(viewSession = { _, _, _ -> })
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.SharesReceived(listOf(share(ShareHandoff.Delivery.openInComposer, to = target.id, text = "More to say"))))
        store.receive(sessionTapped, "sessionTapped")
        assertEquals(target.id, store.state.sessionDetail?.session?.id)
        assertEquals("More to say", store.state.sessionDetail?.draft)
        assertEquals(1, store.state.sessionDetail?.composerFocusRequest)
        assertNull(store.state.sessionDetail?.sharedSend)
    }

    @Test
    fun leavingTheAppSharesTheServersCredentials() = runTest {
        val published = Collections.synchronizedList(mutableListOf<ShareCredentials>())
        val store = context.store(backgroundScope, { home() }) {
            this[ShareInboxClient] = shareInbox(publishCredentials = { credentials -> published += credentials })
            this[TranscriptCachePersistence] = TranscriptCachePersistence(identity = "test", load = { null }, schedule = {}, flush = {}, remove = {})
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.TranscriptCacheFlushRequested)
        assertEquals(listOf(ShareCredentials(server)), published.toList())
    }

    @Test
    fun sendLaterToANewSessionCreatesTheOneTheSheetTried() = runTest {
        val created = Collections.synchronizedList(mutableListOf<String>())
        val handoff = ReceivedShare(
            handoff = ShareHandoff(
                id = UUID.randomUUID(), createdAt = Instant.ofEpochSecond(100), serverID = server.persistenceID,
                target = ShareHandoff.Target.NewSession(projectID = "proj"), text = "Start here", delivery = ShareHandoff.Delivery.send,
                messageID = "msg_tried", newSessionID = "ses_tried",
            ),
            images = emptyList(),
        )
        val haptics = Collections.synchronizedList(mutableListOf<Haptic>())
        val store = context.store(backgroundScope, { home() }) {
            this[UuidClient] = UuidClient.incrementing()
            // The outbox delivers on the test's scheduler (live: its own background scope).
            this[PromptOutbox] = PromptOutbox(scope = backgroundScope)
            this[HapticsClient] = HapticsClient(play = { haptics += it })
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                viewSession = { _, _, _ -> },
                createSession = { _, directory, id ->
                    created += id ?: "none"
                    OpenCodeSession(id = id ?: "ses_x", serverTitle = "New session", projectID = "proj", directory = directory, updatedAt = Instant.now())
                },
                getSession = { _, id ->
                    OpenCodeSession(id = id, serverTitle = "New session", projectID = "proj", directory = "/work", updatedAt = Instant.now())
                },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                sendPrompt = { _, _, _, _, _, _, _ -> },
                waitForIdle = { _, _ -> awaitCancellation() },
                transcript = { _, _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.SharesReceived(listOf(handoff)))
        store.receive(HomeFeature.Action.detail(SessionFeature.Action.SharedSendRequested))
        eventually { created.isNotEmpty() }
        assertEquals(listOf("ses_tried"), created.toList())
        assertFalse("No finger asked for it: no tap", Haptic.tap in haptics)
    }

    @Test
    fun sharesForTheComposerOnScreenWaitWhileAnotherSessionOpens() = runTest {
        val store = context.store(backgroundScope, { home() }) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(viewSession = { _, _, _ -> })
        }
        store.exhaustive = false
        store.send(
            HomeFeature.Action.SharesReceived(
                listOf(
                    share(ShareHandoff.Delivery.draft, to = current.id, text = "for the open one"),
                    share(ShareHandoff.Delivery.openInComposer, to = target.id, text = "newest"),
                ),
            ),
        )
        // Not absorbed into the composer about to be replaced.
        assertEquals("for the open one", store.withDependencies { store.state.sharedDrafts[SharedDraftTarget.Session(current.id)]?.text })
        store.receive(sessionTapped, "sessionTapped")
        assertEquals(target.id, store.state.sessionDetail?.session?.id)
        assertEquals("newest", store.state.sessionDetail?.draft)
        assertNull(store.state.shareNavigationTarget)
        store.send(HomeFeature.Action.SessionTapped(current.id))
        assertEquals("for the open one", store.state.sessionDetail?.draft)
    }

    @Test
    fun openingASessionOffersItInTheShareSheet() = runTest {
        val donated = Collections.synchronizedList(mutableListOf<String>())
        val store = context.store(backgroundScope, { home() }) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(viewSession = { _, _, _ -> })
            this[ShareInboxClient] = shareInbox(donateSuggestion = { _, sessionID, title -> donated += "$sessionID $title" })
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.SessionTapped(target.id))
        assertEquals(listOf("${target.id} Target"), donated.toList())
    }
}
