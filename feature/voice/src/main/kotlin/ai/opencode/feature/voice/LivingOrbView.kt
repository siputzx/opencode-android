package ai.opencode.feature.voice

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.tuning.AnimationTimeScale
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Half
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** What a Living Orb draws. */
data class LivingOrbConfig(
    val mood: FloatingCallMood,
    val isMuted: Boolean,
    val isSpeaking: Boolean,
    val scene: FloatingCallScene? = null,
    /** Where the disc sits when the view is bigger than it (One Shape's card and sheet). Null centres it in the view. */
    val bloom: LivingOrbBloom? = null,
    /** The tool the agent is running, which picks Thinking's choreography. */
    val tool: String? = null,
) {
    /** Wisp's state for this call moment: speech first, then a request, work, and an open, listening call. */
    fun wispState(userLevel: Float): WispOrbState {
        if (scene != null) {
            return when (scene) {
                FloatingCallScene.idle, FloatingCallScene.listening -> WispOrbState.listening
                FloatingCallScene.agentSpeaking -> WispOrbState.speaking
                FloatingCallScene.bothSpeaking -> if (isMuted) WispOrbState.speaking else WispOrbState.bothSpeaking
                FloatingCallScene.thinking -> WispOrbState.thinking
                FloatingCallScene.needsInput -> WispOrbState.confirming
            }
        }
        if (mood == FloatingCallMood.connecting) return WispOrbState.connecting
        if (mood == FloatingCallMood.ended) return WispOrbState.sleeping
        val talking = !isMuted && userLevel > 0.06f
        if (isSpeaking) return if (talking) WispOrbState.bothSpeaking else WispOrbState.speaking
        if (mood == FloatingCallMood.needsInput) return WispOrbState.confirming
        if (mood == FloatingCallMood.thinking) return WispOrbState.thinking
        return WispOrbState.listening
    }
}

/** The disc inside a bigger field, in the view's dp. */
data class LivingOrbBloom(
    val center: Offset,
    val radius: Float,
    /** The dots below this fade out over [fade] dp above it. */
    val bottom: Float = Float.MAX_VALUE,
    val fade: Float = 0.5f,
    /** The glass the disc sits in, which it feathers into (the card or sheet). */
    val visible: Rect? = null,
) {
    /** What the placed dots feather into: the visible shape (or the disc), its corner, and how far in they fade. */
    data class Edge(val shape: Rect, val corner: Float, val feather: Float)

    fun edge(feather: Double): Edge {
        val shape = visible ?: Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
        val half = min(shape.width, shape.height) / 2
        val corner = FloatingCallStageTunables.cardRadius.value.toFloat()
        return Edge(shape, if (visible == null) radius else min(corner, half), min(feather.toFloat(), 0.18f * half))
    }
}

/**
 * The call's voice orb: Wisp's glass lens and phosphor dots ([WispOrbSim], [WispOrbShaders]).
 *
 * Android: an AGSL [RuntimeShader] drawn by a Compose [Canvas]; a `withFrameNanos` loop steps the
 * plain-object [WispOrbRenderer] only while it shows. Under Reduce Motion it draws once whenever
 * its inputs change. Wisp's glass looks the same in light and dark.
 *
 * @param isHidden faded out behind the panel: no drawing.
 * @param bloom where the bloom is this frame, read as each frame renders (overrides `config.bloom`).
 */
@Composable
fun LivingOrbView(config: LivingOrbConfig, modifier: Modifier = Modifier, isHidden: Boolean = false, bloom: (() -> LivingOrbBloom?)? = null) {
    LaunchedEffect(Unit) { WispOrbTunables.register() }
    val reduceMotion = rememberReduceMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val renderer = remember { WispOrbRenderer.make() } ?: return
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableIntStateOf(0) }
    val currentConfig by rememberUpdatedState(config)
    val currentBloom by rememberUpdatedState(bloom)

    fun configure() {
        // A bloom that moves every frame is read here, each frame, rather than recomposing.
        val bloomNow = currentBloom
        renderer.config = if (bloomNow != null) currentConfig.copy(bloom = bloomNow()) else currentConfig
        renderer.tuning = WispOrbTuning.current
        renderer.isStill = reduceMotion
    }

    val isPaused = isHidden || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(renderer, isPaused, reduceMotion, size, density, if (reduceMotion) config else Unit) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        renderer.resetClock()
        if (isPaused) return@LaunchedEffect
        if (reduceMotion) {
            configure()
            if (renderer.render(System.nanoTime() / 1e9, size.width, size.height, density)) frame++
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { nanos ->
                configure()
                if (renderer.render(nanos / 1e9, size.width, size.height, density)) frame++
            }
        }
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .clearAndSetSemantics {},
    ) {
        @Suppress("UNUSED_EXPRESSION") frame
        val brush = renderer.brush ?: return@Canvas
        drawRect(brush)
    }
}

