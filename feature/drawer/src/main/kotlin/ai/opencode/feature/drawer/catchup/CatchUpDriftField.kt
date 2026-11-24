package ai.opencode.feature.drawer.catchup

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import android.graphics.RuntimeShader
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The calm dot-matrix Drift field behind the Catch Up stack that blooms as the last card goes, an
 * AGSL [RuntimeShader]. Reduce Motion holds one still frame; the field pauses while the screen
 * isn't resumed. Draw it edge to edge: the stage centre comes from the system-bar and cutout insets
 * plus the header and footer.
 */
@Composable
fun CatchUpDriftFieldView(isFinished: Boolean, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Spacer(modifier)
        return
    }
    val isStill = rememberReduceMotion() || rememberTouchExplorationEnabled()
    val isDark = OpenCodeTheme.colors.isDark
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isPaused = !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val density = LocalDensity.current
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val safeTop = insets.getTop(density)
    val safeBottom = insets.getBottom(density)

    val renderer = remember { CatchUpDriftRenderer() }
    var finishStart by remember { mutableStateOf<Double?>(null) }
    val now = remember { mutableDoubleStateOf(CatchUpDriftRenderer.clock()) }

    LaunchedEffect(isFinished) {
        finishStart = if (isFinished) CatchUpDriftRenderer.clock() else null
    }
    LaunchedEffect(isStill, isPaused) {
        if (isStill || isPaused) return@LaunchedEffect
        // A slow drift: 30 frames a second look the same as 60, so the field redraws only every
        // other frame or so (a little early, so 60 Hz lands on every second).
        val frameSeconds = 1.0 / CatchUpDriftRenderer.FRAMES_PER_SECOND
        while (true) {
            withFrameNanos { frameNanos ->
                val time = frameNanos / 1e9
                if (time - now.doubleValue >= frameSeconds - FRAME_SLACK_SECONDS) now.doubleValue = time
            }
        }
    }

    Canvas(modifier.clearAndSetSemantics {}) {
        val scale = density.density
        val shader = renderer.update(
            widthPx = size.width,
            heightPx = size.height,
            scale = scale,
            now = now.doubleValue,
            finishStart = finishStart,
            isFinished = isFinished,
            isStill = isStill,
            isDark = isDark,
            safeTopPx = safeTop.toFloat(),
            safeBottomPx = safeBottom.toFloat(),
        ) ?: return@Canvas
        drawRect(ShaderBrush(shader))
    }
}

private const val FRAME_SLACK_SECONDS = 0.002


