package ai.opencode.feature.composer

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.feature.voice.AudioSessionGeneration
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.resume
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * On-device dictation through Android's `SpeechRecognizer` (the on-device recognizer when the
 * device has one, else the default service preferring offline), fed the app's own microphone audio
 * so the mic goes live at the touch and streams live partial results.
 */
class DictationClient(
    /**
     * Readies the speech model so the first mic press doesn't stall behind it. Safe to call
     * repeatedly. Never installs anything: on Android installing the on-device language raises a
     * system download dialog, so that waits for the first press that needs it.
     */
    val prepare: suspend () -> Unit = {},
    /**
     * A new press: starts its microphone capture at once, from the touch itself.
     * Returns the press's token, which [start], [stop] and [cancel] name, so a
     * late call for one press never reaches another.
     */
    val begin: () -> Int = { 0 },
    /**
     * Adopts `press`'s recording and streams the transcript so far: finalized
     * text plus the current volatile tail. Each element replaces the previous.
     * Cancelling the channel (its reader) stops capture, like [stop].
     */
    val start: suspend (press: Int) -> ReceiveChannel<String> = { Channel<String>().also { it.close() } },
    /** Stops `press`'s capture and finalizes: the last transcript arrives, then the channel closes. */
    val stop: suspend (press: Int) -> Unit = {},
    /** Discards `press`: stops capture at once (or keeps it from starting) and drops what wasn't transcribed. */
    val cancel: suspend (press: Int) -> Unit = {},
    /** How `press` ends, said before its [stop]: it decides how long the audio stays ready afterwards. */
    val ends: (press: Int, end: DictationEnd) -> Unit = { _, _ -> },
    /** The composer moved on: a held session ends now if `reason` applies to it. Returns once that's done. */
    val releaseHold: suspend (reason: DictationHoldRelease) -> Unit = {},
    /** Whether dictation is recording, finalizing, or holding the audio: a call then starts only once `releaseHold(Call)` returns. */
    val holdsAudio: () -> Boolean = { false },
) {
    companion object Key : DependencyKey<DictationClient> {
        override val liveValue: DictationClient by lazy {
            val context = dependency(AppContext)
            val audio = dependency(DictationAudio)
            val permission = dependency(DictationPermissionClient)
            val live = LiveDictation(DictationEngine.live(context, audio, permission))
            DictationClient(
                prepare = { live.prepare() },
                begin = { live.begin() },
                start = { live.start(it) },
                stop = { live.stop(it) },
                cancel = { live.cancel(it) },
                ends = { press, end -> live.ends(press, end) },
                releaseHold = { audio.endHoldAndWait(it) },
                holdsAudio = { audio.isInUse },
            )
        }
        override val testValue: DictationClient = DictationClient()
    }
}

/**
 * Asks for `RECORD_AUDIO`. The screen hosting the composer registers how
 * (an `ActivityResultLauncher` it owns) while it's shown; the engine asks
 * through it on a press without the permission. With nothing registered the
 * answer is no. Reducers never touch it.
 */
class DictationPermissionClient {
    private val lock = Any()
    private var requester: (suspend () -> Boolean)? = null

    /** Installs [requester] (it must ask the system and return the grant); returns its removal. */
    fun register(requester: suspend () -> Boolean): () -> Unit {
        synchronized(lock) { this.requester = requester }
        return { synchronized(lock) { if (this.requester === requester) this.requester = null } }
    }

    suspend fun request(): Boolean {
        val requester = synchronized(lock) { requester } ?: return false
        return withContext(Dispatchers.Main) { requester() }
    }

    companion object Key : DependencyKey<DictationPermissionClient> {
        override val liveValue: DictationPermissionClient by lazy { DictationPermissionClient() }
        override val testValue: DictationPermissionClient get() = DictationPermissionClient()
    }
}

/**
 * The microphone's live level while dictating, for the recording button's halo. Kept outside the
 * store, like `VoiceAudioLevels`, so per-buffer updates never become actions. A plain meter.
 */
object DictationLevels {
    @Volatile
    private var value = 0f

    /** 0 (silence) to 1 (loud speech). */
    val level: Float get() = value

