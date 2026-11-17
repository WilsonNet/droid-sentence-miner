package io.github.wilsonnet.droidsentenceminer.manga

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Display copies of manga pages run through [PagePipeline] at the exact size they are drawn, cached
 * per page, filter, size and AI step. The decoded page stays untouched for OCR and box coordinates.
 * Black-and-white results are `ALPHA_8` bitmaps holding *ink* (255 − luma), a quarter of the
 * memory of ARGB; [isInk] tells the drawing code to paint them black over white paper.
 */
object MangaPageEnhancer {
    private const val TAG = "DSM-Enhance"
    private const val CACHE_SHARE = 6
    private const val CACHE_CAP = 192L * 1024 * 1024
    private const val MAX_SCALE = 3f
    private const val SAME_SIZE = 0.03f
    private const val NANOS_PER_MS = 1_000_000L

    private val cache =
        object : LruCache<String, Bitmap>(min(a = Runtime.getRuntime().maxMemory() / CACHE_SHARE, b = CACHE_CAP).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }

    // Quick filters never wait behind a page in the AI upscaler.
    private val quickLock = Mutex()
    private val aiLock = Mutex()

    @Volatile
    private var prefetchJob: Job? = null

    @Volatile
    private var lastBox: Pair<Int, Int>? = null

    /** Last run's timing summary (debug API, logcat `DSM-Enhance`). */
    @Volatile
    var lastTimings: String = ""
        private set

    /** True when [bitmap] is a monochrome result to draw as black ink over white. */
    fun isInk(bitmap: Bitmap): Boolean = PageBitmaps.isInk(bitmap)

    /**
     * Size to render [src] at inside a [boxWidth]×[boxHeight] slot (fit, centred). While [isZoomed],
     * pages bigger than the slot render at their own size so zooming in keeps their detail.
     */
    fun targetSize(src: Bitmap, boxWidth: Int, boxHeight: Int, isZoomed: Boolean): Pair<Int, Int> {
        val fit = min(a = boxWidth.toFloat() / src.width, b = boxHeight.toFloat() / src.height)
        val scale = (if (isZoomed && fit < 1f) 1f else fit).coerceAtMost(MAX_SCALE)
        val isSame = abs(scale - 1f) < SAME_SIZE
        return if (isSame) {
            src.width to src.height
        } else {
            (src.width * scale).roundToInt().coerceAtLeast(1) to (src.height * scale).roundToInt().coerceAtLeast(1)
        }
    }

    /** Remembers the slot pages are drawn in, so [prefetch] can prepare the next pages at that size. */
    fun rememberBox(width: Int, height: Int) {
        if (width > 0 && height > 0) lastBox = width to height
    }

    /** Cached result for [src] at [size] (with the AI [upscaler] or without), or null. */
    @Suppress("ExplicitCollectionElementAccessMethod") // android.util.LruCache has no Kotlin [] operator.
    fun cached(
        src: Bitmap,
        filter: MangaPageFilter,
        size: Pair<Int, Int>,
        upscaler: PagePipeline.Upscaler? = null,
    ): Bitmap? =
        if (filter == MangaPageFilter.OFF) src else cache.get(key(src = src, filter = filter, size = size, isAi = upscaler != null))

    /**
     * Prepares the pages [sources] yields, in order, in the background at the last slot size, so a
     * page turn shows them at once. A new call cancels the previous one (the reader moved on).
     */
    fun prefetch(
        scope: CoroutineScope,
        filter: MangaPageFilter,
        upscaler: PagePipeline.Upscaler?,
        sources: suspend () -> List<Bitmap>,
    ) {
        prefetchJob?.cancel()
        val box = lastBox
        if (filter != MangaPageFilter.OFF && box != null) {
            prefetchJob =
                scope.launch {
                    for (src in sources()) {
                        val size = targetSize(src = src, boxWidth = box.first, boxHeight = box.second, isZoomed = false)
                        if (cached(src = src, filter = filter, size = size, upscaler = upscaler) == null) {
                            enhance(src = src, filter = filter, size = size, upscaler = upscaler)
                        }
                    }
                }
        }
    }

    /**
     * [src] filtered with [filter] at [size], through the AI [upscaler] when given; [src] itself for
     * [MangaPageFilter.OFF] or on failure.
     */
    suspend fun enhance(
        src: Bitmap,
        filter: MangaPageFilter,
        size: Pair<Int, Int>,
        upscaler: PagePipeline.Upscaler? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): Bitmap {
        val result =
            if (filter == MangaPageFilter.OFF) {
                src
            } else {
                val lock = if (upscaler != null) aiLock else quickLock
                lock.withLock {
                    cached(src = src, filter = filter, size = size, upscaler = upscaler)
                        ?: renderAndCache(src = src, filter = filter, size = size, upscaler = upscaler, dispatcher = dispatcher)
                        ?: src
                }
            }
        return result
    }

    /** Drops every cached page (memory pressure, filter change). */
    fun clear() {
        cache.evictAll()
    }

    /** Runs the pipeline on [src] with [settings] at [size], uncached (also the debug API's benchmark). */
    internal suspend fun render(
        src: Bitmap,
        settings: PagePipeline.Settings,
        size: Pair<Int, Int>,
        upscaler: PagePipeline.Upscaler? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): Bitmap {
        val started = System.nanoTime()
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        val rows =
            PagePipeline.Rows { count, block ->
                PageBitmaps.parallelRows(count = count, dispatcher = dispatcher, block = block)
            }
        val page =
            PagePipeline.run(
                pixels = pixels,
                width = src.width,
                height = src.height,
                size = size,
                settings = settings,
                rows = rows,
                upscaler = upscaler,
            )
        val filtered = System.nanoTime()
        val bitmap = PageBitmaps.toBitmap(page)
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        val kind = if (page.gray != null) "mono" else "colour"
        lastTimings =
            "${src.width}x${src.height} -> ${page.width}x${page.height} $settings ai=${upscaler != null} $kind: " +
            "${(filtered - started) / NANOS_PER_MS} ms filter, $ms ms total"
        Log.i(TAG, lastTimings)
        return bitmap
    }

    // A failed page (out of memory, ONNX error) falls back to the plain page; cancellation propagates.
    @Suppress("TooGenericExceptionCaught") // Any failure of the optional filter must not break reading.
    private suspend fun renderAndCache(
        src: Bitmap,
        filter: MangaPageFilter,
        size: Pair<Int, Int>,
        upscaler: PagePipeline.Upscaler?,
        dispatcher: CoroutineDispatcher,
    ): Bitmap? {
        val rendered =
            try {
                withContext(dispatcher) {
                    render(src = src, settings = filter.settings, size = size, upscaler = upscaler, dispatcher = dispatcher)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Page filter failed", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Page filter ran out of memory", e)
                cache.evictAll()
                null
            }
        if (rendered != null) cache.put(key(src = src, filter = filter, size = size, isAi = upscaler != null), rendered)
        return rendered
    }

    private fun key(src: Bitmap, filter: MangaPageFilter, size: Pair<Int, Int>, isAi: Boolean): String =
        "${PageBitmaps.fingerprint(src)}:${filter.id}:${size.first}x${size.second}:$isAi"
}
