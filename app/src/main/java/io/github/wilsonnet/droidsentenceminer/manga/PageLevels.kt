package io.github.wilsonnet.droidsentenceminer.manga

import kotlin.math.pow

/**
 * Tone curve for a scanned page, the "Levels" step of scan cleanup guides: the paper peak goes to
 * white, the ink peak to black, then a gamma darkens the anti-aliased greys of the line art.
 * Also tells black-and-white pages (including yellowed or sepia scans) from colour ones.
 */
internal object PageLevels {
    private const val LEVELS = 256
    private const val MAX = 255
    private const val MAX_F = 255f
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE = 0xFF
    private const val LUMA_R = 77
    private const val LUMA_G = 150
    private const val LUMA_B = 29
    private const val LUMA_SHIFT = 8

    // Paper: the most common value above PAPER_FROM, if it covers PAPER_SHARE of the page.
    private const val PAPER_FROM = 150
    private const val PAPER_MIN = 170
    private const val PAPER_SHARE = 0.03f
    private const val PAPER_MARGIN = 3

    // Ink: the most common value below INK_TO, if it covers INK_SHARE of the page.
    private const val INK_TO = 90
    private const val INK_SHARE = 0.01f
    private const val INK_MARGIN = 3
    private const val INK_MAX = 80
    private const val MIN_RANGE = 96
    private const val CHANNELS = 3
    private const val HALF = 0.5f

    // Monochrome: almost every pixel sits on the line from the ink colour to the paper colour.
    private const val PAPER_BAND = 8
    private const val INK_BAND = 24
    private const val OFF_LINE = 18
    private const val OFF_LINE_SHARE = 0.01f

    /**
     * Black and white points of a page.
     *
     * @property black luma that becomes pure black
     * @property white luma that becomes pure white
     */
    data class Points(val black: Int, val white: Int)

    /** Luma 0..255 of packed ARGB [pixel] (BT.601 integer weights). */
    fun luma(pixel: Int): Int {
        val r = pixel shr RED_SHIFT and BYTE
        val g = pixel shr GREEN_SHIFT and BYTE
        val b = pixel and BYTE
        return r * LUMA_R + g * LUMA_G + b * LUMA_B shr LUMA_SHIFT
    }

    /** 256-bin luma histogram of every [step]th pixel of every [step]th row. */
    fun histogram(pixels: IntArray, width: Int, height: Int, step: Int): IntArray {
        val hist = IntArray(LEVELS)
        for (y in 0 until height step step) {
            val row = y * width
            for (x in 0 until width step step) hist[luma(pixels[row + x])]++
        }
        return hist
    }

    /** Paper and ink points found in [hist]; (0, 255) when the page has no clear paper or ink. */
    fun points(hist: IntArray): Points {
        val total = hist.sum().coerceAtLeast(1)
        val paper = peak(hist = hist, from = PAPER_FROM, to = MAX)
        val isPaper = paper >= PAPER_MIN && hist[paper] >= total * PAPER_SHARE
        val white = if (isPaper) paper - PAPER_MARGIN else MAX
        val ink = peak(hist = hist, from = 0, to = INK_TO)
        val isInk = hist[ink] >= total * INK_SHARE
        val black = if (isInk) (ink + INK_MARGIN).coerceAtMost(INK_MAX) else 0
        val isUsable = white - black >= MIN_RANGE
        return if (isUsable) Points(black = black, white = white) else Points(black = 0, white = MAX)
    }

    /** Lookup table mapping 0..255 through [points] applied at [strength] (0..1), then [gamma] (>1 darkens). */
    fun lut(points: Points, strength: Float, gamma: Float): IntArray {
        val black = points.black * strength
        val white = MAX - (MAX - points.white) * strength
        val range = (white - black).coerceAtLeast(1f)
        return IntArray(LEVELS) { i ->
            val t = ((i - black) / range).coerceIn(minimumValue = 0f, maximumValue = 1f)
            (t.pow(gamma) * MAX_F + HALF).toInt()
        }
    }

    /**
     * True when the page is black-and-white: sampled pixels (every [step]th) stay within a few levels
     * of the line between its ink and paper colours, so a yellowed or sepia scan counts too.
     */
    fun isMonochrome(pixels: IntArray, width: Int, height: Int, step: Int, points: Points): Boolean {
        val paper =
            meanColor(pixels = pixels, width = width, height = height, step = step, lumaRange = points.white - PAPER_BAND..MAX)
        val ink =
            meanColor(pixels = pixels, width = width, height = height, step = step, lumaRange = 0..points.black + INK_BAND)
        val line = ColorLine(from = ink ?: FloatArray(CHANNELS), to = paper ?: floatArrayOf(MAX_F, MAX_F, MAX_F))
        var off = 0
        var count = 0
        for (y in 0 until height step step) {
            val row = y * width
            for (x in 0 until width step step) {
                count++
                if (line.distanceSquared(pixels[row + x]) > OFF_LINE * OFF_LINE) off++
            }
        }
        return off <= count * OFF_LINE_SHARE
    }

    private fun peak(hist: IntArray, from: Int, to: Int): Int {
        var best = from
        for (i in from..to) if (hist[i] > hist[best]) best = i
        return best
    }

    private fun meanColor(pixels: IntArray, width: Int, height: Int, step: Int, lumaRange: IntRange): FloatArray? {
        val sum = LongArray(CHANNELS)
        var count = 0
        for (y in 0 until height step step) {
            val row = y * width
            for (x in 0 until width step step) {
                val p = pixels[row + x]
                if (luma(p) in lumaRange) {
                    sum[0] += (p shr RED_SHIFT and BYTE).toLong()
                    sum[1] += (p shr GREEN_SHIFT and BYTE).toLong()
                    sum[2] += (p and BYTE).toLong()
                    count++
                }
            }
        }
        return if (count == 0) null else FloatArray(CHANNELS) { sum[it].toFloat() / count }
    }

    // Distance of a colour from the ink → paper segment in RGB.
    private class ColorLine(private val from: FloatArray, to: FloatArray) {
        private val dir = FloatArray(CHANNELS) { to[it] - from[it] }
        private val lengthSquared = (dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]).coerceAtLeast(1f)

        fun distanceSquared(pixel: Int): Float {
            val r = (pixel shr RED_SHIFT and BYTE) - from[0]
            val g = (pixel shr GREEN_SHIFT and BYTE) - from[1]
            val b = (pixel and BYTE) - from[2]
            val t = ((r * dir[0] + g * dir[1] + b * dir[2]) / lengthSquared).coerceIn(minimumValue = 0f, maximumValue = 1f)
            val dr = r - t * dir[0]
            val dg = g - t * dir[1]
            val db = b - t * dir[2]
            return dr * dr + dg * dg + db * db
        }
    }
}