    fun update(samples: ShortArray) {
        if (samples.isEmpty()) return
        var sumSquares = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }
        // The same −48…−12 dB speech window as the voice call's meter.
        val db = 20 * log10(sqrt(sumSquares / samples.size) + 1e-6)
        set(((db + 48) / 36).toFloat())
    }

    fun set(level: Float) {
        value = level.coerceIn(0f, 1f)
    }
}

/**
 * How long a press takes to become a hot mic and then words: milliseconds from the touch landing on
 * the mic to each step, recorded once per press. The session and mic steps also go to [AppLog].
 */
object DictationLatency {
    private class Press(val touchDownNanos: Long) {
        val marks = HashMap<String, Int>()
    }

    private val lock = Any()
    private var press: Press? = null

    /** The steps also written to `AppLog`: the session up, and the mic live. */
    private val logged = setOf("session_active", "mic_live")

    /** The finger landed on the mic: a new press's clock starts. */
    fun touchDownAt(nanos: Long = System.nanoTime()) {
        synchronized(lock) { press = Press(nanos) }
    }

    /**
     * The finger landed at [uptimeMillis] (a pointer event's `uptimeMillis`,
     * `SystemClock.uptimeMillis`'s clock): the press counts from the touch
     * itself, including any wait before the app saw it.
     */
    fun touchDown(uptimeMillis: Long) {
        val age = SystemClock.uptimeMillis() - uptimeMillis
        touchDownAt(System.nanoTime() - (if (age in 0..5_000) age else 0) * 1_000_000)
    }

    /** [step] happened now. Only its first time in a press counts; with no press the clock starts here. */
    fun mark(step: String) {
        val elapsed = synchronized(lock) {
            val current = press ?: Press(System.nanoTime()).also { press = it }
            if (current.marks.containsKey(step)) return@synchronized null
            val milliseconds = ((System.nanoTime() - current.touchDownNanos) / 1_000_000).toInt()
            current.marks[step] = milliseconds
            milliseconds
        } ?: return
        // In problem reports: a slow session or mic start outlives the press.
        if (step in logged) AppLog.voice.notice("Dictation $step $elapsed ms after the touch")
    }

    /** The current press's steps. */
    val marks: Map<String, Int> get() = synchronized(lock) { press?.marks?.toMap() ?: emptyMap() }
}

sealed class DictationError(val errorDescription: String) : Exception(errorDescription) {
    class MicrophoneDenied : DictationError(localized(R.string.composer_dictation_microphone_denied))
    class LocaleUnsupported : DictationError(localized(R.string.composer_dictation_locale_unsupported))
    class AudioFormatUnavailable : DictationError(localized(R.string.composer_dictation_audio_unavailable))

    /** The recognizer failed. */
    class Recognizer(val code: Int) : DictationError(localized(R.string.composer_dictation_recognizer_failed, code))
}

/** A warm-up load found the on-device language not installed: the first press installs it. */
class DictationModelNotInstalled : Exception("The dictation language isn’t installed yet")

/**
 * What [LiveDictation] drives: the shared audio, the microphone and the speech
 * model. Behind a seam so the ordering of presses, stops and cancels is
 * testable without either.
 */
class DictationEngine(
    /** Already granted: the press starts without the async permission hop. */
    val isPermissionGranted: () -> Boolean = { false },
    val requestPermission: suspend () -> Boolean,
    /** Takes the shared audio for a recording; returns the owner token it holds until a call or another press claims it. */
    val claimSession: () -> Int,
    /** Who owns the session now. */
    val sessionOwner: () -> Int,
    /** Activates the session and starts the mic, feeding `input`. `onEvent` hears what ends a recording from outside. */
    val startCapture: (input: DictationInput, onEvent: (DictationCaptureEvent) -> Unit) -> DictationCapture,
    /**
     * Lets go of the session after a recording that ended as `end`, given the
     * owner token its press claimed.
     */
    val releaseSession: (end: DictationEnd, owner: Int) -> Unit,
    /**
     * A recognizer with its language ready. With `install` false (warming up
     * between presses) a language not yet on the device fails the load instead
     * of downloading it; a press loads with `install` true.
     */
    val loadModel: suspend (install: Boolean) -> DictationModel,
) {
    companion object {
        fun live(context: Context, audio: DictationAudio, permission: DictationPermissionClient): DictationEngine {
            val speech = SpeechSetup(context.applicationContext)
            return DictationEngine(
                isPermissionGranted = {
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                },
                requestPermission = { permission.request() },
                claimSession = {
                    DictationAudioSession.claim()
                    // A held session is taken as it is.
                    audio.cancelHold()
                    AudioSessionGeneration.value
                },
                sessionOwner = { AudioSessionGeneration.value },
                startCapture = { input, onEvent -> LiveCapture.start(context, audio, input, onEvent) },
                releaseSession = { end, owner -> audio.release(end, owner) },
                loadModel = { install -> speech.loadedModel(install).model() },
            )
        }
    }
}

