package ai.opencode.feature.drawer.style

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.motion.DrawerDepth
import ai.opencode.feature.drawer.motion.DrawerDim
import ai.opencode.feature.drawer.motion.DrawerMotionTunables
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Deleted (no Tuning Panel, presets or `TuningStore` registration on Android):
 * `topAndBottomEdgeTunablesAreIndependentUnderTheDrawerGroup`,
 * `presetsAreNumberedFromTodayAndValidForTheirKnobs`, `sidebarStylesStartFromTheShippedList`,
 * `applyingAPresetSetsItsKnobsAndTodayPutsThemBack`, `presetsListOnlyWhileTuningIsOn`,
 * `cardFeelsStayInRangeAndNativeRestoresOnlyTheirOwnControls`, `thePanelAppliesAPreset`.
 */
class TuningStoreTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun drawerMotionTunablesDefaultToNativeLiterals() {
        assertEquals(0.96f, DrawerMotionTunables.RECEDE_SCALE)
        assertEquals(0.7f, DrawerMotionTunables.DIM_DARK)
        assertEquals(0.6f, DrawerMotionTunables.DIM_LIGHT)
        assertEquals(1.3f, DrawerMotionTunables.DIM_POWER)
        assertTrue(abs(DrawerDepth.scale(progress = 0f, reducedMotion = false) - 0.96f) < 1e-6)
        assertTrue(abs(DrawerDepth.scale(progress = 1f, reducedMotion = false) - 1.0f) < 1e-6)
        assertTrue(abs(DrawerDim.maximum(isDark = true) - 0.7f) < 1e-6)
        assertTrue(abs(DrawerDim.maximum(isDark = false) - 0.6f) < 1e-6)
        assertTrue(abs(DrawerDim.alpha(progress = 0f, maximum = 0.7f) - 0.7f) < 1e-6)
        assertTrue(abs(DrawerDim.alpha(progress = 1f, maximum = 0.7f)) < 1e-6)
    }
}

class DrawerSpacingTests {
    @get:Rule val isolation = TestIsolationRule()

    /** What the drawer ships: Sidebar Grey spacing. */
    @Test
    fun defaultsAreWhatShipped() {
        // The drawer follows the OpenCode desktop sidebar (6 dp row radius, 14 sp subheadline rows)
        // and rows are 48 dp touch targets.
        val shipped: List<Triple<String, Dp, Float>> = listOf(
            Triple("drawer.row.height", DrawerTunables.rowHeight, 48f),
            Triple("drawer.row.gap", DrawerTunables.rowGap, 0f),
            Triple("drawer.row.edge", DrawerTunables.rowEdge, 10f),
            Triple("drawer.row.textInset", DrawerTunables.textInset, 10f),
            Triple("drawer.row.trailingInset", DrawerTunables.trailingInset, 12f),
            Triple("drawer.row.cornerRadius", DrawerTunables.rowCornerRadius, 6f),
            Triple("drawer.section.above", DrawerTunables.sectionAbove, 18f),
            Triple("drawer.section.height", DrawerTunables.sectionHeight, 34f),
            Triple("drawer.section.below", DrawerTunables.sectionBelow, 4f),
            Triple("drawer.shortcuts.top", DrawerTunables.shortcutsTop, 4f),
            Triple("drawer.shortcuts.gap", DrawerTunables.shortcutsGap, 0f),
            Triple("drawer.shortcuts.below", DrawerTunables.shortcutsBelow, 0f),
            Triple("drawer.header.bottom", DrawerTunables.headerBottom, 4f),
            Triple("drawer.header.searchGap", DrawerTunables.searchGap, 12f),
            Triple("drawer.footer.height", DrawerTunables.footerHeight, 50f),
            Triple("drawer.footer.padding", DrawerTunables.footerPadding, 18f),
            Triple("drawer.edge.topGap", DrawerTunables.topGap, 4f),
            Triple("drawer.edge.bottomGap", DrawerTunables.bottomGap, 12f),
            Triple("drawer.edge.fade", DrawerTunables.fade, 19f),
            Triple("drawer.edge.blurRadius", DrawerTunables.blurRadius, 2.5f),
            Triple("drawer.edge.bottom.fade", DrawerTunables.bottomFade, 14f),
            Triple("drawer.edge.bottom.blurRadius", DrawerTunables.bottomBlurRadius, 2.5f),
        )
        for ((key, tunable, value) in shipped) assertEquals(key, value.dp, tunable)
        assertEquals(0.84f, DrawerTunables.washStop)
        assertEquals(0.5f, DrawerTunables.controlsReach)
        assertEquals(0.65f, DrawerTunables.bottomWashStop)
        assertEquals(0.7f, DrawerTunables.washOpacity)
        assertEquals(0.75f, DrawerTunables.bottomWashOpacity)
        assertEquals(DrawerEdgeCurve.Smoothstep, DrawerTunables.blurCurve)
        assertEquals(DrawerEdgeCurve.Smoothstep, DrawerTunables.bottomBlurCurve)
        assertEquals(DrawerEdgeStyle.Soft, DrawerTunables.bottomEdge)
        assertEquals(DrawerBottomEdgeMode.System, DrawerTunables.bottomMode)
        assertEquals(DrawerTextStyle.Subheadline, DrawerTunables.rowFont)
        // Sidebar Grey: headline secondary headers and a title-size title, medium unread and
        // waiting rows, read rows at 86% and a 15 pt Catch Up count.
        assertEquals(DrawerTextStyle.Headline, DrawerTunables.sectionFont)
        assertEquals(DrawerTone.Secondary, DrawerTunables.sectionTone)
        assertEquals(DrawerFontWeight.Medium, DrawerTunables.unreadWeight)
        assertEquals(0.86f, DrawerTunables.readOpacity)
        assertEquals(DrawerTextStyle.Subheadline, DrawerTunables.countFont)
        assertEquals(DrawerTextStyle.Title, DrawerTunables.titleFont)
        assertEquals(DrawerTextStyle.Headline.font, DrawerTextStyle.Headline.heading)

        // The metrics are those values, the one leading edge among them.
        assertEquals(10.dp, DrawerMetrics.rowEdge)
        assertEquals(10.dp, DrawerMetrics.textInset)
        assertEquals(12.dp, DrawerMetrics.trailingInset)
        assertEquals(20.dp, DrawerMetrics.leadingEdge)
        assertEquals(6.dp, DrawerMetrics.rowCornerRadius)
        assertEquals(48.dp, DrawerMetrics.rowHeight)
    }

