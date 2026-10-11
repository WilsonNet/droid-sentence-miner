package io.github.wilsonnet.droidsentenceminer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPostProcessorTest {

    private fun line(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) = OcrLine(text, BoundingBox(left, top, right, bottom))

    @Test
    fun `normalizes full width and removes spaces between japanese`() {
        val normalized = OcrPostProcessor.normalize("こんにちは　世界 ！")
        assertEquals("こんにちは世界！", normalized)
    }

    @Test
    fun `merges lines on the same row`() {
        val lines = listOf(
            line("俺は", 100, 500, 220, 550),
            line("お前を信じている", 230, 502, 600, 552),
        )
        val merged = OcrPostProcessor.merge(lines)
        assertEquals(1, merged.size)
        assertEquals("俺はお前を信じている", merged.first().text)
    }

    @Test
    fun `keeps separate rows apart`() {
        val lines = listOf(
            line("こんにちは", 100, 400, 400, 450),
            line("さようなら", 100, 600, 400, 650),
        )
        val merged = OcrPostProcessor.merge(lines)
        assertEquals(2, merged.size)
        assertEquals("こんにちは\nさようなら", OcrPostProcessor.toSentenceText(merged))
    }

    @Test
    fun `drops furigana above kanji`() {
        val lines = listOf(
            line("しん", 300, 480, 340, 495),
            line("信じている", 100, 500, 600, 550),
        )
        val merged = OcrPostProcessor.merge(lines)
        assertEquals(1, merged.size)
        assertEquals("信じている", merged.first().text)
    }

    @Test
    fun `drops mostly latin noise when japanese is present`() {
        val lines = listOf(
            line("こんにちは、元気ですか", 100, 500, 600, 550),
            line("HP 1200 MP 340", 100, 100, 400, 140),
        )
        val merged = OcrPostProcessor.merge(lines)
        assertEquals(1, merged.size)
        assertEquals("こんにちは、元気ですか", merged.first().text)
    }

    @Test
    fun `keeps latin lines when no japanese present`() {
        val lines = listOf(line("Hello world", 100, 100, 400, 140))
        val merged = OcrPostProcessor.merge(lines)
        assertEquals(1, merged.size)
    }

    @Test
    fun `deduplicates identical merged lines`() {
        val lines = listOf(
            line("同じ台詞", 100, 500, 400, 550),
            line("同じ台詞", 110, 502, 410, 552),
        )
        val merged = OcrPostProcessor.merge(lines)
        assertEquals(1, merged.size)
    }

    @Test
    fun `keeps japanese lines that embed latin words like NG`() {
        val lines = listOf(
            line("彼はNGを出した", 100, 500, 600, 550),
            line("HP 1200 MP 340", 100, 100, 400, 140),
        )
        val merged = OcrPostProcessor.merge(lines)
        assertEquals(1, merged.size)
        assertEquals("彼はNGを出した", merged.first().text)
    }

    @Test
    fun `latin inside a sentence survives normalization`() {
        assertEquals("ナルトはNGワードを言った", OcrPostProcessor.normalize("ナルトは NG ワードを言った"))
    }

    @Test
    fun `standalone short latin line is treated as ui noise`() {
        val lines = listOf(
            line("こんにちは", 100, 700, 400, 750),
            line("NG", 100, 100, 200, 140),
        )
        val merged = OcrPostProcessor.merge(lines)
        assertEquals(1, merged.size)
        assertEquals("こんにちは", merged.first().text)

        // ...but is kept when the noise filter is disabled.
        val unfiltered = OcrPostProcessor.merge(lines, dropNoise = false)
        assertEquals(2, unfiltered.size)
    }

    @Test
    fun `dropNoise false keeps non japanese lines`() {
        val lines = listOf(
            line("こんにちは", 100, 500, 400, 550),
            line("HP 1200 MP 340", 100, 100, 400, 140),
        )
        val merged = OcrPostProcessor.merge(lines, dropNoise = false)
        assertEquals(2, merged.size)
    }

    @Test
    fun `japanese text score prefers real japanese`() {
        val japanese = JapaneseTextScore.score("俺はお前を信じている")
        val gibberish = JapaneseTextScore.score("|||| \u0001 \uFFFD")
        assertTrue(japanese > gibberish)
        assertTrue(japanese > 5.0)
    }

    @Test
    fun `empty input yields empty result`() {
        assertTrue(OcrPostProcessor.merge(emptyList()).isEmpty())
        assertFalse(OcrPostProcessor.merge(listOf(line("   ", 0, 0, 10, 10))).isNotEmpty())
    }
}
