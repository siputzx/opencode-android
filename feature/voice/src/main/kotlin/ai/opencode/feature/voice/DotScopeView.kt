package ai.opencode.feature.voice

import ai.opencode.core.log.AppLog
import ai.opencode.core.diagnostics.PerfTrace
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Half
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Session sets it while the session is covered (a browser page, Catch Up), so every scope
 * underneath stops drawing while keeping its simulation. Covered scopes run no frame loop at all.
 */
val LocalDotScopeCovered = staticCompositionLocalOf { false }

/** Composer's dictation meter, read once per frame. */
fun interface DotScopeLevelSource {
    fun level(): Float
}

sealed interface DotScopeMode {
    /** Both traces render in your warm pearl and follow [level] (Composer's dictation levels). */
    data class Dictation(val leadingInset: Float, val trailingInset: Float, val level: DotScopeLevelSource) : DotScopeMode

    /**
     * Settings → Voice: a simulated conversation, you then the agent, while [active]; at rest
     * otherwise. No microphone or call involved.
     */
    data class Preview(val active: Boolean) : DotScopeMode
}

/**
 * The dictation bar's visual: an LED dot-matrix oscilloscope where your voice (warm
 * pearl) and the agent's (cool pearl) are two braided traces leaving phosphor trails over a
 * soft glow behind each button. In dictation mode both traces render in your warm pearl and
 * follow the dictation level. Under Reduce Motion it is the quiet state, still; in the
 * background it stops drawing.
 *
 * Android: an AGSL [RuntimeShader] ([DotScopeShaders]) drawn by a Compose [Canvas]; a
 * `withFrameNanos` loop advances the plain-object [DotScopeRenderer] (never reducer actions) only
 * while the scope is visible: not [LocalDotScopeCovered], and the lifecycle resumed. A paused scope
 * keeps its last frame and resumes with a reset clock, so it never fast-forwards. Under Reduce
 * Motion it draws one still frame whenever its inputs change. After [DotScopeRenderer.QUIET_AFTER]
 * of silence only the slow carrier moves, so it draws every other frame.
 */
@Composable
fun DotScopeView(
    mode: DotScopeMode,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val isDark = OpenCodeTheme.colors.isDark
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isCovered = LocalDotScopeCovered.current
    val scope = remember { DotScopeRenderer.make() } ?: return
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableIntStateOf(0) }
    val currentMode by rememberUpdatedState(mode)

    // Each mounted surface owns its configuration: apply it at draw time.
    fun configure() {
        scope.isDark = isDark
        scope.isStill = reduceMotion
        scope.mode = currentMode
    }

    val isPaused = isCovered || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(scope, isPaused, reduceMotion, size, density, if (reduceMotion) mode else Unit, isDark) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        if (isPaused) return@LaunchedEffect
        scope.resetClock()
        if (reduceMotion) {
            // Still: draw only when something changes.
            configure()
            if (scope.render(nowSeconds = System.nanoTime() / 1e9, pixelWidth = size.width, pixelHeight = size.height, scale = density)) frame++
            return@LaunchedEffect
        }
        var frames = 0L
        while (true) {
            val nanos = withFrameNanos { it }
            frames++
            if (!DotScopeRenderer.drawsFrame(isQuiet = scope.isQuiet, frame = frames)) continue
            configure()
            if (scope.render(nowSeconds = nanos / 1e9, pixelWidth = size.width, pixelHeight = size.height, scale = density)) frame++
        }
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .clearAndSetSemantics {},
    ) {
        @Suppress("UNUSED_EXPRESSION") frame
        val brush = scope.brush ?: return@Canvas
        drawRect(brush)
    }
}

/** The pearl palette shared by the dot scopes, `AgentActivityOrb` and Catch Up's drift field. */
object DotScopePalette {
    /** Dark-mode cool pearl base and core shared by the dot scopes and `AgentActivityOrb`. */
    val coolPearlDarkBase = floatArrayOf(0.70f, 0.75f, 0.84f)
    val coolPearlDarkCore = floatArrayOf(0.92f, 0.95f, 1.0f)

    data class Pearl(val you: FloatArray, val youCore: FloatArray, val agent: FloatArray, val agentCore: FloatArray)

