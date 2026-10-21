package io.github.wilsonnet.droidsentenceminer.capture

import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTextTest {

    // A subtitle band covering the bottom fifth of the screen, cropped to 1000x200 px.
    private val area = TextArea(0f, 0.8f, 1f, 1f)

    @Test
    fun placesSubtitleCharactersOnTheScreen() {
        val subtitle = OcrLine(
            "今日は",
            BoundingBox(400, 100, 700, 160),
            chars = listOf(BoundingBox(400, 100, 500, 160), BoundingBox(500, 100, 600, 160), BoundingBox(600, 100, 700, 160)),
        )
        val lines = ScreenText.fromRead(listOf(subtitle), listOf(subtitle.box), 1000, 200, area, 0)

        val line = lines.single()
        assertEquals("今日は", line.text)
        assertEquals(0.4f, line.glyphs[0].left, 1e-4f)
        assertEquals(0.9f, line.glyphs[0].top, 1e-4f) // 0.8 + 100/200 * 0.2
        assertEquals(0 to 1, ScreenText.glyphAt(lines, 0.55f, 0.92f))
        assertNull(ScreenText.glyphAt(lines, 0.1f, 0.5f))
    }

    @Test
    fun skipsSignageTheSubtitleFilterDropped() {
        val subtitle = OcrLine("行こう", BoundingBox(400, 100, 700, 160))
        val sign = OcrLine("出口", BoundingBox(10, 10, 60, 30))
        val lines = ScreenText.fromRead(listOf(subtitle, sign), listOf(subtitle.box), 1000, 200, area, 0)
        assertEquals(listOf("行こう"), lines.map { it.text })
        assertEquals(3, lines.single().glyphs.size) // no symbol boxes: split evenly
    }
}