@Composable
internal fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        enabled = manager.isTouchExplorationEnabled
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}
/** Owns the shader and its uniforms. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class CatchUpDriftRenderer {
    private val shader = RuntimeShader(SHADER)
    private val startTime = clock()

    fun update(
        widthPx: Float,
        heightPx: Float,
        scale: Float,
        now: Double,
        finishStart: Double?,
        isFinished: Boolean,
        isStill: Boolean,
        isDark: Boolean,
        safeTopPx: Float,
        safeBottomPx: Float,
    ): RuntimeShader? {
        if (widthPx < 1 || heightPx < 1 || scale <= 0) return null
        val widthPt = widthPx / scale
        val heightPt = heightPx / scale
        val cols = floor(widthPt / PITCH_PT)
        val rows = floor(heightPt / PITCH_PT)
        val originXPt = (widthPt - cols * PITCH_PT) * 0.5f
        val originYPt = (heightPt - rows * PITCH_PT) * 0.5f

        val time = if (isStill) STILL_TIME else max(0.0, now - startTime).toFloat()
        val level: Float
        val swell: Float
        val finishTime: Float
        if (isFinished) {
            if (isStill) {
                level = FINISH_LEVEL
                swell = 0.65f
                finishTime = 0.6f
            } else {
                val tf = max(0.0, now - (finishStart ?: now)).toFloat()
                val k = min(1f, tf / 0.65f)
                val smooth = k * k * (3 - 2 * k)
                level = STACK_LEVEL + (FINISH_LEVEL - STACK_LEVEL) * smooth
                swell = min(1f, tf / 0.55f)
                finishTime = tf
            }
        } else {
            level = STACK_LEVEL
            swell = 0f
            finishTime = -1f
        }

        val stageTop = safeTopPx / scale + CatchUpMetrics.headerHeight.value
        val stageBottom = heightPt - (safeBottomPx / scale + CatchUpMetrics.footerHeight.value)
        val centreX = widthPt * 0.5f
        val centreY = (stageTop + stageBottom) * 0.5f

        val palette = if (isDark) DARK else LIGHT
        shader.setFloatUniform("grid", PITCH_PT * scale, cols, rows, scale)
        shader.setFloatUniform("origin", originXPt * scale, originYPt * scale, heightPx, if (isDark) 1f else 0f)
        shader.setFloatUniform("look", time, level, swell, finishTime)
        shader.setFloatUniform("centre", centreX, centreY, 82f, 42f)
        shader.setFloatUniform("you", palette[0], palette[1], palette[2], 1f)
        shader.setFloatUniform("youCore", palette[3], palette[4], palette[5], 1f)
        shader.setFloatUniform("agent", palette[6], palette[7], palette[8], 1f)
        shader.setFloatUniform("agentCore", palette[9], palette[10], palette[11], 1f)
        return shader
    }

    companion object {
        /**
         * The field drifts over seconds, so it's drawn 30 times a second. Android draws it at the
         * screen's own density: a [RuntimeShader] brush has no cheaper lower-resolution target
         * short of an offscreen hardware renderer.
         */
        const val FRAMES_PER_SECOND = 30
        const val STACK_LEVEL = 0.28f
        const val FINISH_LEVEL = 0.56f
        const val STILL_TIME = 1.3f
        const val PITCH_PT = 3.5f

        /** The frame clock's base (`System.nanoTime`) in seconds. */
        fun clock(): Double = System.nanoTime() / 1e9

        /** Warm for you, cool for the agent (base, core). */
        val DARK = floatArrayOf(
            0.86f, 0.83f, 0.77f, 0.98f, 0.96f, 0.92f,
            0.70f, 0.75f, 0.84f, 0.92f, 0.95f, 1.0f,
        )
        val LIGHT = floatArrayOf(
            0.62f, 0.53f, 0.40f, 0.47f, 0.38f, 0.26f,
            0.40f, 0.47f, 0.58f, 0.24f, 0.31f, 0.43f,
        )

        /** `catchUpDriftFragment` (DotScopeShaders.metal), line for line in AGSL. */
        val SHADER = """
            uniform float4 grid;    // pitch (px), columns, rows, scale (px per pt)
            uniform float4 origin;  // first cell (px), view height (px), dark (0 or 1)
            uniform float4 look;    // time (s), level (0…1), swell (0…1), finish time (s, -1 before)
            uniform float4 centre;  // stage centre (pt.x, pt.y), title half-size (pt.w, pt.h)
            uniform float4 you;
            uniform float4 youCore;
            uniform float4 agent;
            uniform float4 agentCore;

            float driftHash(float2 p) {
              return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
            }

            float driftNoise(float2 p) {
              float2 i = floor(p);
              float2 f = fract(p);
              float2 u = f * f * (3.0 - 2.0 * f);
              float a = driftHash(i);
              float b = driftHash(i + float2(1.0, 0.0));
              float c = driftHash(i + float2(0.0, 1.0));
              float d = driftHash(i + float2(1.0, 1.0));
              return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            float driftFbm(float2 p) {
              return 0.62 * driftNoise(p) + 0.38 * driftNoise(p * 2.03 + 7.1);
            }

            half4 main(float2 position) {
              float pitch = grid.x;
              float cols = grid.y;
              float rows = grid.z;
              float sc = grid.w;
              float2 g = (position - origin.xy) / pitch;
              float2 cell = floor(g);
              if (cell.x < 0.0 || cell.y < 0.0 || cell.x >= cols || cell.y >= rows) return half4(0.0);
              float2 ctr = origin.xy + (cell + 0.5) * pitch;
              float d = length(position - ctr);
              if (d > (look.w < 0.0 ? 0.36 : 0.52) * pitch + 0.5) return half4(0.0);
              bool dark = origin.w > 0.5;

              float2 pt = ctr / sc - centre.xy;
              float dist = length(pt);
              float t = look.x;
              float level = look.y;
              float swell = look.z;
              float tf = look.w;
              float breath = 0.5 + 0.5 * sin(6.2831853 * t / 3.8);

              // Offset from (0, 0) so the stage centre never sits in sin(0) = 0's trough.
              float2 q = pt / 105.0 + float2(3.7, 1.9);
              float2 w = float2(driftFbm(q + float2(0.0, t * 0.04)), driftFbm(q + float2(5.2, -t * 0.035)));
              float n = driftFbm(q + 1.4 * w + float2(t * 0.045, -t * 0.025));
              float I = 0.05 + 0.28 * pow(max(n, 0.0), 2.0) * (0.85 + 0.15 * breath);

              // A calm, gently pulsating radial aura on the All Caught Up screen.
              float pulse = swell * (0.55 + 0.45 * breath) * exp(-dist * dist / (2.0 * 195.0 * 195.0));
              I += 0.24 * pulse;

              float clearStrength = tf < 0.0 ? 0.0 : clamp(tf / 0.3, 0.0, 1.0);
              float e = length(pt / (centre.zw + float2(32.0, 24.0)));
              float clearing = mix(1.0, mix(0.55, 1.0, smoothstep(0.45, 1.15, e)), clearStrength);
              float P = clamp(I * level * clearing, 0.0, 1.0);
              float ratio = clamp(mix(w.x, 0.68, clamp(pulse * 0.8, 0.0, 0.55)), 0.0, 1.0);

              float a0 = dark ? 0.022 : 0.032;
              float top = dark ? 0.36 : 0.40;
              float alpha = a0 + (top - a0) * pow(max(P, 1e-5), dark ? 1.05 : 0.95);
              float rad = (0.24 + 0.13 * pow(max(P, 1e-5), 0.65)) * pitch;
              float cov = clamp((rad - d) + 0.5, 0.0, 1.0);
              float halo = 0.10 * P * P * exp(-max(d - rad, 0.0) / (0.12 * pitch)) * (1.0 - cov);
              alpha = alpha * cov + halo;

              float core = clamp(P * P * 1.1, 0.0, 0.9) * 0.5;
              float3 cy = mix(you.rgb, youCore.rgb, core);
              float3 ca = mix(agent.rgb, agentCore.rgb, core);
              float3 col = clamp(mix(ca, cy, ratio), 0.0, 1.0);
              return half4(half3(col * alpha), half(alpha));
            }
        """.trimIndent()
    }
}
