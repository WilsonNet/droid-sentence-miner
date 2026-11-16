package io.github.wilsonnet.droidsentenceminer.manga

import io.github.wilsonnet.droidsentenceminer.book.PageLayout
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Parses `.mokuro` JSON files produced by `kha-white/mokuro` (v0.2.0+) and consumed by
 * Mokuro Reader / Manatan.
 *
 * Schema:
 * `{ "version": "...", "title": "...", "volume": "...", "pages": [{ "img_path": "...",
 *    "img_width": 1200, "img_height": 1800, "blocks": [{ "box": [xmin, ymin, xmax, ymax],
 *    "vertical": true, "font_size": 24, "lines": ["..."], "lines_coords": [[[x,y], ...]] }] }] }`
 */
object MokuroParser {
    private const val MIN_BOX_COORDS = 4
    private const val MIN_POINT_COORDS = 2

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    /** Top-level `.mokuro` JSON structure. */
    @Serializable
    data class RawMokuroFile(
        /** Generator schema version string. */
        val version: String = "",
        /** Series title. */
        val title: String = "",
        /** Optional series UUID. */
        @SerialName("title_uuid") val titleUuid: String = "",
        /** Volume title. */
        val volume: String = "",
        /** Optional volume UUID. */
        @SerialName("volume_uuid") val volumeUuid: String = "",
        /** Page entries in archive order. */
        val pages: List<RawMokuroPage> = emptyList(),
    )

    /** One page object inside a `.mokuro` file. */
    @Serializable
    data class RawMokuroPage(
        /** Image path relative to the volume folder. */
        @SerialName("img_path") val imgPath: String = "",
        /** Pixel width at which coordinates were measured. */
        @SerialName("img_width") val imgWidth: Int = 0,
        /** Pixel height at which coordinates were measured. */
        @SerialName("img_height") val imgHeight: Int = 0,
        /** Recognized text blocks on this page. */
        val blocks: List<RawMokuroBlock> = emptyList(),
    )

    /** One speech bubble / block inside [RawMokuroPage]. */
    @Serializable
    data class RawMokuroBlock(
        /** `[xmin, ymin, xmax, ymax]` bounding box. */
        val box: List<Double> = emptyList(),
        /** Whether text reads vertically. */
        @SerialName("vertical") val isVertical: Boolean = true,
        /** Estimated font size in page pixels. */
        @SerialName("font_size") val fontSize: Float = 0f,
        /** Recognized lines in reading order. */
        val lines: List<String> = emptyList(),
        /** Optional polygon coordinates per line (`lines[i]` -> 4+ `[x, y]` points). */
        @SerialName("lines_coords") val linesCoords: List<List<List<Double>>> = emptyList(),
    )

    /** Result returned by [parse]. */
    data class ParsedMokuro(
        /** Series title if non-blank. */
        val title: String?,
        /** Volume title if non-blank. */
        val volume: String?,
        /** Parsed page objects. */
        val pages: List<RawMokuroPage>,
    )

    /** Decodes a `.mokuro` JSON string into a [ParsedMokuro]. */
    fun parse(rawJson: String): ParsedMokuro {
        val decoded = json.decodeFromString<RawMokuroFile>(rawJson)
        return ParsedMokuro(
            title = decoded.title.takeIf { it.isNotBlank() },
            volume = decoded.volume.takeIf { it.isNotBlank() },
            pages = decoded.pages,
        )
    }

    /**
     * Maps `.mokuro` pages onto the sorted [cbzEntryNames] of a `.cbz` archive.
     * Tries exact path, normalized path without leading root folder, and filename fallback.
     */
    fun matchPages(mokuroPages: List<RawMokuroPage>, cbzEntryNames: List<String>): Map<Int, RawMokuroPage> {
        if (mokuroPages.isEmpty() || cbzEntryNames.isEmpty()) return emptyMap()
        val normalizedEntries = cbzEntryNames.mapIndexed { idx, name -> normalizePath(name) to idx }
        val byExact = normalizedEntries.toMap()
        val byStem = normalizedEntries.groupBy { it.first.substringAfterLast('/') }
        val byBasenameNoExt = normalizedEntries.groupBy { it.first.substringAfterLast('/').substringBeforeLast('.') }
        val commonPrefix = commonRootPrefix(cbzEntryNames)

        val matched = LinkedHashMap<Int, RawMokuroPage>()
        for (page in mokuroPages) {
            val target = normalizePath(page.imgPath)
            if (target.isEmpty()) continue
            val idx =
                byExact[target]
                    ?: commonPrefix?.let { byExact["$it$target"] }
                    ?: byExact[target.substringAfter('/')]
                    ?: byStem[target.substringAfterLast('/')]?.singleOrNull()?.second
                    ?: byBasenameNoExt[target.substringAfterLast('/').substringBeforeLast('.')]?.singleOrNull()?.second
            if (idx != null && idx !in matched) {
                matched[idx] = page
            }
        }
        return matched
    }

    /** Converts a [RawMokuroPage] into our unified [MangaPageOcr] with per-character glyph boxes. */
    fun toPageOcr(page: RawMokuroPage): MangaPageOcr {
        val blocks = page.blocks.mapNotNull { toMangaBlock(it) }
        return MangaPageOcr(
            imgWidth = page.imgWidth,
            imgHeight = page.imgHeight,
            blocks = blocks,
            source = MangaPageOcr.SOURCE_MOKURO,
        )
    }

