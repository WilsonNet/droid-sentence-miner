package io.github.wilsonnet.droidsentenceminer.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleDecoderTest {

    @Test
    fun `detector size caps the long side and rounds to 32`() {
        assertEquals(960 to 160, PaddleDecoder.detectorSize(1280, 220, 960))
        assertEquals(320 to 64, PaddleDecoder.detectorSize(320, 70, 960))
        assertEquals(32 to 32, PaddleDecoder.detectorSize(10, 10, 960))
    }

    @Test
    fun `db boxes find regions, drop weak ones and unclip`() {
        val w = 40
        val h = 20
        val prob = FloatArray(w * h)
        // A strong 20x6 text region and a weak 5x5 one.
        for (y in 5 until 11) for (x in 5 until 25) prob[y * w + x] = 0.9f
        for (y in 12 until 17) for (x in 30 until 35) prob[y * w + x] = 0.4f
        val boxes = PaddleDecoder.boxes(prob, w, h, thresh = 0.3f, boxThresh = 0.6f, unclipRatio = 1.5f)
        assertEquals(1, boxes.size)
        val b = boxes[0]
        // distance = 20*6*1.5 / (2*26) ≈ 3.46 → 4 px each side
        assertEquals(1, b.left)
        assertEquals(1, b.top)
        assertEquals(29, b.right)
        assertEquals(15, b.bottom)
    }

    @Test
    fun `ctc decode collapses repeats and blanks and keeps x ranges`() {
        val dict = listOf("a", "b")
        val classes = 4 // blank, a, b, space
        fun step(c: Int) = FloatArray(classes) { if (it == c) 0.9f else 0.03f }
        val steps = listOf(1, 1, 0, 1, 2, 3, 2) // a a _ a b ␠ b
        val scores = steps.flatMap { step(it).toList() }.toFloatArray()
        val glyphs = PaddleDecoder.ctcDecode(scores, steps.size, classes, dict)
        assertEquals("aab b", glyphs.joinToString("") { it.char })
        assertEquals(0, glyphs[0].start)
        assertEquals(2, glyphs[0].end)
        assertEquals(3, glyphs[1].start)
    }

    @Test
    fun `dictionary from inference yml`() {
        val yml = """
            PostProcess:
              name: CTCLabelDecode
              character_dict:
              - '!'
              - '"'
              - ''''
              - "\\"
              - a
              - '-'
            PreProcess:
              transform_ops: []
        """.trimIndent()
        assertEquals(listOf("!", "\"", "'", "\\", "a", "-"), PaddleDecoder.parseDictionary(yml))
        assertEquals(listOf("a", "b", " "), PaddleDecoder.parseDictionary("a\nb\n \n"))
    }
}