/**
 * Steps [WispOrbSim] at display rate and draws a frame: [WispOrbCells] lights every LED, then
 * [WispOrbShaders] rasterises the lens. A plain object, one per mounted orb; main thread only.
 *
 * Android: the cell kernel runs on the CPU and its cells reach the shader as a half-float bitmap.
 */
@SuppressLint("NewApi") // API 33 shader; make() falls back to no orb below 33.
class WispOrbRenderer private constructor() {
    private val shader = RuntimeShader(WispOrbShaders.wispOrbFragment)
    private val sim = WispOrbSim()
    private var columns = 0
    /** Each cell's fast and slow phosphor for both voices, kept between frames. */
    private var phosphor = FloatArray(0)
    /** [WispOrbCells.floatsPerCell] floats per LED, written by the kernel and uploaded for the raster. */
    private var cells = FloatArray(0)
    /** The newest steps of both voices' wave history (`WISP_TRACE`). */
    private val traces = FloatArray(traceCount * 4)
    private var bitmap: Bitmap? = null
    private var pixels: ByteBuffer? = null
    private var tool: String? = null
    private var last: Double? = null

    // Hot loop: the meter resolved once.
    private val levels = VoiceAudioLevels.shared

    var config = LivingOrbConfig(mood = FloatingCallMood.live, isMuted = false, isSpeaking = false)
    var tuning = WispOrbTuning()
    var isStill = false

    /** The shader brush once a frame has been drawn into it. */
    var brush: ShaderBrush? = null
        private set

    fun resetClock() {
        last = null
    }

    /** New buffers, every LED dark, only when the lattice changes. */
    private fun prepare(next: Int) {
        if (next == columns && bitmap != null) return
        val count = max(1, next * next)
        phosphor = FloatArray(count * 4)
        cells = FloatArray(count * WispOrbCells.floatsPerCell)
        val texture = createBitmap(max(1, 2 * next), max(1, next), Bitmap.Config.RGBA_F16, hasAlpha = true, colorSpace = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
        bitmap = texture
        pixels = ByteBuffer.allocateDirect(max(1, 2 * next) * max(1, next) * 8).order(ByteOrder.nativeOrder())
        val input = BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST }
        shader.setInputBuffer("cells", input)
        columns = next
        brush = null
    }

    /** Steps (or stills) the orb for the frame at [nowSeconds] and uploads it. */
    fun render(nowSeconds: Double, pixelWidth: Int, pixelHeight: Int, scale: Float): Boolean {
        if (pixelWidth < 1 || pixelHeight < 1 || scale <= 0f) return false
        val tuning = tuning.clamped()
        prepare(tuning.columns)
        val dt = AnimationTimeScale.shared.scaledDelta(max(0.0, min(0.05, nowSeconds - (last ?: nowSeconds))))
        last = if (isStill) null else nowSeconds

        val (user, agent) = voice(nowSeconds)
        if (config.tool != tool) {
            tool = config.tool
            sim.setWorkingTool(tool)
        }
        sim.setMuted(config.isMuted)
        sim.step(dt.toFloat(), user, agent, config.wispState(user), isStill, tuning)
        val uniforms = sim.frame(tuning, placement(pixelWidth, pixelHeight, scale, tuning))
        config.bloom?.let { bloom ->
            val clip = uniforms[WispOrbUniforms.clipShape]
            if (bloom.visible != null) {
                val edge = bloom.edge(feather = 8.0)
                uniforms[WispOrbUniforms.clipRect] = floatArrayOf(
                    edge.shape.left * scale, edge.shape.top * scale, edge.shape.right * scale, edge.shape.bottom * scale,
                )
                clip[0] = edge.corner * scale
                clip[1] = edge.feather * scale
            }
            clip[2] = min(bloom.bottom, 1e6f) * scale
            clip[3] = max(0.5f, bloom.fade) * scale
            uniforms[WispOrbUniforms.clipShape] = clip
        }
        for (i in 0 until traceCount) {
            traces[i * 4] = sim.userTrace.newestAmplitude(i)
            traces[i * 4 + 1] = sim.userTrace.newestHarmonic(i)
            traces[i * 4 + 2] = sim.agentTrace.newestAmplitude(i)
            traces[i * 4 + 3] = sim.agentTrace.newestHarmonic(i)
        }
        WispOrbCells.evaluate(uniforms.data, phosphor, traces, cells)
        upload()
        for ((name, slot) in WispOrbShaders.rasterSlots) {
            val o = slot * 4
            shader.setFloatUniform(name, uniforms.data[o], uniforms.data[o + 1], uniforms.data[o + 2], uniforms.data[o + 3])
        }
        if (brush == null) brush = ShaderBrush(shader)
        return true
    }

