package io.github.wilsonnet.droidsentenceminer.manga

import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrEngineTest {
    @Test
    fun mergesAdjacentVerticalSpeechBubbleColumnsRightToLeft() {
        val rawBlocks =
            listOf(
                listOf(
                    OcrLine(
                        text = "かかった",
                        box = BoundingBox(left = 500, top = 200, right = 540, bottom = 360),
                    ),
                ),
                listOf(
                    OcrLine(
                        text = "のは",
                        box = BoundingBox(left = 455, top = 200, right = 492, bottom = 290),
                    ),
                ),
                listOf(
                    OcrLine(
                        text = "てめえだよ",
                        box = BoundingBox(left = 405, top = 202, right = 445, bottom = 400),
                    ),
                ),
                listOf(
                    OcrLine(
                        text = "いい!!",
                        box = BoundingBox(left = 120, top = 520, right = 160, bottom = 650),
                    ),
                ),
            )

        val blocks = MangaOcrEngine.buildBlocks(rawBlocks)
        assertEquals(2, blocks.size)
        assertEquals("かかったのはてめえだよ", blocks[0].text)
        assertEquals("いい！！", blocks[1].text)
    }

    @Test
    fun dropsOverlappingDuplicateDetections() {
        val rawBlocks =
            listOf(
                listOf(
                    OcrLine(
                        text = "わいながら",
                        box = BoundingBox(left = 600, top = 400, right = 640, bottom = 620),
                    ),
                ),
                listOf(
                    OcrLine(
                        text = "あじ",
                        box = BoundingBox(left = 602, top = 405, right = 638, bottom = 610),
                    ),
                ),
            )

        val blocks = MangaOcrEngine.buildBlocks(rawBlocks)
        assertEquals(1, blocks.size)
        assertEquals("わいながら", blocks[0].text)
    }

    @Test
    fun filtersThinFuriganaAndEdgePageNumbers() {
        val rawBlocks =
            listOf(
                listOf(
                    OcrLine(
                        text = "言伝",
                        box = BoundingBox(left = 400, top = 300, right = 440, bottom = 500),
                    ),
                    OcrLine(
                        text = "ことづて",
                        box = BoundingBox(left = 444, top = 310, right = 462, bottom = 490),
                    ),
                    OcrLine(
                        text = "|",
                        box = BoundingBox(left = 200, top = 300, right = 212, bottom = 360),
                    ),
                    OcrLine(
                        text = "18",
                        box = BoundingBox(left = 780, top = 1140, right = 820, bottom = 1175),
                    ),
                ),
            )

        val blocks = MangaOcrEngine.buildBlocks(rawBlocks = rawBlocks, pageWidth = 850, pageHeight = 1200)
        assertEquals(1, blocks.size)
        assertEquals("言伝", blocks[0].text)
    }

    @Test
    fun snapsNearestGlyphWhenTapLandsInsideBubbleGap() {
        val block =
            MangaBlock(
                text = "天国",
                box = BoundingBoxDto(left = 100, top = 100, right = 200, bottom = 300),
                isVertical = true,
                glyphs =
                listOf(
                    BoundingBoxDto(left = 135, top = 120, right = 165, bottom = 160),
                    BoundingBoxDto(left = 135, top = 175, right = 165, bottom = 215),
                ),
            )
        assertEquals(0, block.nearestGlyph(x = 110f, y = 135f))
        assertEquals(1, block.nearestGlyph(x = 185f, y = 260f))
    }
}
