package io.github.wilsonnet.droidsentenceminer.ui.components

import android.graphics.Bitmap
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import kotlin.math.max
import kotlin.math.roundToInt

private const val FULL_EDGE_EPS = 0.01f
private const val DEFAULT_INSET_X = 0.10f
private const val DEFAULT_INSET_Y = 0.12f
private const val PANEL_PAD_X = 0.24f
private const val PANEL_PAD_Y = 0.16f
private const val BUBBLE_PAD_DIVISOR = 10f
private const val BUBBLE_PAD_PX = 16f

/** Normalized `[0f..1f]` crop rectangle over an image. */
data class NormalizedCropRect(
    /** Normalized left edge `[0f..1f]`. */
    val left: Float,
    /** Normalized top edge `[0f..1f]`. */
    val top: Float,
    /** Normalized right edge `[0f..1f]`. */
    val right: Float,
    /** Normalized bottom edge `[0f..1f]`. */
    val bottom: Float,
) {
    /** Normalized rectangle width. */
    val width: Float get() = right - left

    /** Normalized rectangle height. */
    val height: Float get() = bottom - top

    /** True when the crop spans effectively the entire image. */
    val isFullPage: Boolean
        get() = left <= FULL_EDGE_EPS && top <= FULL_EDGE_EPS && right >= 1f - FULL_EDGE_EPS && bottom >= 1f - FULL_EDGE_EPS
}

/** Pure geometry helpers for the panel cropper (unit-tested). */
object PanelCropMath {
    /** Minimum normalized width/height allowed for any crop selection. */
    const val MIN_FRACTION = 0.04f

    /** Computes a default manga-panel crop framed around [focusBox], or centered margins if null. */
    fun initialPanelRect(imgWidth: Int, imgHeight: Int, focusBox: BoundingBox?): NormalizedCropRect {
        if (focusBox == null || imgWidth <= 0 || imgHeight <= 0) {
            return NormalizedCropRect(
                left = DEFAULT_INSET_X,
                top = DEFAULT_INSET_Y,
                right = 1f - DEFAULT_INSET_X,
                bottom = 1f - DEFAULT_INSET_Y,
            )
        }
        val fl = (focusBox.left.toFloat() / imgWidth).coerceIn(minimumValue = 0f, maximumValue = 1f)
        val ft = (focusBox.top.toFloat() / imgHeight).coerceIn(minimumValue = 0f, maximumValue = 1f)
        val fr = (focusBox.right.toFloat() / imgWidth).coerceIn(minimumValue = fl, maximumValue = 1f)
        val fb = (focusBox.bottom.toFloat() / imgHeight).coerceIn(minimumValue = ft, maximumValue = 1f)
        return clamp(
            NormalizedCropRect(
                left = (fl - PANEL_PAD_X).coerceAtLeast(0f),
                top = (ft - PANEL_PAD_Y).coerceAtLeast(0f),
                right = (fr + PANEL_PAD_X).coerceAtMost(1f),
                bottom = (fb + PANEL_PAD_Y).coerceAtMost(1f),
            ),
        )
    }

    /** Computes a tight crop tightly framing [focusBox] (single speech bubble). */
    fun tightBubbleRect(imgWidth: Int, imgHeight: Int, focusBox: BoundingBox): NormalizedCropRect {
        if (imgWidth <= 0 || imgHeight <= 0) return NormalizedCropRect(left = 0f, top = 0f, right = 1f, bottom = 1f)
        val pad = max(a = focusBox.width, b = focusBox.height) / BUBBLE_PAD_DIVISOR + BUBBLE_PAD_PX
        return clamp(
            NormalizedCropRect(
                left = ((focusBox.left - pad) / imgWidth).coerceIn(minimumValue = 0f, maximumValue = 1f),
                top = ((focusBox.top - pad) / imgHeight).coerceIn(minimumValue = 0f, maximumValue = 1f),
                right = ((focusBox.right + pad) / imgWidth).coerceIn(minimumValue = 0f, maximumValue = 1f),
                bottom = ((focusBox.bottom + pad) / imgHeight).coerceIn(minimumValue = 0f, maximumValue = 1f),
            ),
        )
    }

    /** Clamps [rect] within `[0f..1f]` while preserving [MIN_FRACTION] dimensions. */
    fun clamp(rect: NormalizedCropRect): NormalizedCropRect {
        var l = rect.left.coerceIn(minimumValue = 0f, maximumValue = 1f - MIN_FRACTION)
        var t = rect.top.coerceIn(minimumValue = 0f, maximumValue = 1f - MIN_FRACTION)
        var r = rect.right.coerceIn(minimumValue = l + MIN_FRACTION, maximumValue = 1f)
        var b = rect.bottom.coerceIn(minimumValue = t + MIN_FRACTION, maximumValue = 1f)
        if (r - l < MIN_FRACTION) r = (l + MIN_FRACTION).coerceAtMost(1f)
        if (b - t < MIN_FRACTION) b = (t + MIN_FRACTION).coerceAtMost(1f)
        l = l.coerceAtMost(r - MIN_FRACTION)
        t = t.coerceAtMost(b - MIN_FRACTION)
        return NormalizedCropRect(left = l, top = t, right = r, bottom = b)
    }

    /** Converts normalized [rect] into integer image pixel bounds. */
    fun toPixelBox(rect: NormalizedCropRect, imgWidth: Int, imgHeight: Int): BoundingBox {
        val l = (rect.left * imgWidth).roundToInt().coerceIn(minimumValue = 0, maximumValue = (imgWidth - 1).coerceAtLeast(0))
        val t = (rect.top * imgHeight).roundToInt().coerceIn(minimumValue = 0, maximumValue = (imgHeight - 1).coerceAtLeast(0))
        val r = (rect.right * imgWidth).roundToInt().coerceIn(minimumValue = (l + 1).coerceAtMost(imgWidth), maximumValue = imgWidth)
        val b = (rect.bottom * imgHeight).roundToInt().coerceIn(minimumValue = (t + 1).coerceAtMost(imgHeight), maximumValue = imgHeight)
        return BoundingBox(left = l, top = t, right = r, bottom = b)
    }

    /** Crops [bitmap] to [rect], returning [bitmap] unchanged when [NormalizedCropRect.isFullPage]. */
    fun cropBitmap(bitmap: Bitmap, rect: NormalizedCropRect): Bitmap {
        if (rect.isFullPage) return bitmap
        val box = toPixelBox(rect = rect, imgWidth = bitmap.width, imgHeight = bitmap.height)
        val w = (box.right - box.left).coerceAtLeast(1)
        val h = (box.bottom - box.top).coerceAtLeast(1)
        return Bitmap.createBitmap(bitmap, box.left, box.top, w, h)
    }
}
