package io.github.wilsonnet.droidsentenceminer.debug

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import com.ichi2.anki.api.AddContentApi
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.MainActivity
import io.github.wilsonnet.droidsentenceminer.anki.AnkiResult
import io.github.wilsonnet.droidsentenceminer.capture.ScreenCaptureManager
import io.github.wilsonnet.droidsentenceminer.dict.RecommendedDictionaries
import io.github.wilsonnet.droidsentenceminer.overlay.OverlayService
import io.github.wilsonnet.droidsentenceminer.ocr.MlKitJapaneseOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Debug-only text API over a loopback HTTP server. Debug builds only (this file
 * lives in the `debug` source set and is booted reflectively from [DsmApp]).
 *
 * Drive it from the host:
 *
 *   adb forward tcp:9099 tcp:9099
 *   curl -s localhost:9099/state
 *   curl -s "localhost:9099/scan?l=0.25&t=0.33&r=0.76&b=0.45"
 *   curl -s "localhost:9099/frame.png?l=0.25&t=0.33&r=0.76&b=0.45" -o crop.png
 *   curl -s "localhost:9099/ocr?file=/sdcard/Pictures/test.png"
 *   curl -s "localhost:9099/lookup?word=%E7%B6%99%E6%89%BF"
 *   curl -s "localhost:9099/mine?word=...&sentence=..."
 */
object DebugApi {

    private const val TAG = "DSM-DebugApi"
    private const val PORT = 9099

    private lateinit var app: Application
    private val pool = Executors.newCachedThreadPool()
    private lateinit var server: ServerSocket

    private class DictInstall(val id: String) {
        @Volatile var stage: String = "starting"
        @Volatile var progress: Int = 0
        @Volatile var done: Boolean = false
        @Volatile var error: String? = null
    }

    @Volatile
    private var dictInstall: DictInstall? = null

