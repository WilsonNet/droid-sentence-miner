package io.github.wilsonnet.droidsentenceminer.manga

/**
 * Runs a [MangaPageFilter] on decoded ARGB pixels: [PageLevels] → optional AI 2x ([Upscaler], only
 * for pages drawn at least [AI_FROM] times their size) → [PageResampler] → [PageSharpen].
 * Black-and-white pages (yellowed scans included) are processed as one grey plane and come back as
 * [Result.gray]; colour pages as three planes packed into [Result.argb].
 */
object PagePipeline {
    private const val HIST_STEP = 2
    private const val MONO_STEP = 3
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE = 0xFF
    private const val OPAQUE = -0x1000000
    private const val SHRINK_SHARPNESS = 0.5f
    private const val SHRINK_BELOW = 0.75f
    private const val AI_FROM = 1.2f
    private const val AI_SHARPNESS = 0.5f

    /**
     * Processed page.
     *
     * @property width output width
     * @property height output height
     * @property gray one luma byte per pixel for monochrome pages, else null
     * @property argb packed opaque ARGB for colour pages, else null
     */
    @Suppress("UseDataClass") // Holds large arrays; data-class equality on them would be meaningless.
    class Result(val width: Int, val height: Int, val gray: ByteArray?, val argb: IntArray?)

    /**
     * Filter strengths.
     *
     * @property levels how far the paper/ink points are pulled (0..1)
     * @property gamma midtone curve; above 1 darkens thin, grey line art
     * @property sharpness CAS sharpness (0..1); 0 skips sharpening
     * @property isSharpenFirst sharpen before resizing (true), after (false) or on the smaller of the
     *   two images (null, the default: the page when it grows, the result when it shrinks)
     */
    data class Settings(val levels: Float, val gamma: Float, val sharpness: Float, val isSharpenFirst: Boolean? = null)

    /** Runs [block] over `0 until rows`, possibly split into parallel row ranges. */
    fun interface Rows {
        /** Calls [block] on ranges covering `0 until rows` and returns when all are done. */
        suspend fun run(rows: Int, block: (IntRange) -> Unit)
    }

    /** Doubles the size of 8-bit planes (one grey plane or three RGB planes). */
    fun interface Upscaler {
        /** [planes] at [width]×[height] → the same planes at twice the size. */
        suspend fun upscale2x(planes: List<ByteArray>, width: Int, height: Int): List<ByteArray>
    }

    /**
     * Filters [pixels] ([width]×[height]) with [settings] into a page of [size] (width to height),
     * running [upscaler] first when the page grows by at least [AI_FROM].
     */
    @Suppress("LongParameterList") // Pixels, their size, the target size and three collaborators.
    suspend fun run(
        pixels: IntArray,
        width: Int,
        height: Int,
        size: Pair<Int, Int>,
        settings: Settings,
        rows: Rows,
        upscaler: Upscaler? = null,
    ): Result {
        val (dstWidth, dstHeight) = size
        val points = PageLevels.points(PageLevels.histogram(pixels = pixels, width = width, height = height, step = HIST_STEP))
        val lut = PageLevels.lut(points = points, strength = settings.levels, gamma = settings.gamma)
        val isMono = PageLevels.isMonochrome(pixels = pixels, width = width, height = height, step = MONO_STEP, points = points)
        val planes = toPlanes(pixels = pixels, size = width to height, lut = lut, isMono = isMono, rows = rows)
        val isAi = upscaler != null && dstWidth >= width * AI_FROM
        val base = if (isAi && upscaler != null) upscaler.upscale2x(planes = planes, width = width, height = height) else planes
        val baseWidth = if (isAi) width * 2 else width
        val baseHeight = if (isAi) height * 2 else height
        val isShrinking = dstWidth < baseWidth * SHRINK_BELOW
        val sharpness =
            when {
                isAi -> settings.sharpness * AI_SHARPNESS
                isShrinking -> settings.sharpness * SHRINK_SHARPNESS
                else -> settings.sharpness
            }
        val isSharpenFirst = settings.isSharpenFirst ?: (dstWidth.toLong() * dstHeight > baseWidth.toLong() * baseHeight)
        val plane =
            Plane(width = baseWidth, height = baseHeight, size = size, sharpness = sharpness, isSharpenFirst = isSharpenFirst, rows = rows)
        val result =
            if (isMono) {
                Result(width = dstWidth, height = dstHeight, gray = plane.process(base[0]), argb = null)
            } else {
                val argb = IntArray(dstWidth * dstHeight) { OPAQUE }
                listOf(RED_SHIFT, GREEN_SHIFT, 0).forEachIndexed { i, shift ->
                    val out = plane.process(base[i])
                    rows.run(dstHeight) { range ->
                        packChannel(out = out, argb = argb, width = dstWidth, shift = shift, range = range)
                    }
                }
                Result(width = dstWidth, height = dstHeight, gray = null, argb = argb)
            }
        return result
    }

