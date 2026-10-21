package io.github.wilsonnet.droidsentenceminer.ui.components

import io.github.wilsonnet.droidsentenceminer.ui.components.PopupPlacement.Side
import org.junit.Assert.assertEquals
import org.junit.Test

class PopupPlacementTest {

    private val gap = 20f
    private val preferred = 700f // 260 dp at density 2.7
    private val width = 1130f

    @Test
    fun fullscreenLandscapeSubtitleFlipsAboveAndUsesTheRoom() {
        // 2340x1080 landscape, subtitle near the bottom: was capped at 45 % of 1080 px before.
        val plan = PopupPlacement.plan(531f, 940f, 1804f, 1050f, 2340f, 1080f, width, preferred, gap)
        assertEquals(Side.ABOVE, plan.side)
        assertEquals(900f, plan.maxHeight, 0.1f) // 940 - 2 * gap
        val (x, y) = PopupPlacement.position(plan.side, 531f, 940f, 1804f, 1050f, 2340f, 1080f, width, 880f, gap)
        assertEquals(1167.5f - width / 2f, x, 0.1f)
        assertEquals(40f, y, 0.1f) // 940 - 20 - 880
    }

    @Test
    fun portraitSubtitleNearTheTopGoesBelow() {
        val plan = PopupPlacement.plan(210f, 601f, 865f, 711f, 1080f, 2340f, width, preferred, gap)
        assertEquals(Side.BELOW, plan.side)
        assertEquals(2340f * 0.6f, plan.maxHeight, 0.1f) // room 1589 px, capped at 60 %
    }

    @Test
    fun shortScreenWithNoRoomAboveOrBelowGoesBeside() {
        // A word in the middle of a short landscape screen, at the left edge.
        val plan = PopupPlacement.plan(100f, 400f, 300f, 500f, 2340f, 1080f, width, preferred, gap)
        assertEquals(Side.RIGHT, plan.side)
        val (x, _) = PopupPlacement.position(plan.side, 100f, 400f, 300f, 500f, 2340f, 1080f, width, 600f, gap)
        assertEquals(320f, x, 0.1f)
    }
}