    /** The pearl palette: warm for you, cool for the agent; cores are where a trace burns brightest. */
    fun pearlPalette(dark: Boolean): Pearl = if (dark) {
        Pearl(
            floatArrayOf(0.86f, 0.83f, 0.77f, 1f),
            floatArrayOf(0.98f, 0.96f, 0.92f, 1f),
            floatArrayOf(coolPearlDarkBase[0], coolPearlDarkBase[1], coolPearlDarkBase[2], 1f),
            floatArrayOf(coolPearlDarkCore[0], coolPearlDarkCore[1], coolPearlDarkCore[2], 1f),
        )
    } else {
        Pearl(
            floatArrayOf(0.62f, 0.53f, 0.40f, 1f),
            floatArrayOf(0.47f, 0.38f, 0.26f, 1f),
            floatArrayOf(0.40f, 0.47f, 0.58f, 1f),
            floatArrayOf(0.24f, 0.31f, 0.43f, 1f),
        )
    }
}

/**
 * Steps the scope's simulation at a fixed rate and draws the lit grid. A plain object, one per
 * mounted scope. Configuration and publication are main thread only; simulation and half-float
 * packing run on Default. The mutex covers both, so frames never advance the simulation or reuse
 * its buffer concurrently.
 */
@SuppressLint("NewApi") // API 33 shader; make() falls back to no scope below 33.
class DotScopeRenderer private constructor() {
    private var simulation = DotScopeSimulation()
    private var lastFrame: Double? = null
    private val frameMutex = Mutex()
    private var clockGeneration = 0L // Main thread; snapshotted with each request.
    private var simulatedGeneration = -1L // Worker, under frameMutex.

    /** Dictation's window eases to new insets as the bar's buttons come and go. */
    private var insets: ScopeVector2? = null

    var mode: DotScopeMode = DotScopeMode.Preview(active = false)
    var isDark = true
    var isStill = false

    /** When a voice was last above [QUIET_LEVEL]. Worker, under frameMutex. */
    private var lastHeard = Double.NEGATIVE_INFINITY

    /** Nothing on screen moves faster than a slow breath: half the frame rate draws the same picture. */
    @Volatile
    var isQuiet = false
        private set

    private val shader = RuntimeShader(DotScopeShaders.dotScopeFragment)
    private var grid: DotScopeGrid? = null
    private var field = FloatArray(0)
    private var bitmap: Bitmap? = null
    private var publishedBitmap: Bitmap? = null
    private var pixels: ByteBuffer? = null

    /** The shader brush once a frame has been drawn into it. */
    var brush: ShaderBrush? = null
        private set

    /** After a pause the scope resumes where it was instead of fast-forwarding. */
    fun resetClock() {
        clockGeneration++
    }