enum class DictationCaptureEvent {
    /** A phone call, a voice call taking the mic, or a route change stalled or changed it. */
    Interrupted,
}

/** 16-bit signed integer PCM, interleaved. */
data class DictationAudioFormat(val sampleRate: Int, val channelCount: Int)

/** One read of microphone audio. */
class DictationBuffer(val samples: ShortArray, val format: DictationAudioFormat) {
    val frameLength: Int get() = if (format.channelCount > 0) samples.size / format.channelCount else 0
}

class DictationCapture(
    /** The microphone's format. */
    val format: DictationAudioFormat,
    /** Stops the mic. Safe to call more than once. */
    val stop: () -> Unit,
)

data class DictationResult(val text: String, val isFinal: Boolean)

/** One loaded speech model, used for one recording. */
class DictationModel(
    /** Starts transcribing `input`, heard in `format`. */
    val start: suspend (input: DictationInput, format: DictationAudioFormat) -> ReceiveChannel<DictationResult>,
    /** Transcribes to the end of the input, then ends the results. */
    val finish: suspend () -> Unit,
    /** Ends at once, transcribing nothing more. */
    val cancel: suspend () -> Unit,
    /** The default recognizer is trying to recover this press's failed language install. */
    val isInstallFallback: Boolean = false,
)

/** Where [DictationInput] hands converted audio. */
interface DictationInputSink {
    fun yield(samples: ShortArray)
    fun finish()
}

/**
 * Microphone audio on its way to the recognizer. The mic starts before the
 * model is ready, so buffers wait here (bounded) until [connect]; from then on
 * each is converted and handed straight on. Used from the capture thread and
 * the dictation dispatcher.
 */
class DictationInput {
    private val lock = Any()
    private val pending = ArrayList<DictationBuffer>()
    private var pendingFrames = 0L
    private var converter: BufferConverter? = null
    private var sink: DictationInputSink? = null
    private var ended = false
    private var discarded = false

    fun append(buffer: DictationBuffer) = synchronized(lock) {
        if (ended) return@synchronized
        val converter = converter
        val sink = sink
        if (converter != null && sink != null) {
            converter.convert(buffer)?.let(sink::yield)
        } else if (pendingFrames < MAX_PENDING_FRAMES) {
            pending += buffer
            pendingFrames += buffer.frameLength
        }
    }

    /** Hands over the audio heard so far, then everything that follows. */
    fun connect(converter: BufferConverter, sink: DictationInputSink) = synchronized(lock) {
        if (discarded) return@synchronized sink.finish()
        for (buffer in pending) converter.convert(buffer)?.let(sink::yield)
        pending.clear()
        pendingFrames = 0
        this.converter = converter
        this.sink = sink
        if (ended) sink.finish()
    }

    /** The mic stopped: once connected, the recognizer's input ends. */
    fun end() = synchronized(lock) {
        ended = true
        sink?.finish()
    }

    /** Cancelled: nothing heard so far, or from now on, reaches the recognizer. */
    fun discard() = synchronized(lock) {
        pending.clear()
        pendingFrames = 0
        discarded = true
        ended = true
        sink?.finish()
    }

    val isDiscarded: Boolean get() = synchronized(lock) { discarded }

    private companion object {
        /** About two minutes at 48 kHz bounds what waits for a model that never loads. */
        const val MAX_PENDING_FRAMES = 48_000L * 120
    }
}

/**
 * Converts microphone buffers to the recognizer's format (16 kHz mono 16-bit
 * PCM): downmixes channels and resamples linearly, keeping its phase across
 * buffers. Only the capture thread (and [DictationInput.connect]) touch it.
 */
