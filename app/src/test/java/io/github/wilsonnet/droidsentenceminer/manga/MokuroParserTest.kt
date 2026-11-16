package io.github.wilsonnet.droidsentenceminer.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MokuroParserTest {
    @Test
    fun parsesMokuroAndComputesGlyphBoxes() {
        val raw =
            """{"version":"0.2.0","title":"Yotsuba","volume":"Vol 01","pages":[""" +
                """{"img_path":"001.jpg","img_width":1000,"img_height":1500,"blocks":[""" +
                """{"box":[700,100,820,420],"vertical":true,"font_size":28,""" +
                """"lines":["おはよう","ございます"],""" +
                """"lines_coords":[[[760,100],[820,100],[820,320],[760,320]],""" +
                """[[700,120],[755,120],[755,420],[700,420]]]}]}]}"""

        val parsed = MokuroParser.parse(raw)
        assertEquals("Yotsuba", parsed.title)
        assertEquals("Vol 01", parsed.volume)
        assertEquals(1, parsed.pages.size)

        val pageOcr = MokuroParser.toPageOcr(parsed.pages.single())
        assertEquals(MangaPageOcr.SOURCE_MOKURO, pageOcr.source)
        assertEquals(1000, pageOcr.imgWidth)
        assertEquals(1500, pageOcr.imgHeight)
        val block = pageOcr.blocks.single()
        assertEquals("おはようございます", block.text)
        assertEquals(block.text.length, block.glyphs.size)
        assertTrue(block.isVertical)
        assertNotNull(block.glyphAt(x = 790f, y = 120f))
        assertEquals(0, block.glyphAt(x = 790f, y = 120f))
        assertEquals(4, block.glyphAt(x = 725f, y = 140f))
    }

    @Test
    fun matchesPagesAcrossSubfolderPrefixesAndStems() {
        val pages =
            listOf(
                MokuroParser.RawMokuroPage(imgPath = "001.jpg", imgWidth = 100, imgHeight = 100),
                MokuroParser.RawMokuroPage(imgPath = "ch1/002.png", imgWidth = 100, imgHeight = 100),
                MokuroParser.RawMokuroPage(imgPath = "page_003.webp", imgWidth = 100, imgHeight = 100),
            )
        val entries =
            listOf(
                "Vol1/001.jpg",
                "Vol1/ch1/002.png",
                "Vol1/page_003.jpg",
            )
        val matched = MokuroParser.matchPages(pages, entries)
        assertEquals(setOf(0, 1, 2), matched.keys)
        assertEquals("001.jpg", matched[0]?.imgPath)
        assertEquals("ch1/002.png", matched[1]?.imgPath)
        assertEquals("page_003.webp", matched[2]?.imgPath)
    }

    @Test
    fun verticalFallbackSplitsRightToLeft() {
        val block =
            MokuroParser.RawMokuroBlock(
                box = listOf(100.0, 100.0, 200.0, 300.0),
                isVertical = true,
                lines = listOf("あい", "うえ"),
            )
        val parsed = MokuroParser.toMangaBlock(block)!!
        assertEquals("あいうえ", parsed.text)
        assertEquals(4, parsed.glyphs.size)
        // First vertical column ("あい") sits to the right of the second column ("うえ").
        assertTrue(parsed.glyphs[0].left >= parsed.glyphs[2].right)
    }
}
