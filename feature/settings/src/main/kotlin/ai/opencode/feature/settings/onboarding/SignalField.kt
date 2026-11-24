package ai.opencode.feature.settings.onboarding

import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberFrameDurationSeconds
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Phase
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Variant
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The onboarding's tuning values, baked to their defaults. */
internal object OnboardingTunables {
    /** Ripple speed (pt/s). */
    const val rippleSpeed = 120.0
    /** Ripple amplitude (pt). */
    const val rippleAmplitude = 2.6
    /** Ripple light. */
    const val rippleLight = 0.5
    /** Ring spacing (pt). */
    const val rippleSpacing = 70.0
    /** Heartbeat strength. */
    const val heartbeatStrength = 1.0
    /** Pulse height (pt). */
    const val heartbeatHeight = 56.0
    /** Second beat after (s). */
    const val heartbeatGap = 0.22
    /** Second beat strength. */
    const val heartbeatEcho = 0.55
    /** Resting dot light. */
    const val restingLight = 0.085
    /** Glow. */
    const val glow = 0.24
    /** Page stagger (s). */
    const val stagger = 0.05
    /** Enter blur (pt). */
    const val pageBlur = 3.0
    /** Enter rise (pt). */
    const val pageRise = 10.0
    /** Quiet Relay band height (pt). */
    const val bandHeight = 65.0

    val layout = MotionSpring(0.45, 0.0)
    val heartbeat = MotionSpring(0.42, 0.0)
    val sweepRipple = MotionSpring(0.85, 0.0)
    val sweepWipe = MotionSpring(0.85, 0.0)
    val relayRipple = MotionSpring(0.65, 0.0)
    val relayHandoff = MotionSpring(0.55, 0.0)
    val echoRipple = MotionSpring(0.85, 0.0)
    val echoWipe = MotionSpring(1.0, 0.0)

    fun ripple(variant: Variant): MotionSpring = when (variant) {
        Variant.signalSweep -> sweepRipple
        Variant.quietRelay -> relayRipple
        Variant.echoHandshake -> echoRipple
    }

    /** The move into the first session: the wipe, or Relay's handoff. */
    fun landing(variant: Variant): MotionSpring = when (variant) {
        Variant.signalSweep -> sweepWipe
        Variant.quietRelay -> relayHandoff
        Variant.echoHandshake -> echoWipe
    }
}

/**
 * What the dot field draws: the view's coarse scene. The renderer springs its own channels toward
 * it every frame, so a change mid-flight retargets from where the light is, with its velocity.
 * Lengths are dp in the field's own coordinates.
 */
internal data class SignalFieldScene(
    val phase: Phase,
    val variant: Variant,
    val heartbeats: Int,
    /** The camera aperture: the scan ripples start here. */
    val aperture: Rect,
    /** Where the trace runs: the pages' shared centre, or for Quiet Relay's first session, under its header. */
    val traceY: Float,
)

/**
 * The full-screen LED field behind the onboarding: a fixed grid of dots, the EKG trace, scan
 * ripples, the server's answering ripple and the heartbeat wipe, in one AGSL pass. Text and
 * controls stay Compose above it. Still under Reduce Motion: drawn only when the scene changes.
 * Paused while the screen isn't resumed.
 */
@Composable
internal fun SignalFieldView(scene: SignalFieldScene, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Spacer(modifier.fillMaxSize())
        return
    }
    val reduceMotion = rememberReduceMotion()
    val colors = OpenCodeTheme.colors
    val renderer = remember { SignalFieldRenderer() }
    val currentScene by rememberUpdatedState(scene)
    val isDark by rememberUpdatedState(colors.isDark)
    val highContrast by rememberUpdatedState(colors.highContrast)
    val isStill by rememberUpdatedState(reduceMotion)
    // The frame clock (seconds), written by the loop and read only while drawing.
    val frameTime = remember { mutableDoubleStateOf(0.0) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val running = !reduceMotion && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val frameDuration = rememberFrameDurationSeconds()

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        renderer.resetClock()
        motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
            frameTime.doubleValue += tick.dt
            MotionFrameAction.Continue
        }
    }

    Spacer(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics { }
            .drawWithCache {
                val shader = RuntimeShader(SignalFieldShaderSource)
                val brush = ShaderBrush(shader)
                onDrawBehind {
                    renderer.isDark = isDark
                    renderer.highContrast = highContrast
                    renderer.isStill = isStill
                    if (renderer.draw(shader, currentScene, frameTime.doubleValue, size.width, size.height, density)) {
                        drawRect(brush)
                    }
                }
            },
    )
}

