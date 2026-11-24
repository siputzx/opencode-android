package ai.opencode.feature.transcript.user

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.TranscriptTunables
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A message an agent sent (Voice, the delegator, another session, a parent spawning a subagent)
 * arriving bright and settling: the bubble fades in while a luminous edge inside it and a slight
 * lift of its fill rise in 70 ms, then decay like phosphor, fast and then a slower tail, to exactly
 * the plain bubble. Under Reduce Motion it only fades in. Messages typed or dictated on this phone
 * never reveal.
 */
object AgentMessageReveal {
    @Immutable
    data class Frame(
        /** The light envelope, 0...1. */
        val light: Double = 0.0,
        /** The bubble's fill, its labels, and the Voice badge. */
        val body: Double = 1.0,
        val text: Double = 1.0,
    ) {
        companion object {
            val rest = Frame()
        }
    }

    val rise: Double get() = TranscriptTunables.agentGlowRise
    const val halfLife = 0.18
    const val slowHalfLife = 0.32
    const val slowShare = 0.22
    const val innerGlow = 0.7
    const val fillLift = 0.08
    const val fadeIn = 0.14
    const val textStart = 0.06
    const val textDuration = 0.2
    /** About 1.39 s: the light below 2% of its peak, then a final ease to zero. */
    val duration: Double get() = TranscriptTunables.agentGlowDuration

    /**
     * The clock arrivals are recorded on and the reveal samples: seconds on the monotonic clock
     * Compose's frame times use (`System.nanoTime`), so a frame's `withFrameNanos` time compares
     * directly with an arrival.
     */
    fun now(): Double = System.nanoTime() / 1e9

    /** 0 → 1 at [rise], then the two-half-life decay. */
    fun envelope(t: Double): Double {
        val rise = this.rise
        if (t <= 0) return 0.0
        if (t < rise) return cubic(t / max(0.001, rise))
        val x = t - rise
        return (1 - slowShare) * 2.0.pow(-x / halfLife) + slowShare * 2.0.pow(-x / slowHalfLife)
    }

    /** Android has no lab argument, so real time. */
    const val timeScale = 1.0

    /** Under Reduce Motion the light stays off: the bubble only fades in. */
    fun frame(t: Double, reduceMotion: Boolean = false): Frame {
        if (t >= duration) return Frame.rest
        val light = if (reduceMotion) 0.0 else envelope(t) * (1 - smootherstep(duration - 0.22, duration, t))
        return Frame(light = light, body = cubic(t / fadeIn), text = cubic((t - textStart) / textDuration))
    }

    /**
     * The colour the fill lifts toward and the inner edge's light: the voice scope's cool pearl,
     * a cool light (not a grey shade) in light mode.
     */
    fun colors(dark: Boolean): Pair<Color, Color> =
        if (dark) Color(0.30f, 0.33f, 0.39f) to Color(0.72f, 0.80f, 0.98f)
        else Color(0.90f, 0.93f, 1.0f) to Color(0.748f, 0.822f, 1.0f)

    private fun cubic(x: Double): Double = 1 - (1 - min(1.0, max(0.0, x))).pow(3)

    private fun smootherstep(a: Double, b: Double, x: Double): Double {
        val t = min(1.0, max(0.0, (x - a) / (b - a)))
        return t * t * t * (t * (6 * t - 15) + 10)
    }
}

/**
 * Port of `ShaderLibrary.agentMessageRevealGlow` (`AgentActivityShaders.metal`): an analytical
 * rounded-rect SDF inner glow, the uniform fill lift and inward exponential edge glow
 * (`exp(-depth / 7)`) in one pass. `radii`: (topLeading, bottomLeading, topTrailing,
 * bottomTrailing) in pixels.
 */
private const val AGENT_REVEAL_GLOW_AGSL = """
uniform float2 size;
uniform float4 radii;
uniform float density;
layout(color) uniform half4 liftIn;
layout(color) uniform half4 glowIn;
uniform float fillLift;
uniform float innerGlow;

half4 main(float2 position) {
  if ((fillLift <= 1e-4 && innerGlow <= 1e-4) || size.x <= 0.0 || size.y <= 0.0) {
    return half4(0.0);
  }
  // The Metal shader works in points; do the same so the 7 pt falloff matches.
  float2 sizePt = size / density;
  float2 halfSize = sizePt * 0.5;
  float2 p = position / density - halfSize;
  float r = p.x > 0.0 ? (p.y > 0.0 ? radii.w : radii.z) : (p.y > 0.0 ? radii.y : radii.x);
  r = min(r, min(halfSize.x, halfSize.y));
  float2 q = abs(p) - halfSize + r;
  float2 qPos = max(q, 0.0);
  float cornerDist = r > 1e-3
    ? pow(pow(qPos.x / r, 2.4) + pow(qPos.y / r, 2.4), 1.0 / 2.4) * r
    : length(qPos);
  float sd = min(max(q.x, q.y), 0.0) + cornerDist - r;
  if (sd >= 0.5) {
    return half4(0.0);
  }
  float edgeMask = clamp(0.5 - sd, 0.0, 1.0);
  float depth = max(0.0, -sd);

  float aLift = clamp(fillLift, 0.0, 1.0);
  float3 premul = float3(liftIn.rgb) * aLift;
  float alpha = aLift;

  float shoulder = 1.0 - 0.24 * exp(-depth * depth / 5.5);
  float aGlow = clamp(innerGlow * shoulder * exp(-depth / 7.0), 0.0, 1.0);
  premul = premul * (1.0 - aGlow) + float3(glowIn.rgb) * aGlow;
  alpha = alpha * (1.0 - aGlow) + aGlow;

  return half4(half3(premul * edgeMask), half(alpha * edgeMask));
}
"""

