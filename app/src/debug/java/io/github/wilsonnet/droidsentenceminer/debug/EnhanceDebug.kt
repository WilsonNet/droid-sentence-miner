package io.github.wilsonnet.droidsentenceminer.debug

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import io.github.wilsonnet.droidsentenceminer.manga.MangaPageEnhancer
import io.github.wilsonnet.droidsentenceminer.manga.MangaPageFilter
import io.github.wilsonnet.droidsentenceminer.manga.PagePipeline
import io.github.wilsonnet.droidsentenceminer.ocr.PageUpscaler
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * `/manga_enhance?file=<path>&w=<px>&h=<px>&method=<m>&n=<runs>&out=<name>`: runs the manga page
 * filter on an image file and reports per-run milliseconds (`ms`, `median`). `method` is
 * `bilinear` (Android's filtered scale, what the reader drew before), `lanczos` (resize only),
 * `cas` (resize + sharpening, no tone change), a [MangaPageFilter] id (`soft`, `crisp`, `strong`)
 * or `custom&levels=&gamma=&sharp=`; `order=before|after` forces sharpening before/after the resize;
 * `ai=1` adds the Anime4K 2x step ([PageUpscaler]) on pages that grow by 1.2x or more. `out` saves
 * the result as `files/enhance/<out>.png` (ink pages converted to grey) for host-side scoring
 * (`tools/manga-eval/enhance_bench.py`). Without `w`/`h` the page keeps its size.
 */
internal object EnhanceDebug {
    private const val NANOS_PER_MS = 1_000_000.0
    private const val PNG_QUALITY = 100
    private const val DEFAULT_RUNS = 3

    /** Answers one `/manga_enhance` request. */
    fun handle(app: Application, params: Map<String, String>): JSONObject {
        val path = params["file"]
        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        val src = path?.let { BitmapFactory.decodeFile(it, options) }
        val response =
            if (src == null) {
                JSONObject().put("ok", false).put("error", "could not decode ${path.orEmpty()}")
            } else {
                run(app = app, src = src, params = params).also { src.recycle() }
            }
        return response
    }

    private fun run(app: Application, src: Bitmap, params: Map<String, String>): JSONObject {
        val width = params["w"]?.toIntOrNull() ?: src.width
        val height = params["h"]?.toIntOrNull() ?: src.height
        val method = params["method"] ?: "crisp"
        val runs = params["n"]?.toIntOrNull() ?: DEFAULT_RUNS
        val times = ArrayList<Double>()
        var result: Bitmap? = null
        repeat(runs) {
            result?.recycle()
            val started = System.nanoTime()
            result = render(app = app, src = src, size = width to height, method = method, params = params)
            times += (System.nanoTime() - started) / NANOS_PER_MS
        }
        val out = result
        val name = params["out"]
        if (out != null && name != null) save(app = app, bitmap = out, name = name)
        val sorted = times.sorted()
        return JSONObject()
            .put("ok", true)
            .put("method", method)
            .put("src", "${src.width}x${src.height}")
            .put("dst", "${out?.width ?: 0}x${out?.height ?: 0}")
            .put("mono", out?.let(MangaPageEnhancer::isInk) == true)
            .put("ms", JSONArray(times.map { "%.1f".format(Locale.ROOT, it) }))
            .put("median", "%.1f".format(Locale.ROOT, sorted[sorted.size / 2]))
            .put("detail", MangaPageEnhancer.lastTimings)
            .also { out?.recycle() }
    }

    private fun render(
        app: Application,
        src: Bitmap,
        size: Pair<Int, Int>,
        method: String,
        params: Map<String, String>,
    ): Bitmap {
        val upscaler = if (params["ai"] == "1") PageUpscaler.get(app) else null
        val settings =
            when (method) {
                "bilinear" -> {
                    null
                }

                "lanczos" -> {
                    PagePipeline.Settings(levels = 0f, gamma = 1f, sharpness = 0f)
                }

                "cas" -> {
                    PagePipeline.Settings(levels = 0f, gamma = 1f, sharpness = MangaPageFilter.CRISP.sharpness)
                }

                "custom" -> {
                    PagePipeline.Settings(
                        levels = params["levels"]?.toFloatOrNull() ?: 0f,
                        gamma = params["gamma"]?.toFloatOrNull() ?: 1f,
                        sharpness = params["sharp"]?.toFloatOrNull() ?: 0f,
                    )
                }

                else -> {
                    MangaPageFilter.fromId(method).settings
                }
            }
        val isSharpenFirst =
            when (params["order"]) {
                "before" -> true
                "after" -> false
                else -> null
            }
        return if (settings == null) {
            src.scale(width = size.first, height = size.second)
        } else {
            runBlocking {
                MangaPageEnhancer.render(
                    src = src,
                    settings = settings.copy(isSharpenFirst = isSharpenFirst),
                    size = size,
                    upscaler = upscaler,
                )
            }
        }
    }

    private fun save(app: Application, bitmap: Bitmap, name: String) {
        val dir = File(app.filesDir, "enhance").apply { mkdirs() }
        val opaque =
            if (MangaPageEnhancer.isInk(bitmap)) {
                createBitmap(width = bitmap.width, height = bitmap.height).also { grey ->
                    val canvas = Canvas(grey)
                    val ink = Paint()
                    ink.color = Color.BLACK
                    canvas.drawColor(Color.WHITE)
                    canvas.drawBitmap(bitmap, 0f, 0f, ink)
                }
            } else {
                bitmap
            }
        File(dir, "$name.png").outputStream().use { opaque.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        if (opaque !== bitmap) opaque.recycle()
    }
}
