package ai.opencode.feature.drawer.list

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.AmbientClock
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.drawer.catchup.rememberTouchExplorationEnabled
import ai.opencode.feature.drawer.icons.ProjectAvatarPalette
import ai.opencode.feature.drawer.icons.ProjectIcon
import ai.opencode.feature.drawer.icons.ProjectIconClient
import ai.opencode.feature.drawer.icons.ProjectIconRequest
import ai.opencode.feature.drawer.icons.iconRequest
import ai.opencode.feature.drawer.style.DrawerFontWeight
import ai.opencode.feature.drawer.style.DrawerTextStyle
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeProject
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import kotlin.math.ceil
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The pieces of a drawer session row.

/** The drawer's row fonts. */
object DrawerListFonts {
    /**
     * A text style at a weight. Android's Bold Text (`Configuration.fontWeightAdjustment`) is
     * applied by Compose's font resolution, so it isn't added again here.
     */
    fun font(style: TextStyle, weight: FontWeight): TextStyle = style.copy(fontWeight = weight)

    /** A heading at a style: Headline is already semibold. */
    fun heading(style: DrawerTextStyle, weight: DrawerFontWeight): TextStyle {
        val resolved = if (weight == DrawerFontWeight.Semibold && style == DrawerTextStyle.Headline) FontWeight.SemiBold else weight.weight
        return font(style.font, resolved)
    }

    /** Tabular figures. */
    fun monospacedDigits(style: TextStyle): TextStyle = style.copy(fontFeatureSettings = "tnum")
}

/**
 * The Working ring: an open arc (72 % of the circle, 1.8 dp, round caps) with a fading tail, turned
 * once every 0.8 s in the draw phase (no recomposition or positional layer invalidation per frame).
 * Pass `isSpinning = running && !reduceMotion`: it stops under reduce motion and while the drawer
 * is hidden. [opaqueTail] (high contrast) draws the tail at full strength. Size it with [modifier].
 */
