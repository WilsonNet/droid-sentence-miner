package io.github.wilsonnet.droidsentenceminer.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import io.github.wilsonnet.droidsentenceminer.manga.PagePipeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * AI 2x upscaling of a page with Anime4K's CNNs (bloc97/Anime4K, MIT): Restore-CNN-M cleans blur
 * and compression, Upscale-CNN-x2-M doubles the size, both chained in one tiny ONNX graph in
 * `assets/anime4k` (built by `tools/manga-eval/anime4k_onnx.py`). Runs in [TILE]-pixel tiles
 * with a [HALO] of context so a whole colour page never holds every layer in memory at once.
 */
class PageUpscaler private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val dispatcher: CoroutineDispatcher,
) : PagePipeline.Upscaler {
    override suspend fun upscale2x(planes: List<ByteArray>, width: Int, height: Int): List<ByteArray> {
        val outWidth = width * 2
        val out = List(planes.size) { ByteArray(outWidth * height * 2) }
        withContext(dispatcher) {
            for (y0 in 0 until height step TILE) {
                for (x0 in 0 until width step TILE) {
                    ensureActive()
                    val tile = Tile(x0 = x0, y0 = y0, width = width, height = height)
                    val result = runTile(planes = planes, tile = tile, width = width)
                    writeTile(result = result, tile = tile, out = out, outWidth = outWidth)
                }
            }
        }
        return out
    }

    // Input window (with halo) and the interior it is responsible for.
    private class Tile(x0: Int, y0: Int, width: Int, height: Int) {
        val x1 = min(a = x0 + TILE, b = width)
        val y1 = min(a = y0 + TILE, b = height)
        val left = max(a = x0 - HALO, b = 0)
        val top = max(a = y0 - HALO, b = 0)
        val right = min(a = x1 + HALO, b = width)
        val bottom = min(a = y1 + HALO, b = height)
        val tileWidth = right - left
        val tileHeight = bottom - top
        val innerX = x0
        val innerY = y0
    }

    private fun runTile(planes: List<ByteArray>, tile: Tile, width: Int): FloatArray {
        val area = tile.tileWidth * tile.tileHeight
        val input = FloatArray(CHANNELS * area)
        for (c in 0 until CHANNELS) {
            val plane = planes[min(a = c, b = planes.size - 1)]
            var i = c * area
            for (y in tile.top until tile.bottom) {
                val row = y * width
                for (x in tile.left until tile.right) input[i++] = (plane[row + x].toInt() and BYTE) / MAX
            }
        }
        val shape = longArrayOf(1, CHANNELS.toLong(), tile.tileHeight.toLong(), tile.tileWidth.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(INPUT to tensor)).use { result ->
                val buffer = (result.get(0) as OnnxTensor).floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
    }

    private fun writeTile(result: FloatArray, tile: Tile, out: List<ByteArray>, outWidth: Int) {
        val resWidth = tile.tileWidth * 2
        val resArea = resWidth * tile.tileHeight * 2
        val offX = (tile.innerX - tile.left) * 2
        val offY = (tile.innerY - tile.top) * 2
        val isGray = out.size == 1
        for (y in 0 until (tile.y1 - tile.innerY) * 2) {
            val src = (offY + y) * resWidth + offX
            val dst = (tile.innerY * 2 + y) * outWidth + tile.innerX * 2
            for (x in 0 until (tile.x1 - tile.innerX) * 2) {
                val i = src + x
                if (isGray) {
                    val v = result[i] * LUMA_R + result[resArea + i] * LUMA_G + result[2 * resArea + i] * LUMA_B
                    out[0][dst + x] = toByte(v)
                } else {
                    for (c in 0 until CHANNELS) out[c][dst + x] = toByte(result[c * resArea + i])
                }
            }
        }
    }

    private fun toByte(v: Float): Byte =
        (v.coerceIn(minimumValue = 0f, maximumValue = 1f) * MAX + ROUND).toInt().toByte()

    /** Loading helpers. */
    companion object {
        private const val TAG = "DSM-Enhance"
        private const val MODEL = "anime4k/restore_x2_M.onnx"
        private const val INPUT = "page"
        private const val TILE = 256
        private const val HALO = 16
        private const val CHANNELS = 3
        private const val BYTE = 0xFF
        private const val MAX = 255f
        private const val ROUND = 0.5f
        private const val LUMA_R = 0.299f
        private const val LUMA_G = 0.587f
        private const val LUMA_B = 0.114f
        private const val THREADS = 4

        @Volatile
        private var shared: PageUpscaler? = null

        /** The loaded upscaler, if [get] has run (prefetch code without a [Context]). */
        val loaded: PageUpscaler? get() = shared

        /** Process-wide upscaler, loaded from the APK's assets on first use; null if it failed. */
        fun get(context: Context, dispatcher: CoroutineDispatcher = Dispatchers.Default): PageUpscaler? {
            val upscaler =
                shared ?: synchronized(this) {
                    shared ?: runCatching { load(context = context, dispatcher = dispatcher) }
                        .onFailure { Log.w(TAG, "AI upscaler failed to load", it) }
                        .getOrNull()
                        ?.also { shared = it }
                }
            return upscaler
        }

        private fun load(context: Context, dispatcher: CoroutineDispatcher): PageUpscaler {
            val bytes = context.assets.open(MODEL).use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            val opts =
                OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(THREADS)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                }
            return PageUpscaler(env = env, session = env.createSession(bytes, opts), dispatcher = dispatcher)
        }
    }
}