/**
 * A spring in duration and bounce (ω = 2π / duration, ζ = 1 − bounce), stepped at a fixed
 * 480 Hz like knobs' solver: a new target keeps the position and velocity it has.
 */
internal class FieldSpring(var value: Double) {
    var velocity: Double = 0.0
    var target: Double = value

    fun step(dt: Double, spring: MotionSpring) {
        val omega = 2 * PI / max(spring.duration, 0.02)
        val zeta = max(0.05, 1 - spring.bounce)
        var remaining = dt
        val h = 1.0 / 480
        while (remaining > 1e-9) {
            val step = min(h, remaining)
            val acceleration = -omega * omega * (value - target) - 2 * zeta * omega * velocity
            velocity += acceleration * step
            value += velocity * step
            remaining -= step
        }
        if (abs(value - target) < 1e-4 && abs(velocity) < 1e-3) {
            value = target
            velocity = 0.0
        }
    }

    fun snap() {
        value = target
        velocity = 0.0
    }
}

/** Springs the field's channels toward the scene and feeds them to the shader. A plain object. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class SignalFieldRenderer {
    /** Every channel the field draws, each its own spring. */
    private class Channels {
        val traceLight = FieldSpring(1.0)
        val traceY = FieldSpring(0.0)
        val blip = FieldSpring(0.6)
        val scanning = FieldSpring(0.0)
        val found = FieldSpring(0.0)
        val reply = FieldSpring(0.0)
        val wipe = FieldSpring(0.0)
        val fieldLight = FieldSpring(1.0)
        val failed = FieldSpring(0.0)
        val quiet = FieldSpring(0.0)
        val echo = FieldSpring(0.0)
        var beat = FieldSpring(0.0)
    }

    var isDark = true
    var isStill = false
    var highContrast = false

    private val channels = Channels()
    private var hasStarted = false
    private var lastFrame: Double? = null
    /** How far the scan rings have travelled (pt); it only advances while they show. */
    private var ringTravel = 0.0
    private var heartbeats = 0
    /** The heartbeat's second beat, still to come. */
    private var pendingEcho: Double? = null

    fun resetClock() {
        lastFrame = null
    }

    /** Steps to [now] (seconds on the frame clock) and sets [shader]'s uniforms. False draws nothing. */
    fun draw(shader: RuntimeShader, scene: SignalFieldScene, now: Double, widthPx: Float, heightPx: Float, scale: Float): Boolean {
        val width = widthPx / scale
        val height = heightPx / scale
        if (width <= 1 || height <= 1) return false
        retarget(scene)
        if (!hasStarted || isStill) {
            // The first frame is the resting state; Reduce Motion never travels.
            snapAll()
            heartbeats = scene.heartbeats
            hasStarted = true
            lastFrame = now
        } else {
            val dt = min(0.05, max(0.0, now - (lastFrame ?: now)))
            lastFrame = now
            step(dt, scene)
        }
        setUniforms(shader, scene, width, height, scale)
        return true
    }

    private fun retarget(scene: SignalFieldScene) {
        val phase = scene.phase
        val quiet = scene.variant == Variant.quietRelay
        val c = channels
        c.traceLight.target = when (phase) {
            Phase.welcome, Phase.story, Phase.waiting, Phase.connected -> 1.0
            Phase.camera -> 0.8
            Phase.failed -> 0.35
            Phase.session -> if (quiet) 0.4 else 0.0
        }
        c.traceY.target = scene.traceY.toDouble()
        c.blip.target = when (phase) {
            Phase.welcome, Phase.story -> 0.6
            Phase.camera -> 0.3
            Phase.waiting, Phase.failed -> 0.0
            Phase.connected -> 0.8
            Phase.session -> if (quiet) 0.25 else 0.8
        }
        c.scanning.target = when (phase) {
            Phase.camera -> 1.0
            Phase.waiting -> 0.3
            else -> 0.0
        }
        c.found.target = if (phase in setOf(Phase.waiting, Phase.connected, Phase.failed, Phase.session)) 1.0 else 0.0
        c.reply.target = if (phase == Phase.connected || phase == Phase.session) 1.0 else 0.0
        c.wipe.target = if (phase == Phase.session && !quiet) 1.0 else 0.0
        c.fieldLight.target = if (phase == Phase.session && quiet) 0.0 else 1.0
        c.failed.target = if (phase == Phase.failed) 1.0 else 0.0
        c.quiet.target = if (quiet) 1.0 else 0.0
        c.echo.target = if (scene.variant == Variant.echoHandshake) 1.0 else 0.0
    }

    private fun step(dt: Double, scene: SignalFieldScene) {
        val c = channels
        val layout = OnboardingTunables.layout
        val ripple = OnboardingTunables.ripple(scene.variant)
        val landing = OnboardingTunables.landing(scene.variant)
        c.traceLight.step(dt, layout)
        c.traceY.step(dt, if (scene.phase == Phase.session) landing else layout)
        c.blip.step(dt, layout)
        c.scanning.step(dt, layout)
        c.found.step(dt, ripple)
        c.reply.step(dt, ripple)
        c.wipe.step(dt, landing)
        c.fieldLight.step(dt, landing)
        c.failed.step(dt, layout)
        c.quiet.step(dt, layout)
        c.echo.step(dt, layout)

        // The server's reply earns one heartbeat: a kick from rest that rises and falls on its
        // own spring, then a softer second beat.
        val beat = OnboardingTunables.heartbeat
        val omega = 2 * PI / max(beat.duration, 0.02)
        val peak = omega * E
        if (scene.heartbeats > heartbeats) {
            c.beat.velocity += OnboardingTunables.heartbeatStrength * peak
            pendingEcho = OnboardingTunables.heartbeatGap
        }
        heartbeats = scene.heartbeats
        pendingEcho?.let { wait ->
            if (wait <= dt) {
                c.beat.velocity += OnboardingTunables.heartbeatStrength * OnboardingTunables.heartbeatEcho * peak
                pendingEcho = null
            } else {
                pendingEcho = wait - dt
            }
        }
        c.beat.step(dt, beat)

        if (c.scanning.value > 0.001) {
            ringTravel += dt * OnboardingTunables.rippleSpeed
            val spacing = OnboardingTunables.rippleSpacing
            if (ringTravel > spacing * 1000) ringTravel %= spacing
        }
    }

    private fun snapAll() {
        val c = channels
        c.traceLight.snap()
        c.traceY.snap()
        c.blip.snap()
        c.scanning.snap()
        c.found.snap()
        c.reply.snap()
        c.wipe.snap()
        c.fieldLight.snap()
        c.failed.snap()
        c.quiet.snap()
        c.echo.snap()
        c.beat = FieldSpring(0.0)
        pendingEcho = null
    }

    private fun setUniforms(shader: RuntimeShader, scene: SignalFieldScene, w: Float, h: Float, scale: Float) {
        val ink = when {
            isDark && !highContrast -> floatArrayOf(0.886f, 0.906f, 0.945f)
            isDark -> floatArrayOf(1f, 1f, 1f)
            !highContrast -> floatArrayOf(0.137f, 0.169f, 0.227f)
            else -> floatArrayOf(0.03f, 0.05f, 0.10f)
        }
        val c = channels
        val pitch = (7 * scale).roundToInt().toFloat()
        // Reduce Motion keeps the field's shapes and drops the travel: no rings in flight.
        val rings = if (isStill) 0f else (c.scanning.value * OnboardingTunables.rippleLight).toFloat()
        val height = OnboardingTunables.heartbeatHeight.toFloat()
        val blip = height * (c.blip.value + max(0.0, c.beat.value)).toFloat()
        val ap = scene.aperture
        shader.setFloatUniform("grid", pitch, scale, w, h)
        shader.setFloatUniform("ink", ink[0], ink[1], ink[2], if (isDark) 1f else 0f)
        shader.setFloatUniform(
            "field",
            (OnboardingTunables.restingLight * if (highContrast) 1.4 else 1.0).toFloat(),
            OnboardingTunables.glow.toFloat(),
            max(0.0, c.beat.value).toFloat(),
            c.failed.value.toFloat(),
        )
        shader.setFloatUniform("trace", c.traceLight.value.toFloat(), c.traceY.value.toFloat(), blip, w * 0.5f)
        shader.setFloatUniform(
            "scan",
            rings,
            ringTravel.toFloat(),
            OnboardingTunables.rippleSpacing.toFloat(),
            if (isStill) 0f else OnboardingTunables.rippleAmplitude.toFloat(),
        )
        shader.setFloatUniform("aperture", ap.center.x, ap.center.y, min(ap.width, ap.height) / 2, 0f)
        shader.setFloatUniform("waves", c.found.value.toFloat(), c.reply.value.toFloat(), max(w, h) * 0.85f, c.echo.value.toFloat())
        shader.setFloatUniform("reply", w * 0.79f, h * 0.2f, 0f, 0f)
        shader.setFloatUniform("wipe", c.wipe.value.toFloat(), 1 - c.quiet.value.toFloat(), if (isStill) 0f else height, c.fieldLight.value.toFloat())
        shader.setFloatUniform("band", c.quiet.value.toFloat(), OnboardingTunables.bandHeight.toFloat(), 0f, 0f)
    }
}