@Composable
fun DrawerListActivityRing(isSpinning: Boolean, opaqueTail: Boolean, modifier: Modifier = Modifier) {
    val color = OpenCodeTheme.colors.secondaryText
    val tail = if (opaqueTail) color else color.copy(alpha = 0f)
    // Half an ambient sweep per turn (`AmbientClock`, so every ring turns in step). Holds still
    // while the drawer is hidden or TalkBack explores by touch.
    val hidden = LocalDrawerHidden.current.value
    val spins = isSpinning && !hidden && !rememberTouchExplorationEnabled()
    val rotation = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(spins) {
        if (!spins) return@LaunchedEffect
        while (true) {
            withFrameNanos { rotation.floatValue = 360f * AmbientClock.sweep(AmbientClock.mediaTime(), fraction = 0.5).toFloat() }
        }
    }
    Canvas(modifier.clearAndSetSemantics {}) {
        val inset = 0.9.dp.toPx()
        val brush = Brush.sweepGradient(
            0f to tail,
            0.72f to color,
            0.78f to color,
            0.9f to tail,
            center = center,
        )
        rotate(degrees = if (spins) rotation.floatValue else 0f) {
            drawArc(
                brush = brush,
                startAngle = 0f,
                sweepAngle = 360f * 0.72f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/** Metrics of the project tile. */
object DrawerListProjectTile {
    /**
     * The tile's side at the current font scale: a little larger from XXXL. These are the content
     * size categories' body sizes over Large (17 pt): XXXL 23 (≈1.35), AX Medium 28 (≈1.65), AX
     * Extra Large 40 (≈2.35).
     */
    fun side(base: Dp, fontScale: Float): Dp = when {
        fontScale >= 40f / 17f -> base + 4.dp
        fontScale >= 28f / 17f -> base + 3.dp
        fontScale >= 23f / 17f -> base + 2.dp
        else -> base
    }
}

/**
 * The project's icon to show: the cached one at once, else the initial tile until loading brings
 * one. Loading runs in [ProjectIconClient]'s own scope; leaving composition stops observing, not
 * the shared fetch.
 */
@Composable
fun rememberProjectIcon(
    request: ProjectIconRequest?,
    client: ProjectIconClient = remember { dependency(ProjectIconClient) },
): ProjectIcon? {
    val key = request?.cacheKey
    val initial = remember(key, client) { request?.let { client.cached(it)?.icon } }
    val icons = remember(key, client) { request?.let(client::observe) }
    if (icons == null) return null
    val icon by icons.collectAsState(initial = initial)
    return icon
}

/**
 * The project tile leading a drawer row, loading its icon through [ProjectIconClient], cached
 * first. See the overload taking an icon.
 */
@Composable
fun DrawerListProjectTile(
    project: OpenCodeProject,
    server: OpenCodeServer,
    side: Dp,
    increasedContrast: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    client: ProjectIconClient = remember { dependency(ProjectIconClient) },
) {
    val request = remember(project, server) { project.iconRequest(server) }
    val icon = rememberProjectIcon(request, client)
    DrawerListProjectTile(
        project = project,
        icon = icon,
        side = side,
        increasedContrast = increasedContrast,
        reducedMotion = reducedMotion,
        modifier = modifier,
    )
}

/**
 * The [side]-square project tile leading a drawer row: the project's [icon] (full-bleed, or padded
 * on a neutral backing) or its initial on its palette colour, lit by [ProjectTileLighting] with a
 * soft contact shadow. High contrast text swaps the edge for a solid 1 dp palette border. An
 * arriving icon cross-fades in over 0.18 s (cut under reduce motion). Decorative: hidden from
 * TalkBack.
 */
@Composable
fun DrawerListProjectTile(
    project: OpenCodeProject,
    icon: ProjectIcon?,
    side: Dp,
    increasedContrast: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = OpenCodeTheme.colors.isDark
    val palette = remember(project.icon?.color, dark) { ProjectAvatarPalette.resolve(project.icon?.color, dark) }
    val k = side / 20.dp
    val radius = DrawerTunables.projectIconCornerRadius * k
    val shape = RoundedCornerShape(radius)
    // Shadows read less on black, so dark mode deepens them.
    val depth = DrawerTunables.projectIconShadow * (if (dark) 1.5f else 1f)
    Box(
        modifier
            .size(side)
            .projectTileShadow(radius, k, depth)
            .clip(shape)
            .clearAndSetSemantics {},
    ) {
        Crossfade(
            targetState = icon,
            // The initial and palette dissolve into the icon.
            animationSpec = MotionRole.Swap.animation(reduceMotion = reducedMotion),
            label = "project-tile",
        ) { shown ->
            if (shown != null) {
                val background = when (shown.backing) {
                    ProjectIcon.Backing.FullBleed -> palette.background
                    ProjectIcon.Backing.PaddedDark -> ProjectAvatarPalette.hex(0x161618)
                    ProjectIcon.Backing.PaddedLight -> if (dark) ProjectAvatarPalette.hex(0xf2f2f7) else ProjectAvatarPalette.hex(0xe5e5ea)
                }
                val inset = if (shown.backing == ProjectIcon.Backing.FullBleed) 0.dp else (side.value * 0.15f).roundToInt().dp
                Box(Modifier.fillMaxSize().background(background)) {
                    Image(
                        bitmap = shown.image,
                        contentDescription = null,
                        contentScale = if (shown.backing == ProjectIcon.Backing.FullBleed) ContentScale.Crop else ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(inset),
                    )
                    // Images carry their own detail, so they take a softer highlight.
                    ProjectTileLighting(radius, DrawerTunables.projectIconHighlight * 0.6f, if (increasedContrast) 0f else DrawerTunables.projectIconEdgeOpacity)
                }
            } else {
                Box(Modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.Center) {
                    TileInitial(project.initial, palette.foreground, side)
                    ProjectTileLighting(radius, DrawerTunables.projectIconHighlight, if (increasedContrast) 0f else DrawerTunables.projectIconEdgeOpacity)
                }
            }
        }
        if (increasedContrast) Canvas(Modifier.matchParentSize()) {
            val width = 1.dp.toPx()
            val r = max(radius.toPx() - width / 2, 0f)
            drawRoundRect(
                color = palette.border,
                topLeft = Offset(width / 2, width / 2),
                size = Size(size.width - width, size.height - width),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width),
            )
        }
    }
}

@Composable
private fun TileInitial(initial: String, color: Color, side: Dp) {
    val density = LocalDensity.current
    // A fixed size from the tile's side (which already follows the font scale).
    val fontSize = with(density) { (side.value * 0.55f).roundToInt().dp.toSp() }
    val shadowBlur = with(density) { 2.dp.toPx() }
    Text(
        text = initial,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        style = TextStyle(
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = "tnum",
            shadow = Shadow(color = Color.Black.copy(alpha = 0.2f), offset = Offset.Zero, blurRadius = shadowBlur),
        ),
    )
}

@Composable
private fun ProjectTileLighting(radius: Dp, highlight: Float, edgeOpacity: Float) {
    if (highlight <= 0f && edgeOpacity <= 0f) return
    val density = LocalDensity.current.density
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val key = ProjectTileLighting.Key(size.width / density, radius.value, density, highlight, edgeOpacity)
            val image = ProjectTileLighting.image(key)
            onDrawBehind {
                if (image != null) drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
            }
        },
    )
}

/**
 * The project tile's light, drawn once per size and look: white over the palette, so every tile
 * shares it. A highlight centred just above the top edge, wider than tall, and a 0.5 dp inner edge,
 * bright at the top and fading 85 % toward the bottom.
 */
object ProjectTileLighting {
    /** [side] and [radius] in dp; [scale] is pixels per dp. */
    data class Key(val side: Float, val radius: Float, val scale: Float, val highlight: Float, val edgeOpacity: Float)

    private val cache = HashMap<Key, ImageBitmap>()

    fun image(key: Key): ImageBitmap? {
        if (key.side <= 0f || (key.highlight <= 0f && key.edgeOpacity <= 0f)) return null
        synchronized(cache) { cache[key]?.let { return it } }
        val side = key.side
        val pixels = ceil(side * key.scale).toInt().coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(pixels, pixels, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.scale(key.scale, key.scale)
        val white = { alpha: Float -> android.graphics.Color.argb((alpha.coerceIn(0f, 1f) * 255).roundToInt(), 255, 255, 255) }
        canvas.clipPath(android.graphics.Path().apply { addRoundRect(0f, 0f, side, side, key.radius, key.radius, android.graphics.Path.Direction.CW) })
        val h = key.highlight
        if (h > 0f) {
            val rx = side * 1.3f
            val ry = side * 0.75f
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(
                    0f, 0f, rx, intArrayOf(white(h), white(h * 0.42f), white(0f)), floatArrayOf(0f, 0.42f, 0.72f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.save()
            canvas.translate(side / 2, -0.12f * side)
            canvas.scale(1f, ry / rx)
            canvas.drawCircle(0f, 0f, rx, paint)
            canvas.restore()
        }
        val a = key.edgeOpacity
        if (a > 0f) {
            val width = 0.5f
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = width
                shader = android.graphics.LinearGradient(0f, 0f, 0f, side, white(a), white(a * 0.15f), android.graphics.Shader.TileMode.CLAMP)
            }
            val r = max(key.radius - width / 2, 0f)
            canvas.drawRoundRect(width / 2, width / 2, side - width / 2, side - width / 2, r, r, paint)
        }
        val image = bitmap.asImageBitmap()
        synchronized(cache) {
            if (cache.size > 32) cache.clear()
            cache[key] = image
        }
        return image
    }
}

/**
 * The tile's contact shadow: 0.5 dp down with a 0.5 dp blur at 30 %, and 1.5 dp down with a 1.75 dp
 * blur at 14 %, both × [depth], scaled with the tile ([k]). Drawn behind the clipped surface, so it
 * spills past the tile's edge.
 */
private fun Modifier.projectTileShadow(radius: Dp, k: Float, depth: Float): Modifier = if (depth <= 0f) this else drawBehind {
    val r = radius.toPx()
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.TRANSPARENT }
        for ((dy, blur, opacity) in listOf(Triple(0.5f, 0.5f, 0.3f), Triple(1.5f, 1.75f, 0.14f))) {
            val alpha = (opacity * depth).coerceAtMost(1f)
            paint.setShadowLayer((blur * k).dp.toPx(), 0f, (dy * k).dp.toPx(), Color.Black.copy(alpha = alpha).toArgb())
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
        }
    }
}

/** Writing-direction helpers for [DrawerListTitleView]. */
object DrawerListTitle {
    /**
     * The direction of the title's first strongly directional character: Hebrew, Arabic and
     * the other right-to-left scripts, or any other letter. Null for a title with neither
     * (digits, symbols).
     */
    fun writingDirection(title: String): LayoutDirection? {
        var i = 0
        while (i < title.length) {
            val cp = title.codePointAt(i)
            when (cp) {
                in 0x0590..0x08FF, in 0xFB1D..0xFDFF, in 0xFE70..0xFEFF, in 0x10800..0x10FFF, in 0x1E800..0x1EFFF ->
                    return LayoutDirection.Rtl
                else -> if (Character.isAlphabetic(cp)) return LayoutDirection.Ltr
            }
            i += Character.charCount(cp)
        }
        return null
    }

    /** Whether the title reads right to left: by its own first strong character, else the layout's direction. */
    fun isRightToLeft(title: String, layoutRTL: Boolean): Boolean = when (writingDirection(title)) {
        LayoutDirection.Rtl -> true
        LayoutDirection.Ltr -> false
        null -> layoutRTL
    }

    /** The end of the row an overflowing title fades out at. */
    enum class ClearEnd { Left, Right }

    /**
     * Where a [naturalWidth]-wide title line sits in a [width]-wide row (its left x), and the end it
     * fades out at (`null` when it fits): an overflowing title keeps its own start in view whatever
     * the layout direction; a title that fits sits on the layout's leading edge.
     */
    fun placement(naturalWidth: Int, width: Int, overflows: Boolean, titleRTL: Boolean, layoutRTL: Boolean): Pair<Int, ClearEnd?> =
        if (overflows) {
            if (titleRTL) (width - naturalWidth) to ClearEnd.Left else 0 to ClearEnd.Right
        } else {
            (if (layoutRTL) width - naturalWidth else 0) to null
        }
}

/**
 * A session row's title: one line that, when it doesn't fit, keeps its own start in view whatever
 * the layout direction (an English title in a right-to-left layout still shows its first words) and
 * fades out over its last [fade] instead of an ellipsis (a mask, so it works on any highlight, the
 * selected row's included). The card-edge fade during the time reveal is the whole row's. Under
 * reduce motion the reveal cross-fades the title into the session's [time] instead;
 * [revealProgress] is read in the draw phase only, so a per-frame reveal never recomposes the row.
 */
@Composable
fun DrawerListTitleView(
    text: String,
    style: TextStyle,
    color: Color,
    fade: Dp,
    time: String,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    revealProgress: () -> Float = { 0f },
) {
    val layoutRTL = LocalLayoutDirection.current == LayoutDirection.Rtl
    val titleRTL = remember(text, layoutRTL) { DrawerListTitle.isRightToLeft(text, layoutRTL) }
    val timeStyle = remember { DrawerListFonts.monospacedDigits(DrawerListFonts.font(OpenCodeTextStyles.footnote, FontWeight.Normal)) }
    Box(modifier) {
        FadingTitle(
            text = text,
            style = style,
            color = color,
            fade = fade,
            titleRTL = titleRTL,
            layoutRTL = layoutRTL,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (reducedMotion) 1f - revealProgress().coerceIn(0f, 1f) else 1f },
        )
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterStart) {
            Text(
                text = time,
                style = timeStyle,
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.graphicsLayer { alpha = if (reducedMotion) revealProgress().coerceIn(0f, 1f) else 0f },
            )
        }
    }
}

