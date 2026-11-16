package io.github.wilsonnet.droidsentenceminer.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.roundToInt

/**
 * ogkalu's comic-text-and-bubble-detector (RT-DETR-v2, Apache-2.0; the int8 `v4-s` export):
 * finds speech bubbles, text inside bubbles and free text on a comic page. Text regions are what
 * groups OCR lines into one bubble — the step Manatan's geometry-only merge gets wrong when a
 * bubble's columns differ in width (kana columns are thinner than kanji ones).
 */
class BubbleDetector private constructor(private val env: OrtEnvironment, private val session: OrtSession) {
    /** Region classes the model predicts. */
    enum class Kind {
        /** A speech bubble outline. */
        BUBBLE,

        /** Text inside a speech bubble. */
        TEXT_BUBBLE,

        /** Text outside bubbles (narration boxes, signs, sound effects). */
        TEXT_FREE,
    }

    /**
     * One detected region in page pixels.
     *
     * @property box region bounds
     * @property kind what the region holds
     * @property score detector confidence
     */
    data class Region(val box: BoundingBox, val kind: Kind, val score: Float)

    /** Detects regions on [bitmap] scoring at least [minScore]. */
    fun detect(bitmap: Bitmap, minScore: Float = MIN_SCORE): List<Region> {
        val scaled = createBitmap(width = INPUT, height = INPUT)
        Canvas(scaled).drawBitmap(bitmap, null, Rect(0, 0, INPUT, INPUT), Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(INPUT * INPUT)
        scaled.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        scaled.recycle()
        val plane = INPUT * INPUT
        val input = FloatArray(CHANNELS * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            input[i] = (p shr RED_SHIFT and BYTE_MASK) / MAX_BYTE
            input[plane + i] = (p shr GREEN_SHIFT and BYTE_MASK) / MAX_BYTE
            input[2 * plane + i] = (p and BYTE_MASK) / MAX_BYTE
        }
        val regions = ArrayList<Region>()
        synchronized(this) {
            val image =
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(input),
                    longArrayOf(1, CHANNELS.toLong(), INPUT.toLong(), INPUT.toLong()),
                )
            val sizes =
                OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf(bitmap.width.toLong(), bitmap.height.toLong())),
                    longArrayOf(1, 2),
                )
            image.use {
                sizes.use {
                    session.run(mapOf("images" to image, "orig_target_sizes" to sizes)).use { out ->
                        regions += decode(out = out, width = bitmap.width, height = bitmap.height, minScore = minScore)
                    }
                }
            }
        }
        return regions
    }

    private fun decode(out: OrtSession.Result, width: Int, height: Int, minScore: Float): List<Region> {
        val labels = (out.get("labels").get() as OnnxTensor).longBuffer
        val boxes = (out.get("boxes").get() as OnnxTensor).floatBuffer
        val scores = (out.get("scores").get() as OnnxTensor).floatBuffer
        val result = ArrayList<Region>()
        for (i in 0 until scores.remaining()) {
            val score = scores.get(i)
            val label = labels.get(i).toInt()
            if (score >= minScore && label in Kind.entries.indices) {
                val box =
                    BoundingBox(
                        left = boxes.get(i * BOX_VALUES).roundToInt().coerceIn(minimumValue = 0, maximumValue = width),
                        top = boxes.get(i * BOX_VALUES + 1).roundToInt().coerceIn(minimumValue = 0, maximumValue = height),
                        right = boxes.get(i * BOX_VALUES + 2).roundToInt().coerceIn(minimumValue = 0, maximumValue = width),
                        bottom = boxes.get(i * BOX_VALUES + BOX_BOTTOM).roundToInt().coerceIn(minimumValue = 0, maximumValue = height),
                    )
                if (box.width > 1 && box.height > 1) result += Region(box = box, kind = Kind.entries[label], score = score)
            }
        }
        return result
    }

    /** Releases the ONNX session. */
    fun close() {
        runCatching { session.close() }
    }

    /** Loading helpers. */
    companion object {
        private const val INPUT = 640
        private const val CHANNELS = 3
        private const val BYTE_MASK = 0xFF
        private const val GREEN_SHIFT = 8
        private const val RED_SHIFT = 16
        private const val MAX_BYTE = 255f
        private const val BOX_VALUES = 4
        private const val BOX_BOTTOM = 3
        private const val MIN_SCORE = 0.3f

        /** Loads the detector model [file]. */
        fun load(file: File, threads: Int): BubbleDetector {
            val env = OrtEnvironment.getEnvironment()
            val opts =
                OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(threads)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                }
            return BubbleDetector(env = env, session = env.createSession(file.absolutePath, opts))
        }
    }
}
