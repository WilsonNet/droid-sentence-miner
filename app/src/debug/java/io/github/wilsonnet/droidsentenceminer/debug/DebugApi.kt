package io.github.wilsonnet.droidsentenceminer.debug

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import com.ichi2.anki.api.AddContentApi
import io.github.wilsonnet.droidsentenceminer.AppContainer
import io.github.wilsonnet.droidsentenceminer.book.BookOcr
import io.github.wilsonnet.droidsentenceminer.MainActivity
import io.github.wilsonnet.droidsentenceminer.anki.AnkiResult
import io.github.wilsonnet.droidsentenceminer.capture.ScreenCaptureManager
import io.github.wilsonnet.droidsentenceminer.dict.DriveLinks
import io.github.wilsonnet.droidsentenceminer.dict.RecommendedDictionaries
import io.github.wilsonnet.droidsentenceminer.overlay.OverlayService
import io.github.wilsonnet.droidsentenceminer.ocr.MeikiModels
import io.github.wilsonnet.droidsentenceminer.ocr.MeikiOcr
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
            "/ocr_bench" -> respondApi(socket) { ocrBench(params) }
                "/book" -> respondApi(socket) { bookFile(params) }
            "/lookup" -> respondApi(socket) { lookup(params) }
            "/lookup_bench" -> respondApi(socket) { lookupBench(params) }
            "/popup.html" -> {
                val word = params["word"] ?: return respondText(socket, 400, "missing 'word'")
                val dark = params["dark"]?.let { it == "1" || it == "true" } ?: false
                val container = AppContainer.from(app)
                val html = runBlocking {
                    val details = container.lookup.lookup(word)
                    container.miner.buildPopupHtml(word, details, dark)
                }
                respondBytes(socket, "text/html; charset=utf-8", html.toByteArray())
            }
            "/mine" -> respondApi(socket) { mine(params) }
            "/dicts" -> respondApi(socket) { dicts() }
            "/order_dicts" -> respondApi(socket) { orderDicts(params) }
            "/dict_updates" -> respondApi(socket) { dictUpdates() }
            "/install_dict" -> respondApi(socket) { installDict(params) }
            "/drive_list" -> respondApi(socket) { driveList(params) }
            "/install_drive" -> respondApi(socket) { installDrive(params) }
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
            "/autoocr" -> respondApi(socket) { autoOcr(params) }
            "/live" -> respondApi(socket) {
                // Live text window: ?show=1|0 opens/hides it, ?mode=compact|normal|fullscreen switches.
                val service = OverlayService.activeInstance ?: return@respondApi errorJson("overlay not running")
                val mode = params["mode"]?.let { m -> io.github.wilsonnet.droidsentenceminer.overlay.LiveMode.entries.firstOrNull { it.name.equals(m, true) } }
                if (params["show"] != null || mode != null) {
                    val show = params["show"]?.let { it == "1" || it == "true" } ?: service.debugLiveWindow.first
                    service.debugSetLive(show, mode)
                    Thread.sleep(400)
                }
                val (shown, current, rect) = service.debugLiveWindow
                JSONObject().put("shown", shown).put("mode", current.name.lowercase()).put("rect", JSONArray(rect))
            }
            "/quick" -> respondApi(socket) {
                // Quick lookup of a box (screen fractions l,t,r,b) as if drawn after a bubble tap.
                val service = OverlayService.activeInstance ?: return@respondApi errorJson("overlay not running")
                val region = regionParam(params) ?: return@respondApi errorJson("l,t,r,b required")
                val lines = runBlocking { service.debugQuickLookup(region) }
                JSONObject().put("ok", lines > 0).put("lines", lines)
            }
            "/lines" -> respondApi(socket) { lines(params) }
            "/clip.wav" -> {
                val wav = clipWav(params)
                if (wav == null) respondText(socket, 404, "no audio for line")
                else respondBytes(socket, "audio/wav", wav)
            }
            "/line_shot.jpg" -> {
                val id = params["id"]?.toLongOrNull()
                val path = id?.let { runBlocking { AppContainer.from(app).textLog.get(it) }?.screenshotPath }
                if (path == null || !File(path).exists()) respondText(socket, 404, "no screenshot")
                else respondBytes(socket, "image/jpeg", File(path).readBytes())
            }
            "/mine_line" -> respondApi(socket) { mineLine(params) }
            "/add_line" -> respondApi(socket) { addLine(params) }
            "/preview_card" -> respondApi(socket) {
                // Builds the card and maps it onto the configured note type without adding it.
                val container = AppContainer.from(app)
                val word = params["word"] ?: return@respondApi errorJson("missing 'word'")
                runBlocking {
                    val line = params["line"]?.toLongOrNull()?.let { container.textLog.get(it) }
                    val sentence = line?.text ?: params["sentence"].orEmpty()
                    val card = container.miner.buildCard(word, sentence, details = container.lookup.lookup(word))
                    val model = container.prefs.ankiModel.first()
                    val api = AddContentApi(app)
                    val modelId = api.modelList?.entries?.firstOrNull { it.value == model }?.key
                    val fieldNames = modelId?.let { api.getFieldList(it)?.toList() } ?: io.github.wilsonnet.droidsentenceminer.anki.CardFields.ORDER
                    val values = mapOf(
                        io.github.wilsonnet.droidsentenceminer.anki.CardFields.EXPRESSION to card.expression,
                        io.github.wilsonnet.droidsentenceminer.anki.CardFields.READING to card.reading,
                        io.github.wilsonnet.droidsentenceminer.anki.CardFields.MEANING to card.meaningHtml,
                        io.github.wilsonnet.droidsentenceminer.anki.CardFields.SENTENCE to io.github.wilsonnet.droidsentenceminer.anki.CardHtml.sentenceFor(model, card),
                        io.github.wilsonnet.droidsentenceminer.anki.CardFields.IMAGE to "<img src=\"(screenshot)\">",
                        io.github.wilsonnet.droidsentenceminer.anki.CardFields.AUDIO to "[sound:(sentence clip)]",
                        io.github.wilsonnet.droidsentenceminer.anki.CardFields.SOURCE to (card.sourceTitle ?: ""),
                        io.github.wilsonnet.droidsentenceminer.anki.CardFields.WORD_AUDIO to (card.wordAudioBytes?.let { "[sound:${card.wordAudioName}] (${it.size} bytes)" } ?: ""),
                        io.github.wilsonnet.droidsentenceminer.anki.CardFields.PITCH_GRAPH to card.pitchGraphHtml,
                    )
                    val mapped = io.github.wilsonnet.droidsentenceminer.anki.AnkiFieldMapper.mapToNamedFields(fieldNames, values, container.prefs.ankiFieldMap(model))
                    val fields = JSONObject()
                    fieldNames.forEach { fields.put(it, mapped[it] ?: "") }
                    JSONObject().put("ok", true).put("model", model).put("deck", container.prefs.ankiDeck.first()).put("fields", fields)
                }
            }
            "/anki_sample" -> respondApi(socket) {
                // Read-only: newest notes of a note type, to see how the user fills its fields.
                val name = params["model"] ?: return@respondApi errorJson("missing 'model'")
                val limit = params["limit"]?.toIntOrNull() ?: 10
                val api = AddContentApi(app)
                val id = api.modelList?.entries?.firstOrNull { it.value == name }?.key
                    ?: return@respondApi errorJson("no model $name")
                val fieldNames = api.getFieldList(id)?.toList().orEmpty()
                val out = JSONArray()
                app.contentResolver.query(
                    com.ichi2.anki.FlashCardsContract.Note.CONTENT_URI,
                    arrayOf(com.ichi2.anki.FlashCardsContract.Note._ID, com.ichi2.anki.FlashCardsContract.Note.FLDS, com.ichi2.anki.FlashCardsContract.Note.TAGS),
                    "mid:$id",
                    null,
                    null,
                )?.use { c ->
                    val rows = ArrayList<JSONObject>()
                    while (c.moveToNext()) {
                        val values = c.getString(1).split('\u001f')
                        val fields = JSONObject()
                        fieldNames.forEachIndexed { i, f -> fields.put(f, values.getOrNull(i) ?: "") }
                        rows += JSONObject().put("id", c.getLong(0)).put("tags", c.getString(2)).put("fields", fields)
                    }
                    rows.sortedByDescending { it.getLong("id") }.take(limit).forEach { out.put(it) }
                    JSONObject().put("ok", true).put("total", rows.size).put("notes", out)
                } ?: errorJson("query failed")
            }
            "/anki_templates" -> respondApi(socket) {
                // Read-only: card templates of a note type (which fields the card actually shows).
                val name = params["model"] ?: return@respondApi errorJson("missing 'model'")
                val api = AddContentApi(app)
                val id = api.modelList?.entries?.firstOrNull { it.value == name }?.key
                    ?: return@respondApi errorJson("no model $name")
                val uri = android.net.Uri.parse("content://com.ichi2.anki.flashcards/models/$id/templates")
                val out = JSONArray()
                app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val row = JSONObject()
                        for (i in 0 until c.columnCount) row.put(c.getColumnName(i), c.getString(i))
                        out.put(row)
                    }
                }
                JSONObject().put("ok", true).put("templates", out)
            }
            "/set_anki" -> respondApi(socket) {
                val prefs = AppContainer.from(app).prefs
                runBlocking {
                    params["provider"]?.let { prefs.setAnkiProvider(it) }
                    params["deck"]?.let { prefs.setAnkiDeck(it) }
                    params["model"]?.let { prefs.setAnkiModel(it) }
                    params["firstdict"]?.let { prefs.setGlossaryFirstDictionary(it == "1" || it == "true") }
                    JSONObject().put("ok", true).put("provider", prefs.ankiProvider.first())
                        .put("deck", prefs.ankiDeck.first()).put("model", prefs.ankiModel.first())
                }
            }
            "/word_audio" -> respondApi(socket) {
                val term = params["term"] ?: return@respondApi errorJson("missing 'term'")
                val clip = runBlocking {
                    io.github.wilsonnet.droidsentenceminer.anki.WordAudio().fetch(term, params["reading"] ?: term, params["custom"])
                }
                JSONObject().put("ok", clip != null).put("source", clip?.source).put("bytes", clip?.bytes?.size).put("ext", clip?.extension)
            }
            "/mic_fallback" -> respondApi(socket) {
                val prefs = AppContainer.from(app).prefs
                params["on"]?.let { on -> runBlocking { prefs.setAudioMicFallback(on == "1" || on == "true") } }
                JSONObject().put("ok", true).put("on", runBlocking { prefs.audioMicFallback.first() })
            }
            "/mic_measure.wav" -> respondBytes(
                socket,
                "audio/wav",
                MicMeasure.run(
                    app,
                    signal = params["signal"] ?: "sweep",
                    seconds = params["sec"]?.toDoubleOrNull() ?: 8.0,
                    amp = params["amp"]?.toDoubleOrNull() ?: 0.5,
                    ns = params["ns"] == "1",
                    file = params["file"],
                    fx = if (params["fx"] == "1") runBlocking { AppContainer.from(app).prefs.audioMicFx.first() } else null,
                ),
            )
            "/mic_calibrate" -> respondApi(socket) {
                val prefs = AppContainer.from(app).prefs
                val result = io.github.wilsonnet.droidsentenceminer.audio.MicCalibration.run(
                    app,
                    io.github.wilsonnet.droidsentenceminer.textlog.TextLog.SAMPLE_RATE,
                    runBlocking { prefs.audioNoiseSuppression.first() },
                )
                if (params["save"] != "0") result.profile?.let { runBlocking { prefs.setAudioMicEqProfile(it) } }
                JSONObject().put("ok", result.profile != null).put("error", result.error)
                    .put("gains", result.profile?.gainsDb?.let { JSONArray(it) })
                    .put("measured", result.profile?.measuredDb?.let { JSONArray(it) })
            }
            "/mic_fx" -> respondApi(socket) {
                val prefs = AppContainer.from(app).prefs
                params["eq"]?.let { runBlocking { prefs.setAudioMicEq(it == "1") } }
                params["compressor"]?.let { runBlocking { prefs.setAudioMicCompressor(it == "1") } }
                params["ns"]?.let { runBlocking { prefs.setAudioNoiseSuppression(it == "1") } }
                if (params["reset"] == "1") runBlocking { prefs.setAudioMicEqProfile(null) }
                val fx = runBlocking { prefs.audioMicFx.first() }
                JSONObject().put("ok", true).put("noiseSuppression", fx.noiseSuppression).put("eq", fx.eq)
                    .put("compressor", fx.compressor).put("calibrated", fx.profile != null)
                    .put("gains", fx.profile?.gainsDb?.let { JSONArray(it) })
            }
            "/select_areas" -> {
                OverlayService.selectAreas(app)
                respondJson(socket, JSONObject().put("ok", true))
            }
            "/install_ocr_models" -> respondApi(socket) {
                val error = runBlocking { io.github.wilsonnet.droidsentenceminer.ocr.MeikiModels.download(app) }
                JSONObject().put("ok", error == null).put("error", error)
                    .put("installed", io.github.wilsonnet.droidsentenceminer.ocr.MeikiModels.installed(app))
            }
            "/clear_lines" -> respondApi(socket) {
                runBlocking { AppContainer.from(app).textLog.clear() }
                JSONObject().put("ok", true)
            }
            "/set_region" -> respondApi(socket) { setRegion(params) }
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
        json.put("accessibilityScreenshots", io.github.wilsonnet.droidsentenceminer.capture.ScreenshotAccessibilityService.isConnected)
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
            json.put("audioEnabled", container.prefs.audioEnabled.first())
            json.put("areasPortrait", container.prefs.autoAreas(false).first().size)
            json.put("areasLandscape", container.prefs.autoAreas(true).first().size)
        }
        val auto = OverlayService.observeAutoOcr().value
        json.put(
            "autoOcr",
            JSONObject()
                .put("running", auto.running)
                .put("reads", auto.reads)
                .put("lines", auto.lines)
                .put("lastRead", auto.lastRead)
                .put("ocrRuns", auto.ocrRuns)
                .put("ocrMsAvg", if (auto.ocrRuns > 0) auto.ocrMs / auto.ocrRuns else 0)
                .put("error", auto.error),
        )
        // Screen dictionary: the invisible text layer over the real text (screen fractions).
        val layer = JSONArray()
        service?.debugScreenText?.forEach { line ->
            val b = line.bounds
            layer.put(JSONObject().put("text", line.text).put("glyphs", line.glyphs.size).put("confidence", line.confidence.toDouble()).put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom))))
        }
        json.put("screenText", JSONObject().put("lines", layer).put("popupOpen", service?.debugScreenPopupOpen == true))
        val buffer = container.textLog.audioBuffer
        json.put(
            "audioBuffer",
            JSONObject()
                .put("capturing", service?.debugAudioRunning == true)
                .put("source", if (service?.debugAudioUsingMic == true) "mic" else "device")
                .put("bufferedMs", buffer.bufferedMs)
                .put("capacitySeconds", buffer.capacitySeconds)
                .put("newestAgeMs", buffer.newestMs?.let { System.currentTimeMillis() - it }),
        )
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
        service.debugRequestProjection()
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
        val decoded = BitmapFactory.decodeFile(path)
            ?: return errorJson("could not decode $path")
        // Optional l,t,r,b fractions crop the image first (e.g. a stored line screenshot).
        val bitmap = regionParam(params)?.let { r ->
            val left = (r.left * decoded.width).toInt()
            val top = (r.top * decoded.height).toInt()
            Bitmap.createBitmap(decoded, left, top, (r.right * decoded.width).toInt() - left, (r.bottom * decoded.height).toInt() - top)
        } ?: decoded
        return ocrBitmap(bitmap, params)
    }

    /**
     * meikiocr benchmark: every image in `dir`, `n` timed runs each after one warm-up, with the
     * session options given (threads, xnnpack, det/rec model file names). Leaves those options loaded.
     */
    private fun ocrBench(params: Map<String, String>): JSONObject {
        val dir = File(params["dir"] ?: return errorJson("missing 'dir'"))
        val files = dir.listFiles { f -> f.name.endsWith(".png") || f.name.endsWith(".jpg") }?.sortedBy { it.name }
            ?: return errorJson("cannot list $dir")
        val defaults = MeikiOcr.Options()
        val options = MeikiOcr.Options(
            threads = params["threads"]?.toIntOrNull() ?: defaults.threads,
            xnnpack = params["xnnpack"] == "1",
            detector = params["det"] ?: defaults.detector,
            recognizer = params["rec"] ?: defaults.recognizer,
            variants = params["variants"] != "0",
            anyWidthRecognizer = params["anyw"] != "0",
            // cfg=key:value|key:value → ORT session config entries
            config = params["cfg"]?.split("|")?.filter { it.contains(":") }?.associate { it.substringBefore(":") to it.substringAfter(":") }
                ?: defaults.config,
        )
        MeikiModels.installVariants(app)
        val engine = MeikiOcr.load(MeikiModels.dir(app), options) ?: return errorJson("could not load $options")
        val runs = params["n"]?.toIntOrNull() ?: 3
        val texts = JSONObject()
        val samples = mutableMapOf<String, MutableList<Double>>()
        fun sample(key: String, value: Double) = samples.getOrPut(key) { mutableListOf() }.add(value)
        for (file in files) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
            val first = runBlocking { engine.recognize(bitmap) }
            texts.put(file.name, first.text)
            repeat(runs) {
                val start = System.nanoTime()
                runBlocking { engine.recognize(bitmap) }
                val t = engine.lastTimings
                sample("total", (System.nanoTime() - start) / 1e6)
                sample("detPrep", t.detPrep)
                sample("detRun", t.detRun)
                sample("recPrep", t.recPrep)
                sample("recRun", t.recRun)
                sample("detHeight", t.detHeight.toDouble())
            }
        }
        val stats = JSONObject()
        samples.forEach { (key, values) ->
            val sorted = values.sorted()
            stats.put(key, JSONObject().put("median", sorted[sorted.size / 2]).put("mean", values.average()).put("p90", sorted[(sorted.size * 9) / 10]))
        }
        return JSONObject().put("ok", true).put("options", options.toString()).put("images", files.size).put("stats", stats).put("texts", texts)
    }

    /** Book mode: reads a page photo into paragraphs and shows it in the Book tab. */
    private fun bookFile(params: Map<String, String>): JSONObject {
        val path = params["file"] ?: return errorJson("missing 'file' parameter")
        val container = AppContainer.from(app)
        val bitmap = runBlocking { BookOcr.loadPhoto(app, android.net.Uri.fromFile(java.io.File(path))) }
            ?: return errorJson("could not decode $path")
        val page = runBlocking { container.bookOcr.read(bitmap) }
        container.bookPage.value = page
        val blocks = JSONArray()
        page.blocks.forEach { block ->
            blocks.put(
                JSONObject()
                    .put("text", block.text)
                    .put("vertical", block.vertical)
                    .put("box", JSONArray(listOf(block.box.left, block.box.top, block.box.right, block.box.bottom))),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("size", JSONArray(listOf(bitmap.width, bitmap.height)))
            .put("elapsedMs", page.elapsedMs)
            .put("blocks", blocks)
    }

    private fun ocrBitmap(bitmap: Bitmap, params: Map<String, String>): JSONObject {
        val container = AppContainer.from(app)
        val (twoPass, dropNoise) = runBlocking {
            container.prefs.ocrTwoPass.first() to container.prefs.ocrDropNoise.first()
        }
        val engine = runBlocking {
            container.ocrEngine(
                twoPass = params["twoPass"]?.let { it == "1" || it == "true" } ?: twoPass,
                dropNoise = params["noise"]?.let { it == "1" || it == "true" } ?: dropNoise,
                preferred = params["engine"],
            )
        }
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
            .put("engine", engine.name)
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
            .put("baseForm", details.baseForm)
            .put("meta", JSONArray(details.frequencies.map { "${it.dictionaryTitle}|${it.mode}|${it.expression}|${it.dataJson.take(120)}" }))
    }

    /**
     * Times one screen-dictionary tap: [TermLookup.wordAt] (scans the whole line), [TermLookup.lookup]
     * of the word under the finger and [CardMiner.buildPopupHtml]. Run 0 is reported apart (cold:
     * SQLite page cache, JIT); the rest as median/min/max ms. `fresh=1` (default) drops the lookup
     * caches before every run, so `wordAt` is the uncached scan of a new line; `fresh=0` measures a
     * second tap on the same line.
     */
    private fun lookupBench(params: Map<String, String>): JSONObject {
        val text = params["text"] ?: return errorJson("missing 'text' parameter")
        val index = params["index"]?.toIntOrNull() ?: 0
        val n = (params["n"]?.toIntOrNull() ?: 10).coerceIn(1, 200)
        val dark = params["dark"] == "1"
        val fresh = params["fresh"] != "0"
        val container = AppContainer.from(app)
        val runs = mutableListOf<LongArray>()
        var word = ""
        var termCount = 0
        var htmlBytes = 0
        runBlocking {
            repeat(n + 1) {
                if (fresh) container.lookup.invalidateCaches()
                val t0 = System.nanoTime()
                val range = container.lookup.wordAt(text, index)
                val t1 = System.nanoTime()
                word = text.substring(range.first, minOf(range.last + 1, text.length))
                val details = container.lookup.lookup(word)
                val t2 = System.nanoTime()
                val html = container.miner.buildPopupHtml(word, details, dark)
                val t3 = System.nanoTime()
                termCount = details.terms.size
                htmlBytes = html.length
                runs += longArrayOf(t1 - t0, t2 - t1, t3 - t2, t3 - t0)
            }
        }
        fun ms(ns: Long) = Math.round(ns / 10_000.0) / 100.0
        val names = listOf("wordAt", "lookup", "popupHtml", "total")
        val cold = JSONObject()
        names.forEachIndexed { i, name -> cold.put(name, ms(runs[0][i])) }
        val warm = JSONObject()
        names.forEachIndexed { i, name ->
            val sorted = runs.drop(1).map { it[i] }.sorted()
            warm.put(name, JSONObject().put("median", ms(sorted[sorted.size / 2])).put("min", ms(sorted.first())).put("max", ms(sorted.last())))
        }
        return JSONObject()
            .put("ok", true)
            .put("word", word)
            .put("termCount", termCount)
            .put("htmlBytes", htmlBytes)
            .put("dicts", runBlocking { container.db.dictionaryDao().getEnabled().size })
            .put("n", n)
            .put("coldMs", cold)
            .put("warmMs", warm)
            .put("lastScan", container.lookup.lastScanStats?.let {
                JSONObject().put("forms", it.forms).put("rows", it.rows)
                    .put("deinflectMs", it.deinflectMs).put("queryMs", it.queryMs).put("totalMs", it.totalMs)
            })
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
                    .put("revision", dict.revision)
                    .put("priority", dict.priority)
                    .put("enabled", dict.enabled)
                    .put("termCount", dict.termCount)
                    .put("kanjiCount", dict.kanjiCount)
                    .put("zipPath", dict.zipPath),
            )
        }
        return JSONObject().put("ok", true).put("dictionaries", array)
    }

    private fun orderDicts(params: Map<String, String>): JSONObject {
        val idsParam = params["ids"] ?: return errorJson("missing 'ids' parameter (comma-separated dictionary ids)")
        val ids = idsParam.split(",").mapNotNull { it.trim().toLongOrNull() }
        if (ids.isEmpty()) return errorJson("empty or invalid ids")
        val container = AppContainer.from(app)
        runBlocking { container.db.dictionaryDao().updatePriorities(ids) }
        return dicts()
    }

    private fun dictUpdates(): JSONObject {
        val container = AppContainer.from(app)
        val updates = runBlocking { container.refreshDictUpdates() }
        val array = JSONArray()
        updates.values.forEach { u ->
            array.put(
                JSONObject()
                    .put("dictId", u.dictId)
                    .put("installedTitle", u.installedTitle)
                    .put("installedRevision", u.installedRevision)
                    .put("latestTitle", u.latestTitle)
                    .put("latestRevision", u.latestRevision)
                    .put("downloadUrl", u.downloadUrl),
            )
        }
        return JSONObject().put("ok", true).put("updates", array)
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
                    container.importer.importRecommended(recommended, zipFile) { progress ->
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

    /** Lists a public Google Drive folder (`?url=` share link or `?id=` folder id). */
    private fun driveList(params: Map<String, String>): JSONObject {
        val id = params["id"] ?: (params["url"]?.let { DriveLinks.parse(it) } as? DriveLinks.Link.Folder)?.id
            ?: return errorJson("pass ?url=<Drive folder link> or ?id=<folder id>")
        val folder = runBlocking { AppContainer.from(app).dictDownloader.listDriveFolder(id) }
        val entries = JSONArray()
        folder.entries.forEach { entry ->
            entries.put(
                JSONObject().put("id", entry.id).put("name", entry.name).put("folder", entry.isFolder)
                    .put("zip", entry.isZip).put("modified", entry.modified),
            )
        }
        return JSONObject().put("ok", true).put("id", folder.id).put("title", folder.title).put("entries", entries)
    }

    /** Background install of a public Drive file (`?url=` file link or `?id=`); progress in `/state.dictInstall`. */
    private fun installDrive(params: Map<String, String>): JSONObject {
        val fileId = params["id"] ?: (params["url"]?.let { DriveLinks.parse(it) } as? DriveLinks.Link.File)?.id
            ?: return errorJson("pass ?url=<Drive file link> or ?id=<file id>")
        if (dictInstall?.done == false) return errorJson("an install is already running")
        val container = AppContainer.from(app)
        val install = DictInstall("drive:$fileId")
        dictInstall = install
        pool.execute {
            try {
                runBlocking {
                    val zipFile = File(app.cacheDir, "drive-$fileId.zip")
                    try {
                        container.dictDownloader.downloadDrive(fileId, zipFile) { downloaded, total ->
                            install.stage = "downloading"
                            install.progress = if (total != null && total > 0) (downloaded * 100 / total).toInt() else (downloaded / (1024 * 1024)).toInt()
                        }
                        install.stage = "importing"
                        install.progress = 0
                        container.importer.import(zipFile) { progress ->
                            install.stage = progress.stage
                            install.progress = progress.current.toInt()
                        }
                    } finally {
                        zipFile.delete()
                    }
                }
                install.stage = "done"
                install.done = true
            } catch (t: Throwable) {
                install.error = t.message ?: t.toString()
                install.done = true
            }
        }
        return JSONObject().put("ok", true).put("started", install.id)
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

    private fun autoOcr(params: Map<String, String>): JSONObject {
        when (params["on"]) {
            "1", "true" -> {
                runCatching { OverlayService.startAutoOcr(app) }
            }
            "0", "false" -> OverlayService.stopAutoOcr(app)
        }
        return JSONObject().put("ok", true).put("running", OverlayService.observeAutoOcr().value.running)
    }

    private fun lines(params: Map<String, String>): JSONObject {
        val container = AppContainer.from(app)
        val limit = params["limit"]?.toIntOrNull() ?: 50
        val out = JSONArray()
        runBlocking {
            for (line in container.textLog.dao.recent(limit)) {
                val bounds = container.textLog.clipBounds(line)
                out.put(
                    JSONObject()
                        .put("id", line.id)
                        .put("text", line.text)
                        .put("startMs", line.startMs)
                        .put("endMs", line.endMs)
                        .put("source", line.source)
                        .put("screenshot", line.screenshotPath)
                        .put("mined", line.minedCount)
                        .put("clip", JSONArray(listOf(bounds.startMs, bounds.endMs)))
                        .put("hasAudio", container.textLog.audioBuffer.covers(bounds.startMs, bounds.endMs)),
                )
            }
        }
        return JSONObject().put("ok", true).put("lines", out)
    }

    private fun clipWav(params: Map<String, String>): ByteArray? {
        val container = AppContainer.from(app)
        val id = params["id"]?.toLongOrNull() ?: return null
        return runBlocking {
            val line = container.textLog.get(id) ?: return@runBlocking null
            val trim = params["trim"]?.let { it == "1" || it == "true" }
            container.textLog.clip(line, trim)?.segment?.let {
                io.github.wilsonnet.droidsentenceminer.audio.WavEncoder.encode(it)
            }
        }
    }

    private fun mineLine(params: Map<String, String>): JSONObject {
        val container = AppContainer.from(app)
        val id = params["id"]?.toLongOrNull() ?: return errorJson("missing 'id' parameter")
        val word = params["word"] ?: return errorJson("missing 'word' parameter")
        val result = runBlocking {
            val line = container.textLog.get(id) ?: return@runBlocking null
            container.miner.mineLine(line, word, container.lookup.lookup(word))
        } ?: return errorJson("no line $id")
        return when (result) {
            is AnkiResult.Success -> JSONObject().put("ok", true)
            is AnkiResult.Error -> JSONObject().put("ok", false).put("error", result.message)
            AnkiResult.NotAvailable -> JSONObject().put("ok", false).put("error", "anki not available")
        }
    }

    /** Logs a synthetic line [ago] ms in the past lasting [dur] ms — exercises clip/mining without OCR. */
    private fun addLine(params: Map<String, String>): JSONObject {
        val text = params["text"] ?: return errorJson("missing 'text' parameter")
        val ago = params["ago"]?.toLongOrNull() ?: 4_000L
        val dur = params["dur"]?.toLongOrNull() ?: 3_000L
        val start = System.currentTimeMillis() - ago
        val log = AppContainer.from(app).textLog
        val id = runBlocking {
            log.startLine(text, start, io.github.wilsonnet.droidsentenceminer.textlog.TextLineEntity.SOURCE_AUTO, null)
                .also { log.endLine(it, start + dur) }
        }
        return JSONObject().put("ok", true).put("id", id)
    }

    private fun setRegion(params: Map<String, String>): JSONObject {
        val region = regionParam(params) ?: return errorJson("need l,t,r,b")
        // Sets the auto OCR area for the current (or ?orientation=landscape|portrait) orientation.
        val landscape = when (params["orientation"]) {
            "landscape" -> true
            "portrait" -> false
            else -> app.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        runBlocking {
            val prefs = AppContainer.from(app).prefs
            prefs.setCaptureRegion(
                io.github.wilsonnet.droidsentenceminer.core.DsmPrefs.CaptureRegion(region.left, region.top, region.right, region.bottom),
            )
            prefs.setAutoAreas(
                landscape,
                listOf(io.github.wilsonnet.droidsentenceminer.capture.TextArea(region.left, region.top, region.right, region.bottom)),
            )
        }
        OverlayService.activeInstance?.debugSetRegion(region)
        return JSONObject().put("ok", true).put("orientation", if (landscape) "landscape" else "portrait")
    }

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
