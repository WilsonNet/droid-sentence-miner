package io.github.wilsonnet.droidsentenceminer.manga

import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One text bubble or block on a manga page, in reading order with per-character hit-test boxes.
 * Produced either by [MokuroParser] (from a `.mokuro` file) or by [MangaOcrEngine] (on-device OCR).
 */
@Serializable
data class MangaBlock(
    /** Joined reading-order text inside this bubble. */
    val text: String,
    /** Outer bounding rectangle in page pixel coordinates. */
    val box: BoundingBoxDto,
    /** True when lines inside the block read top-to-bottom, right-to-left. */
    @SerialName("vertical") val isVertical: Boolean,
    /** Approximate glyph size in page pixels, used by readable-box overlays. */
    val fontSize: Float = 0f,
    /** Individual lines in reading order. */
    val lines: List<String> = emptyList(),
    /** One box per character of [text] in page pixel coordinates. */
    val glyphs: List<BoundingBoxDto> = emptyList(),
) {
    /** Converts [box] to an OCR [BoundingBox]. */
    val boundingBox: BoundingBox
        get() = BoundingBox(left = box.left, top = box.top, right = box.right, bottom = box.bottom)
}

/** Returns the index of the character at ([x], [y]) in page pixels within [slack] pixels, or null. */
fun MangaBlock.glyphAt(x: Float, y: Float, slack: Float = 0f): Int? {
    val candidates =
        glyphs.indices.filter { i ->
            val g = glyphs[i]
            x >= g.left - slack && x <= g.right + slack && y >= g.top - slack && y <= g.bottom + slack
        }
    return candidates.minByOrNull { i ->
        val g = glyphs[i]
        val cx = (g.left + g.right) / 2f
        val cy = (g.top + g.bottom) / 2f
        (cx - x) * (cx - x) + (cy - y) * (cy - y)
    }
}

/** Returns the index of the glyph inside this block closest to ([x], [y]), or null if empty. */
fun MangaBlock.nearestGlyph(x: Float, y: Float): Int? {
    val idx =
        glyphs.indices.minByOrNull { i ->
            val g = glyphs[i]
            val cx = (g.left + g.right) / 2f
            val cy = (g.top + g.bottom) / 2f
            (cx - x) * (cx - x) + (cy - y) * (cy - y)
        }
    return idx ?: if (text.isNotEmpty()) 0 else null
}

/** Returns a copy with [newText], remapping characters proportionally onto existing glyph boxes. */
fun MangaBlock.withText(newText: String): MangaBlock =
    if (glyphs.isEmpty() || newText.isEmpty() || newText.length == glyphs.size) {
        copy(text = newText, lines = listOf(newText))
    } else {
        copy(
            text = newText,
            lines = listOf(newText),
            glyphs = List(newText.length) { glyphs[it * glyphs.size / newText.length] },
        )
    }

/** Serializable mirror of [BoundingBox] so OCR blocks persist cleanly in `manga.db`. */
@Serializable
data class BoundingBoxDto(
    /** Left edge in page pixels. */
    val left: Int,
    /** Top edge in page pixels. */
    val top: Int,
    /** Right edge in page pixels. */
    val right: Int,
    /** Bottom edge in page pixels. */
    val bottom: Int,
) {
    /** Pixel width of the rectangle. */
    val width: Int get() = right - left

    /** Pixel height of the rectangle. */
    val height: Int get() = bottom - top

    /** Converts back to [BoundingBox]. */
    fun toBoundingBox(): BoundingBox = BoundingBox(left = left, top = top, right = right, bottom = bottom)

    /** Helpers for building [BoundingBoxDto] instances. */
    companion object {
        /** Creates a [BoundingBoxDto] from an OCR [BoundingBox]. */
        fun from(box: BoundingBox): BoundingBoxDto =
            BoundingBoxDto(left = box.left, top = box.top, right = box.right, bottom = box.bottom)
    }
}

/** Cached OCR / Mokuro blocks for a single manga page. */
data class MangaPageOcr(
    /** Source image width matching block coordinates. */
    val imgWidth: Int,
    /** Source image height matching block coordinates. */
    val imgHeight: Int,
    /** Recognized text blocks on the page. */
    val blocks: List<MangaBlock>,
    /** Origin of [blocks] (`mokuro`, `ocr`, or `edited`). */
    val source: String,
) {
    /** Known [source] identifiers. */
    companion object {
        /** Parsed from a `.mokuro` JSON file. */
        const val SOURCE_MOKURO = "mokuro"

        /** Recognized via Google Lens (`ChromeLensOcr`), matching Manatan. */
        const val SOURCE_LENS = "lens"

        /** Generated on-device by ML Kit / PaddleOCR. */
        const val SOURCE_OCR = "ocr"

        /** Manually edited by the user inside the reader. */
        const val SOURCE_EDITED = "edited"
    }
}

