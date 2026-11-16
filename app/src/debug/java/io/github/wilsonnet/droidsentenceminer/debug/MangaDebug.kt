package io.github.wilsonnet.droidsentenceminer.debug

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import io.github.wilsonnet.droidsentenceminer.manga.MangaBlock
import io.github.wilsonnet.droidsentenceminer.manga.MangaLocalOcr
import io.github.wilsonnet.droidsentenceminer.manga.MangaModelDownloads
import io.github.wilsonnet.droidsentenceminer.manga.MangaOcrEngine
import io.github.wilsonnet.droidsentenceminer.manga.MangaOcrModels
import io.github.wilsonnet.droidsentenceminer.manga.MangaRegionGrouping
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.ChromeLensOcr
import io.github.wilsonnet.droidsentenceminer.ocr.ImagePreprocessor
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitJapaneseOcr
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * `/manga_ocr?file=<path>&engine=local|lens|lensreg|mlkit&maxdim=<px>&raw=1`: runs one manga OCR
 * engine on an image file on-device and reports `ms`, the grouped `blocks` the reader would show
 * and, with `raw=1`, the engine's lines (with per-glyph boxes) and text regions before grouping
 * (for host-side grouping experiments, `tools/manga-eval`). `lensreg` = Lens lines grouped by the
 * bubble detector's regions. `/manga_ocr?models=install|remove|status` manages the on-device models.
 */
internal object MangaDebug {
    private const val NANOS_PER_MS = 1_000_000L
    private const val DEFAULT_LENS_DIM = 1500
    private const val OK = "ok"
    private const val ERROR = "error"

    /** Answers one `/manga_ocr` request. */
    fun handle(app: Application, params: Map<String, String>): JSONObject {
        val models = params["models"]
        if (models != null) return manageModels(app = app, action = models)
        val path = params["file"]
        val bitmap = path?.let(BitmapFactory::decodeFile)
        val response =
            when {
                path == null -> JSONObject().put(OK, false).put(ERROR, "missing file")
                bitmap == null -> JSONObject().put(OK, false).put(ERROR, "could not decode $path")
                else -> runEngine(app = app, bitmap = bitmap, params = params)
            }
        bitmap?.recycle()
        return response
    }

    private fun manageModels(app: Application, action: String): JSONObject {
        when (action) {
            "install" -> MangaModelDownloads.start(app, AppContainer.from(app).appScope)
            "remove" -> MangaModelDownloads.remove(app)
            else -> MangaModelDownloads.refresh(app)
        }
        val state = MangaModelDownloads.state.value
        return JSONObject()
            .put(OK, true)
            .put("installed", MangaOcrModels.isInstalled(app))
            .put("progress", state.progress ?: JSONObject.NULL)
            .put(ERROR, state.error ?: JSONObject.NULL)
    }

    private fun runEngine(app: Application, bitmap: Bitmap, params: Map<String, String>): JSONObject {
        val engine = params["engine"] ?: "lens"
        val started = System.nanoTime()
        val raw = runBlocking { rawLines(app = app, engine = engine, bitmap = bitmap, params = params) }
        val rawMs = (System.nanoTime() - started) / NANOS_PER_MS
        val blocks =
            if (raw.regions != null) {
                val groups =
                    MangaRegionGrouping.group(
                        lines = raw.blocks.flatten(),
                        regions = raw.regions,
                        pageWidth = raw.width,
                        pageHeight = raw.height,
                        language = Languages.current,
                    )
                MangaOcrEngine.buildOrderedBlocks(groups)
            } else {
                MangaOcrEngine.buildBlocks(
                    rawBlocks = raw.blocks,
                    pageWidth = raw.width,
                    pageHeight = raw.height,
                )
            }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS
        val out =
            JSONObject()
                .put(OK, true)
                .put("engine", engine)
                .put("ms", elapsedMs)
                .put("ocrMs", rawMs)
                .put("w", raw.width)
                .put("h", raw.height)
                .put("blocks", blocksJson(blocks))
                .put("timings", raw.timings)
        if (params["raw"] == "1") {
            out.put("lines", linesJson(raw.blocks))
            out.put("regions", JSONArray(raw.regions.orEmpty().map { boxJson(it.box) }))
        }
        return out
    }

    // The engine's paragraph-grouped lines, the text regions grouping them (null = geometric
    // merge) and the coordinate space they are in.
    private data class RawPage(
        val blocks: List<List<OcrLine>>,
        val width: Int,
        val height: Int,
        val regions: List<MangaRegionGrouping.TextRegion>? = null,
        val timings: JSONObject = JSONObject(),
    )

    private suspend fun rawLines(
        app: Application,
        engine: String,
        bitmap: Bitmap,
        params: Map<String, String>,
    ): RawPage {
        val local = if (engine == "local" || engine == "lensreg") MangaLocalOcr.get(app) else null
        val page =
            when (engine) {
                "local" -> {
                    val result = checkNotNull(local) { "manga OCR models not installed" }.recognize(bitmap)
                    RawPage(
                        blocks = result.lines.map { listOf(it) },
                        width = bitmap.width,
                        height = bitmap.height,
                        regions = result.regions,
                        timings =
                        JSONObject()
                            .put("regionMs", result.regionMs)
                            .put("detMs", result.detMs)
                            .put("recMs", result.recMs)
                            .put("boxes", result.boxes),
                    )
                }

                "lensreg" -> {
                    val dim = params["maxdim"]?.toIntOrNull() ?: DEFAULT_LENS_DIM
                    val blocks = ChromeLensOcr(maxUploadDim = dim).recognizeBlocks(bitmap, Languages.current.code)
                    val regions = checkNotNull(local) { "manga OCR models not installed" }.regions(bitmap)
                    RawPage(blocks = blocks, width = bitmap.width, height = bitmap.height, regions = regions)
                }

                "mlkit" -> {
                    val prepared = ImagePreprocessor.upscaleForOcr(bitmap)
                    val blocks = MlKitJapaneseOcr(twoPass = false).recognizeBlocks(prepared)
                    RawPage(blocks = blocks, width = prepared.width, height = prepared.height)
                }

                else -> {
                    val dim = params["maxdim"]?.toIntOrNull() ?: DEFAULT_LENS_DIM
                    val blocks = ChromeLensOcr(maxUploadDim = dim).recognizeBlocks(bitmap, Languages.current.code)
                    RawPage(blocks = blocks, width = bitmap.width, height = bitmap.height)
                }
            }
        return page
    }

    private fun boxJson(box: BoundingBox): JSONArray = JSONArray(listOf(box.left, box.top, box.right, box.bottom))

    private fun blocksJson(blocks: List<MangaBlock>): JSONArray {
        val arr = JSONArray()
        for (b in blocks) {
            arr.put(
                JSONObject()
                    .put("text", b.text)
                    .put("vertical", b.isVertical)
                    .put("box", boxJson(b.boundingBox))
                    .put("lines", JSONArray(b.lines)),
            )
        }
        return arr
    }

    private fun linesJson(blocks: List<List<OcrLine>>): JSONArray {
        val arr = JSONArray()
        blocks.forEachIndexed { index, block ->
            for (line in block) {
                arr.put(
                    JSONObject()
                        .put("text", line.text)
                        .put("box", boxJson(line.box))
                        .put("conf", line.confidence?.toDouble() ?: JSONObject.NULL)
                        .put("glyphs", JSONArray(line.chars.orEmpty().map(::boxJson)))
                        .put("para", index),
                )
            }
        }
        return arr
    }
}
