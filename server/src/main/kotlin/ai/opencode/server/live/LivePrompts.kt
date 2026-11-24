package ai.opencode.server.live

import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerIDClient
import ai.opencode.server.model.OpenCodeAgentMention
import ai.opencode.server.model.OpenCodeAutocompleteMentions
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeMention
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePageContext
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePromptPresentation
import ai.opencode.server.model.OpenCodeQuote
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeSkillMention
import ai.opencode.server.model.PromptContext
import ai.opencode.server.model.trimmedWhitespace
import ai.opencode.server.retry.RequestIdempotency
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

internal suspend fun requestSendPrompt(
    server: OpenCodeServer,
    sessionID: String,
    text: String,
    attachments: List<OpenCodeOutgoingAttachment>,
    files: List<OpenCodeFileReference>,
    messageID: String,
    delivery: OpenCodeDelivery,
) = postPrompt(server, sessionID, messageID, text, attachments, files, delivery)

internal suspend fun requestSendDeliveredPrompt(server: OpenCodeServer, sessionID: String, text: String, delivery: OpenCodeDelivery?): String {
    val messageID = PromptContext.messageID.current() ?: ServerIDClient.currentMessageID()
    postPrompt(server, sessionID, messageID, text, delivery = delivery)
    return messageID
}

private suspend fun postPrompt(
    server: OpenCodeServer,
    sessionID: String,
    messageID: String,
    text: String,
    attachments: List<OpenCodeOutgoingAttachment> = emptyList(),
    files: List<OpenCodeFileReference> = emptyList(),
    delivery: OpenCodeDelivery?,
) {
    val body = encodedBody(
        promptBody(
            messageID, text, attachments, files, delivery,
            page = PromptContext.page.current(),
            mentions = PromptContext.mentions.current(),
            quotes = PromptContext.quotes.current(),
        ),
        "message",
    )
    // The server admits a message ID once (first admission wins), so a retry after a lost
    // response can't make a second copy.
    perform(
        "/api/session/$sessionID/prompt", method = "POST", body = body, timeout = 30.seconds,
        idempotency = RequestIdempotency.Idempotent, server = server,
    )
}

/** The prompt admission body (see [PromptBody]). */
internal fun promptBody(
    messageID: String,
    text: String,
    attachments: List<OpenCodeOutgoingAttachment>,
    files: List<OpenCodeFileReference>,
    delivery: OpenCodeDelivery?,
    page: OpenCodePageContext? = null,
    mentions: OpenCodeAutocompleteMentions? = null,
    quotes: List<OpenCodeQuote> = emptyList(),
): PromptBody {
    val bounded = page?.bounded
    val attachmentFiles = attachments.map { PromptBody.File(uri = it.promptURI, name = it.name) }
    val mentionFiles = (mentions?.files ?: emptyList()).map { PromptBody.File(it.uri, it.name, it.description, it.mention) }
    val combinedFiles = attachmentFiles + mentionFiles
    return PromptBody(
        id = messageID,
        text = OpenCodePromptPresentation.modelText(text, files, bounded, quotes),
        files = combinedFiles.ifEmpty { null },
        agents = mentions?.agents?.ifEmpty { null },
        skills = mentions?.skills?.ifEmpty { null },
        delivery = delivery?.rawValue,
        metadata = PromptBody.Metadata.of(text, files, bounded, quotes),
    )
}

/**
 * The prompt admission body. Absent files, delivery, and metadata are omitted. Path
 * attachments follow desktop's wire format: reference lines in `text`, with `displayText`,
 * `comments`, and `attachments` metadata that lets every client render chips.
 */
