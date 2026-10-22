package io.github.wilsonnet.droidsentenceminer.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextFixesTest {

    @Test
    fun turnsKanjiOneAfterKanaIntoLongVowel() {
        assertEquals("おー広い!", OcrTextFixes.apply("お一広い!"))
        assertEquals("わー明るい", OcrTextFixes.apply("わ一明るい"))
        assertEquals("いいなーこの錆びれた感じ", OcrTextFixes.apply("いいな一この錆びれた感じ"))
        assertEquals("メニュー", OcrTextFixes.apply("メニュ一"))
        assertEquals("背中から一筋の汗が流れる。", OcrTextFixes.apply("背中から一筋の汗が流れる。"))
    }

    @Test
    fun readsLatinHBeforeKatakanaAsE() {
        assertEquals("エリート", OcrTextFixes.apply("Hリート"))
        assertEquals("エーダイ", OcrTextFixes.apply("Hーダイ"))
        assertEquals("HP回復", OcrTextFixes.apply("HP回復"))
    }

    @Test
    fun keepsTheNumeral() {
        assertEquals("もう一つ", OcrTextFixes.apply("もう一つ"))
        assertEquals("お一人様", OcrTextFixes.apply("お一人様"))
        assertEquals("もう一度", OcrTextFixes.apply("もう一度"))
        assertEquals("第一層", OcrTextFixes.apply("第一層"))
        assertEquals("一番", OcrTextFixes.apply("一番"))
    }
}
