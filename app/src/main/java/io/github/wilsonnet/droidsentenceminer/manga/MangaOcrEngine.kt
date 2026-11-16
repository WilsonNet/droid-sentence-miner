package io.github.wilsonnet.droidsentenceminer.manga

import android.graphics.Bitmap
import io.github.wilsonnet.droidsentenceminer.book.PageLayout
import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.ImagePreprocessor
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitJapaneseOcr
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max

/**
 * On-device manga page OCR with Room caching: uses ML Kit's block recognizer (which supports
 * vertical tategaki speech bubbles and horizontal text) when a page has no `.mokuro` data.
 */
class MangaOcrEngine(
    private val dao: MangaPageOcrDao,
    private val recognizer: MlKitJapaneseOcr = MlKitJapaneseOcr(twoPass = false),
) {
    /** Returns cached `.mokuro` / OCR data if present, else runs OCR on [bitmap] and persists it. */
    @Suppress("InjectDispatcher") // Room cache lookup runs on the I/O dispatcher.
    suspend fun getOrRecognize(volumeId: Long, pageIndex: Int, bitmap: Bitmap): MangaPageOcr {
        val cached = withContext(Dispatchers.IO) { dao.getPageOcr(volumeId, pageIndex) }
        return if (cached != null) decodeEntity(cached) else recognizeAndStore(volumeId, pageIndex, bitmap)
    }

    /** Forces a fresh OCR pass on [bitmap] and overwrites any cached blocks for that page. */
    @Suppress("InjectDispatcher") // ML Kit OCR runs runs off the main thread.
    suspend fun recognizeAndStore(volumeId: Long, pageIndex: Int, bitmap: Bitmap): MangaPageOcr {
        val prepared = withContext(Dispatchers.Default) { ImagePreprocessor.upscaleForOcr(bitmap) }
        val prepWidth = prepared.width
        val prepHeight = prepared.height
        val rawBlocks =
            try {
                withContext(Dispatchers.Default) { recognizer.recognizeBlocks(prepared) }
            } finally {
                if (prepared !== bitmap) prepared.recycle()
            }
        val blocks = buildBlocks(rawBlocks)
        val pageOcr =
            MangaPageOcr(
                imgWidth = prepWidth,
                imgHeight = prepHeight,
                blocks = blocks,
                source = MangaPageOcr.SOURCE_OCR,
            )
        save(volumeId, pageIndex, pageOcr)
        return pageOcr
    }

    /** Persists edited or freshly recognized blocks for a page. */
    @Suppress("InjectDispatcher") // Room persistence runs on the I/O dispatcher.
    suspend fun save(volumeId: Long, pageIndex: Int, pageOcr: MangaPageOcr) {
        withContext(Dispatchers.IO) {
            dao.upsertPageOcr(
                MangaPageOcrEntity(
                    volumeId = volumeId,
                    pageIndex = pageIndex,
                    imgWidth = pageOcr.imgWidth,
                    imgHeight = pageOcr.imgHeight,
                    blocksJson = json.encodeToString(pageOcr.blocks),
                    source = pageOcr.source,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Returns already-cached OCR/Mokuro data for a page without running OCR, or null if absent. */
    @Suppress("InjectDispatcher") // Room lookup runs on the I/O dispatcher.
    suspend fun getCached(volumeId: Long, pageIndex: Int): MangaPageOcr? {
        val entity = withContext(Dispatchers.IO) { dao.getPageOcr(volumeId, pageIndex) }
        return entity?.let(::decodeEntity)
    }

    /** Decoding and block-assembly helpers. */
    companion object {
        private const val MIN_FONT_SIZE = 12f
        private const val VERTICAL_ASPECT_RATIO = 1.2f
        private const val MAX_RUBY_LENGTH = 12
        private const val RUBY_THICKNESS_RATIO = 0.6f
        private val json = Json { ignoreUnknownKeys = true }

        /** Decodes a persisted [MangaPageOcrEntity] back into a [MangaPageOcr]. */
        fun decodeEntity(entity: MangaPageOcrEntity): MangaPageOcr {
            val blocks =
                runCatching {
                    json.decodeFromString<List<MangaBlock>>(entity.blocksJson)
                }.getOrDefault(emptyList())
            return MangaPageOcr(
                imgWidth = entity.imgWidth,
                imgHeight = entity.imgHeight,
                blocks = blocks,
                source = entity.source,
            )
        }

        /** Encodes [blocks] as JSON for storage in [MangaPageOcrEntity]. */
        fun encodeBlocks(blocks: List<MangaBlock>): String = json.encodeToString(blocks)

        /**
         * Turns raw ML Kit text blocks into [MangaBlock]s. Each block decides its own writing
         * direction (manga pages often mix vertical speech bubbles with horizontal signs).
         */
        fun buildBlocks(rawBlocks: List<List<OcrLine>>): List<MangaBlock> {
            if (rawBlocks.isEmpty()) return emptyList()
            val language = Languages.current
            val grouped = MangaOcrGrouping.groupSpeechBubbles(rawBlocks = rawBlocks, usesSpaces = language.usesSpaces)
            return grouped.mapNotNull { rawBlock -> buildSingleBlock(rawBlock, language) }
        }

        private fun buildSingleBlock(rawBlock: List<OcrLine>, language: LanguageModule): MangaBlock? {
            if (rawBlock.isEmpty()) return null
            val isVertical = isBlockVertical(rawBlock, language.usesSpaces)
            val thicknesses = rawBlock.map { if (isVertical) it.box.width else it.box.height }.sorted()
            val medianThickness = thicknesses.getOrNull(thicknesses.size / 2) ?: 0
            val filtered =
                rawBlock.filterNot { line ->
                    isRubyLine(
                        line = line,
                        isVertical = isVertical,
                        medianThickness = medianThickness,
                        language = language,
                    )
                }
            val ordered =
                if (isVertical) {
                    filtered.sortedByDescending { it.box.centerX }
                } else {
                    filtered.sortedWith(compareBy({ it.box.top }, { it.box.left }))
                }
            val cleaned = cleanLines(ordered, isVertical)
            return if (cleaned.isEmpty()) {
                null
            } else {
                assembleBlock(
                    ordered = ordered,
                    cleaned = cleaned,
                    isVertical = isVertical,
                    medianThickness = medianThickness,
                    language = language,
                )
            }
        }

        private fun cleanLines(ordered: List<OcrLine>, isVertical: Boolean): List<Pair<String, List<BoundingBox>>> =
            ordered.mapNotNull { line ->
                val text = PageLayout.clean(line.text).trim()
                if (text.isEmpty()) null else text to PageLayout.glyphsOf(line, text, isVertical)
            }

        private fun assembleBlock(
            ordered: List<OcrLine>,
            cleaned: List<Pair<String, List<BoundingBox>>>,
            isVertical: Boolean,
            medianThickness: Int,
            language: LanguageModule,
        ): MangaBlock? {
            var joinedText = ""
            val allGlyphs = ArrayList<BoundingBoxDto>()
            val cleanedTexts = ArrayList<String>(cleaned.size)
            for ((piece, lineGlyphs) in cleaned) {
                cleanedTexts += piece
                val boxes = lineGlyphs.map(BoundingBoxDto::from)
                val joined = language.joinRows(previous = joinedText, next = piece)
                val added = joined.length - joinedText.length - piece.length
                if (added > 0 && allGlyphs.isNotEmpty()) {
                    repeat(added) { allGlyphs += allGlyphs.last() }
                }
                joinedText = joined
                allGlyphs += boxes
            }
            if (joinedText.none { language.isTargetChar(it) }) return null
            val blockBox = ordered.map { it.box }.reduce(BoundingBox::union)
            return MangaBlock(
                text = joinedText,
                box = BoundingBoxDto.from(blockBox),
                isVertical = isVertical,
                fontSize = max(a = MIN_FONT_SIZE, b = medianThickness.toFloat()),
                lines = cleanedTexts,
                glyphs = allGlyphs,
            )
        }

        private fun isBlockVertical(lines: List<OcrLine>, usesSpaces: Boolean): Boolean {
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

        private fun isRubyLine(
            line: OcrLine,
            isVertical: Boolean,
            medianThickness: Int,
            language: LanguageModule,
        ): Boolean {
            val text = PageLayout.clean(line.text).trim()
            val isInvalid = medianThickness <= 0 || text.isEmpty() || text.length > MAX_RUBY_LENGTH
            if (isInvalid) return false
            val thickness = if (isVertical) line.box.width else line.box.height
            return text.all { ch -> language.isRubyChar(ch) } && thickness < medianThickness * RUBY_THICKNESS_RATIO
        }
    }
}
