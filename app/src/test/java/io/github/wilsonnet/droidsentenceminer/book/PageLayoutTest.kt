package io.github.wilsonnet.droidsentenceminer.book

import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLayoutTest {

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = OcrLine(text, BoundingBox(l, t, r, b))

    @Test
    fun readsVerticalColumnsRightToLeft() {
        // Two paragraphs; within each, the rightmost column comes first.
        val right = listOf(
            line("た。", 840, 100, 880, 200),
            line("吾輩は猫であ", 900, 100, 940, 700),
            line("る。名前はまだ無い。", 870, 100, 910, 900),
        )
        val left = listOf(line("どこで生れたか", 500, 100, 540, 800))
        val blocks = PageLayout.build(listOf(left, right))

        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it.vertical })
        assertEquals("吾輩は猫である。名前はまだ無い。た。", blocks[0].text)
        assertEquals("どこで生れたか", blocks[1].text)
    }

    @Test
    fun readsHorizontalLinesTopToBottom() {
        val block = listOf(
            line("二行目です。", 50, 140, 600, 180),
            line("一行目です、", 50, 80, 600, 120),
        )
        val blocks = PageLayout.build(listOf(block))
        assertFalse(blocks.single().vertical)
        assertEquals("一行目です、二行目です。", blocks.single().text)
    }

    @Test
    fun joinsASentenceSplitAcrossBlocks() {
        val blocks = PageLayout.build(
            listOf(
                listOf(line("今日は雨だった。", 100, 100, 700, 142)),
                listOf(line("彼女は窓の外を見ながら、", 100, 180, 600, 222)),
                listOf(line("静かに飲んでいる。", 100, 260, 690, 302)),
                listOf(line("別の話。", 100, 600, 400, 642)),
            ),
        )
        assertEquals(listOf("今日は雨だった。", "彼女は窓の外を見ながら、静かに飲んでいる。", "別の話。"), blocks.map { it.text })
    }

    @Test
    fun dropsFuriganaAndLatinNoise() {
        val body = listOf(
            line("先生は教室に入った", 900, 100, 950, 900),
            line("せんせい", 952, 100, 968, 180), // ruby column, a third as wide
            line("学生たちが待っていた", 840, 100, 890, 900),
        )
        val pageNumber = listOf(line("123", 500, 950, 540, 980))
        val blocks = PageLayout.build(listOf(body, pageNumber))
        assertEquals(listOf("先生は教室に入った学生たちが待っていた"), blocks.map { it.text })
    }

    @Test
    fun dropsLongAndMisreadRubyFromMangaBubbles() {
        // Raw ML Kit lines from a manga page: ruby columns are about half the body width.
        val name = listOf(
            line("斉藤英二郎", 1144, 1014, 1178, 1160),
            line("さいとうえいじろう", 1174, 1014, 1190, 1159),
        )
        val bubble = listOf(
            line("Hーダイ", 768, 1408, 785, 1476),
            line("永大卒か..…", 741, 1411, 772, 1556),
        )
        val blocks = PageLayout.build(listOf(name, bubble))
        assertEquals(listOf("斉藤英二郎", "永大卒か……"), blocks.map { it.text })
    }

    @Test
    fun placesEveryCharacterOnThePhoto() {
        val column = OcrLine(
            "猫である",
            BoundingBox(900, 100, 940, 260),
            chars = listOf(BoundingBox(900, 100, 940, 140), BoundingBox(900, 140, 940, 180), BoundingBox(900, 180, 940, 220), BoundingBox(900, 220, 940, 260)),
        )
        val next = line("名前は", 850, 100, 890, 220) // no symbol boxes: split evenly
        val block = PageLayout.build(listOf(listOf(column, next))).single()

        assertEquals("猫である名前は", block.text)
        assertEquals(block.text.length, block.glyphs.size)
        assertEquals(1, block.glyphAt(920f, 150f))
        assertEquals(5, block.glyphAt(870f, 170f)) // 前: second third of the left column
        assertEquals(null, block.glyphAt(500f, 500f))
        assertEquals(2, block.withText("猫だ").glyphs.size)
    }

    @Test
    fun findsTheSentenceAroundATap() {
        val text = "彼は言った。「明日また来るね。」そして帰った。"
        assertEquals("彼は言った。", PageLayout.sentenceAt(text, 1))
        assertEquals("「明日また来るね。」", PageLayout.sentenceAt(text, 9))
        assertEquals("そして帰った。", PageLayout.sentenceAt(text, text.indexOf("帰")))
        assertEquals("終わらない文", PageLayout.sentenceAt("終わらない文", 3))
    }
}
