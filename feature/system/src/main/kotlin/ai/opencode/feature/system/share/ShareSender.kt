package ai.opencode.feature.system.share

import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerID
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * One message from the share sheet: its text, images and files, to a session, or to a new one in a
 * project.
 */
data class ShareSendRequest(
    val target: Target,
    /** Made once per message: every try sends the same ID, so the server admits it at most once. */
    val messageID: String,
    val text: String,
    val images: List<Image> = emptyList(),
    val files: List<ComposerStagedFile> = emptyList(),
) {
    sealed interface Target {
        val sessionID: String
        val directory: String?

        data class Session(val id: String, override val directory: String?) : Target {
            override val sessionID: String get() = id
        }

        /** Created with a client-made ID first (`ServerID.newSessionID`'s shape), so a retried create finds the session it already made. */
        data class NewSession(val id: String, override val directory: String) : Target {
            override val sessionID: String get() = id
        }
    }

    class Image(val data: ByteArray, val mime: String) {
        override fun equals(other: Any?): Boolean = other is Image && other.mime == mime && other.data.contentEquals(data)
        override fun hashCode(): Int = mime.hashCode() * 31 + data.contentHashCode()
    }

    companion object {
        /** A message ID in the server's ascending shape ([ServerID]). */
        fun newMessageID(): String = ServerID.Generator.live.next("msg_")

    }
}

/** Why a send from the share sheet didn't go. */
sealed class ShareSendFailure : Exception() {
    /** No answer, or only answers worth trying again, within the sheet's budget. */
    data object Unreachable : ShareSendFailure()
    /** More file bytes than the sheet uploads ([ShareSender.MAXIMUM_FILE_BYTES]). */
    data object TooLarge : ShareSendFailure()
    /** The server has no upload route. */
    data object UploadsUnsupported : ShareSendFailure()
    /** The server refused (401, or another status not worth repeating). */
    data class Rejected(val status: Int) : ShareSendFailure()
}

/**
 * The share sheet's whole conversation with the server, in the app's wire format: `POST
 * /api/session` for New Session (client-made ID), uploads through `/api/experimental/fs/write`,
 * then `POST /api/session/{id}/prompt` with a client-made message ID.
 *
 * Every request it makes is safe to repeat (a GET, writes to one path, creates and prompts the
 * server dedupes by ID), so it retries connection failures and 408/429/502–504/520–530 on its own
 * short budget: the sheet is in front of the person, who gets Try Again and Send Later once it runs
 * out. It deliberately doesn't use the app's `NetworkRetry`; Send Later hands the message to the
 * app, whose outbox sends it under the full policy.
 */
