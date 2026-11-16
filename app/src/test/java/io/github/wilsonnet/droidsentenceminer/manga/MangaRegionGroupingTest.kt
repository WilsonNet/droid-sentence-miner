package io.github.wilsonnet.droidsentenceminer.manga

import io.github.wilsonnet.droidsentenceminer.lang.ja.JapaneseLanguage
import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.MangaPaddleOcr
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Test

// Boxes are real Google Lens / PP-OCRv6_manga lines from One Piece v99 and Berserk v1 pages
// (tools/manga-eval).
class MangaRegionGroupingTest {
    private fun line(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        chars: List<BoundingBox>? = null,
    ): OcrLine =
        OcrLine(text = text, box = BoundingBox(left = left, top = top, right = right, bottom = bottom), chars = chars)

    private fun region(left: Int, top: Int, right: Int, bottom: Int): MangaRegionGrouping.TextRegion =
        MangaRegionGrouping.TextRegion(BoundingBox(left = left, top = top, right = right, bottom = bottom))

    private fun texts(groups: List<List<OcrLine>>): List<String> {
        val joined = groups.map { group -> group.joinToString("") { it.text } }
        return joined
    }

    @Test
    fun `drops furigana but keeps a thin kana column beside kanji`() {
        val lines =
            listOf(
                line(text = "あれ?", left = 254, top = 106, right = 281, bottom = 206),
                line(text = "燃えてる", left = 197, top = 108, right = 232, bottom = 238),
                line(text = "のに…", left = 158, top = 107, right = 187, bottom = 216),
                line(text = "やけど", left = 143, top = 109, right = 159, bottom = 173),
                line(text = "火傷しない", left = 108, top = 107, right = 141, bottom = 272),
            )
        val groups =
            MangaRegionGrouping.group(
                lines = lines,
                regions = listOf(region(left = 84, top = 96, right = 306, bottom = 280)),
                pageWidth = 1370,
                pageHeight = 2160,
                language = JapaneseLanguage,
            )
        assertEquals(listOf("あれ?燃えてるのに…火傷しない"), texts(groups))
    }

    @Test
    fun `groups lines per text region in right to left column order`() {
        val lines =
            listOf(
                line(text = "群れだ!!", left = 579, top = 1505, right = 619, bottom = 1662),
                line(text = "うわー", left = 706, top = 1493, right = 735, bottom = 1614),
                line(text = "逃げろ!!!", left = 337, top = 1511, right = 377, bottom = 1682),
                line(text = "氷鬼の", left = 640, top = 1492, right = 677, bottom = 1614),
                line(text = "2人共", left = 396, top = 1511, right = 436, bottom = 1634),
                line(text = "わー!!", left = 440, top = 1511, right = 480, bottom = 1640),
            )
        val groups =
            MangaRegionGrouping.group(
                lines = lines,
                regions =
                listOf(
                    region(left = 568, top = 1482, right = 752, bottom = 1668),
                    region(left = 334, top = 1500, right = 504, bottom = 1688),
                ),
                pageWidth = 1370,
                pageHeight = 2160,
                language = JapaneseLanguage,
            )
        assertEquals(listOf("うわー氷鬼の群れだ!!", "わー!!2人共逃げろ!!!"), texts(groups))
    }

    @Test
    fun `cuts a detector line that runs into the next bubble`() {
        val chars =
            (0 until 9).map { i ->
                BoundingBox(left = 485, top = 628 + i * 21, right = 511, bottom = 649 + i * 21)
            }
        val lines =
            listOf(
                line(text = "うんとかすん", left = 514, top = 626, right = 536, bottom = 736),
                line(text = "とか言ったらああ?", left = 485, top = 628, right = 511, bottom = 822, chars = chars),
            )
        val groups =
            MangaRegionGrouping.group(
                lines = lines,
                regions =
                listOf(
                    region(left = 472, top = 624, right = 559, bottom = 743),
                    region(left = 480, top = 750, right = 515, bottom = 828),
                ),
                pageWidth = 844,
                pageHeight = 1200,
                language = JapaneseLanguage,
            )
        assertEquals(listOf("うんとかすんとか言ったら", "ああ?"), texts(groups))
    }

    @Test
    fun `reads two pieces of one column top to bottom`() {
        val ordered =
            MangaRegionGrouping.readingOrder(
                listOf(
                    line(text = "わからぬわ!!", left = 86, top = 380, right = 120, bottom = 500),
                    line(text = "意味が", left = 120, top = 380, right = 152, bottom = 470),
                    line(text = "余計", left = 122, top = 305, right = 150, bottom = 370),
                ),
            )
        assertEquals("余計意味がわからぬわ!!", ordered.joinToString("") { it.text })
    }

    @Test
    fun `lines outside every region fall back to the geometric merge`() {
        val groups =
            MangaRegionGrouping.group(
                lines =
                listOf(
                    line(text = "かかった", left = 500, top = 200, right = 540, bottom = 360),
                    line(text = "のは", left = 455, top = 200, right = 492, bottom = 290),
                ),
                regions = emptyList(),
                pageWidth = 844,
                pageHeight = 1200,
                language = JapaneseLanguage,
            )
        assertEquals(listOf("かかったのは"), texts(groups))
    }

    @Test
    fun `detector input keeps the long side at 960 and webtoon strips at a page budget`() {
        assertEquals(608 to 960, MangaPaddleOcr.detectorSize(width = 1370, height = 2160))
        assertEquals(672 to 960, MangaPaddleOcr.detectorSize(width = 844, height = 1200))
        val strip = MangaPaddleOcr.detectorSize(width = 800, height = 4000)
        assertEquals(384 to 1856, strip)
    }
}
