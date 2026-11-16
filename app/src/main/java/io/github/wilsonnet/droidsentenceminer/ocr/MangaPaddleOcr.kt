package io.github.wilsonnet.droidsentenceminer.ocr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.platform.Fp16Conversions
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * PP-OCRv6_manga (Kellenok, Apache-2.0) on ONNX Runtime: a DB line detector and a CTC recognizer
 * fine-tuned on manga. Vertical lines (taller than wide) are rotated 90° counter-clockwise before
 * recognition, as the model was trained; per-character boxes come from the CTC timesteps, so taps
 * land on the right glyph. Mirrors the model card's `examples/onnx_openvino.py` (thresh 0.15,
 * box thresh 0.25, unclip 1.4, long side 960).
 */
class MangaPaddleOcr private constructor(
    private val env: OrtEnvironment,
    private val det: OrtSession,
    private val rec: OrtSession,
    private val dictionary: List<String>,
) {
    /**
     * Detection and recognition time of the last [recognizeLines] call.
     *
     * @property detMs line detection time
     * @property recMs recognition time of all lines
     * @property boxes detected line boxes
     * @property lines lines read (boxes with readable text)
     */
    data class Timings(val detMs: Long = 0, val recMs: Long = 0, val boxes: Int = 0, val lines: Int = 0)

    /** Timings of the most recent page. */
    @Volatile
    var lastTimings: Timings = Timings()
        private set

    /** Reads every text line on [bitmap] (BGR model input), in page pixel coordinates. */
    fun recognizeLines(bitmap: Bitmap): List<OcrLine> {
        val lines: List<OcrLine>
        synchronized(this) {
            val started = System.nanoTime()
            val boxes = detect(bitmap)
            val detected = System.nanoTime()
            lines = boxes.mapNotNull { recognizeLine(bitmap = bitmap, box = it) }
            lastTimings =
                Timings(
                    detMs = (detected - started) / NANOS_PER_MS,
                    recMs = (System.nanoTime() - detected) / NANOS_PER_MS,
                    boxes = boxes.size,
                    lines = lines.size,
                )
        }
        return lines
    }

    /** Releases both ONNX sessions. */
    fun close() {
        runCatching { det.close() }
        runCatching { rec.close() }
    }

    private fun detect(bitmap: Bitmap): List<Rect> {
        val size = detectorSize(width = bitmap.width, height = bitmap.height)
        val w = size.first
        val h = size.second
        val scaled = scaleTo(bitmap = bitmap, width = w, height = h)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        val plane = w * h
        val input = FloatArray(CHANNELS * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            input[i] = ((p and BYTE_MASK) / MAX_BYTE - MEAN_0) / STD_0
            input[plane + i] = ((p shr GREEN_SHIFT and BYTE_MASK) / MAX_BYTE - MEAN_1) / STD_1
            input[2 * plane + i] = ((p shr RED_SHIFT and BYTE_MASK) / MAX_BYTE - MEAN_2) / STD_2
        }
        val prob = run(session = det, input = input, shape = longArrayOf(1, CHANNELS.toLong(), h.toLong(), w.toLong()))
        val sx = bitmap.width.toFloat() / w
        val sy = bitmap.height.toFloat() / h
        val boxes =
            PaddleDecoder.boxes(
                prob = prob.values,
                width = w,
                height = h,
                thresh = DET_THRESH,
                boxThresh = BOX_THRESH,
                unclipRatio = UNCLIP_RATIO,
            )
        val rects =
            boxes.map { box ->
                Rect(
                    (box.left * sx).toInt().coerceIn(minimumValue = 0, maximumValue = bitmap.width - 1),
                    (box.top * sy).toInt().coerceIn(minimumValue = 0, maximumValue = bitmap.height - 1),
                    ceil(box.right * sx).toInt().coerceIn(minimumValue = 1, maximumValue = bitmap.width),
                    ceil(box.bottom * sy).toInt().coerceIn(minimumValue = 1, maximumValue = bitmap.height),
                )
            }
        return rects.filter { it.width() >= MIN_BOX_PX && it.height() >= MIN_BOX_PX }
    }

    private fun recognizeLine(bitmap: Bitmap, box: Rect): OcrLine? {
        val isVertical = box.height() > box.width()
        val along = if (isVertical) box.height() else box.width()
        val across = if (isVertical) box.width() else box.height()
        val w =
            min(a = MAX_REC_WIDTH, b = max(a = MIN_REC_WIDTH, b = (REC_HEIGHT.toFloat() * along / across).roundToInt()))
        val pixels = linePixels(bitmap = bitmap, box = box, isVertical = isVertical, width = w)
        val plane = w * REC_HEIGHT
        val input = FloatArray(CHANNELS * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            input[i] = (p and BYTE_MASK) / HALF_BYTE - 1f
            input[plane + i] = (p shr GREEN_SHIFT and BYTE_MASK) / HALF_BYTE - 1f
            input[2 * plane + i] = (p shr RED_SHIFT and BYTE_MASK) / HALF_BYTE - 1f
        }
        val out =
            run(session = rec, input = input, shape = longArrayOf(1, CHANNELS.toLong(), REC_HEIGHT.toLong(), w.toLong()))
        val timesteps = out.shape[1].toInt()
        val classes = out.shape[2].toInt()
        val glyphs = PaddleDecoder.ctcDecode(scores = out.values, timesteps = timesteps, classes = classes, dictionary = dictionary)
        val text = glyphs.joinToString("") { it.char }
        val score = if (glyphs.isEmpty()) 0f else glyphs.map { it.score }.average().toFloat()
        return if (text.isBlank() || score < MIN_SCORE) {
            null
        } else {
            OcrLine(
                text = text,
                box = BoundingBox.from(box),
                confidence = score,
                chars = glyphBoxes(glyphs = glyphs, box = box, isVertical = isVertical, timesteps = timesteps),
            )
        }
    }

    // The line crop scaled to REC_HEIGHT rows, rotated 90° counter-clockwise when vertical
    // (rotated column u = original row, rotated row v = original column from the right).
    private fun linePixels(bitmap: Bitmap, box: Rect, isVertical: Boolean, width: Int): IntArray {
        val out = IntArray(width * REC_HEIGHT)
        if (isVertical) {
            val scaled = createBitmap(width = REC_HEIGHT, height = width)
            Canvas(scaled).drawBitmap(bitmap, box, Rect(0, 0, REC_HEIGHT, width), Paint(Paint.FILTER_BITMAP_FLAG))
            val src = IntArray(REC_HEIGHT * width)
            scaled.getPixels(src, 0, REC_HEIGHT, 0, 0, REC_HEIGHT, width)
            scaled.recycle()
            for (v in 0 until REC_HEIGHT) {
                val x = REC_HEIGHT - 1 - v
                for (u in 0 until width) {
                    out[v * width + u] = src[u * REC_HEIGHT + x]
                }
            }
        } else {
            val scaled = createBitmap(width = width, height = REC_HEIGHT)
            Canvas(scaled).drawBitmap(bitmap, box, Rect(0, 0, width, REC_HEIGHT), Paint(Paint.FILTER_BITMAP_FLAG))
            scaled.getPixels(out, 0, width, 0, 0, width, REC_HEIGHT)
            scaled.recycle()
        }
        return out
    }

    // Per-character boxes: each glyph spans from the midpoint to its previous neighbour to the
    // midpoint to its next one along the line (CTC peaks are narrower than the glyphs).
    private fun glyphBoxes(
        glyphs: List<PaddleDecoder.Glyph>,
        box: Rect,
        isVertical: Boolean,
        timesteps: Int,
    ): List<BoundingBox> {
        val length = if (isVertical) box.height() else box.width()
        val centers = glyphs.map { (it.start + it.end) / 2f / timesteps * length }
        val out = ArrayList<BoundingBox>()
        centers.forEachIndexed { i, c ->
            val lo = if (i == 0) 0f else (centers[i - 1] + c) / 2f
            val hi = if (i == centers.lastIndex) length.toFloat() else (c + centers[i + 1]) / 2f
            val piece =
                if (isVertical) {
                    BoundingBox(
                        left = box.left,
                        top = box.top + lo.toInt(),
                        right = box.right,
                        bottom = box.top + max(a = lo + 1f, b = hi).toInt(),
                    )
                } else {
                    BoundingBox(
                        left = box.left + lo.toInt(),
                        top = box.top,
                        right = box.left + max(a = lo + 1f, b = hi).toInt(),
                        bottom = box.bottom,
                    )
                }
            repeat(glyphs[i].char.length) { out += piece }
        }
        return out
    }

    // Model output values (upcast to float) and shape.
    @Suppress("ArrayInDataClass") // Never compared; a holder for one model run.
    private data class Output(val values: FloatArray, val shape: LongArray)

    private fun run(session: OrtSession, input: FloatArray, shape: LongArray): Output {
        val name = session.inputNames.first()
        val isHalf = (session.inputInfo.getValue(name).info as TensorInfo).type == OnnxJavaType.FLOAT16
        val tensor =
            if (isHalf) {
                val bytes = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.nativeOrder())
                val halves = bytes.asShortBuffer()
                for (v in input) halves.put(Fp16Conversions.floatToFp16(v))
                bytes.rewind()
                OnnxTensor.createTensor(env, bytes, shape, OnnxJavaType.FLOAT16)
            } else {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
            }
        return tensor.use { t ->
            session.run(mapOf(name to t)).use { result ->
                val out = result[0] as OnnxTensor
                val fb = out.floatBuffer
                Output(values = FloatArray(fb.remaining()).also { fb.get(it) }, shape = out.info.shape)
            }
        }
    }

    /** Loading and model-size helpers. */
    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val CHANNELS = 3
        private const val BYTE_MASK = 0xFF
        private const val GREEN_SHIFT = 8
        private const val RED_SHIFT = 16
        private const val MAX_BYTE = 255f
        private const val HALF_BYTE = 127.5f

        // ImageNet mean/std applied to BGR planes, as PaddleOCR does on cv2 images.
        private const val MEAN_0 = 0.485f
        private const val MEAN_1 = 0.456f
        private const val MEAN_2 = 0.406f
        private const val STD_0 = 0.229f
        private const val STD_1 = 0.224f
        private const val STD_2 = 0.225f
        private const val DET_LONG_SIDE = 960f
        private const val STRIP_ASPECT = 2f
        private const val STRIP_PIXEL_FRACTION = 0.75f
        private const val STRIDE = 32
        private const val DET_THRESH = 0.15f
        private const val BOX_THRESH = 0.25f
        private const val UNCLIP_RATIO = 1.4f
        private const val MIN_BOX_PX = 4
        private const val REC_HEIGHT = 48
        private const val MIN_REC_WIDTH = 16
        private const val MAX_REC_WIDTH = 3200
        private const val MIN_SCORE = 0.3f

        /**
         * Detector input size: long side 960 (also upscaling small pages), but long webtoon strips
         * (aspect > 2) get the pixel budget of a 3:4 page at 960; both sides multiples of 32.
         */
        fun detectorSize(width: Int, height: Int): Pair<Int, Int> {
            val longSide = max(a = width, b = height).toFloat()
            val shortSide = min(a = width, b = height).toFloat().coerceAtLeast(1f)
            val ratio =
                if (longSide / shortSide > STRIP_ASPECT) {
                    min(a = 1f, b = sqrt(STRIP_PIXEL_FRACTION * DET_LONG_SIDE * DET_LONG_SIDE / (width.toFloat() * height)))
                } else {
                    DET_LONG_SIDE / longSide
                }
            val w = max(a = STRIDE, b = (width * ratio / STRIDE).roundToInt() * STRIDE)
            val h = max(a = STRIDE, b = (height * ratio / STRIDE).roundToInt() * STRIDE)
            return w to h
        }

        internal fun scaleTo(bitmap: Bitmap, width: Int, height: Int): Bitmap {
            val out = createBitmap(width = width, height = height)
            Canvas(out).drawBitmap(bitmap, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }

        /** Loads the detector, recognizer and dictionary from [dir]. */
        fun load(dir: File, detector: String, recognizer: String, dictionary: String, threads: Int): MangaPaddleOcr {
            val env = OrtEnvironment.getEnvironment()
            val opts =
                OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(threads)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                }
            val dict = PaddleDecoder.parseDictionary(File(dir, dictionary).readText())
            return MangaPaddleOcr(
                env = env,
                det = env.createSession(File(dir, detector).absolutePath, opts),
                rec = env.createSession(File(dir, recognizer).absolutePath, opts),
                dictionary = dict,
            )
        }
    }
}
