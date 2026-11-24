package ai.opencode.feature.system.share

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.files.ComposerFileStaging
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.intents.SystemQuickAction
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerID
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import java.io.File
import java.net.ConnectException
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// The share sheet's composer: the credentials the app shares with it, its sends, what it hands the
// app.

private val server = OpenCodeServer(
    url = "http://studio.tail1234.ts.net:4096", username = "opencode", password = "secret",
    alternateURLs = listOf("http://192.168.1.20:4096"), headers = listOf(OpenCodeServer.Header("CF-Access-Client-Id", "abc")),
)

// MARK: Credentials

/**
 * `removingAServerForgetsItsCredentialsOnly` (`ShareInboxClient.live` needs an Android `Context`
 * for its files and sharing shortcuts) runs under Robolectric in [ShareCredentialsFrameworkTests].
 */
class ShareCredentialsTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun publishingWritesOnlyChanges() {
        var writes = 0
        val memory = ShareCredentialStore.inMemory()
        val store = ShareCredentialStore(load = memory.load, save = { writes += 1; memory.save(it) }, remove = memory.remove)
        ShareCredentials.publish(ShareCredentials(server), store)
        ShareCredentials.publish(ShareCredentials(server), store)
        assertEquals(1, writes)
        assertEquals(server, store.load()?.server)

        val renamed = server.copy(password = "rotated")
        ShareCredentials.publish(ShareCredentials(renamed), store)
        assertEquals(2, writes)
        assertEquals("rotated", store.load()?.server?.password)
    }

    @Test
    fun upgradingShareKeepsTheServer() {
        val store = ShareCredentialStore.inMemory()
        store.save(ShareCredentials(server).copy(version = 1))
        assertEquals(server, store.load()?.server)
    }
}

/** [ShareCredentialsTests]' test of the live inbox client, which needs an Android `Context` (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareCredentialsFrameworkTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun donationsNeverAddToOrReorderTheLaunchersQuickActions() {
        val context = RuntimeEnvironment.getApplication()
        val sessions = (1..3).map { SystemQuickAction(SystemQuickAction.Kind.OpenSession, "Recent $it", symbol = SFSymbol.BubbleLeft, serverKey = "server", sessionID = "ses_$it") }
        val startCall = SystemQuickAction(SystemQuickAction.Kind.StartCall, "Start Call", symbol = SFSymbol.Waveform, serverKey = "server")
        for (items in listOf(emptyList(), sessions.take(1), sessions, listOf(startCall) + sessions.take(2))) {
            // This module's JVM tests don't package launcher drawable resources. Seed the real
            // shortcut manager with the launcher's IDs, labels and ranks; donate must leave them alone.
            val launcher = items.mapIndexed { index, item ->
                ShortcutInfoCompat.Builder(context, item.shortcutID).setShortLabel(item.title)
                    .setRank(index).setIntent(Intent(Intent.ACTION_VIEW)).build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, launcher)
            for (n in 4 downTo 1) ShareSuggestion.donate(context, "server", "ses_$n")
            val published = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }
            assertEquals(items.map { it.shortcutID }, published.map { it.id })
            for ((item, shortcut) in items.zip(published)) {
                assertEquals(item.sessionID?.let { "server:$it" }, ShareSuggestion.conversation(shortcut.id))
                assertEquals(item.title, shortcut.shortLabel)
            }
        }
    }

    @Test
    fun removingAServerForgetsItsCredentialsOnly() {
        val store = ShareCredentialStore.inMemory()
        val inbox = withDependencies({ this[AppContext] = RuntimeEnvironment.getApplication() }) {
            ShareInboxClient.live(credentials = store)
        }
        inbox.publishCredentials(ShareCredentials(server))
        inbox.removeTargets("someone-else")
        assertNotNull(store.load())
        inbox.removeTargets(server.persistenceID)
        assertNull(store.load())
    }
}

// MARK: Handoff format

/**
 * Android writes and reads its own handoff files (the share target runs in the app's process), so
 * “from before” means this port's JSON without the fields added later.
 */
class ShareHandoffDeliveryTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun sharesFromBeforeTheSheetSentStayDrafts() {
        val json = """{"version":1,"id":"6F9619FF-8B86-D011-B42D-00CF4FC964FF","createdAt":"1970-01-01T00:00:00Z","serverID":"s","target":{"type":"newSession","projectID":"p"},"text":"hi","images":[]}"""
        val handoff = ShareJson.decodeFromString(ShareHandoff.serializer(), json)
        assertEquals(ShareHandoff.Delivery.draft, handoff.resolvedDelivery)
        assertNull(handoff.messageID)
    }

    @Test
    fun targetsFromBeforeProjectsAndDirectoriesStillDecode() {
        val json = """{"version":1,"serverID":"s","serverName":"Studio","newSessionProject":{"id":"p","name":"Work"},"pinned":[],"recents":[{"id":"ses_1","title":"T","projectID":"p","projectName":"Work"}]}"""
        val targets = ShareJson.decodeFromString(ShareTargets.serializer(), json)
        assertEquals(listOf(ShareTargets.Project(id = "p", name = "Work")), targets.newSessionProjects)
        assertNull(targets.recents.first().directory)
    }
}

// MARK: Sending

/** Records each request and answers from a script. */
private class FakeServer(answers: List<(Seen) -> Pair<Int, ByteArray>>) {
    class Seen(val method: String, val host: String, val path: String, val query: Map<String, String>, val body: ByteArray?)

    private val lock = Any()
    private val seen = mutableListOf<Seen>()
    private val script = answers.toMutableList()

    val requests: List<Seen> get() = synchronized(lock) { seen.toList() }

    val transport = ShareSender.Transport { request, _ ->
        val body = request.body?.let { Buffer().also(it::writeTo).readByteArray() }
        val url = request.url
        val entry = Seen(
            method = request.method,
            host = url.host,
            path = url.encodedPath,
            query = url.queryParameterNames.associateWith { url.queryParameter(it) ?: "" },
            body = body,
        )
        assertEquals(server.basicAuthorization, request.header("Authorization"))
        assertEquals("abc", request.header("CF-Access-Client-Id"))
        val answer = synchronized(lock) {
            seen += entry
            if (script.isEmpty()) ok() else script.removeAt(0)
        }
        val (status, payload) = answer(entry)
        ShareSender.TransportResponse(status, Headers.headersOf(), payload)
    }
}

private fun ok(json: String = "{}"): (FakeServer.Seen) -> Pair<Int, ByteArray> = { 200 to json.toByteArray() }
private fun status(code: Int): (FakeServer.Seen) -> Pair<Int, ByteArray> = { code to ByteArray(0) }
private val offline: (FakeServer.Seen) -> Pair<Int, ByteArray> = { throw ConnectException("Couldn’t connect") }

private fun sender(fake: FakeServer, sleep: suspend (Duration) -> Unit = {}, now: (() -> Duration)? = null): ShareSender =
    if (now == null) ShareSender(server, transport = fake.transport, sleep = sleep)
    else ShareSender(server, transport = fake.transport, sleep = sleep, now = now)

private fun json(data: ByteArray?): JsonObject = Json.parseToJsonElement(String(data!!)).jsonObject

private val encoder = Json { encodeDefaults = true; explicitNulls = false }

/**
 * The two file tests stage through `ComposerFileStaging`, whose type sniffing reads Android's
 * `MimeTypeMap`: they're in [ShareSenderFrameworkTests].
 */
class ShareSenderTests {
    @get:Rule val isolation = TestIsolationRule()