    fun boot(application: Application) {
        app = application
        Thread({
            try {
                server = ServerSocket(PORT, 32, InetAddress.getLoopbackAddress())
                Log.i(TAG, "listening on 127.0.0.1:$PORT")
                while (!server.isClosed) {
                    val socket = server.accept()
                    pool.execute { handleSafely(socket) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "debug api server stopped", t)
            }
        }, "dsm-debug-api").start()
    }

    // ------------------------------------------------------------- request

    private fun handleSafely(socket: Socket) {
        try {
            handle(socket)
        } catch (t: Throwable) {
            runCatching { respondText(socket, 500, "error: ${t.message}") }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handle(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return respondText(socket, 400, "bad request line")
        val path = parts[1]
        // Drain headers.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val pathOnly = path.substringBefore('?')
        val params = parseQuery(path.substringAfter('?', ""))

        when (pathOnly) {
            "/state" -> respondJson(socket, stateJson())
            "/scan" -> respondApi(socket) { scan(params) }
            "/frame.png" -> {
                val png = framePng(params)
                if (png == null) respondText(socket, 404, "no capture available")
                else respondBytes(socket, "image/png", png)
            }
            "/ocr" -> respondApi(socket) { ocrFile(params) }
            "/lookup" -> respondApi(socket) { lookup(params) }
            "/mine" -> respondApi(socket) { mine(params) }
            "/dicts" -> respondApi(socket) { dicts() }
            "/install_dict" -> respondApi(socket) { installDict(params) }
            "/anki_notes" -> respondApi(socket) { ankiNotes(params) }
            "/anki_meta" -> respondApi(socket) { ankiMeta() }
            "/startoverlay" -> {
                // Foreground-start restriction: bring the app forward first, then start the service.
                app.startActivity(
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                pool.execute {
                    Thread.sleep(800)
                    runCatching { OverlayService.start(app) }
                }
                respondJson(socket, JSONObject().put("ok", true).put("action", "start"))
            }
            "/stopoverlay" -> {
                OverlayService.stop(app)
                respondJson(socket, JSONObject().put("ok", true).put("action", "stop"))
            }
            "/request_projection" -> respondApi(socket) { requestProjection(params) }
            else -> respondText(socket, 404, "unknown endpoint: $pathOnly")
        }
    }

    // ------------------------------------------------------------ handlers

    private fun stateJson(): JSONObject {
        val container = AppContainer.from(app)
        val service = OverlayService.activeInstance
        val json = JSONObject()
        json.put("overlayService", service != null)
        json.put("captureReady", service?.debugCaptureManager?.isReady == true)
        val screen = service?.debugCaptureManager
        json.put("screen", JSONArray(listOf(screen?.screenWidth, screen?.screenHeight)))
        dictInstall?.let { install ->
            json.put(
                "dictInstall",
                JSONObject()
                    .put("id", install.id)
                    .put("stage", install.stage)
                    .put("progress", install.progress)
                    .put("done", install.done)
                    .put("error", install.error),
            )
        }
        runBlocking {
            val region = container.prefs.captureRegion.first()
            json.put("region", JSONArray(listOf(region.left, region.top, region.right, region.bottom)))
            json.put("lastScanText", container.prefs.lastScanText.first())
            json.put("twoPass", container.prefs.ocrTwoPass.first())
            json.put("dropNoise", container.prefs.ocrDropNoise.first())
        }
        service?.debugPanelState?.let { panel ->
            json.put(
                "panel",
                JSONObject()
                    .put("status", panel.status)
                    .put("ocrText", panel.ocrText)
                    .put("matchCount", panel.matches.size)
                    .put("scanning", panel.scanning),
            )
        }
        return json
    }

    private fun scan(params: Map<String, String>): JSONObject {
        val service = OverlayService.activeInstance
            ?: return errorJson("overlay service is not running — call /startoverlay")
        if (!service.debugCaptureManager.isReady) {
            return errorJson("capture not ready — call /request_projection and accept the dialog")
        }
        val region = regionParam(params) ?: lastRegion(service)
        val outcome = runBlocking { withContext(Dispatchers.Main) { service.executeScan(region) } }
        return JSONObject()
            .put("ok", true)
            .put("text", outcome.text)
            .put("elapsedMs", outcome.elapsedMs)
            .put("matchCount", outcome.matchCount)
            .put("status", outcome.status)
            .put("region", JSONArray(listOf(region.left, region.top, region.right, region.bottom)))
    }

    private fun requestProjection(params: Map<String, String>): JSONObject {
        val service = OverlayService.activeInstance
            ?: return errorJson("overlay service is not running — call /startoverlay")
        val region = regionParam(params) ?: lastRegion(service)
        service.debugRequestScan(region)
        return JSONObject().put("ok", true).put("note", "consent dialog may be showing; accept it on-device")
    }

    private fun framePng(params: Map<String, String>): ByteArray? {
        val service = OverlayService.activeInstance ?: return null
        val frame = runBlocking { service.debugCaptureManager.grabFrame() } ?: return null
        val region = regionParam(params)
        val cropped = region?.let {
            service.debugCaptureManager.cropToRegion(frame, it.toFrac())
        } ?: frame
        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun ocrFile(params: Map<String, String>): JSONObject {
        val path = params["file"] ?: return errorJson("missing 'file' parameter")
        val bitmap = BitmapFactory.decodeFile(path)
            ?: return errorJson("could not decode $path")
        return ocrBitmap(bitmap, params)
    }

    private fun ocrBitmap(bitmap: Bitmap, params: Map<String, String>): JSONObject {
        val container = AppContainer.from(app)
        val (twoPass, dropNoise) = runBlocking {
            container.prefs.ocrTwoPass.first() to container.prefs.ocrDropNoise.first()
        }
        val engine = MlKitJapaneseOcr(
            twoPass = params["twoPass"]?.let { it == "1" || it == "true" } ?: twoPass,
            dropNoise = params["noise"]?.let { it == "1" || it == "true" } ?: dropNoise,
        )
        val result = runBlocking { engine.recognize(bitmap) }
        val text = result.text.trim()
        val matches = if (text.isBlank()) emptyList() else runBlocking { container.lookup.scan(text) }
        val lines = JSONArray()
        result.lines.forEach { line ->
            lines.put(
                JSONObject()
                    .put("text", line.text)
                    .put(
                        "box",
                        JSONArray(listOf(line.box.left, line.box.top, line.box.right, line.box.bottom)),
                    )
                    .put("confidence", (line.confidence ?: -1f).toDouble()),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("text", text)
            .put("elapsedMs", result.elapsedMs)
            .put("matchCount", matches.size)
            .put("matches", JSONArray(matches.map { it.text }))
            .put("lines", lines)
    }

    private fun lookup(params: Map<String, String>): JSONObject {
        val word = params["word"] ?: return errorJson("missing 'word' parameter")
        val container = AppContainer.from(app)
        val details = runBlocking { container.lookup.lookup(word) }
        return JSONObject()
            .put("ok", true)
            .put("word", word)
            .put("reading", details.reading.orEmpty())
            .put("termCount", details.terms.size)
    }

    private fun mine(params: Map<String, String>): JSONObject {
        val word = params["word"] ?: return errorJson("missing 'word' parameter")
        val sentence = params["sentence"] ?: return errorJson("missing 'sentence' parameter")
        val container = AppContainer.from(app)
        val details = runBlocking { container.lookup.lookup(word) }
        val result = runBlocking {
            container.miner.mine(word = word, sentence = sentence, details = details)
        }
        return when (result) {
            is AnkiResult.Success -> JSONObject().put("ok", true)
            is AnkiResult.Error -> JSONObject().put("ok", false).put("error", result.message)
            AnkiResult.NotAvailable -> JSONObject().put("ok", false).put("error", "anki not available")
        }
    }

    private fun dicts(): JSONObject {
        val container = AppContainer.from(app)
        val all = runBlocking { container.db.dictionaryDao().getAll() }
        val array = JSONArray()
        all.forEach { dict ->
            array.put(
                JSONObject()
                    .put("id", dict.id)
                    .put("title", dict.title)
                    .put("priority", dict.priority)
                    .put("enabled", dict.enabled)
                    .put("termCount", dict.termCount)
                    .put("kanjiCount", dict.kanjiCount)
                    .put("zipPath", dict.zipPath),
            )
        }
        return JSONObject().put("ok", true).put("dictionaries", array)
    }

    private fun installDict(params: Map<String, String>): JSONObject {
        val id = params["id"] ?: "jitendex"
        val recommended = RecommendedDictionaries.ALL.firstOrNull { it.id == id }
            ?: return errorJson("unknown dictionary id '$id' (known: ${RecommendedDictionaries.ALL.map { it.id }})")
        val url = params["url"] ?: recommended.url
        if (dictInstall?.done == false) return errorJson("an install is already running")
        val container = AppContainer.from(app)
        val install = DictInstall(id)
        dictInstall = install
        pool.execute {
            try {
                runBlocking {
                    val zipFile = File(app.cacheDir, "recommended-${install.id}.zip")
                    container.dictDownloader.download(url, zipFile) { downloaded, total ->
                        install.stage = "downloading"
                        install.progress = if (total != null && total > 0) {
                            (downloaded * 100 / total).toInt()
                        } else {
                            (downloaded / (1024 * 1024)).toInt()
                        }
                    }
                    install.stage = "importing"
                    install.progress = 0
                    container.importer.import(zipFile) { progress ->
                        install.stage = progress.stage
                        install.progress = if (progress.total != null && progress.total > 0) {
                            (progress.current * 100 / progress.total).toInt()
                        } else {
                            progress.current.toInt()
                        }
                    }
                }
                install.stage = "done"
                install.done = true
            } catch (t: Throwable) {
                install.error = t.message ?: t.toString()
                install.done = true
            }
        }
        return JSONObject().put("ok", true).put("started", id).put("url", url)
    }

    /** Inspect notes via the AnkiDroid content provider (fields + tags). */
    private fun ankiNotes(params: Map<String, String>): JSONObject {
        val word = params["word"] ?: return errorJson("missing 'word' parameter")
        val container = AppContainer.from(app)
        val model = params["model"] ?: runBlocking { container.prefs.ankiModel.first() }
        val api = AddContentApi(app)
        val modelId = api.modelList.orEmpty().entries.firstOrNull { it.value == model }?.key
            ?: return errorJson("note type '$model' not found")
        val fieldNames = api.getFieldList(modelId)?.toList().orEmpty()
        val notes = api.findDuplicateNotes(modelId, word).orEmpty()
        val array = JSONArray()
        notes.forEach { note ->
            if (note == null) return@forEach
            // NoteInfo properties are private Kotlin vals; go through the public JVM getters.
            @Suppress("UNCHECKED_CAST")
            val noteFields = note.javaClass.getMethod("getFields").invoke(note) as Array<String>?
            val fieldValues = JSONObject()
            noteFields?.forEachIndexed { index, value ->
                fieldValues.put(fieldNames.getOrElse(index) { "field$index" }, value)
            }
            val tags = note.javaClass.getMethod("getTags").invoke(note) as? Set<String>
            array.put(
                JSONObject()
                    .put("id", note.javaClass.getMethod("getId").invoke(note))
                    .put("key", note.javaClass.getMethod("getKey").invoke(note))
                    .put("tags", JSONArray(tags?.toList().orEmpty()))
                    .put("fields", fieldValues),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("model", model)
            .put("modelId", modelId)
            .put("fieldNames", JSONArray(fieldNames))
            .put("notes", array)
    }

    private fun ankiMeta(): JSONObject {
        val api = AddContentApi(app)
        val decks = JSONObject().apply {
            api.deckList.orEmpty().forEach { (id, name) -> put(name, id) }
        }
        val models = JSONObject().apply {
            api.modelList.orEmpty().forEach { (id, name) -> put(name, id) }
        }
        return JSONObject().put("ok", true).put("decks", decks).put("models", models)
    }

    // ------------------------------------------------------------- helpers

    private fun regionParam(params: Map<String, String>): RectF? {
        val nos = listOf("l", "t", "r", "b").map { params[it] } 
        if (nos.any { it == null }) return null
        return RectF(nos[0]!!.toFloat(), nos[1]!!.toFloat(), nos[2]!!.toFloat(), nos[3]!!.toFloat())
    }

    private fun lastRegion(service: OverlayService): RectF {
        val region = runBlocking { AppContainer.from(app).prefs.captureRegion.first() }
        return RectF(region.left, region.top, region.right, region.bottom)
    }

    private fun RectF.toFrac() = ScreenCaptureManager.RectFrac(left, top, right, bottom)

    private fun parseQuery(query: String): Map<String, String> =
        if (query.isBlank()) emptyMap()
        else query.split('&').associate { part ->
            val key = part.substringBefore('=')
            val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            key to value
        }

    private fun errorJson(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    private inline fun respondApi(socket: Socket, block: () -> JSONObject) {
        respondJson(socket, runCatching { block() }.getOrElse { errorJson(it.message ?: "failed") })
    }

    private fun respondJson(socket: Socket, json: JSONObject) =
        respondBytes(socket, "application/json", json.toString().toByteArray())

    private fun respondText(socket: Socket, status: Int, text: String) =
        respondBytes(socket, "text/plain", text.toByteArray(), status)

    private fun respondBytes(
        socket: Socket,
        contentType: String,
        body: ByteArray,
        status: Int = 200,
    ) {
        val reason = if (status == 200) "OK" else "Error"
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(headers.toByteArray())
        out.write(body)
        out.flush()
    }
}
