package io.github.wilsonnet.droidsentenceminer.manga

import io.github.wilsonnet.droidsentenceminer.book.PageLayout
import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import kotlin.math.max
import kotlin.math.min

/**
 * Deduplicates overlapping detections, removes page numbers/noise/furigana (Manatan's
 * `filter_bad_boxes`), and clusters speech-bubble columns via tiered Union-Find merging
 * (`are_lines_mergeable` from Manatan `merge.rs`).
 */
internal object MangaOcrGrouping {
    private const val OVERLAP_DEDUP_RATIO = 0.65f
    private const val MIN_GHOST_AREA_RATIO = 3.0f
    private const val VERTICAL_ASPECT_RATIO = 1.2f
    private const val MIN_THICKNESS_PX = 10f
    private const val MAX_FONT_RATIO = 2.5f
    private const val HIGHLY_SIMILAR_FONT_RATIO = 1.25f
    private const val FONT_CONSISTENCY_GAP = 1.2f
    private const val FONT_CONSISTENCY_RATIO = 1.15f
    private const val TOUCHING_GAP_RATIO = 0.2f
    private const val RAW_MAIN_NEG_TOLERANCE = 0.5f
    private const val HIGH_OVERLAP_THRESH = 0.8f
    private const val MED_OVERLAP_THRESH = 0.4f
    private const val HIGH_OVERLAP_GAP = 2.0f
    private const val MED_OVERLAP_GAP = 0.95f
    private const val STAGGER_OVERLAP_GAP = 1.35f
    private const val DISSIMILAR_FONT_GAP = 0.8f
    private const val DISSIMILAR_OVERLAP_THRESH = 0.5f
    private const val SIDEBAR_LEN_RATIO = 2.5f
    private const val SIDEBAR_GAP_CAP = 0.8f
    private const val MAX_MAIN_GAP_RATIO = 0.6f
    private const val RUBY_MAX_THICKNESS_RATIO = 0.78f
    private const val RUBY_BACK_OVERLAP_RATIO = 0.5f
    private const val RUBY_PROXIMITY_RATIO = 0.6f
    private const val GIANT_SFX_AREA_FRAC = 0.30f
    private const val MIN_GIANT_SFX_CHARS = 6
    private const val TINY_NOISE_AREA_FRAC = 0.0004f
    private const val PAGE_NUM_TOP_FRAC = 0.08f
    private const val PAGE_NUM_BOTTOM_FRAC = 0.90f
    private const val MAX_PAGE_NUM_DIGITS = 3
    private val noiseRegex = Regex("""^[|—_ノヘく/\\:;!?.]+$""")