    private val image = ShareSendRequest.Image(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01), "image/jpeg")

    /** The sheet's prompt is the app's prompt, byte for byte, for the same message. */
    @Test
    fun promptBodyMatchesTheApps() {
        val file = ShareFileReference(name = "crash.pdf", mime = "application/pdf", path = "/tmp/uploads/x/crash.pdf")
        val share = ShareSender.promptBody(messageID = "msg_1", text = "What failed?", images = listOf(image), files = listOf(file))
        val app = Json.parseToJsonElement(
            """{"id":"msg_1","text":"What failed?\nAttached file: `/tmp/uploads/x/crash.pdf`","files":[{"uri":"data:image/jpeg;base64,/9gB"}],"delivery":"steer","metadata":{"displayText":"What failed?","comments":[],"attachments":[{"name":"crash.pdf","mime":"application/pdf","path":"/tmp/uploads/x/crash.pdf"}]}}""",
        )
        assertEquals(app, encoder.encodeToJsonElement(ShareSender.PromptBody.serializer(), share))

        val plainShare = ShareSender.promptBody(messageID = "msg_2", text = "Hi", images = emptyList(), files = emptyList())
        val plainApp = Json.parseToJsonElement("""{"id":"msg_2","text":"Hi","delivery":"steer"}""")
        assertEquals(plainApp, encoder.encodeToJsonElement(ShareSender.PromptBody.serializer(), plainShare))
    }

    @Test
    fun sendsToASessionInOneRequest() = runBlocking {
        val fake = FakeServer(listOf(ok()))
        val sent = sender(fake).send(
            ShareSendRequest(ShareSendRequest.Target.Session("ses_a", "/work"), messageID = "msg_a", text = "Look", images = listOf(image)),
        )
        assertEquals("ses_a", sent)
        assertEquals(listOf("/api/session/ses_a/prompt"), fake.requests.map { it.path })
        val body = json(fake.requests[0].body)
        assertEquals("msg_a", body["id"]?.jsonPrimitive?.content)
        assertEquals("steer", body["delivery"]?.jsonPrimitive?.content)
    }

    @Test
    fun newSessionIsCreatedWithItsOwnIDFirst() = runBlocking {
        val fake = FakeServer(listOf(ok(), ok()))
        val sent = sender(fake).send(ShareSendRequest(ShareSendRequest.Target.NewSession("ses_new", "/work"), messageID = "msg_b", text = "Start here"))
        assertEquals("ses_new", sent)
        assertEquals(listOf("/api/session", "/api/session/ses_new/prompt"), fake.requests.map { it.path })
        val create = json(fake.requests[0].body)
        assertEquals("ses_new", create["id"]?.jsonPrimitive?.content)
        assertEquals("/work", create["location"]?.jsonObject?.get("directory")?.jsonPrimitive?.content)
    }

    @Test
    fun busyAnswersAreRetriedUnderTheSameID() = runBlocking {
        val fake = FakeServer(listOf(status(503), status(502), ok()))
        sender(fake).send(ShareSendRequest(ShareSendRequest.Target.Session("ses_r", null), messageID = "msg_r", text = "Again"))
        assertEquals(3, fake.requests.size)
        assertEquals(setOf("msg_r"), fake.requests.map { json(it.body)["id"]?.jsonPrimitive?.content }.toSet())
    }

    @Test
    fun refusalsAreNotRetried() = runBlocking {
        val fake = FakeServer(listOf(status(401)))
        try {
            sender(fake).send(ShareSendRequest(ShareSendRequest.Target.Session("ses_x", null), messageID = "msg_x", text = "No"))
            fail("Expected a refusal")
        } catch (failure: ShareSendFailure) {
            assertEquals(ShareSendFailure.Rejected(401), failure)
        }
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun anotherAddressTakesOverWhenTheFirstIsUnreachable() = runBlocking {
        val fake = FakeServer(listOf(offline, ok(), ok()))
        sender(fake).send(ShareSendRequest(ShareSendRequest.Target.Session("ses_l", null), messageID = "msg_l", text = "Hi"))
        assertEquals(listOf("studio.tail1234.ts.net", "192.168.1.20", "192.168.1.20"), fake.requests.map { it.host })
        assertEquals(listOf("/api/session/ses_l/prompt", "/api/info", "/api/session/ses_l/prompt"), fake.requests.map { it.path })
    }

    @Test
    fun unreachableEndsWithinTheBudget() = runBlocking {
        var clock = Duration.ZERO
        val fake = FakeServer(List(50) { offline })
        val sender = sender(fake, sleep = { clock += it }, now = { clock })
        try {
            sender.send(ShareSendRequest(ShareSendRequest.Target.Session("ses_u", null), messageID = "msg_u", text = "Hi"))
            fail("Expected unreachable")
        } catch (failure: ShareSendFailure) {
            assertEquals(ShareSendFailure.Unreachable, failure)
        }
        assertTrue("${fake.requests.size}", fake.requests.size < 20)
    }

    @Test
    fun newIDsHaveTheServersShape() {
        assertTrue(Regex("msg_[0-9a-f]{12}[0-9A-Za-z]{14}").matches(ShareSendRequest.newMessageID()))
        assertEquals("ses_ce7a3afffffeabc00000000000", ServerID.newSessionID(at = Instant.ofEpochSecond(1_800_000_000), entropy = "abc"))
    }
}

