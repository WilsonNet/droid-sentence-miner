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

/** Scores Japanese OCR output; higher is better. Used by the two-pass refinement. */
object JapaneseTextScore {
    private const val REPLACEMENT = '\uFFFD'

    fun score(text: String): Double {
        if (text.isBlank()) return 0.0
        var japanese = 0
        var good = 0
        var bad = 0
        for (ch in text) {
            when {
                ch == REPLACEMENT -> bad += 4
                ch == '\u3000' -> bad += 1
                ch.isWhitespace() -> Unit
                ch.isLetterOrDigit() -> {
                    good++
                    if (isJapanese(ch)) japanese++
                }
                else -> good++
            }
        }
        if (good == 0) return 0.0
        val japaneseRatio = japanese.toDouble() / good
        val penalty = bad.toDouble() / (good + bad).coerceAtLeast(1)
        return japaneseRatio * 10.0 + good * 0.01 - penalty * 20.0
    }

    fun isJapanese(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x3040..0x30FF) || // kana
            (code in 0x4E00..0x9FFF) || // CJK unified
            (code in 0x3400..0x4DBF) || // CJK ext A
            (code in 0xF900..0xFAFF) || // compatibility ideographs
            ch == '々' || ch == '〆' || ch == 'ー'
    }
}
