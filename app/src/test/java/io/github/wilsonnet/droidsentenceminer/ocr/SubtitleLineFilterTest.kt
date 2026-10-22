package io.github.wilsonnet.droidsentenceminer.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleLineFilterTest {

    private fun row(text: String, top: Int, height: Int) =
        OcrPostProcessor.MergedLine(text, BoundingBox(100, top, 100 + text.length * height, top + height))

    @Test
    fun dropsBackgroundSignageAroundTheSubtitle() {
        val rows = listOf(
            row("5ソOK", 10, 22), // sign in the scene
            row("長時間運転していたので", 80, 48),
            row("込)", 140, 18),
        )
        assertEquals(listOf("長時間運転していたので"), SubtitleLineFilter.filter(rows).map { it.text })
    }

    @Test
    fun keepsTwoLineSubtitles() {
        val rows = listOf(
            row("言うのが遅かったりして", 10, 46),
            row("信用できない", 70, 48),
        )
        assertEquals(2, SubtitleLineFilter.filter(rows).size)
    }

    @Test
    fun dropsSmallKanaFragmentEvenIfJapanese() {
        val rows = listOf(row("足が地面につく感じが、気持ち良い", 50, 48), row("たたけま", 120, 20))
        assertEquals(listOf("足が地面につく感じが、気持ち良い"), SubtitleLineFilter.filter(rows).map { it.text })
    }

    @Test
    fun dropsAGarbledRowNextToASureOne() {
        // YouTube captions over a burned-in subtitle: the half-hidden hardsub reads as garbage.
        val lines = listOf(
            OcrLine("部屋が分かったところで探検タイム。これ", BoundingBox(0, 0, 900, 40), confidence = 0.97f),
            OcrLine("メクルツセリいトヤ・トアルロは", BoundingBox(0, 50, 900, 90), confidence = 0.27f),
        )
        assertEquals(listOf("部屋が分かったところで探検タイム。これ"), SubtitleLineFilter.dropUnsure(lines).map { it.text })
    }

    @Test
    fun keepsUniformlyHardReadsAndEnginesWithoutConfidence() {
        val hard = listOf(
            OcrLine("言うのが遅かったりして", BoundingBox(0, 0, 900, 40), confidence = 0.45f),
            OcrLine("信用できない", BoundingBox(0, 50, 900, 90), confidence = 0.4f),
        )
        assertEquals(hard, SubtitleLineFilter.dropUnsure(hard))
        val mlKit = hard.map { it.copy(confidence = null) }
        assertEquals(mlKit, SubtitleLineFilter.dropUnsure(mlKit))
    }

    @Test
    fun leavesNonJapaneseOnlyResultsAlone() {
        val rows = listOf(row("OK", 0, 20), row("123", 40, 20))
        assertEquals(rows, SubtitleLineFilter.filter(rows))
    }
}