    private fun prepareGrid(pixelWidth: Int, pixelHeight: Int, scale: Float): DotScopeGrid? {
        if (pixelWidth < 1 || pixelHeight < 1 || scale <= 0f) return null
        val next = DotScopeGrid(pixelWidth = pixelWidth, pixelHeight = pixelHeight, scale = scale, requestedPitch = 3.5f)
        if (next != grid) {
            grid = next
            field = FloatArray(max(1, next.count) * 4)
            val texture = createBitmap(next.columns, next.rows, Bitmap.Config.RGBA_F16, hasAlpha = true, colorSpace = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
            bitmap = texture
            pixels = ByteBuffer.allocateDirect(next.columns * next.rows * 8).order(ByteOrder.nativeOrder())
        }
        return grid
    }

    private data class FrameState(
        val live: Float, val glow: ScopeVector2, val breath: Float, val insets: ScopeVector2?,
    )

    /** One awaited request per display frame, not a queue of work that can outlive its view. */
    suspend fun render(nowSeconds: Double, pixelWidth: Int, pixelHeight: Int, scale: Float): Boolean {
        // Snapshot this surface before waiting for the previous frame. No worker reads Compose
        // state, a level source, or mutable main-thread configuration.
        val mode = mode
        val isStill = isStill
        val isDark = isDark
        val generation = clockGeneration
        val snapshot = if (isStill) VoiceAudioLevels.Snapshot(0f, 0f) else when (mode) {
            is DotScopeMode.Dictation -> mode.level.level().let { VoiceAudioLevels.Snapshot(it, it * 0.85f) }
            is DotScopeMode.Preview -> (if (mode.active) previewLevels(nowSeconds) else 0f to 0f)
                .let { VoiceAudioLevels.Snapshot(it.first, it.second) }
        }
        return frameMutex.withLock {
            if (generation != clockGeneration) return@withLock false
            val grid = prepareGrid(pixelWidth, pixelHeight, scale) ?: return@withLock false
            val state = withContext(Dispatchers.Default) {
                PerfTrace.section("dot_scope_compute") {
                    if (simulatedGeneration != generation) {
                        lastFrame = null
                        simulatedGeneration = generation
                    }
                    simulate(grid, nowSeconds, mode, isStill, snapshot)
                    pack(grid)
                    FrameState(simulation.live, simulation.glow, simulation.breath, insets)
                }
            }
            // Cancellation of the owning LaunchedEffect prevents publication on return from
            // withContext. A handoff/reset also rejects an old surface's completed frame.
            if (generation != clockGeneration) return@withLock false
            PerfTrace.section("dot_scope_publish") {
                pixels?.let { bitmap?.copyPixelsFromBuffer(it) }
                if (bitmap !== publishedBitmap) {
                    val input = BitmapShader(checkNotNull(bitmap), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        .apply { filterMode = BitmapShader.FILTER_MODE_NEAREST }
                    shader.setInputBuffer("field", input)
                    publishedBitmap = bitmap
                }
                setUniforms(grid, mode, isDark, state)
                if (brush == null) brush = ShaderBrush(shader)
            }
            true
        }
    }

    private fun simulate(
        grid: DotScopeGrid, nowSeconds: Double, mode: DotScopeMode, isStill: Boolean,
        snapshot: VoiceAudioLevels.Snapshot,
    ) {
        val target = when (mode) {
            is DotScopeMode.Preview -> ScopeVector2(DotScopeSimulation.windowInset, DotScopeSimulation.windowInset)
            is DotScopeMode.Dictation -> ScopeVector2(mode.leadingInset, mode.trailingInset)
        }
        if (isStill) {
            val connected = when (mode) {
                is DotScopeMode.Preview -> mode.active
                is DotScopeMode.Dictation -> true
            }
            simulation = DotScopeSimulation.still(connected)
            isQuiet = true
            insets = target
            simulation.windowInsets = target
        } else {
            val rawElapsed = (nowSeconds - (lastFrame ?: nowSeconds)).coerceIn(0.0, 0.1)
            // Real time here.
            val elapsed = rawElapsed
            lastFrame = nowSeconds
            val eased = insets?.let {
                val k = min(1f, elapsed.toFloat() * 12)
                ScopeVector2(it.x + (target.x - it.x) * k, it.y + (target.y - it.y) * k)
            } ?: target
            insets = eased
            simulation.windowInsets = eased
            val loudest: Float
            when (mode) {
                is DotScopeMode.Dictation -> {
                    loudest = snapshot.user
                    simulation.advance(elapsed, you = snapshot.user, agent = snapshot.assistant, connected = true)
                }
                is DotScopeMode.Preview -> {
                    val carrier = if (mode.active) 1f else 0f
                    // Connecting or disconnecting eases the carrier in and out.
                    loudest = if (abs(simulation.live - carrier) > 0.01f) 1f else max(snapshot.user, snapshot.assistant)
                    simulation.advance(elapsed, you = snapshot.user, agent = snapshot.assistant, connected = mode.active)
                }
            }
            if (loudest > QUIET_LEVEL) lastHeard = nowSeconds
            isQuiet = isQuiet(lastHeard = lastHeard, now = nowSeconds)
        }
        simulation.render(grid, field)
    }

    // The texture holds raw half-float bits (`Half.toHalf` → `putShort`), read by the shader as data.
    @SuppressLint("HalfFloat")
    private fun pack(grid: DotScopeGrid) {
        val buffer = pixels ?: return
        buffer.clear()
        val one = Half.toHalf(1f)
        for (n in 0 until grid.count) {
            val i = n * 4
            buffer.putShort(Half.toHalf(field[i]))
            buffer.putShort(Half.toHalf(field[i + 1]))
            buffer.putShort(Half.toHalf(field[i + 2]))
            buffer.putShort(one)
        }
        buffer.rewind()
    }

    private fun setUniforms(grid: DotScopeGrid, mode: DotScopeMode, isDark: Boolean, state: FrameState) {
        val palette = DotScopePalette.pearlPalette(isDark)
        val isDictation: Boolean
        val window: FloatArray
        val bar = floatArrayOf(0f, grid.width, 0f, 0f)
        val shape = floatArrayOf(grid.height / 2, 0f, 0f, 0f)
        when (mode) {
            is DotScopeMode.Preview -> {
                isDictation = false
                window = floatArrayOf(DotScopeSimulation.windowInset, DotScopeSimulation.windowFade, 0f, 0f)
            }
            is DotScopeMode.Dictation -> {
                isDictation = true
                val eased = state.insets ?: ScopeVector2(mode.leadingInset, mode.trailingInset)
                window = floatArrayOf(eased.x, 24f, eased.y, 0f)
            }
        }
        val glow = state.glow
        shader.setFloatUniform("grid", grid.pitch, grid.columns.toFloat(), grid.rows.toFloat(), grid.scale)
        shader.setFloatUniform("origin", grid.originX, grid.originY, grid.width, grid.height)
        shader.setFloatUniform("state", state.live, if (isDark) 1f else 0f, 1f, 1f)
        shader.setFloatUniform("window", window)
        shader.setFloatUniform("glow", glow.x, glow.y, state.breath, state.live)
        shader.setFloatUniform("you", palette.you)
        shader.setFloatUniform("youCore", palette.youCore)
        shader.setFloatUniform("agent", if (isDictation) palette.you else palette.agent)
        shader.setFloatUniform("agentCore", if (isDictation) palette.youCore else palette.agentCore)
        shader.setFloatUniform("bar", bar)
        shader.setFloatUniform("shape", shape)
    }

    companion object {
        /**
         * Builds the shader once off the main thread, so the first scope (a held mic's bar) appears
         * at once. Android compiles AGSL lazily per program; this warms the process's shader cache,
         * at most once a launch.
         */
        fun prepare() {
            if (prepared) return
            prepared = true
            Thread { runCatching { RuntimeShader(DotScopeShaders.dotScopeFragment) } }.start()
        }

        @Volatile
        private var prepared = false

        const val QUIET_LEVEL = 0.02f

        /** Long enough for the last syllable's trails to fade. */
        const val QUIET_AFTER = 1.5

        /** Silent long enough that only the slow carrier moves. */
        fun isQuiet(lastHeard: Double, now: Double): Boolean = now - lastHeard > QUIET_AFTER

        /** Whether display frame [frame] draws: all of them, or every other one while quiet. */
        fun drawsFrame(isQuiet: Boolean, frame: Long): Boolean = !isQuiet || frame % 2 == 0L

        fun make(): DotScopeRenderer? = runCatching { DotScopeRenderer() }
            .onFailure { AppLog.voice.error("dot_scope_shader_failed type=${it.javaClass.simpleName}") }
            .getOrNull()

        /**
         * A 6.4 s exchange: you speak, a beat, the agent answers, a beat. Each voice is a
         * syllable-paced envelope, like speech rather than a tone.
         */
        fun previewLevels(time: Double): Pair<Float, Float> {
            val cycle = time % 6.4
            fun turn(start: Double, length: Double, seed: Double): Float {
                val t = cycle - start
                if (t <= 0 || t >= length) return 0f
                val edge = min(1.0, min(t / 0.25, (length - t) / 0.35))
                val syllables = abs(sin(time * 8.3 + seed)) * (0.55 + 0.45 * abs(sin(time * 3.1 + seed * 2)))
                return (edge * (0.18 + 0.62 * syllables)).toFloat()
            }
            return turn(0.2, 2.3, 0.7) to turn(2.9, 3.0, 2.1)
        }
    }
}
