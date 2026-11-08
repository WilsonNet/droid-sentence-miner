package io.github.wilsonnet.droidsentenceminer.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

/**
 * PaddleOCR (PP-OCRv5/v6) on ONNX Runtime: a DB text detector plus a CTC recognizer. Used for
 * languages whose pack ships these models (English: PP-OCRv5 mobile det + PP-OCRv6 small rec,
 * measured CER 4.5 % / WER 4.4 % vs ML Kit's 6.0 % / 12.2 % on game/subtitle-style English —
 * `tools/ocr-eval/gen_latin_set.py`). PP-OCRv6 recognizers read 50 languages, so other language
 * packs can reuse this engine with their own model files.
 *
 * Model files come from a language pack folder ([load]) with `paddle.json`:
 * `{"det": "det.onnx", "rec": "rec.onnx", "dict": "rec_dict.txt", "detLimit": 960, "thresh": 0.3,
 *   "boxThresh": 0.6, "unclipRatio": 1.5, "minScore": 0.5}`.
 */
class PaddleOcr private constructor(
    private val env: OrtEnvironment,
    private val det: OrtSession,
    private val rec: OrtSession,
    private val dictionary: List<String>,
    private val config: Config,
    override val name: String,
) : OcrEngine {

    data class Config(
        val detLimit: Int = 960,
        val thresh: Float = 0.3f,
        val boxThresh: Float = 0.6f,
        val unclipRatio: Float = 1.5f,
        val minScore: Float = 0.5f,
        val recHeight: Int = 48,
        val maxRecWidth: Int = 3200,
    )

    data class Timings(val detMs: Double = 0.0, val recMs: Double = 0.0, val lines: Int = 0)

    @Volatile
    var lastTimings: Timings = Timings()
        private set

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        var lines: List<OcrLine> = emptyList()
        val elapsed = measureTimeMillis { lines = synchronized(this@PaddleOcr) { run(bitmap) } }
        val text = OcrPostProcessor.toSentenceText(OcrPostProcessor.merge(lines))
        OcrResult(text = text, lines = lines, elapsedMs = elapsed)
    }

    private fun run(bitmap: Bitmap): List<OcrLine> {
        val t0 = System.nanoTime()
        val boxes = detect(bitmap)
        val t1 = System.nanoTime()
        val lines = boxes.mapNotNull { recognizeLine(bitmap, it) }
        lastTimings = Timings((t1 - t0) / 1e6, (System.nanoTime() - t1) / 1e6, boxes.size)
        return lines
    }

    private fun detect(bitmap: Bitmap): List<Rect> {
        val (w, h) = PaddleDecoder.detectorSize(bitmap.width, bitmap.height, config.detLimit)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        // BGR planes, ImageNet mean/std in PaddleOCR's (BGR-image) order.
        val input = FloatArray(3 * w * h)
        val plane = w * h
        for (i in 0 until plane) {
            val p = pixels[i]
            val b = (p and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val r = (p shr 16 and 0xFF) / 255f
            input[i] = (b - 0.485f) / 0.229f
            input[plane + i] = (g - 0.456f) / 0.224f
            input[2 * plane + i] = (r - 0.406f) / 0.225f
        }
        val prob = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            det.run(mapOf(det.inputNames.first() to tensor)).use { out ->
                val t = out[0] as OnnxTensor
                val fb = t.floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        val sx = bitmap.width.toFloat() / w
        val sy = bitmap.height.toFloat() / h
        return PaddleDecoder.boxes(prob, w, h, config.thresh, config.boxThresh, config.unclipRatio).map { box ->
            Rect(
                (box.left * sx).toInt().coerceIn(0, bitmap.width - 1),
                (box.top * sy).toInt().coerceIn(0, bitmap.height - 1),
                ceil(box.right * sx).toInt().coerceIn(1, bitmap.width),
                ceil(box.bottom * sy).toInt().coerceIn(1, bitmap.height),
            )
        }.filter { it.width() >= 4 && it.height() >= 4 }
    }

    private fun recognizeLine(bitmap: Bitmap, box: Rect): OcrLine? {
        val h = config.recHeight
        val w = min(config.maxRecWidth, max(16, ceil(h.toFloat() * box.width() / box.height()).toInt()))
        val line = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(line).drawBitmap(bitmap, box, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(w * h)
        line.getPixels(pixels, 0, w, 0, 0, w, h)
        line.recycle()
        val plane = w * h
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            input[i] = (p and 0xFF) / 127.5f - 1f
            input[plane + i] = (p shr 8 and 0xFF) / 127.5f - 1f
            input[2 * plane + i] = (p shr 16 and 0xFF) / 127.5f - 1f
        }
        val glyphs: List<PaddleDecoder.Glyph>
        val timesteps: Int
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            rec.run(mapOf(rec.inputNames.first() to tensor)).use { out ->
                val t = out[0] as OnnxTensor
                val shape = t.info.shape
                timesteps = shape[1].toInt()
                val classes = shape[2].toInt()
                val fb = t.floatBuffer
                val scores = FloatArray(fb.remaining()).also { fb.get(it) }
                glyphs = PaddleDecoder.ctcDecode(scores, timesteps, classes, dictionary)
            }
        }
        val text = glyphs.joinToString("") { it.char }
        if (text.isBlank()) return null
        val score = glyphs.map { it.score }.average().toFloat()
        if (score < config.minScore) return null
        // Each timestep covers box.width / timesteps source pixels.
        val step = box.width().toFloat() / timesteps
        val chars = glyphs.flatMap { g ->
            val left = box.left + (g.start * step).toInt()
            val right = box.left + ceil(g.end * step).toInt()
            List(g.char.length) { BoundingBox(left, box.top, right, box.bottom) }
        }
        return OcrLine(text = text, box = BoundingBox.from(box), confidence = score, chars = chars)
    }

    fun close() {
        runCatching { det.close() }
        runCatching { rec.close() }
    }

    companion object {
        const val MANIFEST = "paddle.json"

        /** Closes the loaded models (pack removed or replaced). */
        fun unload() {
            synchronized(this) {
                shared?.second?.let { old -> synchronized(old) { old.close() } }
                shared = null
            }
        }

        @Volatile
        private var shared: Pair<File, PaddleOcr>? = null

        /** Loads (once per folder) the models a language pack put in [dir]; null when missing or broken. */
        fun load(dir: File, threads: Int = 3): PaddleOcr? {
            shared?.takeIf { it.first == dir }?.let { return it.second }
            synchronized(this) {
                shared?.takeIf { it.first == dir }?.let { return it.second }
                val manifest = File(dir, MANIFEST).takeIf { it.isFile } ?: return null
                return runCatching {
                    val json = JSONObject(manifest.readText())
                    val config = Config(
                        detLimit = json.optInt("detLimit", 960),
                        thresh = json.optDouble("thresh", 0.3).toFloat(),
                        boxThresh = json.optDouble("boxThresh", 0.6).toFloat(),
                        unclipRatio = json.optDouble("unclipRatio", 1.5).toFloat(),
                        minScore = json.optDouble("minScore", 0.5).toFloat(),
                    )
                    val env = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions().apply {
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        setIntraOpNumThreads(threads)
                        // Same as meikiocr: spinning threads burn a core each between frames.
                        addConfigEntry("session.intra_op.allow_spinning", "0")
                    }
                    val dictionary = PaddleDecoder.parseDictionary(File(dir, json.getString("dict")).readText())
                    PaddleOcr(
                        env,
                        env.createSession(File(dir, json.getString("det")).absolutePath, opts),
                        env.createSession(File(dir, json.getString("rec")).absolutePath, opts),
                        dictionary,
                        config,
                        json.optString("name", "PaddleOCR"),
                    )
                }.onFailure { android.util.Log.w("DSM-Paddle", "load failed", it) }.getOrNull()?.also { engine ->
                    shared?.second?.let { old -> synchronized(old) { old.close() } }
                    shared = dir to engine
                }
            }
        }
    }
}
