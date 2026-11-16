package io.github.wilsonnet.droidsentenceminer.ui.components

import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelCropMathTest {
    @Test
    fun framesPanelAroundFocusBubble() {
        val rect =
            PanelCropMath.initialPanelRect(
                imgWidth = 1000,
                imgHeight = 1500,
                focusBox = BoundingBox(left = 400, top = 600, right = 500, bottom = 900),
            )
        assertFalse(rect.isFullPage)
        assertTrue(rect.left < 0.4f && rect.right > 0.5f)
        assertTrue(rect.top < 0.4f && rect.bottom > 0.6f)
    }

    @Test
    fun tightBubbleFitsCloselyAroundFocus() {
        val focus = BoundingBox(left = 400, top = 600, right = 500, bottom = 900)
        val rect = PanelCropMath.tightBubbleRect(imgWidth = 1000, imgHeight = 1500, focusBox = focus)
        val px = PanelCropMath.toPixelBox(rect = rect, imgWidth = 1000, imgHeight = 1500)
        assertTrue(px.left <= focus.left && px.right >= focus.right)
        assertTrue(px.top <= focus.top && px.bottom >= focus.bottom)
        assertTrue(px.width < 250)
    }

    @Test
    fun clampsMinimumFractionAndPixelBounds() {
        val clamped =
            PanelCropMath.clamp(
                NormalizedCropRect(left = 0.99f, top = 0.99f, right = 0.995f, bottom = 0.995f),
            )
        assertTrue(clamped.width >= PanelCropMath.MIN_FRACTION - 1e-4f)
        assertTrue(clamped.height >= PanelCropMath.MIN_FRACTION - 1e-4f)
        assertTrue(clamped.right <= 1f && clamped.bottom <= 1f)

        val full = NormalizedCropRect(left = 0f, top = 0f, right = 1f, bottom = 1f)
        assertTrue(full.isFullPage)
        assertEquals(
            BoundingBox(left = 0, top = 0, right = 800, bottom = 1200),
            PanelCropMath.toPixelBox(rect = full, imgWidth = 800, imgHeight = 1200),
        )
    }
}
