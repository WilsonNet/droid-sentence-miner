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
    fun leavesNonJapaneseOnlyResultsAlone() {
        val rows = listOf(row("OK", 0, 20), row("123", 40, 20))
        assertEquals(rows, SubtitleLineFilter.filter(rows))
    }
}