/** Preferred OCR backend for manga `.cbz` pages without a `.mokuro` file. */
enum class MangaOcrMode(
    /** Persistent preference identifier. */
    val id: String,
    /** Human-readable menu label. */
    val label: String,
) {
    /** Uses Google Lens when online and seamlessly falls back to on-device OCR offline. */
    AUTO("auto", "Auto (Lens + offline)"),

    /** Always requests Google Lens (requires internet connection). */
    LENS("lens", "Google Lens"),

    /** Always runs on-device OCR offline (no network requests). */
    MLKIT("mlkit", "On-device (Offline)"),
    ;

    /** Lookup helpers. */
    companion object {
        /** Resolves [id] or defaults to [AUTO]. */
        fun fromId(id: String?): MangaOcrMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** Viewer layout mode (mirrors Manatan's ReadingMode). */
enum class MangaReadingMode(
    /** Persistent preference identifier. */
    val id: String,
    /** Human-readable menu label. */
    val label: String,
) {
    /** One full page at a time. */
    SINGLE_PAGE("single", "Single page"),

    /** Two-page spread on wide viewports. */
    DOUBLE_PAGE("double", "Double page"),

    /** Continuous vertical webtoon/scroll strip. */
    VERTICAL_SCROLL("vertical", "Vertical scroll"),
    ;

    /** Lookup helpers. */
    companion object {
        /** Resolves [id] or defaults to [SINGLE_PAGE]. */
        fun fromId(id: String?): MangaReadingMode = entries.firstOrNull { it.id == id } ?: SINGLE_PAGE
    }
}

/** Page turning and spread ordering direction. */
enum class MangaDirection(
    /** Persistent preference identifier. */
    val id: String,
    /** Human-readable menu label. */
    val label: String,
) {
    /** Standard Japanese manga right-to-left order. */
    RTL("rtl", "Right to Left (Manga)"),

    /** Western left-to-right order. */
    LTR("ltr", "Left to Right"),
    ;

    /** True when navigation and double spreads advance right-to-left. */
    val isRtl: Boolean get() = this == RTL

    /** Lookup helpers. */
    companion object {
        /** Resolves [id] or defaults to [RTL]. */
        fun fromId(id: String?): MangaDirection = entries.firstOrNull { it.id == id } ?: RTL
    }
}

/** How OCR / Mokuro text bubbles render above manga artwork. */
enum class MangaOverlayStyle(
    /** Persistent preference identifier. */
    val id: String,
    /** Human-readable menu label. */
    val label: String,
) {
    /** Invisible hit boxes directly over the artwork text. */
    TRANSPARENT("transparent", "Transparent"),

    /** White crisp-text boxes matching Manatan's readable overlay. */
    LIGHT_BOX("light_box", "Readable (Light)"),

    /** High-contrast dark boxes matching Manatan's dark readable theme. */
    DARK_BOX("dark_box", "Readable (Dark)"),

    /** No interactive text overlay rendered. */
    HIDDEN("hidden", "Hidden"),
    ;

    /** Lookup helpers. */
    companion object {
        /** Resolves [id] or defaults to [TRANSPARENT]. */
        fun fromId(id: String?): MangaOverlayStyle = entries.firstOrNull { it.id == id } ?: TRANSPARENT
    }
}

/** Screen tap-zone behavior for turning pages without swiping. */
enum class MangaTapZone(
    /** Persistent preference identifier. */
    val id: String,
    /** Human-readable menu label. */
    val label: String,
) {
    /** Left/right edges turn pages; center toggles chrome. */
    EDGE("edge", "Edges (Prev / Menu / Next)"),

    /** Top-left/right corners backtrack; lower edges advance. */
    L_SHAPE("l_shape", "L-Shape"),

    /** Disable tap page turns (swipe or slider only). */
    OFF("off", "Off (swipe only)"),
    ;

    /** Lookup helpers. */
    companion object {
        /** Resolves [id] or defaults to [EDGE]. */
        fun fromId(id: String?): MangaTapZone = entries.firstOrNull { it.id == id } ?: EDGE
    }
}

/** A single page or 2-page spread slot in paged reader modes. */
data class MangaSpreadSlot(
    /** Zero-based page index displayed on the left side, or null when empty. */
    val leftPage: Int?,
    /** Zero-based page index displayed on the right side, or null when empty. */
    val rightPage: Int?,
) {
    /** Primary page index for progress tracking / bookmarking. */
    val primaryPage: Int get() = rightPage ?: leftPage ?: 0
}

/** Returns displayed page indices in visual reading order for [rtl]. */
fun MangaSpreadSlot.pagesInReadingOrder(rtl: Boolean): List<Int> =
    if (rtl) listOfNotNull(rightPage, leftPage) else listOfNotNull(leftPage, rightPage)

/** Computes spread slots for [pageCount] pages respecting `doublePage`, `coverOffset`, and `rtl`. */
object MangaSpreadLayout {
    /** Builds all page/spread slots across a volume of [pageCount] pages. */
    fun buildSlots(pageCount: Int, doublePage: Boolean, coverOffset: Boolean, rtl: Boolean): List<MangaSpreadSlot> =
        when {
            pageCount <= 0 -> emptyList()
            !doublePage -> List(pageCount) { page -> soloSlot(page, rtl) }
            else -> buildDoubleSlots(pageCount, coverOffset, rtl)
        }

    /** Finds the slot index containing [pageIndex], falling back to 0. */
    fun slotIndexForPage(slots: List<MangaSpreadSlot>, pageIndex: Int): Int {
        val found = slots.indexOfFirst { it.leftPage == pageIndex || it.rightPage == pageIndex }
        return if (found >= 0) found else 0
    }

    private fun buildDoubleSlots(pageCount: Int, coverOffset: Boolean, rtl: Boolean): List<MangaSpreadSlot> {
        val slots = ArrayList<MangaSpreadSlot>()
        var i = 0
        if (coverOffset) {
            slots += soloSlot(0, rtl)
            i = 1
        }
        while (i < pageCount) {
            val first = i
            val second = if (i + 1 < pageCount) i + 1 else null
            slots +=
                if (rtl) {
                    MangaSpreadSlot(leftPage = second, rightPage = first)
                } else {
                    MangaSpreadSlot(leftPage = first, rightPage = second)
                }
            i += 2
        }
        return slots
    }

    private fun soloSlot(page: Int, rtl: Boolean): MangaSpreadSlot =
        if (rtl) MangaSpreadSlot(leftPage = null, rightPage = page) else MangaSpreadSlot(leftPage = page, rightPage = null)
}