class BufferConverter(private val source: DictationAudioFormat, target: DictationAudioFormat) {
    private val target: DictationAudioFormat =
        if (target.channelCount == 1 && target.sampleRate > 0) target else DictationAudioFormat(if (target.sampleRate > 0) target.sampleRate else 16_000, 1)
    private val hasValidFormats = source.channelCount > 0 && source.sampleRate > 0 && this.target.sampleRate > 0
    private val step = if (hasValidFormats) source.sampleRate.toDouble() / this.target.sampleRate else 1.0

    /** Position in the extended signal (index 0 is the previous buffer's last sample). */
    private var position = 1.0
    private var previous = 0.0

    fun convert(buffer: DictationBuffer): ShortArray? {
        // A route change can swap the recorder's format: never pass a mismatched buffer on unconverted.
        if (!hasValidFormats || buffer.format != source || buffer.frameLength <= 0) return null
        if (source == target) return buffer.samples
        val channels = source.channelCount
        val frames = buffer.frameLength
        val mono = DoubleArray(frames) { frame ->
            var sum = 0.0
            for (channel in 0 until channels) sum += buffer.samples[frame * channels + channel]
            sum / channels
        }
        if (source.sampleRate == target.sampleRate) return ShortArray(frames) { mono[it].toInt().toShort() }
        val out = ShortArray((frames / step).toInt() + 2)
        var count = 0
        var pos = position
        while (pos < frames && count < out.size) {
            val index = pos.toInt()
            val fraction = pos - index
            val before = if (index == 0) previous else mono[index - 1]
            val after = mono[index]
            out[count++] = (before + (after - before) * fraction).toInt().coerceIn(-32768, 32767).toShort()
            pos += step
        }
        position = pos - frames
        previous = mono[frames - 1]
        return if (count > 0) out.copyOf(count) else null
    }
}

/**
 * The recognizer's language, install and kind (on-device or not), resolved once.
 * Every recognizer call runs on the main thread, as `SpeechRecognizer` requires.
 */
private class SpeechSetup(private val context: Context) {
    private val mutex = Mutex()

    private data class Language(val tag: String, val onDevice: Boolean, val isInstallFallback: Boolean)
    private data class SpeechSupport(
        val installedOnDeviceLanguages: List<String>,
        val pendingOnDeviceLanguages: List<String>,
        val supportedOnDeviceLanguages: List<String>,
        val onlineLanguages: List<String>,
    )
    private var resolved: Language? = null

    /**
     * A new recognizer with its language ready, for one recording. Without
     * [install], an on-device language that isn't installed fails the load
     * with [DictationModelNotInstalled] and nothing is resolved yet.
     */
    suspend fun loadedModel(install: Boolean): LoadedSpeech {
        val (language, onDevice, isInstallFallback) = setup(install)
        return withContext(Dispatchers.Main) {
            val recognizer = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            LoadedSpeech(recognizer, language, onDevice, isInstallFallback)
        }
    }