/** [ShareSenderTests]' file sends, staged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareSenderFrameworkTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun filesUploadIntoTheSessionsFolderThenAreReferenced() = runBlocking {
        val staging = Files.createTempDirectory("composer-files-").toFile()
        try {
            val staged = stagedFile("notes.md", "# Notes", staging)
            val fake = FakeServer(
                listOf(
                    ok("""{"paths":{"tmp":"/srv/tmp"}}"""),
                    { seen -> 200 to """{"data":{"path":"${seen.query["path"] ?: ""}"}}""".toByteArray() },
                    ok(),
                ),
            )
            sender(fake).send(ShareSendRequest(ShareSendRequest.Target.Session("ses_f", "/work"), messageID = "msg_f", text = "Summarize", files = listOf(staged)))
            assertEquals(listOf("/api/info", "/api/experimental/fs/write", "/api/session/ses_f/prompt"), fake.requests.map { it.path })
            val write = fake.requests[1]
            val path = "/srv/tmp/uploads/${staged.id.toString().lowercase()}/notes.md"
            assertEquals(mapOf("location[directory]" to "/work", "path" to path), write.query)
            assertEquals("# Notes", write.body?.let(::String))
            val prompt = json(fake.requests[2].body)
            assertEquals("Summarize\nAttached file: `$path`", prompt["text"]?.jsonPrimitive?.content)
            assertEquals(path, prompt["metadata"]?.jsonObject?.get("attachments")?.jsonArray?.first()?.jsonObject?.get("path")?.jsonPrimitive?.content)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun filesPastTheLimitAreLeftForTheApp() = runBlocking {
        val staging = Files.createTempDirectory("composer-files-").toFile()
        try {
            val big = stagedFile("movie.mov", "x", staging).copy(size = ShareSender.MAXIMUM_FILE_BYTES + 1)
            val fake = FakeServer(emptyList())
            try {
                sender(fake).send(ShareSendRequest(ShareSendRequest.Target.Session("ses_b", "/w"), messageID = "msg_b", text = "", files = listOf(big)))
                fail("Expected too large")
            } catch (failure: ShareSendFailure) {
                assertEquals(ShareSendFailure.TooLarge, failure)
            }
            assertTrue(fake.requests.isEmpty())
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun stagedFile(name: String, contents: String, directory: File): ComposerStagedFile {
        val source = File.createTempFile("${UUID.randomUUID()}-", "-$name")
        try {
            source.writeText(contents)
            return ComposerFileStaging.stage(source, name = name, directory = directory)
        } finally {
            source.delete()
        }
    }
}

// MARK: The sheet's decisions

/**
 * `suggestionsAreOutgoingMessagesToTheSession`: Android's suggestion is a sharing shortcut, not an
 * `INSendMessageIntent`; its ID carries the same conversation.
 */
class ShareCompositionTests {
    @get:Rule val isolation = TestIsolationRule()

    private val key = ShareConversation.serverKey(server.persistenceID)
    private val work = ShareTargets.Project(id = "proj", name = "Work", directory = "/work")
    private val pinned = ShareTargets.Session(id = "ses_pin", title = "Pinned", projectID = "proj", projectName = "Work", directory = "/work")
    private val recent = ShareTargets.Session(id = "ses_recent", title = "Recent", projectID = "proj", projectName = "Work", directory = "/work")

    private val targets: ShareTargets
        get() = ShareTargets(
            serverID = server.persistenceID, serverName = "Studio", lastViewedSessionID = recent.id,
            newSessionProject = work, pinned = listOf(pinned), recents = listOf(recent), projects = listOf(work),
        )

    @Test
    fun aSuggestionOpensOnItsSessionElseTheLastViewed() {
        val named = ShareConversation.identifier(key, pinned.id)
        assertEquals(ShareComposition.Destination.Session(pinned), ShareComposition.initialDestination(targets, conversation = named))
        // A bare session ID without a server key still resolves on the active server.
        assertEquals(ShareComposition.Destination.Session(pinned), ShareComposition.initialDestination(targets, conversation = pinned.id))
        // Another server's session isn't on offer here.
        assertEquals(ShareComposition.Destination.Session(recent), ShareComposition.initialDestination(targets, conversation = "beef01:${pinned.id}"))
        assertEquals(ShareComposition.Destination.Session(recent), ShareComposition.initialDestination(targets, conversation = null))
        val fresh = targets.copy(lastViewedSessionID = null)
        assertEquals(ShareComposition.Destination.NewSession(work), ShareComposition.initialDestination(fresh, conversation = null))
    }

    @Test
    fun requestsNameTheirFolder() {
        val toSession = ShareComposition.request(
            ShareComposition.Destination.Session(recent), messageID = "msg", newSessionID = "ses_n", text = "t", images = emptyList(), files = emptyList(),
        )
        assertEquals(ShareSendRequest.Target.Session(recent.id, "/work"), toSession?.target)
        val toNew = ShareComposition.request(
            ShareComposition.Destination.NewSession(work), messageID = "msg", newSessionID = "ses_n", text = "t", images = emptyList(), files = emptyList(),
        )
        assertEquals(ShareSendRequest.Target.NewSession("ses_n", "/work"), toNew?.target)
        // A project published before folders were: only the app can start it.
        assertNull(
            ShareComposition.request(
                ShareComposition.Destination.NewSession(ShareTargets.Project(id = "p", name = "Old")), messageID = "m", newSessionID = "s", text = "",
                images = emptyList(), files = emptyList(),
            ),
        )
    }

