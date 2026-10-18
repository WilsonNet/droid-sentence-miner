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
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.system.measureTimeMillis

/**
 * On-device meikiocr: a text-line detector and a character recognizer trained on Japanese
 * video games (rtr46/meiki.text.detect.v0 + meiki.txt.recognition.v0, ONNX). On stylized,
 * outlined subtitle/game fonts it is far more accurate than ML Kit (see AGENTS.md for numbers).
 *
 * Inputs follow the reference pipeline: BGR channel order, values in [0, 1].
 */
class MeikiOcr private constructor(
    private val env: OrtEnvironment,
    private val detector: OrtSession,
    private val recognizer: OrtSession,
    private val detThreshold: Float = 0.5f,
) : OcrEngine {

    override val name: String = "meikiocr"

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        var lines: List<OcrLine> = emptyList()
        val elapsed = measureTimeMillis {
            lines = synchronized(this@MeikiOcr) { run(bitmap) }
        }
        val text = OcrPostProcessor.toSentenceText(OcrPostProcessor.merge(lines))
        OcrResult(text = text, lines = lines, elapsedMs = elapsed)
    }

    private fun run(bitmap: Bitmap): List<OcrLine> {
        val boxes = detect(bitmap)
        // Horizontal lines only; vertical text needs the separate vertical recognizer.
        val horizontal = boxes.filter { it.width() >= it.height() && it.width() > 4 && it.height() > 4 }
        return horizontal.chunked(MAX_BATCH).flatMap { recognize(bitmap, it) }
    }

    private fun detect(bitmap: Bitmap): List<Rect> {
        val lb = MeikiDecoder.letterbox(bitmap.width, bitmap.height)
        val scaled = Bitmap.createScaledBitmap(bitmap, lb.width, lb.height, true)
        val input = FloatBuffer.allocate(3 * MeikiDecoder.DET_HEIGHT * MeikiDecoder.DET_WIDTH)
        writeBgr(scaled, input, MeikiDecoder.DET_WIDTH, MeikiDecoder.DET_HEIGHT, offset = 0)
        if (scaled !== bitmap) scaled.recycle()
        val sizes = LongBuffer.wrap(
            longArrayOf((MeikiDecoder.DET_WIDTH / lb.scale).toLong(), (MeikiDecoder.DET_HEIGHT / lb.scale).toLong()),
        )
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, MeikiDecoder.DET_HEIGHT.toLong(), MeikiDecoder.DET_WIDTH.toLong())).use { images ->
            OnnxTensor.createTensor(env, sizes, longArrayOf(1, 2)).use { target ->
                detector.run(mapOf("images" to images, "orig_target_sizes" to target)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val rawBoxes = (out.get("boxes").get().value as Array<Array<FloatArray>>)[0]
                    @Suppress("UNCHECKED_CAST")
                    val scores = (out.get("scores").get().value as Array<FloatArray>)[0]
                    return rawBoxes.indices
                        .filter { scores[it] > detThreshold }
                        .map { i ->
                            val b = rawBoxes[i]
                            Rect(
                                b[0].toInt().coerceIn(0, bitmap.width),
                                b[1].toInt().coerceIn(0, bitmap.height),
                                b[2].toInt().coerceIn(0, bitmap.width),
                                b[3].toInt().coerceIn(0, bitmap.height),
                            )
                        }
                        .sortedBy { it.top }
                }
            }
        }
    }

    private fun recognize(bitmap: Bitmap, boxes: List<Rect>): List<OcrLine> {
        val w = MeikiDecoder.REC_WIDTH
        val h = MeikiDecoder.REC_HEIGHT
        val input = FloatBuffer.allocate(boxes.size * 3 * h * w)
        val effective = IntArray(boxes.size)
        val canvas = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        boxes.forEachIndexed { i, box ->
            val (rw, rh) = MeikiDecoder.recSize(box.width(), box.height())
            effective[i] = rw
            canvas.eraseColor(android.graphics.Color.BLACK)
            Canvas(canvas).drawBitmap(bitmap, box, Rect(0, 0, rw, rh), paint)
            writeBgr(canvas, input, w, h, offset = i * 3 * h * w)
        }
        canvas.recycle()
        input.rewind()
        val sizes = LongBuffer.allocate(boxes.size * 2)
        repeat(boxes.size) { sizes.put(w.toLong()).put(h.toLong()) }
        sizes.rewind()

        OnnxTensor.createTensor(env, input, longArrayOf(boxes.size.toLong(), 3, h.toLong(), w.toLong())).use { images ->
            OnnxTensor.createTensor(env, sizes, longArrayOf(boxes.size.toLong(), 2)).use { target ->
                recognizer.run(mapOf("images" to images, "orig_target_sizes" to target)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val codes = out.get("char_codes").get().value as Array<IntArray>
                    @Suppress("UNCHECKED_CAST")
                    val charBoxes = out.get("boxes").get().value as Array<Array<FloatArray>>
                    @Suppress("UNCHECKED_CAST")
                    val scores = out.get("scores").get().value as Array<FloatArray>
                    return boxes.indices.mapNotNull { i ->
                        val chars = MeikiDecoder.decodeLine(codes[i], charBoxes[i], scores[i], effective[i])
                        if (chars.isEmpty()) return@mapNotNull null
                        val text = MeikiDecoder.fixSwappedPairs(chars.joinToString("") { it.char })
                        OcrLine(
                            text = text,
                            box = BoundingBox.from(boxes[i]),
                            confidence = chars.map { it.score }.average().toFloat(),
                        )
                    }
                }
            }
        }
    }

    /** Writes [bitmap] (top-left) into a zero-filled CHW BGR float plane set at [offset]. */
    private fun writeBgr(bitmap: Bitmap, buffer: FloatBuffer, planeWidth: Int, planeHeight: Int, offset: Int) {
        val bw = bitmap.width
        val bh = bitmap.height
        val pixels = IntArray(bw * bh)
        bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)
        val plane = planeWidth * planeHeight
        val array = buffer.array()
        java.util.Arrays.fill(array, offset, offset + 3 * plane, 0f)
        for (y in 0 until bh) {
            val row = y * planeWidth
            for (x in 0 until bw) {
                val p = pixels[y * bw + x]
                val idx = offset + row + x
                array[idx] = (p and 0xFF) / 255f // B
                array[idx + plane] = ((p shr 8) and 0xFF) / 255f // G
                array[idx + 2 * plane] = ((p shr 16) and 0xFF) / 255f // R
            }
        }
    }

    fun close() {
        runCatching { detector.close() }
        runCatching { recognizer.close() }
    }

    companion object {
        private const val MAX_BATCH = 8

        @Volatile
        private var shared: MeikiOcr? = null

        /** Loads (once) from [dir]; null when the models aren't downloaded or fail to load. */
        fun load(dir: File): MeikiOcr? {
            shared?.let { return it }
            synchronized(this) {
                shared?.let { return it }
                val det = File(dir, MeikiModels.DETECTOR)
                val rec = File(dir, MeikiModels.RECOGNIZER)
                if (!det.isFile || !rec.isFile) return null
                return runCatching {
                    val env = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions().apply {
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        setIntraOpNumThreads(4)
                    }
                    MeikiOcr(env, env.createSession(det.absolutePath, opts), env.createSession(rec.absolutePath, opts))
                }.getOrNull()?.also { shared = it }
            }
        }

        fun unload() {
            synchronized(this) {
                shared?.close()
                shared = null
            }
        }
    }
}