/** The title measured at its natural width, placed by direction, and masked when it overflows. */
@Composable
private fun FadingTitle(
    text: String,
    style: TextStyle,
    color: Color,
    fade: Dp,
    titleRTL: Boolean,
    layoutRTL: Boolean,
    modifier: Modifier,
) {
    // Written in measure, read in draw: an overflow change only redraws.
    val overflows = remember { mutableStateOf(false) }
    Layout(
        content = { Text(text = text, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
        modifier = modifier
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (!overflows.value) return@drawWithContent
                val stop = (fade.toPx() / max(size.width, 1f)).coerceIn(0f, 1f)
                val brush = if (titleRTL) {
                    Brush.horizontalGradient(0f to Color.Transparent, stop to Color.Black, 1f to Color.Black)
                } else {
                    Brush.horizontalGradient(0f to Color.Black, (1f - stop) to Color.Black, 1f to Color.Transparent)
                }
                drawRect(brush = brush, blendMode = BlendMode.DstIn)
            },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(Constraints(maxHeight = constraints.maxHeight))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        val height = min(max(constraints.minHeight, placeable.height), constraints.maxHeight)
        val overflow = placeable.width > width + 0.5.dp.toPx()
        if (overflows.value != overflow) overflows.value = overflow
        layout(width, height) {
            val (x, _) = DrawerListTitle.placement(placeable.width, width, overflow, titleRTL, layoutRTL)
            placeable.place(x, (height - placeable.height) / 2)
        }
    }
}