    private data class MeasuredLine(
        val line: OcrLine,
        val isVertical: Boolean,
        val fontSize: Float,
        val lengthMain: Float,
        val minMain: Float,
        val maxMain: Float,
        val minCross: Float,
        val maxCross: Float,
    )

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }

        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) root = parent[root]
            var cur = i
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
    }

    /** Filters noise/furigana and merges speech-bubble columns into unified raw blocks. */
    fun groupSpeechBubbles(
        rawBlocks: List<List<OcrLine>>,
        usesSpaces: Boolean,
        pageWidth: Int = 0,
        pageHeight: Int = 0,
        language: LanguageModule = Languages.current,
    ): List<List<OcrLine>> {
        val cleaned =
            filterBadBoxes(
                lines = rawBlocks.flatten(),
                usesSpaces = usesSpaces,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                language = language,
            )
        if (cleaned.isEmpty()) return emptyList()
        val rawBlockVerticalHint =
            if (rawBlocks.size > 1) {
                buildVerticalHintMap(rawBlocks = rawBlocks, usesSpaces = usesSpaces)
            } else {
                emptyMap()
            }
        return mergeWithUnionFind(
            lines = cleaned,
            usesSpaces = usesSpaces,
            verticalHint = rawBlockVerticalHint,
        )
    }

    private fun buildVerticalHintMap(rawBlocks: List<List<OcrLine>>, usesSpaces: Boolean): Map<OcrLine, Boolean> {
        val map = HashMap<OcrLine, Boolean>()
        if (usesSpaces) return map
        for (block in rawBlocks) {
            val isVert = inferVertical(lines = block, usesSpaces = false)
            for (line in block) {
                map[line] = isVert
            }
        }
        return map
    }

    private fun filterBadBoxes(
        lines: List<OcrLine>,
        usesSpaces: Boolean,
        pageWidth: Int,
        pageHeight: Int,
        language: LanguageModule,
    ): List<OcrLine> {
        val pageArea = pageWidth.toLong() * pageHeight.toLong()
        val candidates =
            lines.filterNot { line ->
                isNoiseOrPageNumber(
                    line = line,
                    usesSpaces = usesSpaces,
                    pageArea = pageArea,
                    pageHeight = pageHeight,
                    language = language,
                )
            }
        val kept = BooleanArray(candidates.size) { true }
        for (i in candidates.indices) {
            if (!kept[i]) continue
            for (j in candidates.indices) {
                if (i == j || !kept[j]) continue
                if (isGhostBox(dominant = candidates[i], candidate = candidates[j], usesSpaces = usesSpaces, language = language)) {
                    kept[j] = false
                } else if (isRubyBox(main = candidates[i], sub = candidates[j], usesSpaces = usesSpaces, language = language)) {
                    kept[j] = false
                }
            }
        }
        return candidates.filterIndexed { index, _ -> kept[index] }
    }

    private fun isNoiseOrPageNumber(
        line: OcrLine,
        usesSpaces: Boolean,
        pageArea: Long,
        pageHeight: Int,
        language: LanguageModule,
    ): Boolean {
        val trimmed = line.text.trim()
        val box = line.box
        val boxArea = box.width.toLong() * box.height.toLong()
        val isSingleSymbol = trimmed.length == 1 && (trimmed.matches(noiseRegex) || trimmed[0].isDigit())
        val isGiantSfx = pageArea > 0 && boxArea > pageArea * GIANT_SFX_AREA_FRAC && trimmed.length < MIN_GIANT_SFX_CHARS
        val hasTarget = trimmed.any { ch -> language.isTargetChar(ch) }
        val isTinyNoise = pageArea > 0 && boxArea < pageArea * TINY_NOISE_AREA_FRAC && (usesSpaces || !hasTarget)
        val isPageNum = isEdgePageNumber(trimmed = trimmed, top = box.top, bottom = box.bottom, pageHeight = pageHeight)
        return trimmed.isEmpty() || isSingleSymbol || isPageNum || isGiantSfx || isTinyNoise
    }

    private fun isEdgePageNumber(trimmed: String, top: Int, bottom: Int, pageHeight: Int): Boolean {
        val isDigits = pageHeight > 0 && trimmed.length in 1..MAX_PAGE_NUM_DIGITS && trimmed.all { it.isDigit() }
        val isAtMargin = top > pageHeight * PAGE_NUM_BOTTOM_FRAC || bottom < pageHeight * PAGE_NUM_TOP_FRAC
        return isDigits && isAtMargin
    }

    private fun isGhostBox(
        dominant: OcrLine,
        candidate: OcrLine,
        usesSpaces: Boolean,
        language: LanguageModule,
    ): Boolean {
        val a = dominant.box
        val b = candidate.box
        val ix = min(a = a.right, b = b.right) - max(a = a.left, b = b.left)
        val iy = min(a = a.bottom, b = b.bottom) - max(a = a.top, b = b.top)
        if (ix <= 0 || iy <= 0) return false
        val interArea = ix.toLong() * iy.toLong()
        val aArea = (a.width.toLong() * a.height.toLong()).coerceAtLeast(1L)
        val bArea = (b.width.toLong() * b.height.toLong()).coerceAtLeast(1L)
        val overlapOnB = interArea.toFloat() / bArea
        val isExactDup = overlapOnB >= OVERLAP_DEDUP_RATIO && dominant.text.trim().length >= candidate.text.trim().length
        val isCandidateTarget = candidate.text.any { ch -> language.isTargetChar(ch) }
        val isSupersetGhost =
            aArea > bArea * MIN_GHOST_AREA_RATIO &&
                overlapOnB > OVERLAP_DEDUP_RATIO &&
                (usesSpaces || dominant.text.contains(candidate.text) || !isCandidateTarget)
        return isExactDup || isSupersetGhost
    }

    private fun isRubyBox(main: OcrLine, sub: OcrLine, usesSpaces: Boolean, language: LanguageModule): Boolean {
        val subText = sub.text.trim()
        val mainText = main.text.trim()
        val isSubRubyCharsOnly =
            !usesSpaces &&
                subText.isNotEmpty() &&
                subText.all { ch -> language.isRubyChar(ch) || !language.isTargetChar(ch) }
        val isMainHasNonRubyTarget = mainText.any { ch -> language.isTargetChar(ch) && !language.isRubyChar(ch) }
        val mainThick = min(a = main.box.width, b = main.box.height).toFloat().coerceAtLeast(1f)
        val subThick = min(a = sub.box.width, b = sub.box.height).toFloat()
        val minProx = -mainThick * RUBY_BACK_OVERLAP_RATIO
        val maxProx = mainThick * RUBY_PROXIMITY_RATIO
        val gapV = sub.box.left - main.box.right
        val overlapV = min(a = main.box.bottom, b = sub.box.bottom) - max(a = main.box.top, b = sub.box.top)
        val gapH = main.box.top - sub.box.bottom
        val overlapH = min(a = main.box.right, b = sub.box.right) - max(a = main.box.left, b = sub.box.left)
        val isAdjacentV = gapV > minProx && gapV < maxProx && overlapV > 0
        val isAdjacentH = gapH > minProx && gapH < maxProx && overlapH > 0
        return isSubRubyCharsOnly &&
            isMainHasNonRubyTarget &&
            subThick <= mainThick * RUBY_MAX_THICKNESS_RATIO &&
            (isAdjacentV || isAdjacentH)
    }

    private fun mergeWithUnionFind(
        lines: List<OcrLine>,
        usesSpaces: Boolean,
        verticalHint: Map<OcrLine, Boolean>,
    ): List<List<OcrLine>> {
        val measured =
            lines.map { line ->
                val isVert = verticalHint[line] ?: inferVertical(lines = listOf(line), usesSpaces = usesSpaces)
                val box = line.box
                if (isVert) {
                    MeasuredLine(
                        line = line,
                        isVertical = true,
                        fontSize = box.width.toFloat().coerceAtLeast(MIN_THICKNESS_PX),
                        lengthMain = (box.bottom - box.top).toFloat().coerceAtLeast(1f),
                        minMain = box.top.toFloat(),
                        maxMain = box.bottom.toFloat(),
                        minCross = box.left.toFloat(),
                        maxCross = box.right.toFloat(),
                    )
                } else {
                    MeasuredLine(
                        line = line,
                        isVertical = false,
                        fontSize = box.height.toFloat().coerceAtLeast(MIN_THICKNESS_PX),
                        lengthMain = (box.right - box.left).toFloat().coerceAtLeast(1f),
                        minMain = box.left.toFloat(),
                        maxMain = box.right.toFloat(),
                        minCross = box.top.toFloat(),
                        maxCross = box.bottom.toFloat(),
                    )
                }
            }
        val uf = UnionFind(measured.size)
        for (i in measured.indices) {
            for (j in i + 1 until measured.size) {
                if (areLinesMergeable(a = measured[i], b = measured[j])) {
                    uf.union(a = i, b = j)
                }
            }
        }
        val grouped = LinkedHashMap<Int, MutableList<OcrLine>>()
        for (i in measured.indices) {
            grouped.getOrPut(uf.find(i)) { ArrayList() } += measured[i].line
        }
        return grouped.values.toList()
    }

    private fun areLinesMergeable(a: MeasuredLine, b: MeasuredLine): Boolean {
        val minFont = min(a = a.fontSize, b = b.fontSize)
        val maxFont = max(a = a.fontSize, b = b.fontSize)
        val fontRatio = maxFont / minFont
        val rawOverlapMain = min(a = a.maxMain, b = b.maxMain) - max(a = a.minMain, b = b.minMain)
        val gapCross = max(a = 0f, b = max(a = b.minCross - a.maxCross, b = a.minCross - b.maxCross))
        val overlapMain = max(a = 0f, b = rawOverlapMain)
        val globalOverlap = overlapMain / max(a = a.lengthMain, b = b.lengthMain)
        val allowedGap = allowedCrossGapRatio(a = a, b = b, fontRatio = fontRatio, globalOverlap = globalOverlap)
        val gapMain = max(a = 0f, b = max(a = b.minMain - a.maxMain, b = a.minMain - b.maxMain))
        val isTouching = gapCross < minFont * TOUCHING_GAP_RATIO
        val isBasicCompatible =
            a.isVertical == b.isVertical &&
                fontRatio <= MAX_FONT_RATIO &&
                rawOverlapMain >= -minFont * RAW_MAIN_NEG_TOLERANCE
        val isWithinGap =
            gapCross <= minFont * allowedGap &&
                !(gapCross > minFont * FONT_CONSISTENCY_GAP && fontRatio > FONT_CONSISTENCY_RATIO) &&
                (overlapMain > 0f || gapMain <= minFont * MAX_MAIN_GAP_RATIO)
        return isBasicCompatible && (isTouching || isWithinGap)
    }

    private fun allowedCrossGapRatio(a: MeasuredLine, b: MeasuredLine, fontRatio: Float, globalOverlap: Float): Float {
        var allowed =
            when {
                fontRatio < HIGHLY_SIMILAR_FONT_RATIO && globalOverlap > HIGH_OVERLAP_THRESH -> HIGH_OVERLAP_GAP
                fontRatio < HIGHLY_SIMILAR_FONT_RATIO && globalOverlap > MED_OVERLAP_THRESH -> MED_OVERLAP_GAP
                fontRatio < HIGHLY_SIMILAR_FONT_RATIO -> STAGGER_OVERLAP_GAP
                globalOverlap > DISSIMILAR_OVERLAP_THRESH -> DISSIMILAR_FONT_GAP
                else -> 0f
            }
        val lenRatio = max(a = a.lengthMain, b = b.lengthMain) / min(a = a.lengthMain, b = b.lengthMain)
        if (lenRatio > SIDEBAR_LEN_RATIO) {
            allowed = min(a = allowed, b = SIDEBAR_GAP_CAP)
        }
        return allowed
    }

    private fun inferVertical(lines: List<OcrLine>, usesSpaces: Boolean): Boolean {
        val multiChar = lines.filter { it.text.length >= 2 }
        return when {
            usesSpaces -> {
                false
            }

            multiChar.isNotEmpty() -> {
                PageLayout.isVertical(lines)
            }

            else -> {
                val box = lines.map { it.box }.reduce(BoundingBox::union)
                box.height > box.width * VERTICAL_ASPECT_RATIO
            }
        }
    }
}
