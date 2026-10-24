package io.github.wilsonnet.droidsentenceminer.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class MeikiDecoderTest {

    private fun box(x1: Float, x2: Float) = floatArrayOf(x1, 0f, x2, 32f)

    @Test
    fun keepsTheMostConfidentCharacterPerSlotInReadingOrder() {
        val codes = intArrayOf('ヤ'.code, 'ハ'.code, 'ャ'.code, 'ト'.code)
        val boxes = arrayOf(box(64f, 96f), box(0f, 32f), box(66f, 94f), box(32f, 64f))
        val scores = floatArrayOf(0.9f, 0.95f, 0.4f, 0.8f)
        val text = MeikiDecoder.decodeLine(codes, boxes, scores, effectiveWidth = 400).joinToString("") { it.char }
        assertEquals("ハトヤ", text)
    }

    @Test
    fun dropsLowScoresAndPadding() {
        val codes = intArrayOf('あ'.code, 'い'.code, 'う'.code)
        val boxes = arrayOf(box(0f, 30f), box(30f, 60f), box(500f, 530f))
        val scores = floatArrayOf(0.8f, 0.05f, 0.9f)
        val text = MeikiDecoder.decodeLine(codes, boxes, scores, effectiveWidth = 480).joinToString("") { it.char }
        assertEquals("あ", text)
    }

    @Test
    fun resizesLinesToHeight32AndCapsWidth() {
        assertEquals(320 to 32, MeikiDecoder.recSize(1000, 100))
        assertEquals(960 to 16, MeikiDecoder.recSize(3000, 50))
    }

    @Test
    fun letterboxFitsInsideTheDetectorInput() {
        val lb = MeikiDecoder.letterbox(1920, 238)
        assertEquals(0.5f, lb.scale)
        assertEquals(960, lb.width)
        assertEquals(119, lb.height)
    }

    @Test
    fun picksTheShortestDetectorThatLeavesAMargin() {
        val variants = listOf(160, 224, 288, 352, 448)
        assertEquals(160, MeikiDecoder.detectorHeight(119, variants)) // 1920x238 subtitle band
        assertEquals(224, MeikiDecoder.detectorHeight(129, variants)) // 160 would leave 31 rows
        assertEquals(544, MeikiDecoder.detectorHeight(420, variants))
        assertEquals(544, MeikiDecoder.detectorHeight(119, emptyList()))
    }

    @Test
    fun sizesTheRecognizerToTheWidestLinePlusAMargin() {
        assertEquals(128, MeikiDecoder.recognizerWidth(20))
        assertEquals(576, MeikiDecoder.recognizerWidth(500)) // 564 rounded up to 32
        assertEquals(960, MeikiDecoder.recognizerWidth(900))
    }

    @Test
    fun fixesKnownSwappedPairs() {
        assertEquals("ただの冗談だよ", MeikiDecoder.fixSwappedPairs("ただの談冗だよ"))
    }
}