    @Test
    fun problemsSayWhatHappenedAndWhetherToTryAgain() {
        assertEquals(ShareComposition.Problem("Couldn’t reach Studio", canRetry = true), ShareComposition.problem(ShareSendFailure.Unreachable, serverName = "Studio"))
        assertFalse(ShareComposition.problem(ShareSendFailure.Rejected(401), serverName = "Studio").canRetry)
        assertFalse(ShareComposition.problem(ShareSendFailure.TooLarge, serverName = "Studio").canRetry)
        assertEquals("Open OpenCode once to send from here", ShareComposition.problem(null, serverName = "Studio").message)
    }

    @Test
    fun handoffsCarryTheirDeliveryAndOnlyATriedID() {
        val continued = ShareComposition.handoff(
            targets = targets, destination = ShareComposition.Destination.NewSession(work), text = "More to say", imageIDs = listOf(UUID(0, 1)),
            files = emptyList(), delivery = ShareHandoff.Delivery.openInComposer,
        )
        assertEquals(ShareHandoff.Target.NewSession(projectID = "proj"), continued.target)
        assertEquals(ShareHandoff.Delivery.openInComposer, continued.resolvedDelivery)
        assertEquals(listOf("image-0.jpg"), continued.images.map { it.fileName })
        val later = ShareComposition.handoff(
            targets = targets, destination = ShareComposition.Destination.Session(pinned), text = "", imageIDs = emptyList(), files = emptyList(),
            delivery = ShareHandoff.Delivery.send, messageID = "msg_tried",
        )
        assertEquals(ShareHandoff.Target.Session(id = pinned.id, projectID = "proj"), later.target)
        assertEquals("msg_tried", later.messageID)
        assertNull(later.newSessionID)
        val laterNew = ShareComposition.handoff(
            targets = targets, destination = ShareComposition.Destination.NewSession(work), text = "", imageIDs = emptyList(), files = emptyList(),
            delivery = ShareHandoff.Delivery.send, messageID = "msg_tried", newSessionID = "ses_tried",
        )
        assertEquals("ses_tried", laterNew.newSessionID)
    }

    @Test
    fun notificationBodiesSummarizeTheMessage() {
        assertEquals("Why does this fail? · 1 image", ShareComposition.notificationBody(text = "Why does this fail?\n\nhttps://x.dev", images = 1, files = 0))
        assertEquals("3 images · 2 files", ShareComposition.notificationBody(text = "", images = 3, files = 2))
    }

    @Test
    fun conversationsAndNotificationsNameServerAndSession() {
        val id = ShareConversation.identifier(key, "ses_1")
        assertEquals(key, ShareConversation.parse(id)?.serverKey)
        assertEquals("ses_1", ShareConversation.parse(id)?.sessionID)
        assertNotNull(ShareConversation.parse("ses_bare"))
        assertNull(ShareConversation.parse("ses_bare")?.serverKey)
        assertNull(ShareConversation.parse("nonsense"))
        assertEquals(key, SessionDirectory.serverKey(server.persistenceID))
        val payload = ShareConversation.notificationPayload(key, "ses_1")
        assertEquals(key, ShareConversation.destination(payload)?.serverKey)
        assertEquals("ses_1", ShareConversation.destination(payload)?.sessionID)
        assertNull(ShareConversation.destination(mapOf("server" to key, "session" to "../etc")))
        // Continue's link opens the app and routes nowhere: the share does.
        assertNull(SystemAction.fromUrl(ShareConversation.CONTINUE_URL))
    }

    @Test
    fun suggestionsAreOutgoingMessagesToTheSession() {
        val shortcutID = ShareSuggestion.shortcutID(key, "ses_1")
        assertEquals("$key:ses_1", ShareSuggestion.conversation(shortcutID))
    }
}

// MARK: Send motion

class ShareSendMotionTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun aFastSendHoldsTheSpinnerForABeat() {
        assertEquals(150.milliseconds, ShareComposition.SendMotion.remainingSending(30.milliseconds))
        assertEquals(Duration.ZERO, ShareComposition.SendMotion.remainingSending(180.milliseconds))
        assertEquals(Duration.ZERO, ShareComposition.SendMotion.remainingSending(2.seconds))
    }
}