@Serializable
internal data class PromptBody(
    val id: String,
    val text: String,
    val files: List<File>? = null,
    val agents: List<OpenCodeAgentMention>? = null,
    val skills: List<OpenCodeSkillMention>? = null,
    val delivery: String? = null,
    val metadata: Metadata? = null,
) {
    @Serializable
    data class File(val uri: String, val name: String? = null, val description: String? = null, val mention: OpenCodeMention? = null)

    @Serializable
    data class Metadata(
        val displayText: String? = null,
        /** Desktop only reads `displayText` when `comments` is an array. */
        val comments: List<Comment>? = null,
        val attachments: List<OpenCodeFileReference>? = null,
        val page: Page? = null,
    ) {
        @Serializable data class Comment(val type: String, val text: String, val comment: String? = null)

        /** The page a message was sent from; its visible text travels in `text` as a `<page>` block. */
        @Serializable data class Page(val url: String, val title: String, val selection: String? = null, val scroll: Double? = null)

        companion object {
            /** Null when there is nothing to say, so plain prompts carry no metadata. */
            fun of(
                text: String,
                files: List<OpenCodeFileReference>,
                page: OpenCodePageContext?,
                quotes: List<OpenCodeQuote> = emptyList(),
            ): Metadata? {
                if (files.isEmpty() && page == null && quotes.isEmpty()) return null
                val describes = files.isNotEmpty() || page != null || quotes.isNotEmpty()
                val comments = ArrayList<Comment>()
                for (quote in quotes) comments += Comment("quote", quote.text, quote.comment?.trim()?.ifEmpty { null })
                return Metadata(
                    displayText = if (describes) text else null,
                    comments = if (describes) comments else null,
                    attachments = files.ifEmpty { null },
                    page = page?.let { Page(it.url, it.title, it.selection, it.scroll) },
                )
            }
        }
    }
}

internal suspend fun requestPendingInputs(server: OpenCodeServer, sessionID: String): List<OpenCodePendingInput> =
    pendingInputRows(request<PendingInputsPayload>("/api/session/$sessionID/inbox", server = server))

@Serializable private data class DeliveryBody(val delivery: String)

internal suspend fun requestSetPendingInputDelivery(server: OpenCodeServer, sessionID: String, inputID: String, delivery: OpenCodeDelivery) {
    // Not repeatable: V2 changes the row only from the other delivery, so a change that landed
    // answers a repeat with 409. Resent only when it provably never left; after an ambiguous
    // failure the inbox says whether it took.
    try {
        perform(
            "/api/session/$sessionID/inbox/$inputID",
            method = "PATCH",
            body = encodedBody(DeliveryBody(delivery.rawValue)),
            idempotency = RequestIdempotency.Unsafe,
            server = server,
        )
    } catch (error: OpenCodeClientError) {
        if (!(error.mayHaveReachedServer || error.statusCode == 409)) throw error
        val inbox = try { requestPendingInputs(server, sessionID) } catch (_: OpenCodeClientError) { throw error }
        // Pending with the delivery asked for: the change took. Anything else stays a failure.
        if (inbox.none { it.id == inputID && it.delivery == delivery }) throw error
    }
}

internal suspend fun requestCancelPendingInput(server: OpenCodeServer, sessionID: String, inputID: String) {
    // V2 answers 204 for an item that's no longer pending, so a repeat can't fail or act twice.
    perform("/api/session/$sessionID/inbox/$inputID", method = "DELETE", idempotency = RequestIdempotency.Idempotent, server = server)
}

internal fun pendingInputRows(payload: PendingInputsPayload): List<OpenCodePendingInput> = payload.data.mapNotNull { item ->
    if (item.type == "move") return@mapNotNull null
    val source = item.payload
    if (item.type == "synthetic") {
        val notice = syntheticNotice(item.id, source?.description, source?.metadata) ?: return@mapNotNull null
        return@mapNotNull OpenCodePendingInput(id = item.id, text = "", notice = notice)
    }
    val files = OpenCodePromptPresentation.files(source?.metadata)
    OpenCodePendingInput(
        id = item.id,
        text = OpenCodePromptPresentation.displayText(source?.text ?: "", files, source?.metadata).trimmedWhitespace(),
        isCompaction = item.type == "compaction",
        delivery = item.delivery?.let(OpenCodeDelivery::of) ?: OpenCodeDelivery.Steer,
        files = files,
        quotes = OpenCodePromptPresentation.quotes(source?.metadata),
        source = OpenCodePromptSource.of(source?.text, source?.metadata),
        inlineFiles = FilePayload.attachedFiles(source?.files ?: emptyList(), item.id),
        images = FilePayload.attachedImages(source?.files ?: emptyList(), item.id),
    )
}