    /**
     * One shared resolution and install: every load joins the one under way. A
     * failure isn't kept, so a later attempt retries.
     */
    private suspend fun setup(install: Boolean): Language = mutex.withLock {
        resolved?.let { return it }
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            withContext(Dispatchers.Main) { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
        val available = withContext(Dispatchers.Main) { SpeechRecognizer.isRecognitionAvailable(context) }
        if (!onDevice && !available) throw DictationError.LocaleUnsupported()
        val support = withTimeoutOrNull(INSTALL_WAIT) { support(onDevice) }
        val supported = when {
            support == null -> null
            onDevice -> support.installedOnDeviceLanguages + support.pendingOnDeviceLanguages + support.supportedOnDeviceLanguages
            else -> support.onlineLanguages + support.installedOnDeviceLanguages
        }
        val language = bestLocale(supported)
        val needsInstall = onDevice && support != null && language !in support.installedOnDeviceLanguages
        // Warming up never downloads: `triggerModelDownload` shows a system
        // dialog, which belongs to the press that needs the language.
        if (needsInstall && !install) throw DictationModelNotInstalled()
        val installed = !needsInstall || withTimeoutOrNull(INSTALL_WAIT) {
            install(language) ?: awaitInstalled(language)
        } == true
        if (!installed && !available) throw DictationError.LocaleUnsupported()
        val result = Language(language, onDevice = installed && onDevice, isInstallFallback = !installed)
        resolved = result
        result
    }

    /** What the recognizer supports, or null when it can't say (dictation then tries the current language). */
    private suspend fun support(onDevice: Boolean): SpeechSupport? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val support = supportApi33(onDevice) ?: return null
        return SpeechSupport(
            installedOnDeviceLanguages = support.installedOnDeviceLanguages,
            pendingOnDeviceLanguages = support.pendingOnDeviceLanguages,
            supportedOnDeviceLanguages = support.supportedOnDeviceLanguages,
            onlineLanguages = support.onlineLanguages,
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun supportApi33(onDevice: Boolean): RecognitionSupport? = withContext(Dispatchers.Main) {
        val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        try {
            suspendCancellableCoroutine { continuation ->
                recognizer.checkRecognitionSupport(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                    mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            if (continuation.isActive) continuation.resume(recognitionSupport)
                        }

                        override fun onError(error: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        } finally {
            recognizer.destroy()
        }
    }

    /** Downloads the on-device model for [language]: true installed, null scheduled, false when it won't be there. */
    private suspend fun install(language: String): Boolean? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext false
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 13 has no download listener: request it and let awaitInstalled poll.
                recognizer.triggerModelDownload(intent)
                null
            } else {
                awaitModelDownload { listener -> recognizer.triggerModelDownload(intent, mainExecutor, listener) }
            }
        } finally {
            recognizer.destroy()
        }
    }

    /**
     * A scheduled download of [language]'s model (the system asked the user, then downloads it):
     * waits while dictation shows Preparing for it to be installed. The caller bounds both the
     * request and this polling by [INSTALL_WAIT]. False if no longer pending (declined or failed)
     * after [INSTALL_GRACE].
     */
    private suspend fun awaitInstalled(language: String): Boolean {
        val started = SystemClock.uptimeMillis()
        while (true) {
            val elapsed = (SystemClock.uptimeMillis() - started).milliseconds
            delay(INSTALL_POLL)
            val support = support(onDevice = true) ?: break
            if (support.installedOnDeviceLanguages.any { it.equals(language, ignoreCase = true) }) {
                AppLog.voice.info("dictation_language_installed waited=${elapsed.inWholeMilliseconds}ms")
                return true
            }
            val pending = support.pendingOnDeviceLanguages.any { it.equals(language, ignoreCase = true) }
            if (!pending && elapsed > INSTALL_GRACE) break
        }
        AppLog.voice.notice("dictation_language_not_installed")
        return false
    }

    private fun bestLocale(supported: List<String>?): String {
        val current = Locale.getDefault()
        val tag = current.toLanguageTag()
        if (supported == null) return tag
        if (supported.any { it.equals(tag, ignoreCase = true) }) return tag
        supported.firstOrNull { Locale.forLanguageTag(it).language == current.language }?.let { return it }
        return supported.firstOrNull { it.equals("en-US", ignoreCase = true) } ?: throw DictationError.LocaleUnsupported()
    }

    private companion object {
        val mainExecutor = Executor(Handler(Looper.getMainLooper())::post)
        val INSTALL_POLL = 1.seconds
        val INSTALL_GRACE = 15.seconds
        val INSTALL_WAIT = 5.minutes
    }
}

/** The service can retain the listener after destroy(). Detach the waiting press even if it never calls back. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal suspend fun awaitModelDownload(trigger: (ModelDownloadListener) -> Unit): Boolean? {
    val result = CompletableDeferred<Boolean?>(currentCoroutineContext()[Job])
    try {
        trigger(object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) = Unit
            override fun onSuccess() { result.complete(true) }
            override fun onScheduled() { result.complete(null) }
            override fun onError(error: Int) {
                if (result.complete(false)) AppLog.voice.error("Couldn’t install the dictation language ($error)")
            }
        })
        return result.await()
    } finally {
        result.cancel()
    }
}

/** A recognizer ready for one recording. Touched only on the main thread. */
private class LoadedSpeech(
    private val recognizer: SpeechRecognizer,
    private val language: String,
    private val onDevice: Boolean,
    private val isInstallFallback: Boolean,
) {
    private val done = CompletableDeferred<Unit>()
    private var results: Channel<DictationResult>? = null
    private var readEnd: ParcelFileDescriptor? = null
    private var writer: PipeWriter? = null
    private var destroyed = false

    fun model(): DictationModel = DictationModel(
        start = { input, format -> withContext(Dispatchers.Main) { start(input, format) } },
        finish = { done.await() },
        isInstallFallback = isInstallFallback,
        cancel = {
            withContext(Dispatchers.Main) {
                writer?.close()
                if (!destroyed) recognizer.cancel()
                end(null)
            }
        },
    )

    private fun start(input: DictationInput, format: DictationAudioFormat): ReceiveChannel<DictationResult> {
        val channel = Channel<DictationResult>(Channel.UNLIMITED)
        results = channel
        val (read, write) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
        readEnd = read
        val writer = PipeWriter(write).also { this.writer = it }
        recognizer.setRecognitionListener(Listener(channel))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET.sampleRate)
            // One session for the whole recording: it ends with the audio.
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        recognizer.startListening(intent)
        input.connect(BufferConverter(format, TARGET), writer)
        return channel
    }

