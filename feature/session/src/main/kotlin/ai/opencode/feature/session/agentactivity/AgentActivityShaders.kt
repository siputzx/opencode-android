package ai.opencode.feature.session.agentactivity

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Single-pass analytical fragment shader for [AgentActivityOrb] (the activity glyph and the shell
 * glyph): the central Gaussian glow, pixel-snapped 7×7 grid, analytical exponential per-dot halo
 * (`0.22 · P² · exp(−d / (0.12 · pitch))`), and anti-aliased disc SDF in one draw call.
 *
 * Coordinates are the draw's local pixels (Metal's are points; [AgentActivityOrb.pack] works in
 * pixels and passes `scale = 1`, which is the same maths). AGSL (SkSL ES2) indexes uniform arrays
 * only by loop indices, so where Metal visits the pixel's 3 × 3 neighbourhood this walks all 49
 * cells in the same row-major order and skips any farther than a pitch, which composites the same
 * cells in the same order.
 *
 * minSdk is 33, so `RuntimeShader` (API 33) is always available and no Canvas fallback is needed.
 *
 * Uniforms:
 * - `header0`: originX, originY, pitch, scale
 * - `header1`: glowAmount, glowPeak, dark (0/1), unlitAlpha
 * - `glowRGB`, `neutral`, `neutralCore`, `accent`, `accentCore`
 * - `cells[49]`: (light, fresh, accent, dotSize); dotSize 0 marks a cell outside the disc mask.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object AgentActivityShaders {
    const val ACTIVITY_ORB_FIELD = """
uniform float4 header0;
uniform float4 header1;
uniform float3 glowRGB;
uniform float3 neutral;
uniform float3 neutralCore;
uniform float3 accent;
uniform float3 accentCore;
uniform float4 cells[49];

half4 main(float2 position) {
  float2 origin = header0.xy;
  float pitch = header0.z;
  float scale = max(1.0, header0.w);
  float glowAmount = header1.x;
  float glowPeak = header1.y;
  float isDark = header1.z;
  float a0 = header1.w;

  float gridSide = 7.0 * pitch;
  float2 centre = origin + float2(gridSide * 0.5);

  float3 premul = float3(0.0);
  float alphaAcc = 0.0;

  // 1. Central Gaussian glow.
  if (glowAmount > 1e-3) {
    float sigma = 2.2;
    float reach = 3.6 * sigma;
    float distCells = length(position - centre) / pitch;
    if (distCells < reach) {
      float g = min(1.0, glowAmount * exp(-distCells * distCells / (2.0 * sigma * sigma))) * glowPeak;
      premul = glowRGB * g;
      alphaAcc = g;
    }
  }

  // 2. Dot cores and halos.
  float2 rel = (position - origin) / pitch;
  if (rel.x < -0.6 || rel.y < -0.6 || rel.x > 7.6 || rel.y > 7.6) {
    return half4(half3(premul), half(alphaAcc));
  }
  float halfPx = 0.5 / scale;
  float top = isDark > 0.5 ? 0.96 : 1.0;

  for (int i = 0; i < 49; i++) {
    float4 cell = cells[i];
    float dotSize = cell.w;
    if (dotSize <= 0.0) continue;
    float r = floor(float(i) / 7.0);
    float c = float(i) - r * 7.0;

    float2 dotCenter = origin + float2((c + 0.5) * pitch, (r + 0.5) * pitch);
    float dist = length(position - dotCenter);
    if (dist > pitch + halfPx) continue;

    float P = clamp(cell.x, 0.0, 1.0);
    float haloPeak = 0.22 * P * P;
    if (haloPeak <= 0.008 && dist > 0.48 * pitch + halfPx) continue;

    float fresh = clamp(cell.y, 0.0, 1.0);
    float dotAccent = clamp(cell.z, 0.0, 1.0);

    float sstep = smoothstep(0.03, 0.2, P);
    float shrink = 1.0 + ((0.82 + 0.18 * fresh) - 1.0) * sstep;
    float radius = (0.25 + 0.17 * pow(max(P, 1e-5), 0.6)) * shrink * pitch * dotSize;
    float outer = radius + 0.5 * pitch;
    if (dist > (haloPeak > 0.008 ? outer : radius) + halfPx) continue;

    float expPow = (isDark > 0.5 ? 0.9 : 0.75) * (1.4 + (1.0 - 1.4) * fresh);
    float dotAlpha = a0 + (top - a0) * pow(max(P, 1e-5), expPow);
    float core = clamp(P * P * 1.1, 0.0, 0.9) * (0.3 + 0.7 * fresh);
    float3 rgb = mix(mix(neutral, neutralCore, core), mix(accent, accentCore, core), dotAccent);

    // Analytical exponential halo outside the dot radius.
    if (haloPeak > 0.008) {
      float inner = max(0.0, radius - 0.01 * outer);
      if (dist >= inner && dist <= outer + halfPx) {
        float innerRamp = dist < radius ? clamp((dist - inner) / max(1e-4, radius - inner), 0.0, 1.0) : 1.0;
        float d = max(0.0, dist - radius);
        float outerCov = clamp((outer - dist) * scale + 0.5, 0.0, 1.0);
        float hAlpha = haloPeak * exp(-d / (0.12 * pitch)) * innerRamp * outerCov;
        premul = premul * (1.0 - hAlpha) + rgb * hAlpha;
        alphaAcc = alphaAcc * (1.0 - hAlpha) + hAlpha;
      }
    }

    // Anti-aliased dot disc.
    if (dist <= radius + halfPx) {
      float cov = clamp((radius - dist) * scale + 0.5, 0.0, 1.0);
      float a = dotAlpha * cov;
      premul = premul * (1.0 - a) + rgb * a;
      alphaAcc = alphaAcc * (1.0 - a) + a;
    }
  }

  return half4(half3(premul), half(alphaAcc));
}
"""

    /**
     * The one shared orb shader and paint. Main thread only: a glyph sets its uniforms and draws at
     * once; a recorded draw keeps the native shader it was recorded with, since every uniform
     * change makes a new native instance.
     */
    val orbShader: RuntimeShader by lazy { RuntimeShader(ACTIVITY_ORB_FIELD) }
    val orbPaint: Paint by lazy { Paint().apply { shader = orbShader } }
}
