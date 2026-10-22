package io.github.wilsonnet.droidsentenceminer.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSignatureTest {

    private val width = 2880
    private val height = 490

    private fun box(glyphs: Int, noise: Int = 0): IntArray {
        val pixels = IntArray(width * height) { i ->
            val v = 40 + if (noise == 0) 0 else Math.floorMod(i * 7919L, 2L * noise + 1).toInt() - noise
            gray(v)
        }
        // A visual novel's dialogue line: 45 px glyphs made of thin 3 px strokes, drawn one by one.
        repeat(glyphs) { g ->
            val left = 300 + g * 50
            for (y in 120 until 165) for (x in left until left + 45) {
                if ((x - left) % 15 < 3 || (y - 120) % 20 < 3) pixels[y * width + x] = gray(235)
            }
        }
        return pixels
    }

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun signature(pixels: IntArray) = FrameSignature.of(pixels, width, height)

    @Test
    fun oneMoreTypewriterGlyphCountsAsChanged() {
        assertTrue(FrameSignature.changed(signature(box(7)), signature(box(8))))
    }

    @Test
    fun sameFrameAndSlightNoiseDoNot() {
        assertFalse(FrameSignature.changed(signature(box(7)), signature(box(7))))
        assertFalse(FrameSignature.changed(signature(box(7)), signature(box(7, noise = 4))))
    }
}