class ShareSender(
    server: OpenCodeServer,
    private val transport: Transport = liveTransport,
    /** Total time for one send's retries. */
    private val budget: Duration = 20.seconds,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
    private val now: () -> Duration = { System.nanoTime().nanoseconds },
) {
    var server: OpenCodeServer = server
        private set

    sealed interface Body {
        data object None : Body
        class Data(val bytes: ByteArray) : Body
        class FileBody(val file: File) : Body
    }

    class TransportResponse(val status: Int, val headers: Headers, val body: ByteArray)

    /** One request, with its whole-call timeout; throws [IOException] when the server can't be reached. */
    fun interface Transport {
        suspend fun send(request: Request, timeout: Duration): TransportResponse
    }

    /** Sends [request]; returns the session it went to. Throws [ShareSendFailure]. */
    suspend fun send(request: ShareSendRequest): String {
        val total = request.files.sumOf { it.size }
        if (total > MAXIMUM_FILE_BYTES) throw ShareSendFailure.TooLarge
        // Files get time to upload on top of the retry budget.
        val deadline = now() + budget + (if (request.files.isEmpty()) Duration.ZERO else UPLOAD_ALLOWANCE)
        val target = request.target
        if (target is ShareSendRequest.Target.NewSession) createSession(target.id, target.directory, deadline)
        val references = mutableListOf<ShareFileReference>()
        if (request.files.isNotEmpty()) {
            val tmp = temporaryDirectory(deadline)
            for (file in request.files) references.add(upload(file, tmp, target.directory, deadline))
        }
        val body = promptBody(request.messageID, request.text, request.images, references)
        val data = runCatching { WireJson.encodeToString(PromptBody.serializer(), body) }.getOrNull()
            ?: throw ShareSendFailure.Rejected(0)
        perform(
            path = "/api/session/${target.sessionID}/prompt",
            method = "POST",
            body = Body.Data(data.encodeToByteArray()),
            deadline = deadline,
        )
        return target.sessionID
    }

    // Steps

    @Serializable
    private data class CreateBody(val id: String, val location: Location) {
        @Serializable
        data class Location(val directory: String)
    }

    @Serializable
    private data class Info(val paths: Paths? = null) {
        @Serializable
        data class Paths(val tmp: String)
    }

    @Serializable
    private data class Written(val data: Payload) {
        @Serializable
        data class Payload(val path: String)
    }

    private suspend fun createSession(id: String, directory: String, deadline: Duration) {
        val data = WireJson.encodeToString(CreateBody.serializer(), CreateBody(id, CreateBody.Location(directory)))
        perform(path = "/api/session", method = "POST", body = Body.Data(data.encodeToByteArray()), deadline = deadline)
    }

    private suspend fun temporaryDirectory(deadline: Duration): String {
        val data = try {
            perform(path = "/api/info", method = "GET", body = Body.None, deadline = deadline)
        } catch (failure: ShareSendFailure) {
            if (failure == ShareSendFailure.Rejected(404)) throw ShareSendFailure.UploadsUnsupported
            throw failure
        }
        // `paths.tmp` arrived just before the write route; without it, uploads have nowhere to go.
        val tmp = runCatching { WireJson.decodeFromString(Info.serializer(), data.decodeToString()) }.getOrNull()?.paths?.tmp
        if (tmp.isNullOrEmpty()) throw ShareSendFailure.UploadsUnsupported
        return tmp
    }

    private suspend fun upload(file: ComposerStagedFile, tmp: String, directory: String?, deadline: Duration): ShareFileReference {
        // One path for every try: a retry overwrites, never duplicates.
        val base = if (tmp.endsWith("/")) tmp.dropLast(1) else tmp
        val path = "$base/uploads/${file.id.toString().lowercase()}/${AttachmentFileType.sanitizedName(file.name)}"
        val query = (directory?.let { listOf("location[directory]" to it) } ?: emptyList()) + listOf("path" to path)
        val data = try {
            perform(
                path = "/api/experimental/fs/write", query = query, method = "POST", body = Body.FileBody(file.url),
                contentType = "application/octet-stream", timeout = 120.seconds, deadline = deadline,
            )
        } catch (failure: ShareSendFailure) {
            if (failure == ShareSendFailure.Rejected(404)) throw ShareSendFailure.UploadsUnsupported
            throw failure
        }
        val written = runCatching { WireJson.decodeFromString(Written.serializer(), data.decodeToString()) }.getOrNull()?.data?.path ?: path
        return ShareFileReference(file.name, file.mime, written)
    }

    // Requests

    /**
     * One request, retried while it's worth it and the budget lasts. After a connection failure
     * the server's other addresses are tried once, and the one that answers is used from then on.
     */
    private suspend fun perform(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        method: String,
        body: Body,
        contentType: String = "application/json",
        timeout: Duration = ATTEMPT_TIMEOUT,
        deadline: Duration,
    ): ByteArray {
        var attempt = 0
        var triedAlternates = false
        while (true) {
            attempt += 1
            val left = deadline - now()
            if (left <= 500.milliseconds) throw ShareSendFailure.Unreachable
            val url = server.endpoint(path, query) ?: throw ShareSendFailure.Rejected(0)
            val media = contentType.toMediaType()
            val requestBody = when (body) {
                Body.None -> null
                is Body.Data -> body.bytes.toRequestBody(media)
                is Body.FileBody -> body.file.asRequestBody(media)
            }
            val builder = Request.Builder().url(url).method(method, requestBody).header("Accept", "application/json")
            if (body !is Body.None) builder.header("Content-Type", contentType)
            server.authorize(builder)

            var wait: Duration
            try {
                // Never past the budget: a hung try leaves the sheet with Try Again.
                val response = transport.send(builder.build(), minOf(timeout, left))
                val status = response.status
                if (status in 200..299) return response.body
                if (status !in RETRYABLE_STATUSES) throw ShareSendFailure.Rejected(status)
                wait = retryAfter(response.headers) ?: backoff(attempt)
            } catch (failure: ShareSendFailure) {
                throw failure
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isConnectionFailure(error)) throw ShareSendFailure.Unreachable
                if (!triedAlternates && server.candidateURLs.size > 1) {
                    triedAlternates = true
                    val answering = answeringAddress()
                    if (answering != null) {
                        server = server.at(answering)
                        continue
                    }
                }
                wait = backoff(attempt)
            }
            val remaining = deadline - now()
            if (remaining <= wait) throw ShareSendFailure.Unreachable
            sleep(wait)
        }
    }

    /** The first of the server's other addresses to answer `/api/info`. */
    private suspend fun answeringAddress(): String? {
        val current = server
        val candidates = current.candidateURLs.filter { it != current.url }
        if (candidates.isEmpty()) return null
        return coroutineScope {
            val answers = Channel<String?>(candidates.size)
            val probes = candidates.map { candidate ->
                launch {
                    val answer = runCatching {
                        val alternate = current.at(candidate)
                        val url = alternate.endpoint("/api/info") ?: return@runCatching null
                        val request = alternate.authorize(Request.Builder().url(url)).build()
                        val response = transport.send(request, PROBE_TIMEOUT)
                        if (response.status in 200..299) candidate else null
                    }.getOrNull()
                    answers.send(answer)
                }
            }
            repeat(candidates.size) {
                val answer = answers.receive()
                if (answer != null) {
                    probes.forEach { it.cancel() }
                    return@coroutineScope answer
                }
            }
            null
        }
    }

    // Wire format

    /**
     * `OpenCodeClient.promptBody` for what the share sheet sends: the typed text with one reference
     * line per uploaded file, images inline, and the steering delivery the composer sends by
     * default, with absent optionals omitted.
     */
    @Serializable
    data class PromptBody(
        val id: String,
        val text: String,
        val files: List<File>? = null,
        val delivery: String? = null,
        val metadata: Metadata? = null,
    ) {
        @Serializable
        data class File(val uri: String, val name: String? = null)

        @Serializable
        data class Metadata(
            val displayText: String? = null,
            val comments: List<String>? = null,
            val attachments: List<ShareFileReference>? = null,
        )
    }

    companion object {
        /** What the sheet uploads itself; past it, Send Later hands the files to the app. */
        const val MAXIMUM_FILE_BYTES: Long = 25L * 1024 * 1024
        val UPLOAD_ALLOWANCE: Duration = 90.seconds
        /** One try's timeout: short enough that a dead address leaves time to retry or reach another. */
        val ATTEMPT_TIMEOUT: Duration = 8.seconds
        private val PROBE_TIMEOUT: Duration = 4.seconds

        val RETRYABLE_STATUSES: Set<Int> = setOf(408, 429, 502, 503, 504, 520, 521, 522, 523, 524, 525, 526, 527, 530)

        fun backoff(attempt: Int): Duration = minOf(4000, 500 * (1 shl minOf(attempt - 1, 3))).milliseconds

        fun retryAfter(headers: Headers): Duration? {
            val value = headers["Retry-After"]?.trim() ?: return null
            val seconds = value.toDoubleOrNull() ?: return null
            if (seconds < 0 || seconds.isNaN()) return null
            return (seconds * 1000).toLong().milliseconds
        }

        /** A failure to reach the server at all, worth another try or address. */
        fun isConnectionFailure(error: Throwable): Boolean = when (error) {
            is SocketTimeoutException, is UnknownHostException, is ConnectException, is NoRouteToHostException,
            is PortUnreachableException, is SocketException, is SSLException -> true
            // OkHttp's whole-call timeout.
            is InterruptedIOException -> true
            // A connection lost mid-request ("unexpected end of stream", a reset stream). A
            // malformed reply isn't one.
            is java.net.ProtocolException -> false
            is java.io.IOException -> true
            else -> false
        }

        fun promptBody(
            messageID: String,
            text: String,
            images: List<ShareSendRequest.Image>,
            files: List<ShareFileReference>,
        ): PromptBody {
            val typed = if (text.trim().isEmpty()) emptyList() else listOf(text)
            val modelText = (typed + files.map { "Attached file: `${it.path}`" }).joinToString("\n")
            val metadata = if (files.isNotEmpty()) {
                PromptBody.Metadata(
                    displayText = if (files.isEmpty()) null else text,
                    comments = if (files.isEmpty()) null else emptyList(),
                    attachments = files.ifEmpty { null },
                )
            } else {
                null
            }
            return PromptBody(
                id = messageID,
                text = modelText,
                files = images.ifEmpty { null }?.map {
                    PromptBody.File(uri = "data:${it.mime};base64,${Base64.getEncoder().encodeToString(it.data)}")
                },
                delivery = "steer",
                metadata = metadata,
            )
        }

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(0, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        val liveTransport = Transport { request, timeout ->
            val call = client.newCall(request)
            call.timeout().timeout(timeout.inWholeMilliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use { TransportResponse(it.code, it.headers, it.body.bytes()) }
                        }
                        result.fold(
                            onSuccess = { if (continuation.isActive) continuation.resume(it) },
                            onFailure = { if (continuation.isActive) continuation.resumeWithException(it) },
                        )
                    }
                })
            }
        }

        private val WireJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/** `OpenCodeFileReference`'s shape. */
@Serializable
data class ShareFileReference(val name: String, val mime: String, val path: String)
