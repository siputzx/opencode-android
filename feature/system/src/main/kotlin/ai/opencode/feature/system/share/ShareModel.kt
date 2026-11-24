package ai.opencode.feature.system.share

import ai.opencode.core.files.ComposerFileStaging
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.strings.localized
import ai.opencode.feature.system.notifications.PushSync
import ai.opencode.feature.system.R
import ai.opencode.server.ServerID
import ai.opencode.server.OpenCodeServer
import android.content.Context
import android.graphics.Bitmap
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Port of the share target's `@Observable ShareModel`. A plain object holding one immutable [State]
 * that [ShareView] renders, with methods for what the sheet does. Runs on the main thread.
 */
class ShareModel(
    targets: ShareTargets?,
    credentials: ShareCredentials?,
    private val store: ShareHandoffStore?,
    conversation: String? = null,
    private val sender: (OpenCodeServer) -> ShareSender = { ShareSender(it) },
    private val haptics: HapticsClient,
) {
    /** One shared image: the re-encoded JPEG that goes, and its preview. */
    class SharedImage(val id: UUID, val jpeg: ByteArray, val preview: Bitmap)

    sealed interface Phase {
        data object Composing : Phase
        data object Sending : Phase
        data object Sent : Phase
        /** Didn't go: the problem, with Try Again (when worth it) and Send Later. */
        data class Failed(val problem: ShareComposition.Problem) : Phase
        /** Saved for the app; opening it is under way. */
        data object Continuing : Phase
        /** Saved for the app, but it couldn't be opened from here. */
        data object ContinueManually : Phase
    }

    data class State(
        val targets: ShareTargets?,
        val hasStore: Boolean,
        val images: List<SharedImage> = emptyList(),
        val files: List<ComposerStagedFile> = emptyList(),
        /** Shared text and links, in the order they were shared. */
        val sharedText: List<String> = emptyList(),
        val message: String = "",
        val destination: ShareComposition.Destination? = null,
        val phase: Phase = Phase.Composing,
        val isLoading: Boolean = true,
        val skippedImageCount: Int = 0,
        val skippedFileCount: Int = 0,
        val saveError: String? = null,
    ) {
        val serverName: String get() = targets?.serverName ?: localized(R.string.system_share_your_server)

        val composedText: String get() = ShareComposition.text(message, sharedText)

        val hasContent: Boolean get() = images.isNotEmpty() || files.isNotEmpty() || composedText.isNotEmpty()

        val isBusy: Boolean get() = phase == Phase.Sending || phase == Phase.Sent || phase == Phase.Continuing

        /** Saved for the app: only Done (or Cancel) is left. */
        private val isSaved: Boolean get() = phase == Phase.ContinueManually

        val canSend: Boolean get() = !isLoading && hasContent && destination != null && !isBusy && !isSaved

        val canContinue: Boolean get() = !isLoading && destination != null && hasStore && !isBusy && !isSaved

        /** Chips: New Session, then pinned and recent sessions. */
        val sessions: List<ShareTargets.Session> get() = targets?.sessions ?: emptyList()
        val projects: List<ShareTargets.Project> get() = targets?.newSessionProjects ?: emptyList()
    }

    /** What a successful send went to. */
    data class Sent(val serverKey: String, val sessionID: String, val title: String)

    // Credentials for another server than the sessions offered never send.
    private val credentials: ShareCredentials? = credentials?.takeIf { it.serverID == targets?.serverID }

    private val mutableState = MutableStateFlow(
        State(
            targets = targets,
            hasStore = store != null,
            destination = targets?.let { ShareComposition.initialDestination(it, conversation) },
        ),
    )
    val state: StateFlow<State> = mutableState.asStateFlow()
    val current: State get() = mutableState.value

    /**
     * One ID per message and destination: Try Again sends it again under the same ones;
     * choosing another session makes new ones.
     */
    private var messageID = ShareSendRequest.newMessageID()
    private var newSessionID = ServerID.newSessionID()

    /** Set once a send may have reached the server, so Send Later's send can't land twice. */
    private var mayHaveReachedServer = false

    /** Edits are ignored while it sends (the field keeps the keyboard). */
    fun setMessage(text: String) {
        if (current.isBusy) return
        mutableState.update { it.copy(message = text) }
    }

    fun dismissSaveError() = mutableState.update { it.copy(saveError = null) }

    fun choose(destination: ShareComposition.Destination) {
        if (current.isBusy || destination == current.destination) return
        // What a failed try may have left on the server belongs to the old destination; this
        // one gets its own IDs.
        messageID = ShareSendRequest.newMessageID()
        newSessionID = ServerID.newSessionID()
        mayHaveReachedServer = false
        mutableState.update {
            it.copy(destination = destination, phase = if (it.phase is Phase.Failed) Phase.Composing else it.phase)
        }
    }

    suspend fun load(context: Context, providers: List<SharedItemLoader.Provider>) {
        try {
            for (provider in providers) {
                val state = current
                when (
                    val item = SharedItemLoader.load(
                        context,
                        provider,
                        canAddImage = state.images.size < ShareHandoff.MAXIMUM_IMAGES,
                        canAddFile = state.files.size < ShareHandoff.MAXIMUM_FILES,
                    )
                ) {
                    is SharedItemLoader.LoadedItem.Image -> {
                        val image = item.image
                        if (image != null) {
                            mutableState.update { it.copy(images = it.images + SharedImage(UUID.randomUUID(), image.jpeg, image.preview)) }
                        } else {
                            mutableState.update { it.copy(skippedImageCount = it.skippedImageCount + 1) }
                        }
                    }
                    is SharedItemLoader.LoadedItem.File -> {
                        val file = item.file
                        if (file != null) {
                            mutableState.update { it.copy(files = it.files + file) }
                        } else {
                            mutableState.update { it.copy(skippedFileCount = it.skippedFileCount + 1) }
                        }
                    }
                    is SharedItemLoader.LoadedItem.Text -> item.text?.let(::appendText)
                    null -> {}
                }
            }
        } finally {
            mutableState.update { it.copy(isLoading = false) }
        }
    }

    private fun appendText(text: String) {
        val trimmed = text.trim()
        // Browsers share a page as both its link and the same text.
        if (trimmed.isEmpty() || trimmed in current.sharedText) return
        mutableState.update { it.copy(sharedText = it.sharedText + trimmed) }
    }

    /** Removes this activity's temporary copies; a handoff has its own. */
    fun discardFiles() {
        for (file in current.files) ComposerFileStaging.discard(file.url)
    }

    // Send

    /** Sends from the sheet; on success, returns the session it went to. */
    suspend fun send(): Sent? {
        val state = current
        if (!state.canSend) return null
        val targets = state.targets ?: return null
        val destination = state.destination ?: return null
        mutableState.update { it.copy(phase = Phase.Sending) }
        val text = state.composedText
        val credentials = credentials
        val request = credentials?.let {
            ShareComposition.request(
                destination = destination,
                messageID = messageID,
                newSessionID = newSessionID,
                text = text,
                images = state.images.map { image -> ShareSendRequest.Image(image.jpeg, SharedImageEncoder.MIME) },
                files = state.files,
            )
        }
        if (credentials == null || request == null) {
            mutableState.update { it.copy(phase = Phase.Failed(ShareComposition.problem(null, it.serverName))) }
            return null
        }
        val started = System.nanoTime()
        return try {
            val sessionID = sender(credentials.server).send(request)
            PushSync.sentFromPhone(credentials.server, sessionID)
            // A fast send still shows the spinner for a beat before the checkmark.
            val elapsed: Duration = (System.nanoTime() - started).nanoseconds
            val remaining = ShareComposition.SendMotion.remainingSending(elapsed)
            if (remaining > Duration.ZERO) delay(remaining)
            mutableState.update { it.copy(phase = Phase.Sent) }
            haptics.play(Haptic.success)
            val title = if (destination is ShareComposition.Destination.Session) destination.title
            else localized(R.string.system_share_new_session_in_project, destination.projectName)
            Sent(ShareConversation.serverKey(targets.serverID), sessionID, title)
        } catch (failure: ShareSendFailure) {
            // Anything but a size refusal sent something: Send Later reuses the IDs, which the
            // server dedupes, so nothing lands twice.
            if (failure != ShareSendFailure.TooLarge) mayHaveReachedServer = true
            mutableState.update { it.copy(phase = Phase.Failed(ShareComposition.problem(failure, it.serverName))) }
            null
        }
    }

    // Hand to the app

    /** Send Later: the app sends it on its next return to the foreground. */
    fun sendLater(): Boolean = save(ShareHandoff.Delivery.send)

    /** Continue in OpenCode: the session's draft, opened in the app. */
    fun saveToContinue(): Boolean {
        if (!current.canContinue || !save(ShareHandoff.Delivery.openInComposer)) return false
        mutableState.update { it.copy(phase = Phase.Continuing) }
        return true
    }

    /** Continue in OpenCode couldn't open the app: only Done is left. */
    fun continueManually() = mutableState.update { it.copy(phase = Phase.ContinueManually) }

    private fun save(delivery: ShareHandoff.Delivery): Boolean {
        val state = current
        val targets = state.targets ?: return false
        val store = store ?: return false
        val destination = state.destination ?: return false
        val tried = delivery == ShareHandoff.Delivery.send && mayHaveReachedServer
        val handoff = ShareComposition.handoff(
            targets = targets,
            destination = destination,
            text = state.composedText,
            imageIDs = state.images.map { it.id },
            files = state.files,
            delivery = delivery,
            messageID = if (tried) messageID else null,
            newSessionID = if (tried) newSessionID else null,
        )
        return try {
            store.save(handoff, state.images.map { it.jpeg }, state.files.map { it.url })
            discardFiles()
            true
        } catch (_: IOException) {
            mutableState.update {
                it.copy(saveError = localized(R.string.system_share_save_failed_message))
            }
            false
        }
    }
}
