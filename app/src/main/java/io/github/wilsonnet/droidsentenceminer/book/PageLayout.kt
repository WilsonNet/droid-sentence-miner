package io.github.wilsonnet.droidsentenceminer.book

import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.JapaneseTextScore
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import io.github.wilsonnet.droidsentenceminer.ocr.OcrPostProcessor
import io.github.wilsonnet.droidsentenceminer.ocr.OcrTextFixes

/** One paragraph of a photographed page, text already in reading order. */
data class PageBlock(
    val text: String,
    val box: BoundingBox,
    val vertical: Boolean,
)

/**
 * Turns OCR text blocks from a photo of a printed page into readable paragraphs. Unlike the
 * screen pipeline ([OcrPostProcessor.merge], which joins horizontal rows), this keeps the
 * engine's blocks and understands vertical writing (tategaki): columns are read right to left.
 */
object PageLayout {

    /** [blocks] is the engine's grouping: each inner list is one block's lines. */
    fun build(blocks: List<List<OcrLine>>): List<PageBlock> {
        val all = blocks.flatten()
        if (all.isEmpty()) return emptyList()
        val vertical = isVertical(all)
        val thickness = all.filter { it.text.length >= 2 }.map { thicknessOf(it.box, vertical) }.sorted()
        val medianThickness = thickness.getOrNull(thickness.size / 2) ?: 0

        val built = blocks.mapNotNull { block ->
            val lines = block.filterNot { isFurigana(it, vertical, medianThickness) }
            if (lines.isEmpty()) return@mapNotNull null
            val ordered = if (vertical) {
                lines.sortedByDescending { it.box.centerX }
            } else {
                lines.sortedWith(compareBy({ it.box.top }, { it.box.left }))
            }
            val text = OcrTextFixes.apply(ordered.joinToString("") { OcrPostProcessor.normalize(it.text) })
            // Page numbers, running heads in Latin script, etc.
            if (text.none { JapaneseTextScore.isJapanese(it) }) return@mapNotNull null
            PageBlock(text, ordered.map { it.box }.reduce { a, b -> a.union(b) }, vertical)
        }
        val ordered = if (vertical) {
            built.sortedWith(compareByDescending<PageBlock> { it.box.right }.thenBy { it.box.top })
        } else {
            built.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        }
        return joinContinuations(ordered, vertical, medianThickness)
    }

    /**
     * Engines start a new block at wide line spacing, cutting sentences in half. A block that
     * stops mid-sentence absorbs the next one when it is the adjacent line/column, same margin.
     */
    private fun joinContinuations(blocks: List<PageBlock>, vertical: Boolean, thickness: Int): List<PageBlock> {
        if (thickness <= 0) return blocks
        val out = mutableListOf<PageBlock>()
        for (block in blocks) {
            val prev = out.lastOrNull()
            if (prev != null && prev.text.last() !in TERMINATORS + CLOSERS && continues(prev.box, block.box, vertical, thickness)) {
                out[out.lastIndex] = PageBlock(prev.text + block.text, prev.box.union(block.box), vertical)
            } else {
                out += block
            }
        }
        return out
    }

    private fun continues(a: BoundingBox, b: BoundingBox, vertical: Boolean, thickness: Int): Boolean {
        val gap = if (vertical) a.left - b.right else b.top - a.bottom
        val marginShift = if (vertical) kotlin.math.abs(a.top - b.top) else kotlin.math.abs(a.left - b.left)
        return gap in -thickness / 2..thickness * 2 && marginShift <= thickness * 2
    }

    /** Most multi-character lines taller than wide ⇒ the page is written vertically. */
    fun isVertical(lines: List<OcrLine>): Boolean {
        val sized = lines.filter { it.text.length >= 2 }
        if (sized.isEmpty()) return false
        return sized.count { it.box.height > it.box.width * 1.3f } * 2 > sized.size
    }

    private fun thicknessOf(box: BoundingBox, vertical: Boolean): Int = if (vertical) box.width else box.height

    /** Ruby read as its own line: short, kana only and much thinner than body text. */
    private fun isFurigana(line: OcrLine, vertical: Boolean, medianThickness: Int): Boolean {
        if (medianThickness <= 0 || line.text.length > 8) return false
        val kanaOnly = line.text.all { it in '぀'..'ヿ' }
        return kanaOnly && thicknessOf(line.box, vertical) < medianThickness * 0.6f
    }

    private const val TERMINATORS = "。！？!?\n"
    private const val CLOSERS = "」』）)】〕"

    /**
     * The sentence of [text] that contains [index]: from after the previous 。！？ to the next one,
     * keeping closing quotes that follow it (「…ね。」).
     */
    fun sentenceAt(text: String, index: Int): String {
        if (text.isEmpty()) return ""
        val i = index.coerceIn(0, text.length - 1)
        var start = i
        while (start > 0 && !isEnd(text, start - 1)) start--
        var end = i
        while (end < text.length && text[end] !in TERMINATORS) end++
        if (end < text.length) end++
        while (end < text.length && text[end] in CLOSERS) end++
        return text.substring(start, end).trim()
    }

    /** A terminator, or a closer right after one, ends a sentence. */
    private fun isEnd(text: String, at: Int): Boolean {
        val c = text[at]
        if (c in TERMINATORS) return at + 1 >= text.length || text[at + 1] !in CLOSERS
        if (c in CLOSERS) {
            var k = at
            while (k > 0 && text[k] in CLOSERS) k--
            return text[k] in TERMINATORS && (at + 1 >= text.length || text[at + 1] !in CLOSERS)
        }
        return false
    }
}
