package io.github.wilsonnet.droidsentenceminer.debug

import android.app.Application
import android.graphics.BitmapFactory
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.anki.CardFields
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import io.github.wilsonnet.droidsentenceminer.manga.MangaOcrEngine
import io.github.wilsonnet.droidsentenceminer.ocr.ChromeLensOcr
import io.github.wilsonnet.droidsentenceminer.update.WhatsNew
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug endpoints routed before [DebugApi]'s big `when` (adding a branch there resurfaces its
 * baselined lint findings, see the dsm-lint skill). Add new endpoints here.
 */
object DebugExtras {
    private const val NANOS_PER_MS = 1_000_000L

    /** Paths answered by [handle]. */
    val PATHS = setOf("/whats_new", "/card_contents", "/manga_ocr")

    /** Answers one request for a path in [PATHS]. */
    fun handle(app: Application, path: String, params: Map<String, String>): JSONObject {
        val response =
            when (path) {
                "/whats_new" -> whatsNew(app, params)
                "/card_contents" -> cardContents(app)
                else -> mangaOcrFile(params)
            }
        return response
    }

    // `/whats_new`: seen version and what the dialog would show; `?reset=1` looks like an update
    // (the next launch shows it), `?show=1` opens it now.
    private fun whatsNew(app: Application, params: Map<String, String>): JSONObject {
        val container = AppContainer.from(app)
        return runBlocking {
            if (params["reset"] == "1") container.prefs.setWhatsNewSeen("0.0.0+0")
            val sections = WhatsNew.load(app)
            if (params["show"] == "1") container.whatsNew.value = sections
            JSONObject()
                .put("ok", true)
                .put("version", WhatsNew.versionKey)
                .put("seen", container.prefs.whatsNewSeen.first() ?: JSONObject.NULL)
                .put("pending", JSONArray(WhatsNew.pending(app, container.prefs).map { it.version }))
                .put("sections", JSONArray(sections.map { it.version }))
        }
    }

    // `/card_contents`: the study language's contents and the ones the current note type's field
    // mapping receives (a field set to Nothing leaves its content out).
    private fun cardContents(app: Application): JSONObject {
        val container = AppContainer.from(app)
        return runBlocking {
            JSONObject()
                .put("ok", true)
                .put("language", Languages.current.code)
                .put("all", JSONArray(CardFields.current))
                .put("enabled", JSONArray(container.miner.cardFields()))
        }
    }

    // `/manga_ocr?file=<path>`: runs ChromeLensOcr + Manatan bubble merge on-device and reports ms + blocks.
    private fun mangaOcrFile(params: Map<String, String>): JSONObject {
        val path = params["file"]
        val bitmap = path?.let(BitmapFactory::decodeFile)
        return if (path == null) {
            JSONObject().put("ok", false).put("error", "missing file")
        } else if (bitmap == null) {
            JSONObject().put("ok", false).put("error", "could not decode $path")
        } else {
            try {
                val started = System.nanoTime()
                val blocks =
                    runBlocking {
                        val raw = ChromeLensOcr().recognizeBlocks(bitmap, Languages.current.code)
                        MangaOcrEngine.buildBlocks(rawBlocks = raw, pageWidth = bitmap.width, pageHeight = bitmap.height)
                    }
                val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS
                val arr = JSONArray()
                for (b in blocks) {
                    arr.put(JSONObject().put("text", b.text).put("vertical", b.isVertical))
                }
                JSONObject()
                    .put("ok", true)
                    .put("ms", elapsedMs)
                    .put("width", bitmap.width)
                    .put("height", bitmap.height)
                    .put("blocks", arr)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