    @Test
    fun drawerRowsReadSelectedThenActiveThenRead() {
        // Unread, attention and running are indicators; none isn't.
        assertEquals(DrawerRowEmphasis.Selected, DrawerRowEmphasis.of(selected = true, hasIndicator = true))
        assertEquals(DrawerRowEmphasis.Active, DrawerRowEmphasis.of(selected = false, hasIndicator = true))
        assertEquals(DrawerRowEmphasis.Read, DrawerRowEmphasis.of(selected = false, hasIndicator = false))
        assertEquals(androidx.compose.ui.text.font.FontWeight.SemiBold, DrawerRowEmphasis.Selected.weight)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Medium, DrawerRowEmphasis.Active.weight)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, DrawerRowEmphasis.Read.weight)
        assertEquals(0.86f, DrawerRowEmphasis.Read.opacity(increasedContrast = false))
        // Increase Contrast keeps read rows at full strength.
        assertEquals(1f, DrawerRowEmphasis.Read.opacity(increasedContrast = true))
        assertEquals(1f, DrawerRowEmphasis.Active.opacity(increasedContrast = false))
    }

    @Test
    fun topAndBottomBackdropsCoverToTheScreenEdgesWithoutHardSteps() {
        for (curve in listOf(DrawerEdgeCurve.Smoothstep, DrawerEdgeCurve.Linear)) {
            val topStops = DrawerHeaderBackdrop.stops(
                background = Color.Black,
                safeStop = 62f / 141f,
                washStop = 122f * DrawerTunables.washStop / 141f,
                washOpacity = DrawerTunables.washOpacity,
                curve = curve,
            )
            assertEquals(0f, topStops.first().first)
            assertEquals(1f, topStops.last().first)
            assertEquals(topStops.map { it.first }.sorted(), topStops.map { it.first })
            val topAlphas = topStops.map { it.second.alpha }
            assertTrue("Solid through the status bar", abs(topAlphas.first() - 1) < 1e-6)
            assertTrue("Fades completely to 0 at the bottom of the top band", abs(topAlphas.last()) < 1e-6)
            for (index in 1 until topAlphas.size) {
                val step = topAlphas[index - 1] - topAlphas[index]
                assertTrue("Monotonically non-increasing", step >= -1e-6)
                assertTrue("Bounded step across top stops (step=$step)", step <= 0.35)
            }

            for (reach in listOf(DrawerTunables.controlsReach, 0.35f)) {
                val bar = 96f - 50f * reach
                val height = bar + DrawerTunables.bottomFade.value
                val safeStop = min(34f / height, 0.5f)
                val washStop = min(max(bar * DrawerTunables.bottomWashStop / height, safeStop), 1f)
                val bottomStops = DrawerFooterBackdrop.stops(
                    background = Color.Black, safeStop = safeStop, washStop = washStop,
                    washOpacity = DrawerTunables.bottomWashOpacity, curve = curve,
                )
                assertEquals(0f, bottomStops.first().first)
                assertEquals(1f, bottomStops.last().first)
                assertEquals(bottomStops.map { it.first }.sorted(), bottomStops.map { it.first })
                val bottomAlphas = bottomStops.map { it.second.alpha }
                assertTrue("Starts at 0 where the bottom edge begins", abs(bottomAlphas.first()) < 1e-6)
                assertTrue("Reaches solid 1.0 through the bottom safe area", abs(bottomAlphas.last() - 1) < 1e-6)
                for (index in 1 until bottomAlphas.size) {
                    val step = bottomAlphas[index] - bottomAlphas[index - 1]
                    assertTrue("Monotonically non-decreasing", step >= -1e-6)
                    assertTrue("No hard step between stops (step=$step)", step <= 0.16)
                }
            }

            val topBlur = DrawerEdgeBlur.strengths(fadeStart = 122f / 141f, curve = curve, edge = DrawerEdgeBlur.Edge.Top)
            assertEquals(33, topBlur.size)
            assertEquals(1f, topBlur.first())
            assertTrue(abs(topBlur.last()) < 1e-6)
            for (index in 1 until topBlur.size) assertTrue(topBlur[index - 1] >= topBlur[index] - 1e-6)

            val bottomBlur = DrawerEdgeBlur.strengths(fadeStart = 0.65f, curve = curve, edge = DrawerEdgeBlur.Edge.Bottom)
            assertEquals(33, bottomBlur.size)
            assertTrue(abs(bottomBlur.first()) < 1e-6)
            assertEquals(1f, bottomBlur.last())
            for (index in 1 until bottomBlur.size) assertTrue(bottomBlur[index] >= bottomBlur[index - 1] - 1e-6)
        }
    }
}
