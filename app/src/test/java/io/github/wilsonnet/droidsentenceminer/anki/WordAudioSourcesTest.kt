package io.github.wilsonnet.droidsentenceminer.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordAudioSourcesTest {

    @Test
    fun jpod101UrlUsesKanjiAndKana() {
        assertEquals(
            "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji=%E8%85%B0&kana=%E3%81%93%E3%81%97",
            WordAudioSources.jpod101Url("腰", "こし"),
        )
    }

    @Test
    fun kanaOnlyWordsQueryByReading() {
        assertEquals(
            "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kana=%E3%81%99%E3%81%94%E3%81%84",
            WordAudioSources.jpod101Url("すごい", "すごい"),
        )
    }

    @Test
    fun parsesJapanesePod101RowsMatchingTheReading() {
        val html = """
            <div class="dc-box--white dc-result-row"><div class="dc-result-row__checkbox-field"></div>
              <div class="dc-result-row__audio"><audio><source src="https://cdn/a.mp3"></audio></div>
              <span class='dc-vocab_kana'>こし</span></div>
            <div class="dc-result-row"><audio><source src="https://cdn/b.mp3"></audio><span class='dc-vocab_kana'>よう</span></div>
        """
        assertEquals(listOf("https://cdn/a.mp3"), WordAudioSources.parseLanguagePod101(html, "腰", "こし"))
    }

    @Test
    fun parsesJishoAudioForExactTermAndReading() {
        val html = """<audio id="audio_腰:こし"><source src="//d1vjc5dkcd3yh2.cloudfront.net/audio/7b.mp3" type="audio/mpeg"></audio>"""
        assertEquals("https://d1vjc5dkcd3yh2.cloudfront.net/audio/7b.mp3", WordAudioSources.parseJisho(html, "腰", "こし"))
        assertNull(WordAudioSources.parseJisho(html, "腰", "よう"))
    }

    @Test
    fun rejectsTinyPayloads() {
        assertFalse(WordAudioSources.isValid("jisho", ByteArray(10)))
        assertTrue(WordAudioSources.isValid("jisho", ByteArray(5000) { 1 }))
    }

    @Test
    fun customTemplateIsFilled() {
        assertEquals(
            "http://127.0.0.1:5050/?term=%E8%85%B0&reading=%E3%81%93%E3%81%97",
            WordAudioSources.customUrl("http://127.0.0.1:5050/?term={term}&reading={reading}", "腰", "こし"),
        )
    }
}
