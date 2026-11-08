package io.github.wilsonnet.droidsentenceminer.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/** Platform-independent bounding box (keeps the OCR pipeline unit-testable). */
data class BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerY: Float get() = (top + bottom) / 2f
    val centerX: Float get() = (left + right) / 2f

    fun union(other: BoundingBox): BoundingBox = BoundingBox(
        min(left, other.left),
        min(top, other.top),
        max(right, other.right),
        max(bottom, other.bottom),
    )

    fun expand(padding: Int): BoundingBox = BoundingBox(
        left - padding,
        top - padding,
        right + padding,
        bottom + padding,
    )

    fun toRect(): Rect = Rect(left, top, right, bottom)

    companion object {
        fun from(rect: Rect): BoundingBox = BoundingBox(rect.left, rect.top, rect.right, rect.bottom)
    }
}

data class OcrLine(
    val text: String,
    val box: BoundingBox,
    val confidence: Float? = null,
    val angle: Float? = null,
    /** Per-character boxes when the engine reports them (ML Kit symbols). */
    val chars: List<BoundingBox>? = null,
)

data class OcrResult(
    val text: String,
    val lines: List<OcrLine>,
    val elapsedMs: Long = 0,
)

interface OcrEngine {
    val name: String
    suspend fun recognize(bitmap: Bitmap): OcrResult
}