/**
 * The light inside the bubble: its fill lifted, and an edge glowing inward. Fills its parent;
 * clipped to the run's corners.
 */
@Composable
fun AgentMessageRevealLight(light: Double, corners: UserSurfaceCorners = UserSurfaceCorners.round, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Box(modifier.clip(corners.shape(TranscriptMetrics.bubbleCornerRadius)))
        return
    }
    val dark = OpenCodeTheme.colors.isDark
    val glow = remember { AgentRevealGlow() }
    Box(
        modifier
            .clip(corners.shape(TranscriptMetrics.bubbleCornerRadius))
            .drawBehind { with(glow) { draw(light, corners, dark) } },
    )
}

/** The reveal light's shader, drawn into a surface's own bounds (shared by [AgentMessageRevealLight] and the bubble fill). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class AgentRevealGlow {
    private val shader = RuntimeShader(AGENT_REVEAL_GLOW_AGSL)
    private val brush = ShaderBrush(shader)

    fun DrawScope.draw(light: Double, corners: UserSurfaceCorners, dark: Boolean) {
        if (light <= 1e-4) return
        val (lift, glow) = AgentMessageReveal.colors(dark)
        val full = TranscriptMetrics.bubbleCornerRadius.value
        val tight = UserMessageRun.tightRadius.value
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("radii", full, full, if (corners.top) tight else full, if (corners.bottom) tight else full)
        shader.setFloatUniform("density", density)
        shader.setColorUniform("liftIn", lift.toArgb())
        shader.setColorUniform("glowIn", glow.toArgb())
        shader.setFloatUniform("fillLift", (AgentMessageReveal.fillLift * light).toFloat())
        shader.setFloatUniform("innerGlow", (AgentMessageReveal.innerGlow * light).toFloat())
        drawRect(brush)
    }
}

/**
 * When each agent's message arrived, so its reveal plays once, on arrival. The list owns it and
 * hands each row its arrival as a value: a recycled or recomposed row reads the same clock, and a
 * message it never saw arrive (opening a session, a page of history) has no reveal. Times are
 * [AgentMessageReveal.now] seconds. Main thread only.
 */
class TranscriptAgentMessageReveal {
    private val arrivals = mutableMapOf<String, Double>()

    /** Records [messageID] arriving at [time]; false if it was already seen. */
    fun arrive(messageID: String, time: Double = AgentMessageReveal.now()): Boolean {
        if (arrivals.containsKey(messageID)) return false
        arrivals[messageID] = time
        return true
    }

    fun arrival(of: String): Double? = arrivals[of]

    /** The lab's Replay Reveal. */
    fun replay(messageID: String, time: Double = AgentMessageReveal.now()) {
        arrivals[messageID] = time
    }
}

/**
 * Samples the reveal each frame while it runs, then rests: a finished or absent reveal draws
 * [AgentMessageReveal.Frame.rest], which is the plain bubble.
 */
@Composable
fun AgentMessageRevealClock(arrival: Double?, content: @Composable (AgentMessageReveal.Frame) -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val scale = AgentMessageReveal.timeScale
    fun sample(now: Double): AgentMessageReveal.Frame {
        val start = arrival ?: return AgentMessageReveal.Frame.rest
        if (now >= start + AgentMessageReveal.duration * scale) return AgentMessageReveal.Frame.rest
        return AgentMessageReveal.frame((now - start) / scale, reduceMotion)
    }
    var frame by remember(arrival, reduceMotion) { mutableStateOf(sample(AgentMessageReveal.now())) }
    LaunchedEffect(arrival, reduceMotion) {
        val start = arrival ?: return@LaunchedEffect
        val ends = start + AgentMessageReveal.duration * scale
        while (true) {
            val now = withFrameNanos { it / 1e9 }
            frame = sample(now)
            if (now >= ends) break
        }
    }
    content(frame)
}