    // One grey plane (luma through the tone curve) or three RGB planes (each through it).
    private suspend fun toPlanes(
        pixels: IntArray,
        size: Pair<Int, Int>,
        lut: IntArray,
        isMono: Boolean,
        rows: Rows,
    ): List<ByteArray> {
        val (width, height) = size
        val planes =
            if (isMono) {
                val gray = ByteArray(width * height)
                rows.run(height) { range ->
                    fillGray(pixels = pixels, gray = gray, width = width, lut = lut, range = range)
                }
                listOf(gray)
            } else {
                listOf(RED_SHIFT, GREEN_SHIFT, 0).map { shift ->
                    val channel = ByteArray(width * height)
                    rows.run(height) { range ->
                        fillChannel(pixels = pixels, channel = channel, width = width, lut = lut, shift = shift, range = range)
                    }
                    channel
                }
            }
        return planes
    }

    private fun fillGray(pixels: IntArray, gray: ByteArray, width: Int, lut: IntArray, range: IntRange) {
        for (i in range.first * width until (range.last + 1) * width) gray[i] = lut[PageLevels.luma(pixels[i])].toByte()
    }

    @Suppress("LongParameterList") // Inner loop of a row-parallel pass; a holder object would allocate per range.
    private fun fillChannel(
        pixels: IntArray,
        channel: ByteArray,
        width: Int,
        lut: IntArray,
        shift: Int,
        range: IntRange,
    ) {
        for (i in range.first * width until (range.last + 1) * width) channel[i] = lut[pixels[i] shr shift and BYTE].toByte()
    }

    private fun packChannel(out: ByteArray, argb: IntArray, width: Int, shift: Int, range: IntRange) {
        for (i in range.first * width until (range.last + 1) * width) argb[i] = argb[i] or (out[i].toInt() and BYTE shl shift)
    }

    // Resize + sharpen of one plane; the kernels are shared by the three channels of a colour page.
    private class Plane(
        private val width: Int,
        private val height: Int,
        size: Pair<Int, Int>,
        private val sharpness: Float,
        private val isSharpenFirst: Boolean,
        private val rows: Rows,
    ) {
        private val dstWidth = size.first
        private val dstHeight = size.second
        private val isResized = dstWidth != width || dstHeight != height
        private val horizontal = if (isResized) ResampleKernel(srcLen = width, dstLen = dstWidth) else null
        private val vertical = if (isResized) ResampleKernel(srcLen = height, dstLen = dstHeight) else null

        suspend fun process(src: ByteArray): ByteArray {
            val result =
                if (isSharpenFirst) {
                    resize(sharpen(plane = src, planeWidth = width, planeHeight = height))
                } else {
                    sharpen(plane = resize(src), planeWidth = dstWidth, planeHeight = dstHeight)
                }
            return result
        }

        private suspend fun resize(src: ByteArray): ByteArray {
            val result =
                if (horizontal != null && vertical != null) {
                    val wide = ByteArray(dstWidth * height)
                    rows.run(height) { range ->
                        PageResampler.horizontal(src = src, dst = wide, kernel = horizontal, rows = range)
                    }
                    val out = ByteArray(dstWidth * dstHeight)
                    rows.run(
                        dstHeight,
                    ) { range ->
                        PageResampler.vertical(src = wide, dst = out, width = dstWidth, kernel = vertical, rows = range)
                    }
                    out
                } else {
                    src
                }
            return result
        }

        private suspend fun sharpen(plane: ByteArray, planeWidth: Int, planeHeight: Int): ByteArray {
            val result =
                if (sharpness > 0f) {
                    val out = ByteArray(planeWidth * planeHeight)
                    rows.run(planeHeight) { range ->
                        PageSharpen.cas(
                            src = plane,
                            dst = out,
                            width = planeWidth,
                            height = planeHeight,
                            sharpness = sharpness,
                            rows = range,
                        )
                    }
                    out
                } else {
                    plane
                }
            return result
        }
    }
}
