package io.github.wilsonnet.droidsentenceminer.capture

import io.github.wilsonnet.droidsentenceminer.book.PageLayout
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.JapaneseTextScore
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine

/** A box as fractions of the screen. */
data class FracRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(o: FracRect) = FracRect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
}

/** One line of on-screen text with where each of its characters is drawn. */
data class ScreenTextLine(
    val text: String,
    val glyphs: List<FracRect>,
    /** Index of the auto OCR text area it was read from. */
    val area: Int,
) {
    val bounds: FracRect get() = glyphs.reduce(FracRect::union)
}

/**
 * The invisible text layer of the screen dictionary: OCR'd lines placed over the real text,
 * so tapping a character on screen finds it. Pure Kotlin, unit tested.
 */
object ScreenText {

    /**
     * [raw] are the engine's lines for a crop of [area] ([cropWidth]×[cropHeight] px); [kept] are
     * the rows the subtitle filter kept (crop px), so background signage gets no layer either.
     */
    fun fromRead(
        raw: List<OcrLine>,
        kept: List<BoundingBox>,
        cropWidth: Int,
        cropHeight: Int,
        area: TextArea,
        areaIndex: Int,
    ): List<ScreenTextLine> {
        if (cropWidth <= 0 || cropHeight <= 0) return emptyList()
        fun frac(b: BoundingBox) = FracRect(
            area.left + b.left.toFloat() / cropWidth * area.width,
            area.top + b.top.toFloat() / cropHeight * area.height,
            area.left + b.right.toFloat() / cropWidth * area.width,
            area.top + b.bottom.toFloat() / cropHeight * area.height,
        )
        return raw.mapNotNull { line ->
            val inKept = kept.any { k ->
                line.box.centerX >= k.left && line.box.centerX <= k.right && line.box.centerY >= k.top && line.box.centerY <= k.bottom
            }
            if (!inKept) return@mapNotNull null
            val text = PageLayout.clean(line.text)
            if (text.none { JapaneseTextScore.isJapanese(it) }) return@mapNotNull null
            val vertical = line.box.height > line.box.width * 1.3f && text.length >= 2
            ScreenTextLine(text, PageLayout.glyphsOf(line, text, vertical).map(::frac), areaIndex)
        }.sortedWith(compareBy({ it.area }, { it.bounds.top }, { it.bounds.left }))
    }

    /** The character at screen fraction ([x], [y]) as (line, index), within [slack] (fraction of width). */
    fun glyphAt(lines: List<ScreenTextLine>, x: Float, y: Float, slackX: Float = 0f, slackY: Float = 0f): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestDistance = Float.MAX_VALUE
        lines.forEachIndexed { l, line ->
            line.glyphs.forEachIndexed { i, g ->
                if (x < g.left - slackX || x > g.right + slackX || y < g.top - slackY || y > g.bottom + slackY) return@forEachIndexed
                val d = (g.centerX - x) * (g.centerX - x) + (g.centerY - y) * (g.centerY - y)
                if (d < bestDistance) {
                    bestDistance = d
                    best = l to i
                }
            }
        }
        return best
    }
}
