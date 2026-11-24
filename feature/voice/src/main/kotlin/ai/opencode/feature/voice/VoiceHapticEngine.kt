package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import ai.opencode.core.log.AppLog
import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

interface VoiceHapticPlaying {
    val isCallActive: Boolean
    fun prepare()
    fun play(pattern: VoiceHapticPattern, at: Duration, kind: VoiceHapticEngine.Kind = VoiceHapticEngine.Kind.other): Boolean
    fun cancel(kind: VoiceHapticEngine.Kind)
    fun endCall()
    suspend fun finish()
}

/** Android has one vibrator, not independent Core Haptics players. Mix all active
 * envelopes into one amplitude waveform so a landing never replaces the glide.
 * Sharpness has no continuous API below API 36; amplitude and exact timing survive. */
class VoiceHapticEngine(private val context: Context, private val clock: Clock = dependency(Clock)) : VoiceHapticPlaying {
    enum class Kind { tick, other }
    private data class Scheduled(val kind: Kind, val anchor: Duration, val pattern: VoiceHapticPattern) {
        val end get() = anchor + (pattern.duration + 0.012).seconds
    }
    private val vibrator: Vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val scheduled = mutableListOf<Scheduled>()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile override var isCallActive = false; private set
    @Volatile private var prepared = false
    init {
        scope.launch { ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { synchronized(lock) { scheduled.clear(); vibrator.cancel() } }
        }) }
    }
    override fun prepare() { isCallActive = true; prepared = vibrator.hasVibrator(); if (prepared) AppLog.voice.notice("Call haptics started") }
    @Suppress("DEPRECATION") // API 35 still exposes the user's touch-feedback preference here.
    override fun play(pattern: VoiceHapticPattern, at: Duration, kind: Kind): Boolean = synchronized(lock) {
        if (!prepared || !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return@synchronized false
        val now = clock.now()
        val timed = if (at >= now) pattern else pattern.late((now - at).inWholeNanoseconds / 1e9)
        if (timed.isEmpty) return@synchronized true
        scheduled.removeAll { it.end < now }
        scheduled.add(Scheduled(kind, maxOf(at, now), timed))
        runCatching { render(now) }.isSuccess
    }
    private fun render(now: Duration) {
        if (scheduled.isEmpty()) { vibrator.cancel(); return }
        val count = max(1, ceil((scheduled.maxOf { it.end } - now).inWholeNanoseconds / 1e6).toInt())
        val amplitudes = IntArray(count)
        for (i in 0 until count) {
            var level = 0f
            for (s in scheduled) {
                val t = (now - s.anchor).inWholeNanoseconds / 1e9 + i / 1000.0
                for (event in s.pattern.events) level = max(level, eventLevel(event, t))
                for (event in s.pattern.shaped) level = max(level, eventLevel(event, t) * curve(s.pattern.intensity, t))
            }
            amplitudes[i] = if (vibrator.hasAmplitudeControl()) (level.coerceIn(0f, 1f) * 255).roundToInt() else if (level > 0.1f) VibrationEffect.DEFAULT_AMPLITUDE else 0
        }
        if (amplitudes.all { it == 0 }) { vibrator.cancel(); return }
        val (timings, levels) = waveformSteps(amplitudes)
        val effect = VibrationEffect.createWaveform(timings, levels, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build())
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect)
        }
    }
    override fun cancel(kind: Kind) = synchronized(lock) {
        val now = clock.now()
        scheduled.removeAll { it.end <= now || it.kind == kind }
        runCatching { render(now) }
        Unit
    }
    override fun endCall() { isCallActive = false }
    override suspend fun finish() {
        val now = clock.now(); val end = synchronized(lock) { scheduled.maxOfOrNull { it.end } }
        if (end != null && end > now) clock.sleep(minOf(end, now + 600.milliseconds) - now + 20.milliseconds)
        synchronized(lock) { if (!isCallActive) { scheduled.clear(); vibrator.cancel(); prepared = false } }
    }
    companion object Key : DependencyKey<VoiceHapticPlaying> {
        /**
         * A waveform step costs a vibrator HAL round trip on devices without envelope support
         * (about 20 ms on a Galaxy S24), so 1 ms steps stretched the 0.8 s connect pattern into a
         * 17 s buzz. The mix is played in [stepMillis] windows at each window's peak (a tick
         * keeps its strength), equal neighbours merge, and the silent tail is dropped.
         */
        internal fun waveformSteps(amplitudes: IntArray, stepMillis: Int = 25): Pair<LongArray, IntArray> {
            val timings = mutableListOf<Long>(); val levels = mutableListOf<Int>()
            var start = 0
            while (start < amplitudes.size) {
                val end = min(amplitudes.size, start + stepMillis)
                var peak = 0
                for (i in start until end) peak = max(peak, amplitudes[i])
                if (levels.isNotEmpty() && levels.last() == peak) timings[timings.lastIndex] += (end - start).toLong()
                else { timings.add((end - start).toLong()); levels.add(peak) }
                start = end
            }
            while (levels.size > 1 && levels.last() == 0) { levels.removeAt(levels.lastIndex); timings.removeAt(timings.lastIndex) }
            return timings.toLongArray() to levels.toIntArray()
        }
        override val liveValue by lazy { VoiceHapticEngine(dependency(AppContext)) }
        override val testValue = object : VoiceHapticPlaying {
            override val isCallActive = false
            override fun prepare() {}
            override fun play(pattern: VoiceHapticPattern, at: Duration, kind: Kind) = false
            override fun cancel(kind: Kind) {}
            override fun endCall() {}
            override suspend fun finish() {}
        }
        private fun eventLevel(event: VoiceHapticPattern.Event, t: Double): Float {
            val age = t - event.time
            val duration = if (event.isTransient) 0.012 else event.duration
            if (age < 0 || age >= duration) return 0f
            return event.intensity * (event.decay?.let { max(0.0, 1 - age / it).toFloat() } ?: 1f)
        }
        private fun curve(points: List<VoiceHapticPattern.Point>, t: Double): Float {
            if (points.isEmpty()) return 1f
            if (t <= points.first().time) return points.first().value
            for (i in 1 until min(points.size, VoiceHapticPattern.maxCurvePoints)) {
                val a = points[i - 1]; val b = points[i]
                if (t <= b.time) return a.value + (b.value - a.value) * ((t - a.time) / max(1e-6, b.time - a.time)).toFloat()
            }
            return points.last().value
        }
    }
}
