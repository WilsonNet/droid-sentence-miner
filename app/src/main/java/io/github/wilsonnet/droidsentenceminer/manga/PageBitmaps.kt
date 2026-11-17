package io.github.wilsonnet.droidsentenceminer.manga

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.ByteBuffer
import kotlin.math.min

/** Bitmap plumbing for [MangaPageEnhancer]: pipeline results to bitmaps, row parallelism, page keys. */
internal object PageBitmaps {
    private const val BYTE = 0xFF
    private const val INK = 255
    private const val MIN_ROWS_PER_TASK = 64
    private const val MAX_TASKS = 8
    private const val FINGERPRINT_SAMPLES = 256
    private const val FINGERPRINT_STEP_X = 7919
    private const val FINGERPRINT_STEP_Y = 104_729
    private const val HASH_SEED = 1_469_598_103_934_665_603L
    private const val HASH_PRIME = 1_099_511_628_211L
    private const val HEX = 16

    private val maxTasks = Runtime.getRuntime().availableProcessors().coerceIn(minimumValue = 1, maximumValue = MAX_TASKS)

    /** True when [bitmap] is a monochrome result to draw as black ink over white. */
    fun isInk(bitmap: Bitmap): Boolean = bitmap.config == Bitmap.Config.ALPHA_8

    /** Monochrome pages become `ALPHA_8` ink (255 − luma), colour pages `ARGB_8888`. */
    fun toBitmap(page: PagePipeline.Result): Bitmap {
        val gray = page.gray
        val argb = page.argb
        return if (gray != null) {
            val bitmap = createBitmap(width = page.width, height = page.height, config = Bitmap.Config.ALPHA_8)
            val stride = bitmap.rowBytes
            val ink = ByteArray(stride * page.height)
            for (y in 0 until page.height) {
                val from = y * page.width
                val to = y * stride
                for (x in 0 until page.width) ink[to + x] = (INK - (gray[from + x].toInt() and BYTE)).toByte()
            }
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(ink))
            bitmap
        } else {
            val bitmap = createBitmap(width = page.width, height = page.height)
            bitmap.setPixels(argb ?: IntArray(page.width * page.height), 0, page.width, 0, 0, page.width, page.height)
            bitmap
        }
    }

    /** Runs [block] over `0 until count` in up to one row range per core. */
    suspend fun parallelRows(count: Int, dispatcher: CoroutineDispatcher, block: (IntRange) -> Unit) {
        val tasks = (count / MIN_ROWS_PER_TASK).coerceIn(minimumValue = 1, maximumValue = maxTasks)
        val chunk = (count + tasks - 1) / tasks
        coroutineScope {
            val jobs =
                (0 until tasks).map { t ->
                    async(dispatcher) {
                        val from = t * chunk
                        val to = min(a = count, b = from + chunk) - 1
                        if (from <= to) block(from..to)
                    }
                }
            jobs.awaitAll()
        }
    }

    /**
     * Key for the page's pixels, not the Bitmap object: the reader and the prefetcher can decode the
     * same page twice (both missed the decode cache), and an identity key never matched across them.
     */
    fun fingerprint(src: Bitmap): String {
        var hash = HASH_SEED
        for (i in 0 until FINGERPRINT_SAMPLES) {
            val x = i * FINGERPRINT_STEP_X % src.width
            val y = i * FINGERPRINT_STEP_Y % src.height
            hash = hash * HASH_PRIME + src[x, y]
        }
        return "${src.width}x${src.height}#${hash.toULong().toString(radix = HEX)}"
    }
}
