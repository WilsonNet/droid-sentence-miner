package io.github.wilsonnet.droidsentenceminer.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pre/post-processing math for meikiocr (https://github.com/rtr46/meikiocr), ported from its
 * reference Python pipeline. Its recognizer is a character *detector*: for a 960x32 line it
 * returns candidate characters (Unicode code points) with x-intervals and scores, which are
 * de-duplicated by overlap and read left to right.
 *
 * Pure Kotlin, unit tested.
 */
object MeikiDecoder {

    const val DET_WIDTH = 960
    const val DET_HEIGHT = 544
    const val REC_WIDTH = 960
    const val REC_HEIGHT = 32

    private const val OVERLAP_THRESHOLD = 0.3f

    // The model sometimes emits these kanji compounds in swapped order.
    private val SWAPPED_PAIRS = mapOf(
        "儡傀" to "傀儡", "談冗" to "冗談", "汰淘" to "淘汰", "沱滂" to "滂沱",
        "攣痙" to "痙攣", "酊酩" to "酩酊", "麭麺" to "麺麭", "哭慟" to "慟哭",
    )

    /** Detector letterbox: scale to fit 960x544 keeping aspect, top-left aligned, zero padded. */
    data class Letterbox(val scale: Float, val width: Int, val height: Int)

    fun letterbox(imageWidth: Int, imageHeight: Int): Letterbox {
        val scale = min(DET_WIDTH.toFloat() / imageWidth, DET_HEIGHT.toFloat() / imageHeight)
        return Letterbox(scale, (imageWidth * scale).toInt().coerceAtLeast(1), (imageHeight * scale).toInt().coerceAtLeast(1))
    }

    /** Empty rows kept below the content: variants cut tighter than this drift from the original. */
    private const val DET_MARGIN = 32

    /**
     * Detector input height for letterboxed content [contentHeight] rows tall: the smallest of
     * [variants] that leaves [DET_MARGIN] empty rows below it, else the original 544.
     */
    fun detectorHeight(contentHeight: Int, variants: Collection<Int>): Int =
        variants.filter { it >= contentHeight + DET_MARGIN && it < DET_HEIGHT }.minOrNull() ?: DET_HEIGHT

    /**
     * Input width for the any-width recognizer, from the widest line in the batch: with less than
     * 64 empty columns after the text it reads more differently from the 960-wide original.
     */
    fun recognizerWidth(widestLine: Int): Int =
        (32 * ((widestLine + 64 + 31) / 32)).coerceIn(128, REC_WIDTH)

    /** Recognizer input size for a line crop: height 32 keeping aspect, capped at width 960. */
    fun recSize(cropWidth: Int, cropHeight: Int): Pair<Int, Int> {
        var h = REC_HEIGHT
        var w = (cropWidth * (REC_HEIGHT.toFloat() / cropHeight)).roundToInt().coerceAtLeast(1)
        if (w > REC_WIDTH) {
            h = (h * (REC_WIDTH.toFloat() / w)).roundToInt().coerceAtLeast(1)
            w = REC_WIDTH
        }
        return w to h
    }

    data class Candidate(val char: String, val x1: Float, val x2: Float, val score: Float)

    /**
     * Turns raw recognizer outputs for one line into text. Boxes are in the 960x32 input space;
     * [effectiveWidth] is the resized content width (the rest is padding).
     */
    fun decodeLine(
        labels: IntArray,
        boxes: Array<FloatArray>,
        scores: FloatArray,
        effectiveWidth: Int,
        threshold: Float = 0.1f,
    ): List<Candidate> {
        val candidates = ArrayList<Candidate>()
        for (i in labels.indices) {
            if (scores[i] < threshold) continue
            val x1 = boxes[i][0]
            if (x1 >= effectiveWidth) continue
            val code = labels[i]
            if (code <= 0 || !Character.isValidCodePoint(code)) continue
            candidates += Candidate(
                String(Character.toChars(code)),
                min(x1, effectiveWidth.toFloat()),
                min(boxes[i][2], effectiveWidth.toFloat()),
                scores[i],
            )
        }
        return nms(candidates)
    }

    /** Keeps the most confident character per x-interval, then orders left to right. */
    fun nms(candidates: List<Candidate>): List<Candidate> {
        val accepted = ArrayList<Candidate>()
        for (c in candidates.sortedByDescending { it.score }) {
            val overlaps = accepted.any { a ->
                if (c.x1 >= a.x2 || a.x1 >= c.x2) return@any false
                val inter = max(0f, min(c.x2, a.x2) - max(c.x1, a.x1))
                val shorter = min(c.x2 - c.x1, a.x2 - a.x1) + 1e-6f
                inter / shorter > OVERLAP_THRESHOLD
            }
            if (!overlaps) accepted += c
        }
        return accepted.sortedBy { it.x1 }
    }

    fun fixSwappedPairs(text: String): String =
        SWAPPED_PAIRS.entries.fold(text) { acc, (wrong, right) -> acc.replace(wrong, right) }
}