    /** Ends the results (with [failure], if any) and lets the recognizer go. Main thread. */
    private fun end(failure: Throwable?) {
        results?.close(failure)
        writer?.close()
        if (!destroyed) {
            destroyed = true
            recognizer.destroy()
        }
        runCatching { readEnd?.close() }
        done.complete(Unit)
    }

    private inner class Listener(private val channel: Channel<DictationResult>) : RecognitionListener {
        /** Whether a finalized segment has been delivered: later words follow it after a space. */
        private var hasFinal = false

        private fun spaced(text: String) = if (hasFinal && text.isNotEmpty()) " $text" else text

        private fun text(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        override fun onPartialResults(partialResults: Bundle?) {
            val text = text(partialResults) ?: return
            channel.trySend(DictationResult(spaced(text), isFinal = false))
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val text = text(segmentResults)?.takeIf { it.isNotBlank() } ?: return
            channel.trySend(DictationResult(spaced(text), isFinal = true))
            hasFinal = true
        }

        override fun onEndOfSegmentedSession() = end(null)

        override fun onResults(results: Bundle?) {
            text(results)?.takeIf { it.isNotBlank() }?.let {
                channel.trySend(DictationResult(spaced(it), isFinal = true))
                hasFinal = true
            }
            end(null)
        }

        override fun onError(error: Int) {
            when (error) {
                // Nothing (more) was said: the recording simply ends.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> end(null)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> end(DictationError.MicrophoneDenied())
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    end(DictationError.LocaleUnsupported())
                else -> {
                    AppLog.voice.error("Dictation recognizer failed ($error, onDevice=${if (onDevice) 1 else 0})")
                    end(DictationError.Recognizer(error))
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        val TARGET = DictationAudioFormat(sampleRate = 16_000, channelCount = 1)
    }
}

/**
 * Writes converted audio into the recognizer's pipe on its own thread, so the
 * capture thread never blocks on a recognizer that reads slowly. [finish]
 * closes the pipe once what's queued is written: the recognizer's end of input.
 */
private class PipeWriter(descriptor: ParcelFileDescriptor) : DictationInputSink {
    private val queue = LinkedBlockingQueue<ShortArray>()
    private val stream = FileOutputStream(descriptor.fileDescriptor)
    private val descriptor = descriptor

    @Volatile
    private var closed = false

    private val thread = Thread({
        try {
            while (true) {
                val samples = queue.take()
                if (samples === END || closed) break
                val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asShortBuffer().put(samples)
                stream.write(bytes.array())
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        }
        runCatching { stream.close() }
        runCatching { descriptor.close() }
    }, "ai.opencode.dictation-pipe").apply { start() }

    override fun yield(samples: ShortArray) {
        if (!closed) queue.put(samples)
    }

    override fun finish() {
        queue.put(END)
    }

    /** Stops at once, dropping what's queued. */
    fun close() {
        closed = true
        queue.clear()
        queue.put(END)
    }

    private companion object {
        val END = ShortArray(0)
    }
}
