package ai.opencode.feature.session.agentactivity

import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Draws the orb: its soft radial glow, then each dot with its white-hot core and a faint halo, in
 * one GPU pass via [AgentActivityShaders.orbShader].
 */
object AgentActivityOrb {
    /** The glow's spill past the layout box, in the box's own unit. */
    fun margin(size: Float): Float = (size * 0.42f).roundToInt().toFloat()

    fun diameter(size: Float): Float = size - (if (size >= 24) 4 else 2)

    data class RGB(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: RGB) = RGB(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: RGB) = RGB(x - o.x, y - o.y, z - o.z)
        operator fun times(k: Double) = RGB(x * k, y * k, z * k)
        operator fun div(k: Double) = RGB(x / k, y / k, z / k)
        fun clamped() = RGB(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0), z.coerceIn(0.0, 1.0))

        companion object {
            val zero = RGB(0.0, 0.0, 0.0)
            val one = RGB(1.0, 1.0, 1.0)
            fun repeating(v: Double) = RGB(v, v, v)
        }
    }

    class Palette(
        val base: RGB,
        val core: RGB,
        val accents: Map<ActivityAccent, Pair<RGB, RGB>>,
        val hot: Map<ActivityAccent, Pair<RGB, RGB>>,
    ) {
        /** The flash colour for the state's accents, blended by their weights; null when none flashes. */
        fun hot(weights: Map<ActivityAccent, Double>): Pair<RGB, RGB>? {
            var sum = 0.0
            var a = RGB.zero
            var b = RGB.zero
            for ((key, weight) in weights) {
                if (weight <= 1e-3) continue
                val pair = hot[key] ?: continue
                sum += weight; a += pair.first * weight; b += pair.second * weight
            }
            return if (sum < 1e-3) null else (a / sum) to (b / sum)
        }
    }

    /** Dark-mode cool pearl base and core shared by the dot scopes and the orb. */
    val coolPearlDarkBase = RGB(0.70, 0.75, 0.84)
    val coolPearlDarkCore = RGB(0.92, 0.95, 1.0)

    // `prototypes/ios-kit/dot-orb/index.js`'s palette: the scope's cool pearl and four phosphor accents.
    // Done's green and Failed's red are settled solids (Deep green, Warm red), and `hot` is the
    // flash they cool from (`prototypes/activity-glyph/round4.html`).
    val dark = Palette(
        base = coolPearlDarkBase,
        core = coolPearlDarkCore,
        accents = mapOf(
            ActivityAccent.orange to (RGB(1.0, 0.64, 0.30) to RGB(1.0, 0.88, 0.70)),
            ActivityAccent.red to (RGB(1.0, 0.32, 0.24) to RGB(1.0, 0.56, 0.46)),
            ActivityAccent.green to (RGB(0.20, 0.80, 0.42) to RGB(0.50, 0.93, 0.62)),
            ActivityAccent.blue to (RGB(0.42, 0.66, 1.0) to RGB(0.80, 0.90, 1.0)),
        ),
        hot = mapOf(
            ActivityAccent.green to (RGB(0.86, 1.0, 0.90) to RGB.one),
            ActivityAccent.red to (RGB(1.0, 0.90, 0.84) to RGB.one),
        ),
    )

    // A step darker than the scope's light ink, to carry beside 15 pt text.
    val light = Palette(
        base = RGB(0.27, 0.32, 0.42),
        core = RGB(0.09, 0.12, 0.2),
        accents = mapOf(
            ActivityAccent.orange to (RGB(0.95, 0.52, 0.12) to RGB(0.78, 0.36, 0.02)),
            ActivityAccent.red to (RGB(0.85, 0.17, 0.12) to RGB(0.66, 0.08, 0.05)),
            ActivityAccent.green to (RGB(0.06, 0.55, 0.26) to RGB(0.02, 0.42, 0.18)),
            ActivityAccent.blue to (RGB(0.1, 0.45, 0.95) to RGB(0.02, 0.28, 0.72)),
        ),
        hot = mapOf(
            ActivityAccent.green to (RGB(0.36, 0.86, 0.52) to RGB(0.22, 0.76, 0.40)),
            ActivityAccent.red to (RGB(1.0, 0.46, 0.36) to RGB(0.96, 0.32, 0.24)),
        ),
    )

    // The layer's look: unlit grid, dot scale, glow strength and its light-mode share.
    const val UNLIT = 1.0
    const val DOT_SCALE = 1.0
    const val GLOW_STRENGTH = 1.5
    const val LIGHT_GLOW = 0.45
    const val SHADER_GLOW = 0.55

    private fun saturate(c: RGB, k: Double): RGB {
        val l = c.x * 0.299 + c.y * 0.587 + c.z * 0.114
        return RGB.repeating(l) + (c - RGB.repeating(l)) * k
    }

    private fun mix(a: RGB, b: RGB, t: Double): RGB = a + (b - a) * t

    /** Glow colour, as the scope derives it from a trace's two colours. */
    private fun glowColor(a: RGB, b: RGB, dark: Boolean): RGB {
        val c = if (dark) saturate(mix(a, b, 0.5), 1.5) else mix(saturate(a, 1.95), RGB.one, 0.15)
        return c.clamped()
    }

    /** Uniform values for one frame. */
    class Packed {
        val header0 = FloatArray(4)
        val header1 = FloatArray(4)
        val glowRGB = FloatArray(3)
        val neutral = FloatArray(3)
        val neutralCore = FloatArray(3)
        val accent = FloatArray(3)
        val accentCore = FloatArray(3)
        val cells = FloatArray(49 * 4)
    }

    /**
     * Packs the simulation field into the shader's uniforms. [area] is the orb's drawn square in
     * pixels, [size] its layout box in dp, [density] pixels per dp: the grid snaps to whole pixels
     * per cell.
     * [cells] places the field's dots: the orb's disc ([GyroCell.seven]), which the shell glyph shares.
     */
    fun pack(field: GyroField, cells: List<GyroCell>, area: Float, size: Float, density: Float, dark: Boolean, out: Packed = Packed()): Packed {
        val palette = if (dark) this.dark else this.light
        // The accent blended by the state's colour weights.
        var sum = 0.0
        var accent = RGB.zero
        var accentCore = RGB.zero
        for ((key, weight) in field.weights) {
            if (weight <= 1e-3) continue
            val pair = palette.accents[key] ?: continue
            sum += weight; accent += pair.first * weight; accentCore += pair.second * weight
        }
        val tint = min(1.0, sum)
        if (sum >= 1e-3) { accent /= sum; accentCore /= sum } else { accent = palette.base; accentCore = palette.core }
        var glowRGB = mix(glowColor(palette.base, palette.core, dark), glowColor(accent, accentCore, dark), if (sum >= 1e-3) tint else 0.0)
        // A flash warms the neutral pair toward its hot colour: lit dots cool from it into the accent, dim ones back to pearl.
        var neutral = palette.base
        var neutralCore = palette.core
        val heat = Gyro.clamp(field.heat, 0.0, 1.0)
        if (heat > 1e-3) {
            palette.hot(field.weights)?.let { hot ->
                neutral = mix(neutral, hot.first, heat); neutralCore = mix(neutralCore, hot.second, heat)
                glowRGB = mix(glowRGB, glowColor(hot.first, hot.second, dark), heat)
            }
        }

        // The grid snaps to device pixels: a whole number of pixels per cell.
        val n = 7.0
        val pitch = max(2.0, (diameter(size) * density / n).roundToInt().toDouble())
        val gridSide = n * pitch
        val origin = floor((area - gridSide) / 2)
        val peak = (if (dark) 0.3 else 0.19) * SHADER_GLOW * GLOW_STRENGTH * (if (dark) 1.0 else LIGHT_GLOW)
        val a0 = (if (dark) 0.06 else 0.075) * UNLIT

        out.header0[0] = origin.toFloat(); out.header0[1] = origin.toFloat(); out.header0[2] = pitch.toFloat(); out.header0[3] = 1f
        out.header1[0] = field.glow.toFloat(); out.header1[1] = peak.toFloat(); out.header1[2] = if (dark) 1f else 0f; out.header1[3] = a0.toFloat()
        put(out.glowRGB, glowRGB); put(out.neutral, neutral); put(out.neutralCore, neutralCore)
        put(out.accent, accent); put(out.accentCore, accentCore)
        out.cells.fill(0f)
        val count = min(cells.size, field.dots.size)
        for (i in 0 until count) {
            val cell = cells[i]
            val dot = field.dots[i]
            val idx = cell.row * 7 + cell.column
            if (idx in 0 until 49) {
                val base = idx * 4
                out.cells[base] = dot.light.toFloat()
                out.cells[base + 1] = dot.fresh.toFloat()
                out.cells[base + 2] = dot.accent.toFloat()
                // In the mask: a positive size (a masked dot's size is at least 0.7).
                out.cells[base + 3] = max(1e-3, dot.size * DOT_SCALE).toFloat()
            }
        }
        return out
    }

    private fun put(array: FloatArray, c: RGB) {
        array[0] = c.x.toFloat(); array[1] = c.y.toFloat(); array[2] = c.z.toFloat()
    }

    private val scratch = Packed()

    /**
     * Draws [field] centred on this scope's square layout box, its glow spilling [margin] past it.
     * Nothing when [field] is null. Main thread only (shared shader).
     */
    fun DrawScope.drawOrb(field: GyroField?, cells: List<GyroCell> = GyroCell.seven, dark: Boolean) {
        field ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            drawOrbCpu(field, cells, dark)
            return
        }
        val box = min(size.width, size.height)
        val sizeDp = box / density
        val m = margin(sizeDp) * density
        val side = box + 2 * m
        val packed = pack(field, cells, area = side, size = sizeDp, density = density, dark = dark, out = scratch)
        val shader = AgentActivityShaders.orbShader
        shader.setFloatUniform("header0", packed.header0)
        shader.setFloatUniform("header1", packed.header1)
        shader.setFloatUniform("glowRGB", packed.glowRGB)
        shader.setFloatUniform("neutral", packed.neutral)
        shader.setFloatUniform("neutralCore", packed.neutralCore)
        shader.setFloatUniform("accent", packed.accent)
        shader.setFloatUniform("accentCore", packed.accentCore)
        shader.setFloatUniform("cells", packed.cells)
        translate((size.width - box) / 2 - m, (size.height - box) / 2 - m) {
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, side, side, AgentActivityShaders.orbPaint) }
        }
    }

    /** API 31/32: the same dots without AGSL, drawn as plain circles. */
    private fun DrawScope.drawOrbCpu(field: GyroField, cells: List<GyroCell>, dark: Boolean) {
        val box = min(size.width, size.height)
        val m = margin(box / density) * density
        val side = box + 2 * m
        val pitch = side / 7.0
        val left = (size.width - box) / 2 - m
        val top = (size.height - box) / 2 - m
        val palette = if (dark) this@AgentActivityOrb.dark else this@AgentActivityOrb.light
        var accent = RGB.zero
        var sum = 0.0
        for ((key, weight) in field.weights) {
            if (weight <= 1e-3) continue
            val pair = palette.accents[key] ?: continue
            sum += weight
            accent += pair.first * weight
        }
        val accentColor = if (sum >= 1e-3) accent / sum else palette.base
        val count = min(field.dots.size, cells.size)
        for (i in 0 until count) {
            val dot = field.dots[i]
            if (dot.size <= 0.0) continue
            val cell = cells[i]
            val light = dot.light.coerceIn(0.0, 1.0)
            val color = mix(palette.base, accentColor, dot.accent.coerceIn(0.0, 1.0))
            val radius = (0.25 + 0.17 * light.coerceAtLeast(1e-5).pow(0.6)) * pitch * dot.size * DOT_SCALE
            drawCircle(
                color = Color(color.x.toFloat(), color.y.toFloat(), color.z.toFloat(), alpha = (0.075 + 0.925 * light).toFloat()),
                radius = radius.toFloat(),
                center = Offset(
                    (left + (cell.column + 0.5) * pitch).toFloat(),
                    (top + (cell.row + 0.5) * pitch).toFloat(),
                ),
            )
        }
    }
}

/** [AgentActivityOrb.drawOrb] as a plain `DrawScope` call. */
fun DrawScope.drawAgentActivityOrb(field: GyroField?, cells: List<GyroCell> = GyroCell.seven, dark: Boolean) {
    with(AgentActivityOrb) { drawOrb(field, cells, dark) }
}
