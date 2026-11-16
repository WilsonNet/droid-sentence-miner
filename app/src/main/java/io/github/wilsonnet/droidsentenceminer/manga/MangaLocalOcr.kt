package io.github.wilsonnet.droidsentenceminer.manga

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.github.wilsonnet.droidsentenceminer.ocr.BubbleDetector
import io.github.wilsonnet.droidsentenceminer.ocr.MangaPaddleOcr
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import java.io.File

/**
 * The downloaded on-device manga OCR ([MangaOcrModels]): PP-OCRv6_manga lines + bubble regions.
 * Loaded once per process ([get]); [regions] also serves Google Lens pages for grouping.
 */
class MangaLocalOcr private constructor(private val paddle: MangaPaddleOcr, private val bubbles: BubbleDetector) {
    /** Lines and text regions of one page plus per-stage timings. */
    data class Page(
        /** Recognized lines (vertical and horizontal) in page pixels. */
        val lines: List<OcrLine>,
        /** Detected text regions used to group [lines]. */
        val regions: List<MangaRegionGrouping.TextRegion>,
        /** Region detection time. */
        val regionMs: Long,
        /** Line detection time. */
        val detMs: Long,
        /** Line recognition time. */
        val recMs: Long,
        /** Detected line boxes (before dropping unreadable ones). */
        val boxes: Int = 0,
    )

    /** Runs the region detector and the line OCR on [bitmap]. */
    fun recognize(bitmap: Bitmap): Page {
        val started = System.nanoTime()
        val regions = regions(bitmap)
        val regionMs = (System.nanoTime() - started) / NANOS_PER_MS
        val lines = paddle.recognizeLines(bitmap)
        val timings = paddle.lastTimings
        return Page(
            lines = lines,
            regions = regions,
            regionMs = regionMs,
            detMs = timings.detMs,
            recMs = timings.recMs,
            boxes = timings.boxes,
        )
    }

    /** Text regions (text inside bubbles and free text) on [bitmap]. */
    fun regions(bitmap: Bitmap): List<MangaRegionGrouping.TextRegion> {
        val texts = bubbles.detect(bitmap).filter { it.kind != BubbleDetector.Kind.BUBBLE }
        return texts.map { MangaRegionGrouping.TextRegion(box = it.box) }
    }

    /** Process-wide instance. */
    companion object {
        private const val TAG = "DSM-MangaOCR"
        private const val NANOS_PER_MS = 1_000_000L
        private const val THREADS = 4

        @Volatile
        private var shared: MangaLocalOcr? = null

        /** The loaded models, or null when they aren't downloaded (or failed to load). */
        fun get(context: Context): MangaLocalOcr? {
            val current = shared
            return when {
                current != null -> current
                !MangaOcrModels.isInstalled(context) -> null
                else -> synchronized(this) { shared ?: load(MangaOcrModels.dir(context))?.also { shared = it } }
            }
        }

        /** Closes the models (after they were removed). */
        fun unload() {
            synchronized(this) {
                val current = shared
                shared = null
                current?.run {
                    paddle.close()
                    bubbles.close()
                }
            }
        }

        private fun load(dir: File): MangaLocalOcr? {
            val loaded =
                runCatching {
                    val paddle =
                        MangaPaddleOcr.load(
                            dir = dir,
                            detector = MangaOcrModels.DETECTOR,
                            recognizer = MangaOcrModels.RECOGNIZER,
                            dictionary = MangaOcrModels.DICTIONARY,
                            threads = THREADS,
                        )
                    val bubbles = BubbleDetector.load(file = File(dir, MangaOcrModels.BUBBLES), threads = THREADS)
                    MangaLocalOcr(paddle = paddle, bubbles = bubbles)
                }
            return loaded.onFailure { Log.w(TAG, "Manga OCR models failed to load", it) }.getOrNull()
        }
    }
}
