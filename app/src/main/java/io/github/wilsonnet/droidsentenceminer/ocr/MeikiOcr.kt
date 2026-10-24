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
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val sessionOptions: OrtSession.SessionOptions,
    /** Detector per input height: 544 is the original, the others its shorter variants (see [MeikiModels]). */
    private val detectorFiles: Map<Int, File>,
    private val recognizer: OrtSession,
    /** [recognizer] takes any width (multiple of 32): short lines skip computing the padding. */
    private val anyWidth: Boolean,
    private val detThreshold: Float = 0.5f,
) : OcrEngine {

    /** Loaded detectors, least recently used first; each holds its own copy of the weights. */
    private val detectors = LinkedHashMap<Int, OrtSession>(4, 0.75f, true)
    private val heights = detectorFiles.keys.filter { it != MeikiDecoder.DET_HEIGHT }.toMutableList()

    init {
        detector(MeikiDecoder.DET_HEIGHT)
    }

    private fun detector(height: Int): OrtSession {
        detectors[height]?.let { return it }
        val session = env.createSession(detectorFiles.getValue(height).absolutePath, sessionOptions)
        detectors[height] = session
        while (detectors.size > MAX_DETECTORS) detectors.remove(detectors.keys.first())?.close()
        return session
    }

    override val name: String = "meikiocr"

    /** Session setup; the defaults are what the app uses, others are for debug API benchmarks. */
    data class Options(
        val threads: Int = 4,
        val xnnpack: Boolean = false,
        val detector: String = MeikiModels.DETECTOR,
        val recognizer: String = MeikiModels.RECOGNIZER,
        /** Use the model variants that are installed (see [MeikiModels.installVariants]). */
        val variants: Boolean = true,
        val anyWidthRecognizer: Boolean = true,
        /** ORT session config entries, e.g. session.intra_op.allow_spinning. */
        val config: Map<String, String> = emptyMap(),
    )

    /** Time spent per stage by the last [recognize], in ms (debug API benchmarks). */
    data class Timings(val detPrep: Double = 0.0, val detRun: Double = 0.0, val recPrep: Double = 0.0, val recRun: Double = 0.0, val lines: Int = 0, val detHeight: Int = 0)

    @Volatile
    var lastTimings = Timings()
        private set
    private var timings = Timings()

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        var lines: List<OcrLine> = emptyList()
        val elapsed = measureTimeMillis {
            lines = synchronized(this@MeikiOcr) { run(bitmap) }
        }
        val text = OcrPostProcessor.toSentenceText(OcrPostProcessor.merge(lines))
        OcrResult(text = text, lines = lines, elapsedMs = elapsed)
    }

    private fun run(bitmap: Bitmap): List<OcrLine> {
        timings = Timings()
        val boxes = detect(bitmap)
        // Horizontal lines only; vertical text needs the separate vertical recognizer.
        val horizontal = boxes.filter { it.width() >= it.height() && it.width() > 4 && it.height() > 4 }
        return horizontal.chunked(MAX_BATCH).flatMap { recognize(bitmap, it) }
            .also { lastTimings = timings.copy(lines = horizontal.size) }
    }

    private fun detect(bitmap: Bitmap): List<Rect> {
        val t0 = System.nanoTime()
        val lb = MeikiDecoder.letterbox(bitmap.width, bitmap.height)
        // A wide, short crop (a subtitle band) fills a few rows of the 544: the shorter variants
        // skip computing the zero padding below it.
        var height = MeikiDecoder.detectorHeight(lb.height, heights)
        val session = runCatching { detector(height) }.getOrElse {
            heights.remove(height)
            height = MeikiDecoder.DET_HEIGHT
            detector(height)
        }
        val scaled = Bitmap.createScaledBitmap(bitmap, lb.width, lb.height, true)
        val planes = detPlanes
        val plane = height * MeikiDecoder.DET_WIDTH
        if (height != detHeight) {
            detHeight = height
            detRows = height
        }
        // The letterbox is top-left: only the rows the last image wrote need clearing.
        val rows = maxOf(lb.height, detRows)
        planes.clear(0, 3 * plane, plane, detRows * MeikiDecoder.DET_WIDTH)
        writeBgr(scaled, planes, MeikiDecoder.DET_WIDTH, height, offset = 0)
        planes.upload(0, 3 * plane, plane, rows * MeikiDecoder.DET_WIDTH)
        detRows = lb.height
        if (scaled !== bitmap) scaled.recycle()
        // Variants work in the original 960x544 frame, so the target size is the same for all.
        val sizes = LongBuffer.wrap(
            longArrayOf((MeikiDecoder.DET_WIDTH / lb.scale).toLong(), (MeikiDecoder.DET_HEIGHT / lb.scale).toLong()),
        )
        val t1 = System.nanoTime()
        OnnxTensor.createTensor(env, planes.view(3 * plane), longArrayOf(1, 3, height.toLong(), MeikiDecoder.DET_WIDTH.toLong())).use { images ->
            OnnxTensor.createTensor(env, sizes, longArrayOf(1, 2)).use { target ->
                session.run(mapOf("images" to images, "orig_target_sizes" to target)).use { out ->
                    timings = timings.copy(detPrep = ms(t1 - t0), detRun = ms(System.nanoTime() - t1), detHeight = height)
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
        val t0 = System.nanoTime()
        val h = MeikiDecoder.REC_HEIGHT
        val recSizes = boxes.map { MeikiDecoder.recSize(it.width(), it.height()) }
        val effective = IntArray(boxes.size) { recSizes[it].first }
        val w = if (anyWidth) MeikiDecoder.recognizerWidth(effective.max()) else MeikiDecoder.REC_WIDTH
        val slot = 3 * h * w
        val planes = recPlanes
        val canvas = recCanvas
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        boxes.forEachIndexed { i, box ->
            val (rw, rh) = recSizes[i]
            canvas.eraseColor(android.graphics.Color.BLACK)
            Canvas(canvas).drawBitmap(bitmap, box, Rect(0, 0, rw, rh), paint)
            planes.clear(i * slot, slot, slot, slot)
            writeBgr(canvas, planes, w, h, offset = i * slot, width = w)
            planes.upload(i * slot, slot, slot, slot)
        }
        // Boxes come back in the 960x32 frame whatever the input width.
        val sizes = LongBuffer.allocate(boxes.size * 2)
        repeat(boxes.size) { sizes.put(MeikiDecoder.REC_WIDTH.toLong()).put(h.toLong()) }
        sizes.rewind()

        val t1 = System.nanoTime()
        OnnxTensor.createTensor(env, planes.view(boxes.size * slot), longArrayOf(boxes.size.toLong(), 3, h.toLong(), w.toLong())).use { images ->
            OnnxTensor.createTensor(env, sizes, longArrayOf(boxes.size.toLong(), 2)).use { target ->
                recognizer.run(mapOf("images" to images, "orig_target_sizes" to target)).use { out ->
                    timings = timings.copy(recPrep = timings.recPrep + ms(t1 - t0), recRun = timings.recRun + ms(System.nanoTime() - t1))
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
                        val box = boxes[i]
                        // Character x-intervals are in the resized line; map them back onto the screen.
                        val scale = box.width().toFloat() / effective[i]
                        OcrLine(
                            text = text,
                            box = BoundingBox.from(box),
                            confidence = chars.map { it.score }.average().toFloat(),
                            chars = chars.map {
                                BoundingBox((box.left + it.x1 * scale).toInt(), box.top, (box.left + it.x2 * scale).toInt(), box.bottom)
                            },
                        )
                    }
                }
            }
        }
    }

    /** Writes [bitmap]'s first [width] columns as CHW BGR into planes already zeroed, from [offset]. */
    private fun writeBgr(bitmap: Bitmap, planes: InputPlanes, planeWidth: Int, planeHeight: Int, offset: Int, width: Int = bitmap.width) {
        val bw = width
        val bh = bitmap.height
        if (pixels.size < bw * bh) pixels = IntArray(bw * bh)
        val pixels = pixels
        bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)
        val plane = planeWidth * planeHeight
        val array = planes.array
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

    /**
     * Model input kept between runs: written in a heap array, copied into the direct buffer ORT reads
     * in place (a heap FloatBuffer is copied into a fresh native buffer on every run).
     */
    private class InputPlanes(size: Int) {
        val array = FloatArray(size)
        private val direct: FloatBuffer = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        /** Zeroes the first [count] floats of each [planeSize] plane in [offset, offset + length). */
        fun clear(offset: Int, length: Int, planeSize: Int, count: Int) {
            var start = offset
            while (start < offset + length) {
                java.util.Arrays.fill(array, start, start + count, 0f)
                start += planeSize
            }
        }

        /** Copies the first [count] floats of each plane to the native buffer. */
        fun upload(offset: Int, length: Int, planeSize: Int, count: Int) {
            var start = offset
            while (start < offset + length) {
                direct.position(start)
                direct.put(array, start, count)
                start += planeSize
            }
        }

        fun view(length: Int): FloatBuffer = direct.duplicate().apply {
            position(0)
            limit(length)
        }
    }

    private val detPlanes = InputPlanes(3 * MeikiDecoder.DET_HEIGHT * MeikiDecoder.DET_WIDTH)
    private var detHeight = MeikiDecoder.DET_HEIGHT
    private var detRows = MeikiDecoder.DET_HEIGHT
    private val recPlanes = InputPlanes(MAX_BATCH * 3 * MeikiDecoder.REC_HEIGHT * MeikiDecoder.REC_WIDTH)
    private val recCanvas = Bitmap.createBitmap(MeikiDecoder.REC_WIDTH, MeikiDecoder.REC_HEIGHT, Bitmap.Config.ARGB_8888)
    private var pixels = IntArray(0)

    private fun ms(nanos: Long): Double = nanos / 1e6

    fun close() {
        detectors.values.forEach { runCatching { it.close() } }
        detectors.clear()
        runCatching { recognizer.close() }
        runCatching { sessionOptions.close() }
        recCanvas.recycle()
    }

    companion object {
        private const val MAX_BATCH = 8

        /** Detector sessions kept loaded (an area per orientation usually needs one height each). */
        private const val MAX_DETECTORS = 2

        @Volatile
        private var shared: MeikiOcr? = null
        private var sharedOptions: Options? = null

        /** Loads (once) from [dir]; null when the models aren't downloaded or fail to load. */
        fun load(dir: File, options: Options = sharedOptions ?: Options()): MeikiOcr? {
            shared?.takeIf { options == sharedOptions }?.let { return it }
            synchronized(this) {
                shared?.takeIf { options == sharedOptions }?.let { return it }
                val det = File(dir, options.detector)
                val anyWidth = options.variants && options.anyWidthRecognizer && options.recognizer == MeikiModels.RECOGNIZER &&
                    File(dir, MeikiModels.RECOGNIZER_ANY_WIDTH).isFile
                val rec = File(dir, if (anyWidth) MeikiModels.RECOGNIZER_ANY_WIDTH else options.recognizer)
                if (!det.isFile || !rec.isFile) return null
                shared?.let { old -> synchronized(old) { old.close() } }
                shared = null
                return runCatching {
                    val env = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions().apply {
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        setIntraOpNumThreads(options.threads)
                        options.config.forEach { (key, value) -> addConfigEntry(key, value) }
                        if (options.xnnpack) {
                            // XNNPACK brings its own pool; ORT's then only runs the ops it doesn't take.
                            setIntraOpNumThreads(1)
                            addXnnpack(mapOf("intra_op_num_threads" to options.threads.toString()))
                        }
                    }
                    val detectors = mutableMapOf(MeikiDecoder.DET_HEIGHT to det)
                    if (options.variants && options.detector == MeikiModels.DETECTOR) {
                        MeikiModels.VARIANT_HEIGHTS.forEach { h ->
                            File(dir, MeikiModels.variant(h)).takeIf { it.isFile }?.let { detectors[h] = it }
                        }
                    }
                    MeikiOcr(env, opts, detectors, env.createSession(rec.absolutePath, opts), anyWidth)
                }.getOrNull()?.also {
                    shared = it
                    sharedOptions = options
                }
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
