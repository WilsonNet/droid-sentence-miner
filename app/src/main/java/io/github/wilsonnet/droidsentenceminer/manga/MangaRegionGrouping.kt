package io.github.wilsonnet.droidsentenceminer.manga

import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import kotlin.math.max
import kotlin.math.min

/**
 * Groups OCR lines into speech bubbles with detected text regions (`BubbleDetector`):
 * 1. furigana lines are dropped (kana only, thin, right of / above a line with kanji),
 * 2. a line whose glyphs fall into two regions is cut there (a detector line crossing bubbles),
 * 3. each line joins the text region holding most of it (the smallest one on a tie),
 * 4. lines outside every region fall back to Manatan's geometric Union-Find merge.
 *
 * Every group comes back in reading order (columns right to left, rows top to bottom).
 * Prototype and measurements: `tools/manga-eval/group_regions.py`.
 */
object MangaRegionGrouping {
    private const val RUBY_RATIO = 0.68f
    private const val RUBY_BACK_GAP = 0.6f
    private const val RUBY_FRONT_GAP = 0.5f
    private const val SAME_COLUMN_OVERLAP = 0.5f
    private const val SINGLE_CHAR_VERTICAL = 0.8f

    /**
     * A detected text region.
     *
     * @property box region bounds in page pixels
     */
    data class TextRegion(val box: BoundingBox)

    /** Lines grouped per bubble, each in reading order. */
    fun group(
        lines: List<OcrLine>,
        regions: List<TextRegion>,
        pageWidth: Int,
        pageHeight: Int,
        language: LanguageModule,
    ): List<List<OcrLine>> {
        val nonEmpty = lines.filter { it.text.isNotBlank() }
        val kept =
            if (language.usesSpaces) {
                nonEmpty
            } else {
                nonEmpty.filterNot {
                    isFurigana(sub = it, lines = nonEmpty, language = language)
                }
            }
        val split = kept.flatMap { MangaRegionAssignment.splitByRegions(line = it, regions = regions) }
        val owned = LinkedHashMap<Int, MutableList<OcrLine>>()
        val free = ArrayList<OcrLine>()
        for (line in split) {
            val owner = MangaRegionAssignment.ownerOf(box = line.box, regions = regions)
            if (owner == null) free += line else owned.getOrPut(owner) { ArrayList() } += line
        }
        val groups = owned.values.map { readingOrder(it) }.toMutableList()
        if (free.isNotEmpty()) {
            val merged =
                MangaOcrGrouping.groupSpeechBubbles(
                    rawBlocks = free.map { listOf(it) },
                    usesSpaces = language.usesSpaces,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                    language = language,
                )
            groups += merged.map { readingOrder(it) }
        }
        return groups
    }

    /** True when [lines] mostly read top to bottom. */
    fun isVerticalGroup(lines: List<OcrLine>): Boolean = lines.count { isVerticalLine(it) } * 2 >= lines.size

    private fun isVerticalLine(line: OcrLine): Boolean {
        val box = line.box
        return if (line.text.trim().length <= 1) box.height > box.width * SINGLE_CHAR_VERTICAL else box.height > box.width
    }

    private fun thickness(box: BoundingBox, isVertical: Boolean): Int = if (isVertical) box.width else box.height

    private fun isFurigana(sub: OcrLine, lines: List<OcrLine>, language: LanguageModule): Boolean {
        val text = sub.text.trim()
        val isKanaOnly = text.isNotEmpty() && text.all { language.isRubyChar(it) }
        return isKanaOnly && lines.any { main -> main !== sub && isRubyOf(sub = sub, main = main, language = language) }
    }

    private fun isRubyOf(sub: OcrLine, main: OcrLine, language: LanguageModule): Boolean {
        val hasKanji = main.text.any { language.isTargetChar(it) && !language.isRubyChar(it) }
        val isMainVertical = isVerticalLine(main)
        val mainThickness = thickness(main.box, isMainVertical).toFloat()
        val isThin = thickness(sub.box, isMainVertical) <= mainThickness * RUBY_RATIO
        val gap: Int
        val span: Int
        if (isMainVertical) {
            gap = sub.box.left - main.box.right
            span = min(a = main.box.bottom, b = sub.box.bottom) - max(a = main.box.top, b = sub.box.top)
        } else {
            gap = main.box.top - sub.box.bottom
            span = min(a = main.box.right, b = sub.box.right) - max(a = main.box.left, b = sub.box.left)
        }
        val isBeside = gap > -mainThickness * RUBY_BACK_GAP && gap < mainThickness * RUBY_FRONT_GAP && span > 0
        return hasKanji && isThin && isBeside
    }

    /** Orders [lines]: columns (rows) by cross-axis overlap, right to left (top to bottom). */
    fun readingOrder(lines: List<OcrLine>): List<OcrLine> {
        val isVertical = isVerticalGroup(lines)
        val columns = buildColumns(lines = lines, isVertical = isVertical)
        val byCenter = columns.sortedBy { it.lo + it.hi }
        val ordered = if (isVertical) byCenter.reversed() else byCenter
        return ordered.flatMap { column ->
            column.lines.sortedBy { mainStart(box = it.box, isVertical = isVertical) }
        }
    }

    private fun mainStart(box: BoundingBox, isVertical: Boolean): Int = if (isVertical) box.top else box.left

    private fun buildColumns(lines: List<OcrLine>, isVertical: Boolean): List<Column> {
        val columns = ArrayList<Column>()
        val sorted = if (isVertical) lines.sortedByDescending { it.box.right } else lines.sortedBy { it.box.top }
        for (line in sorted) {
            val lo = if (isVertical) line.box.left else line.box.top
            val hi = if (isVertical) line.box.right else line.box.bottom
            val column = columns.firstOrNull { it.accepts(lo = lo, hi = hi) }
            if (column == null) {
                columns += Column(lo = lo, hi = hi, lines = mutableListOf(line))
            } else {
                column.add(line = line, lo = lo, hi = hi)
            }
        }
        return columns
    }

    // One column (vertical text) or row (horizontal text) of lines and its cross-axis range.
    private class Column(var lo: Int, var hi: Int, val lines: MutableList<OcrLine>) {
        fun accepts(lo: Int, hi: Int): Boolean {
            val shared = min(a = this.hi, b = hi) - max(a = this.lo, b = lo)
            return shared > SAME_COLUMN_OVERLAP * min(a = this.hi - this.lo, b = hi - lo)
        }

        fun add(line: OcrLine, lo: Int, hi: Int) {
            lines += line
            this.lo = min(a = this.lo, b = lo)
            this.hi = max(a = this.hi, b = hi)
        }
    }
}