    /**
     * The disc's centre and scale in the drawable. A bloom's circle is the lens (the glass around it
     * clips the shadow); otherwise the lens and its shadow bleed fill the view, as Wisp's window does.
     */
    private fun placement(width: Int, height: Int, scale: Float, tuning: WispOrbTuning): WispOrbPlacement {
        config.bloom?.let { bloom ->
            return WispOrbPlacement(bloom.center.x * scale, bloom.center.y * scale, max(bloom.radius * scale / (0.5f * tuning.diameter), 0.1f))
        }
        val box = tuning.diameter + 2 * WispOrb.bleed
        return WispOrbPlacement(width / 2f, height / 2f, max(min(width, height) / box, 0.1f))
    }

    private fun voice(time: Double): Pair<Float, Float> {
        val scene = config.scene
        var levels: Pair<Float, Float> = if (scene != null) {
            val virtual = AnimationTimeScale.shared.now(time)
            val you = LivingOrbVoiceSignal.sample(virtual, seed = 0.4).level.toFloat()
            val agent = LivingOrbVoiceSignal.sample(virtual, seed = 1.9).level.toFloat()
            when (scene) {
                FloatingCallScene.listening -> you to 0f
                FloatingCallScene.agentSpeaking -> 0f to agent
                FloatingCallScene.bothSpeaking -> you to agent
                FloatingCallScene.idle, FloatingCallScene.thinking, FloatingCallScene.needsInput -> 0f to 0f
            }
        } else {
            val live = levels.snapshot()
            live.user to live.assistant
        }
        if (config.isMuted) levels = 0f to levels.second
        return levels
    }

    // The texture holds raw half-float bits (`Half.toHalf` → `putShort`), read by the shader as data:
    // pixel 2i is (dot radius, dot alpha, halo alpha, 1), pixel 2i + 1 the colour.
    @SuppressLint("HalfFloat")
    private fun upload() {
        val buffer = pixels ?: return
        val texture = bitmap ?: return
        buffer.clear()
        val one = Half.toHalf(1f)
        for (n in 0 until columns * columns) {
            val c = n * WispOrbCells.floatsPerCell
            buffer.putShort(Half.toHalf(cells[c]))
            buffer.putShort(Half.toHalf(cells[c + 2]))
            buffer.putShort(Half.toHalf(cells[c + 3]))
            buffer.putShort(one)
            buffer.putShort(Half.toHalf(cells[c + 4]))
            buffer.putShort(Half.toHalf(cells[c + 5]))
            buffer.putShort(Half.toHalf(cells[c + 6]))
            buffer.putShort(one)
        }
        buffer.rewind()
        texture.copyPixelsFromBuffer(buffer)
    }

    companion object {
        /** `WISP_TRACE`. */
        const val traceCount = 64

        fun make(): WispOrbRenderer? = runCatching { WispOrbRenderer() }.getOrNull()
    }
}

/**
 * A made-up voice for scripted orbs (the Orb settings preview, the lab): phrases of syllables with
 * pauses between them, stressed syllables that make onsets, and a falling intonation with bright
 * consonants for Voice Tone. Levels only: nothing plays.
 */
object LivingOrbVoiceSignal {
    data class Sample(val level: Double, val tone: Double)

    fun sample(time: Double, seed: Double): Sample {
        val period = 3.6
        val clock = time + seed * 1.7
        val phrase = floor(clock / period)
        val local = clock - phrase * period
        val length = 1.7 + 1.1 * hash(phrase, seed)
        val contour = 0.62 - 0.3 * min(1.0, local / length) + 0.12 * sin(local * 2.3 + seed)
        if (local >= length) return Sample(0.0, contour)
        val edges = smooth(local / 0.12) * smooth((length - local) / 0.25)
        val beat = local * (4.2 + 0.8 * hash(phrase + 9, seed))
        val syllable = floor(beat)
        val within = beat - syllable
        val shape = sin(PI * within).pow(1.5)
        val stress = 0.5 + 0.5 * hash(syllable + phrase * 31, seed)
        val hiss = if (hash(syllable * 7 + phrase, seed + 3) > 0.72) 0.35 * (1 - shape) else 0.0
        val level = edges * (0.15 + 0.75 * shape * stress)
        return Sample(level, min(1.0, max(0.0, contour + hiss + 0.15 * (stress - 0.5))))
    }

    private fun hash(n: Double, seed: Double): Double {
        val x = sin(n * 12.9898 + seed * 78.233) * 43758.5453
        return x - floor(x)
    }

    private fun smooth(x: Double): Double {
        val t = max(0.0, min(1.0, x))
        return t * t * (3 - 2 * t)
    }
}