    /** Converts a single [RawMokuroBlock] into a [MangaBlock] with per-character glyphs, or null if empty. */
    fun toMangaBlock(raw: RawMokuroBlock): MangaBlock? {
        val blockBox = parseBlockBox(raw.box) ?: return null
        val cleanedLines = ArrayList<String>(raw.lines.size)
        val lineBoxes = ArrayList<BoundingBoxDto>(raw.lines.size)
        for (i in raw.lines.indices) {
            val cleaned = PageLayout.clean(raw.lines[i]).trim()
            if (cleaned.isEmpty()) continue
            cleanedLines += cleaned
            val quad = raw.linesCoords.getOrNull(i)
            lineBoxes +=
                quadToBox(quad)
                    ?: fallbackLineBox(
                        blockBox = blockBox,
                        index = i,
                        total = raw.lines.size,
                        isVertical = raw.isVertical,
                    )
        }
        return buildBlockFromLines(
            blockBox = blockBox,
            isVertical = raw.isVertical,
            fontSize = raw.fontSize,
            cleanedLines = cleanedLines,
            lineBoxes = lineBoxes,
        )
    }

    private fun parseBlockBox(coords: List<Double>): BoundingBoxDto? {
        if (coords.size < MIN_BOX_COORDS) return null
        val bxMin = coords[0].roundToInt()
        val byMin = coords[1].roundToInt()
        val bxMax = coords[2].roundToInt().coerceAtLeast(bxMin + 1)
        val byMax = coords[3].roundToInt().coerceAtLeast(byMin + 1)
        return BoundingBoxDto(left = bxMin, top = byMin, right = bxMax, bottom = byMax)
    }

    private fun buildBlockFromLines(
        blockBox: BoundingBoxDto,
        isVertical: Boolean,
        fontSize: Float,
        cleanedLines: List<String>,
        lineBoxes: List<BoundingBoxDto>,
    ): MangaBlock? {
        if (cleanedLines.isEmpty()) return null
        val language = Languages.current
        var joinedText = ""
        val glyphs = ArrayList<BoundingBoxDto>()
        for (i in cleanedLines.indices) {
            val line = cleanedLines[i]
            val lineGlyphs = splitLineIntoGlyphs(lineBoxes[i], line.length, isVertical)
            val nextJoined = language.joinRows(previous = joinedText, next = line)
            val added = nextJoined.length - joinedText.length - line.length
            if (added > 0 && glyphs.isNotEmpty()) {
                repeat(added) { glyphs += glyphs.last() }
            }
            joinedText = nextJoined
            glyphs += lineGlyphs
        }
        return if (joinedText.isBlank()) {
            null
        } else {
            MangaBlock(
                text = joinedText,
                box = blockBox,
                isVertical = isVertical,
                fontSize = fontSize,
                lines = cleanedLines,
                glyphs = glyphs,
            )
        }
    }

    private fun quadToBox(quad: List<List<Double>>?): BoundingBoxDto? {
        if (quad.isNullOrEmpty()) return null
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (pt in quad) {
            if (pt.size < MIN_POINT_COORDS) continue
            if (pt[0] < minX) minX = pt[0]
            if (pt[1] < minY) minY = pt[1]
            if (pt[0] > maxX) maxX = pt[0]
            if (pt[1] > maxY) maxY = pt[1]
        }
        return if (!minX.isFinite() || !minY.isFinite()) {
            null
        } else {
            val l = minX.roundToInt()
            val t = minY.roundToInt()
            val r = maxX.roundToInt().coerceAtLeast(l + 1)
            val b = maxY.roundToInt().coerceAtLeast(t + 1)
            BoundingBoxDto(left = l, top = t, right = r, bottom = b)
        }
    }

    private fun fallbackLineBox(blockBox: BoundingBoxDto, index: Int, total: Int, isVertical: Boolean): BoundingBoxDto {
        if (total <= 1) return blockBox
        return if (isVertical) {
            // Mokuro vertical lines are ordered right-to-left.
            val colFromRight = total - 1 - index
            val left = blockBox.left + blockBox.width * colFromRight / total
            val right = blockBox.left + blockBox.width * (colFromRight + 1) / total
            BoundingBoxDto(left = left, top = blockBox.top, right = right.coerceAtLeast(left + 1), bottom = blockBox.bottom)
        } else {
            val top = blockBox.top + blockBox.height * index / total
            val bottom = blockBox.top + blockBox.height * (index + 1) / total
            BoundingBoxDto(left = blockBox.left, top = top, right = blockBox.right, bottom = bottom.coerceAtLeast(top + 1))
        }
    }

    private fun splitLineIntoGlyphs(box: BoundingBoxDto, count: Int, isVertical: Boolean): List<BoundingBoxDto> {
        if (count <= 0) return emptyList()
        return List(count) { i ->
            if (isVertical) {
                val top = box.top + box.height * i / count
                val bottom = box.top + box.height * (i + 1) / count
                BoundingBoxDto(left = box.left, top = top, right = box.right, bottom = bottom.coerceAtLeast(top + 1))
            } else {
                val left = box.left + box.width * i / count
                val right = box.left + box.width * (i + 1) / count
                BoundingBoxDto(left = left, top = box.top, right = right.coerceAtLeast(left + 1), bottom = box.bottom)
            }
        }
    }

    private fun normalizePath(path: String): String =
        path.replace(oldChar = '\\', newChar = '/').trimStart('/').lowercase()

    private fun commonRootPrefix(entries: List<String>): String? {
        val normalized = entries.map { normalizePath(it) }
        val firstSlash = normalized.firstOrNull()?.indexOf('/') ?: -1
        val prefix = if (firstSlash > 0) normalized.first().substring(startIndex = 0, endIndex = firstSlash + 1) else null
        return prefix?.takeIf { normalized.all { entry -> entry.startsWith(it) } }
    }
}
