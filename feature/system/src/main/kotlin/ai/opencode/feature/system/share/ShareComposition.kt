package ai.opencode.feature.system.share

import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.feature.system.R
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The share sheet's decisions, kept apart from its views: which session it opens on, what a send
 * asks the server for, what a failure offers, and what it leaves for the app.
 */
object ShareComposition {
    /** Send → Sent → close, so it reads as one motion and never flashes. */
    object SendMotion {
        /** The spinner shows at least this long, so a fast send never flashes it for a frame. */
        val minimumSending: Duration = 180.milliseconds
        /** From the checkmark to finishing: long enough to see it land. */
        val closeDelay: Duration = 280.milliseconds
        /** The checkmark's pop. */
        const val POP_SCALE = 1.12f

        /** How much longer the spinner stays after a send took [elapsed]. */
        fun remainingSending(elapsed: Duration): Duration = maxOf(Duration.ZERO, minimumSending - elapsed)
    }

    sealed interface Destination {
        data class Session(val session: ShareTargets.Session) : Destination
        data class NewSession(val project: ShareTargets.Project) : Destination

        val title: String
            get() = when (this) {
                is Session -> session.title
                is NewSession -> localized(R.string.system_new_session)
            }

        val projectName: String
            get() = when (this) {
                is Session -> session.projectName
                is NewSession -> project.name
            }
    }

    /**
     * A suggestion's session when it names one this server offers, else the session last
     * viewed, else a New Session in the latest project.
     */
    fun initialDestination(targets: ShareTargets, conversation: String?): Destination? {
        if (conversation != null) {
            val named = ShareConversation.parse(conversation)
            if (named != null &&
                (named.serverKey == null || named.serverKey == ShareConversation.serverKey(targets.serverID))
            ) {
                targets.sessions.firstOrNull { it.id == named.sessionID }?.let { return Destination.Session(it) }
            }
        }
        targets.sessions.firstOrNull { it.id == targets.lastViewedSessionID }?.let { return Destination.Session(it) }
        return targets.newSessionProjects.firstOrNull()?.let { Destination.NewSession(it) }
    }

    /** The message: what was typed, then shared text and links. */
    fun text(message: String, shared: List<String>): String =
        (listOf(message.trim()) + shared).filter { it.isNotEmpty() }.joinToString("\n\n")

    /**
     * What a send asks for, or null when the sheet can't send there itself (a New Session in a
     * project whose folder the app hasn't published).
     */
    fun request(
        destination: Destination,
        messageID: String,
        newSessionID: String,
        text: String,
        images: List<ShareSendRequest.Image>,
        files: List<ComposerStagedFile>,
    ): ShareSendRequest? {
        val target = when (destination) {
            is Destination.Session -> ShareSendRequest.Target.Session(destination.session.id, destination.session.directory)
            is Destination.NewSession -> {
                val directory = destination.project.directory ?: return null
                ShareSendRequest.Target.NewSession(newSessionID, directory)
            }
        }
        return ShareSendRequest(target, messageID, text, images, files)
    }

    /** What the sheet says when a send didn't go, and what it offers. */
    data class Problem(
        val message: String,
        /** Worth sending again from the sheet. */
        val canRetry: Boolean,
    )

    fun problem(failure: ShareSendFailure?, serverName: String): Problem = when (failure) {
        ShareSendFailure.Unreachable -> Problem(localized(R.string.system_share_problem_unreachable, serverName), canRetry = true)
        ShareSendFailure.TooLarge -> Problem(localized(R.string.system_share_problem_too_large), canRetry = false)
        ShareSendFailure.UploadsUnsupported -> Problem(localized(R.string.system_share_problem_uploads_unsupported, serverName), canRetry = false)
        is ShareSendFailure.Rejected ->
            if (failure.status == 401) Problem(localized(R.string.system_share_problem_sign_in, serverName), canRetry = false)
            else Problem(localized(R.string.system_share_problem_send, serverName), canRetry = true)
        // No credentials yet: the app shares them the next time it's opened.
        null -> Problem(localized(R.string.system_share_problem_no_credentials), canRetry = false)
    }

    /**
     * The share as the app takes it: `.send` (Send Later, under the IDs the sheet tried when it
     * may have reached the server) or `.openInComposer`.
     */
    fun handoff(
        id: UUID = UUID.randomUUID(),
        createdAt: Instant = Instant.now(),
        targets: ShareTargets,
        destination: Destination,
        text: String,
        imageIDs: List<UUID>,
        files: List<ComposerStagedFile>,
        delivery: ShareHandoff.Delivery,
        messageID: String? = null,
        newSessionID: String? = null,
    ): ShareHandoff {
        val target = when (destination) {
            is Destination.Session -> ShareHandoff.Target.Session(destination.session.id, destination.session.projectID)
            is Destination.NewSession -> ShareHandoff.Target.NewSession(destination.project.id)
        }
        val startsSession = destination is Destination.NewSession
        return ShareHandoff(
            id = id,
            createdAt = createdAt,
            serverID = targets.serverID,
            target = target,
            text = text,
            images = imageIDs.mapIndexed { index, imageID ->
                ShareHandoff.Image(imageID, "image-$index.jpg", SharedImageEncoder.MIME)
            },
            files = if (files.isEmpty()) null else files.mapIndexed { index, file ->
                ShareHandoff.FileEntry(file.id, "file-$index", file.name, file.mime)
            },
            delivery = delivery,
            messageID = messageID,
            newSessionID = if (startsSession) newSessionID else null,
        )
    }

    /** The "sent" notification's body: the message (or what went), then a count of what came with it. */
    fun notificationBody(text: String, images: Int, files: Int): String {
        val parts = mutableListOf<String>()
        if (images > 0) parts.add(localizedPlural(R.plurals.system_share_image_count, images, images))
        if (files > 0) parts.add(localizedPlural(R.plurals.system_share_file_count, files, files))
        val first = text.split(NEWLINES).firstOrNull { it.isNotEmpty() }?.trim() ?: ""
        val line = clippedCharacters(first, 120)
        return (listOf(line).filter { it.isNotEmpty() } + parts).joinToString(" · ")
    }

    /** Counting grapheme clusters. */
    private fun clippedCharacters(text: String, limit: Int): String {
        val breaks = java.text.BreakIterator.getCharacterInstance().apply { setText(text) }
        val boundaries = ArrayList<Int>()
        while (boundaries.size <= limit) {
            val next = breaks.next()
            if (next == java.text.BreakIterator.DONE) return text
            boundaries += next
        }
        return text.substring(0, boundaries[limit - 2]) + "…"
    }

    private val NEWLINES = Regex("\r\n|[\n\r\u000B\u000C\u0085\u2028\u2029]")
}
