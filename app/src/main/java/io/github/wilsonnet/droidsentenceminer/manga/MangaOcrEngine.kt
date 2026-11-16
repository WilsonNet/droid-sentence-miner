package io.github.wilsonnet.droidsentenceminer.manga

import android.graphics.Bitmap
import android.util.Log
import io.github.wilsonnet.droidsentenceminer.book.PageLayout
import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.ChromeLensOcr
import io.github.wilsonnet.droidsentenceminer.ocr.ImagePreprocessor
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitJapaneseOcr
import io.github.wilsonnet.droidsentenceminer.ocr.OcrEngine
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.math.max

/**
 * Manga page OCR engine with Room persistence (`manga.db`):
 * defaults to Manatan's Google Lens pipeline (`ChromeLensOcr` + noise/furigana filtering +
 * tiered Union-Find speech-bubble merging) with seamless fallback to upscaled on-device OCR
 * (PaddleOCR for spaced study languages when installed, or ML Kit Japanese).
 */
class MangaOcrEngine(
    private val dao: MangaPageOcrDao,
    private val mlKitRecognizer: MlKitJapaneseOcr = MlKitJapaneseOcr(twoPass = false),
    private val lensOcr: ChromeLensOcr = ChromeLensOcr(),
    private val offlineSpacedProvider: (suspend () -> OcrEngine)? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Returns cached `.mokuro` / OCR data if present, else runs OCR using [mode] and persists it. */
    suspend fun getOrRecognize(
        volumeId: Long,
        pageIndex: Int,
        bitmap: Bitmap,
        mode: MangaOcrMode = MangaOcrMode.AUTO,
    ): MangaPageOcr {
        val cached = withContext(io) { dao.getPageOcr(volumeId, pageIndex) }
        return if (cached != null) {
            decodeEntity(cached)
        } else {
            recognizeAndStore(volumeId = volumeId, pageIndex = pageIndex, bitmap = bitmap, mode = mode)
        }
    }

    /** Forces a fresh OCR pass on [bitmap] via [mode] and overwrites any cached blocks for that page. */
    suspend fun recognizeAndStore(
        volumeId: Long,
        pageIndex: Int,
        bitmap: Bitmap,
        mode: MangaOcrMode = MangaOcrMode.AUTO,
    ): MangaPageOcr {
        val language = Languages.current
        val pageOcr =
            when (mode) {
                MangaOcrMode.MLKIT -> {
                    recognizeOffline(bitmap = bitmap, language = language)
                }

                MangaOcrMode.LENS -> {
                    recognizeWithLens(bitmap = bitmap, language = language)
                }

                MangaOcrMode.AUTO -> {
                    try {
                        recognizeWithLens(bitmap = bitmap, language = language)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        Log.w(TAG, "Lens OCR failed for page $pageIndex, falling back offline", e)
                        recognizeOffline(bitmap = bitmap, language = language)
                    }
                }
            }
        save(volumeId, pageIndex, pageOcr)
        return pageOcr
    }

    /** Persists edited or freshly recognized blocks for a page. */
    suspend fun save(volumeId: Long, pageIndex: Int, pageOcr: MangaPageOcr) {
        withContext(io) {
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
    suspend fun getCached(volumeId: Long, pageIndex: Int): MangaPageOcr? {
        val entity = withContext(io) { dao.getPageOcr(volumeId, pageIndex) }
        return entity?.let(::decodeEntity)
    }

    private suspend fun recognizeWithLens(bitmap: Bitmap, language: LanguageModule): MangaPageOcr {
        val rawBlocks = lensOcr.recognizeBlocks(bitmap = bitmap, languageCode = language.code)
        val blocks =
            buildBlocks(
                rawBlocks = rawBlocks,
                pageWidth = bitmap.width,
                pageHeight = bitmap.height,
                language = language,
            )
        return MangaPageOcr(
            imgWidth = bitmap.width,
            imgHeight = bitmap.height,
            blocks = blocks,
            source = MangaPageOcr.SOURCE_LENS,
        )
    }

    private suspend fun recognizeOffline(bitmap: Bitmap, language: LanguageModule): MangaPageOcr {
        val prepared = withContext(compute) { ImagePreprocessor.upscaleForOcr(bitmap) }
        val prepWidth = prepared.width
        val prepHeight = prepared.height
        val rawBlocks =
            try {
                if (language.usesSpaces && offlineSpacedProvider != null) {
                    val engine = offlineSpacedProvider.invoke()
                    val result = withContext(compute) { engine.recognize(prepared) }
                    result.lines.map { listOf(it) }
                } else {
                    withContext(compute) { mlKitRecognizer.recognizeBlocks(prepared) }
                }
            } finally {
                if (prepared !== bitmap) prepared.recycle()
            }
        val blocks =
            buildBlocks(
                rawBlocks = rawBlocks,
                pageWidth = prepWidth,
                pageHeight = prepHeight,
                language = language,
            )
        return MangaPageOcr(
            imgWidth = prepWidth,
            imgHeight = prepHeight,
            blocks = blocks,
            source = MangaPageOcr.SOURCE_OCR,
        )
    }

    /** Decoding and block-assembly helpers. */
    companion object {
        private const val TAG = "DSM-MangaOCR"
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
         * Turns raw OCR text blocks into [MangaBlock]s. Each block decides its own writing
         * direction (manga pages often mix vertical speech bubbles with horizontal signs).
         */
        fun buildBlocks(
            rawBlocks: List<List<OcrLine>>,
            pageWidth: Int = 0,
            pageHeight: Int = 0,
            language: LanguageModule = Languages.current,
        ): List<MangaBlock> {
            if (rawBlocks.isEmpty()) return emptyList()
            val grouped =
                MangaOcrGrouping.groupSpeechBubbles(
                    rawBlocks = rawBlocks,
                    usesSpaces = language.usesSpaces,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                    language = language,
                )
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
            if (language.usesSpaces) return false
            val text = line.text.trim()
            val thickness = if (isVertical) line.box.width else line.box.height
            return text.isNotEmpty() &&
                text.length <= MAX_RUBY_LENGTH &&
                thickness < medianThickness * RUBY_THICKNESS_RATIO &&
                text.all { ch -> language.isRubyChar(ch) || !language.isTargetChar(ch) }
        }
    }
}
