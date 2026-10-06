package io.github.wilsonnet.droidsentenceminer.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.roundToInt

/** Bitmap preparation tuned for small game/visual-novel fonts. */
object ImagePreprocessor {
    const val MAX_DIMENSION = 2600

    /**
     * Scales the bitmap so that small text becomes recognizable by ML Kit while
     * keeping the image below [MAX_DIMENSION] to avoid excessive memory use.
     */
    fun upscaleForOcr(bitmap: Bitmap, minWidth: Int = 1400): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        var scale = 1f
        if (width in 1 until minWidth) {
            scale = (minWidth.toFloat() / width).coerceAtMost(2.5f)
        }
        val targetW = (width * scale).roundToInt()
        val targetH = (height * scale).roundToInt()
        val maxDim = max(targetW, targetH)
        if (maxDim > MAX_DIMENSION) {
            val shrink = MAX_DIMENSION.toFloat() / maxDim
            scale *= shrink
        }
        if (scale <= 1.01f) return bitmap
        val outW = (width * scale).roundToInt().coerceAtLeast(1)
        val outH = (height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, outW, outH, true)
    }

    /** Optional grayscale + contrast boost. Kept subtle to avoid destroying anti-aliased kanji. */
    fun enhanceContrast(bitmap: Bitmap, contrast: Float = 1.35f): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val cm = ColorMatrix().apply { setSaturation(0.25f) }
        val translate = (-0.5f * contrast + 0.5f) * 255f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    fun crop(bitmap: Bitmap, box: BoundingBox, padding: Int = 0): Bitmap {
        val left = (box.left - padding).coerceIn(0, bitmap.width - 1)
        val top = (box.top - padding).coerceIn(0, bitmap.height - 1)
        val right = (box.right + padding).coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom + padding).coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }
}
