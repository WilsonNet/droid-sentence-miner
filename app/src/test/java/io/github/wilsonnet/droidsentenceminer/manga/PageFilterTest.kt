package io.github.wilsonnet.droidsentenceminer.manga

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFilterTest {
    private val rows = PagePipeline.Rows { count, block -> block(0 until count) }

    private fun rgb(r: Int, g: Int, b: Int): Int = 0xFF shl 24 or (r shl 16) or (g shl 8) or b

    private fun byte(v: Byte): Int = v.toInt() and 0xFF

    // A yellowed scan: cream paper with black ink strokes.
    private fun scan(width: Int, height: Int, extra: (Int, Int) -> Int? = { _, _ -> null }): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            extra(x, y) ?: if (x % 10 < 2) rgb(r = 22, g = 20, b = 18) else rgb(r = 236, g = 228, b = 200)
        }

    @Test
    fun `levels find the paper and ink peaks`() {
        val hist = IntArray(256)
        hist[235] = 8000
        hist[20] = 1500
        hist[128] = 500
        val points = PageLevels.points(hist)
        assertEquals(PageLevels.Points(black = 23, white = 232), points)
        val lut = PageLevels.lut(points = points, strength = 1f, gamma = 1f)
        assertEquals(0, lut[20])
        assertEquals(255, lut[235])
        assertTrue((1 until 256).all { lut[it] >= lut[it - 1] })
    }

    @Test
    fun `a page without clear paper keeps its levels`() {
        val hist = IntArray(256) { 10 }
        assertEquals(PageLevels.Points(black = 0, white = 255), PageLevels.points(hist))
    }

    @Test
    fun `yellowed black and white scans count as monochrome, colour art does not`() {
        val mono = scan(width = 200, height = 100)
        val points = PageLevels.points(PageLevels.histogram(pixels = mono, width = 200, height = 100, step = 1))
        assertTrue(PageLevels.isMonochrome(pixels = mono, width = 200, height = 100, step = 1, points = points))
        val colour =
            scan(width = 200, height = 100) { x, y ->
                if (y < 30 && x > 100) rgb(r = 220, g = 30, b = 40) else null
            }
        assertFalse(PageLevels.isMonochrome(pixels = colour, width = 200, height = 100, step = 1, points = points))
    }

    @Test
    fun `lanczos weights are normalized and a flat page stays flat`() {
        listOf(100 to 237, 237 to 100, 50 to 50).forEach { (from, to) ->
            val kernel = ResampleKernel(srcLen = from, dstLen = to)
            for (o in 0 until to) {
                val sum = (0 until kernel.taps).sumOf { kernel.weights[o * kernel.taps + it].toDouble() }
                assertEquals(1.0, sum, 1e-4)
            }
        }
        val src = ByteArray(40 * 30) { 77 }
        val wide = ByteArray(90 * 30)
        PageResampler.horizontal(src = src, dst = wide, kernel = ResampleKernel(srcLen = 40, dstLen = 90), rows = 0 until 30)
        assertTrue(wide.all { byte(it) == 77 })
    }

    @Test
    fun `upscaling a hard edge does not ring`() {
        val src = ByteArray(20) { if (it < 10) 0 else -1 }
        val dst = ByteArray(50)
        PageResampler.horizontal(src = src, dst = dst, kernel = ResampleKernel(srcLen = 20, dstLen = 50), rows = 0 until 1)
        val values = dst.map(::byte)
        assertTrue(values.all { it in 0..255 })
        assertTrue((1 until values.size).all { values[it] >= values[it - 1] })
        assertEquals(0, values.first())
        assertEquals(255, values.last())
    }

    @Test
    fun `sharpening leaves flat areas alone and steepens soft edges`() {
        val width = 9
        val ramp = intArrayOf(40, 40, 40, 90, 140, 190, 210, 210, 210)
        val src = ByteArray(width * 3) { ramp[it % width].toByte() }
        val dst = ByteArray(width * 3)
        PageSharpen.cas(src = src, dst = dst, width = width, height = 3, sharpness = 1f, rows = 0 until 3)
        assertEquals(40, byte(dst[width + 1]))
        // A linear ramp has no curvature; the knees where the blur starts and ends get pushed apart.
        assertTrue(byte(dst[width + 2]) < 40)
        assertTrue(byte(dst[width + 6]) > 210)
    }

    @Test
    fun `pipeline resizes to the target and only upscales with AI when the page grows`() {
        runBlocking {
            val pixels = scan(width = 60, height = 80)
            var calls = 0
            val upscaler =
                PagePipeline.Upscaler { planes, width, height ->
                    calls++
                    planes.map { plane ->
                        ByteArray(
                            width * height * 4,
                        ) { i -> plane[i / (width * 2) / 2 * width + i % (width * 2) / 2] }
                    }
                }
            val settings = MangaPageFilter.CRISP.settings
            val grown =
                PagePipeline.run(
                    pixels = pixels,
                    width = 60,
                    height = 80,
                    size = 90 to 120,
                    settings = settings,
                    rows = rows,
                    upscaler = upscaler,
                )
            assertEquals(90, grown.width)
            assertNotNull(grown.gray)
            assertNull(grown.argb)
            assertEquals(1, calls)
            val same =
                PagePipeline.run(
                    pixels = pixels,
                    width = 60,
                    height = 80,
                    size = 66 to 88,
                    settings = settings,
                    rows = rows,
                    upscaler = upscaler,
                )
            assertEquals(66 * 88, same.gray?.size)
            assertEquals(1, calls)
            val paper = grown.gray?.let { byte(it[5 + 60 * 90]) }
            assertEquals(255, paper)
        }
    }
}
