package io.github.wilsonnet.droidsentenceminer.lang

import io.github.wilsonnet.droidsentenceminer.lang.en.EnglishLanguage
import io.github.wilsonnet.droidsentenceminer.lang.ja.JapaneseLanguage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishLanguageTest {
    private val en = EnglishLanguage

    @After
    fun reset() {
        Languages.current = JapaneseLanguage
    }

    private fun forms(surface: String): Map<String, Long> = en.deinflect(surface).associate { it.term to it.conditions }

    private fun lengths(text: String, position: Int) = en.candidateLengths(text, position, en.maxTermLength).toList()

    @Test
    fun `scans whole words and phrases only`() {
        val text = "She looked up the dogs' names."
        assertEquals(listOf(3, 10, 13, 17, 23), lengths(text, 0)) // She / She looked / … up to 5 words
        assertEquals(listOf(6, 9, 13, 19, 25), lengths(text, 4)) // looked, looked up, … names
        assertEquals(emptyList<Int>(), lengths(text, 5)) // mid-word
        assertEquals(listOf(5), lengths("dogs' names", 0).take(1)) // trailing apostrophe kept
        assertEquals(listOf(4, 10), lengths("well-known fact.", 0).take(2)) // well, well-known
    }

    @Test
    fun `phrases stop at punctuation`() {
        assertEquals(listOf(3), lengths("end. Next", 0))
    }

    @Test
    fun `deinflects common suffixes with parts of speech`() {
        assertEquals(EnglishLanguage.VERB, forms("running")["run"])
        assertEquals(EnglishLanguage.VERB, forms("stopped")["stop"])
        assertEquals(EnglishLanguage.VERB, forms("tried")["try"])
        assertEquals(EnglishLanguage.VERB, forms("baked")["bake"])
        assertEquals(EnglishLanguage.ADJ, forms("happiest")["happy"])
        assertEquals(EnglishLanguage.ADJ, forms("bigger")["big"])
        assertEquals(EnglishLanguage.ADJ, forms("quickly")["quick"])
        assertTrue(forms("wolves").containsKey("wolf"))
        assertTrue(forms("knives").containsKey("knife"))
        assertTrue(forms("boxes").containsKey("box"))
        assertTrue(forms("dog's").containsKey("dog"))
        assertTrue(forms("dogs'").containsKey("dog")) // possessive, then plural
    }

    @Test
    fun `folds case and curly apostrophes`() {
        assertTrue(forms("The").containsKey("the"))
        assertTrue(forms("It’s").containsKey("it's"))
        assertEquals(0L, forms("Running")["Running"])
    }

    @Test
    fun `phrasal verbs inflect the first word, noun phrases the last`() {
        assertTrue(forms("looked up").containsKey("look up"))
        assertTrue(forms("gave up on").keys.isNotEmpty())
        assertTrue(forms("ice creams").containsKey("ice cream"))
    }

    @Test
    fun `parts of speech filter deinflections but not plain forms`() {
        val noun = en.ruleMask("n")
        val verb = en.ruleMask("v")
        assertTrue(en.termMatches(EnglishLanguage.VERB, verb))
        assertFalse(en.termMatches(EnglishLanguage.VERB, noun)) // "runs" as plural doesn't need verb run
        assertTrue(en.termMatches(0L, noun)) // the surface itself matches anything
        assertTrue(en.termMatches(EnglishLanguage.NOUN, en.ruleMask(""))) // no POS: accept
    }

    @Test
    fun `ocr helpers keep Latin punctuation and spacing`() {
        assertEquals("Wait... I can't!", en.normalizeOcr("Wait...  I can't !"))
        assertEquals("the end of it", en.joinRows("the end", "of it"))
        assertEquals("some-thing", en.joinRows("some-", "thing"))
        assertTrue(en.looksLikeText("Hello there"))
        assertFalse(en.looksLikeText("12:30"))
    }

    @Test
    fun `japanese keeps its behaviour`() {
        val ja = JapaneseLanguage
        assertEquals("日本語！", ja.normalizeOcr("日本 語!"))
        assertEquals("abc def", ja.joinRows("abc", "def"))
        assertEquals("日本語", ja.joinRows("日本", "語"))
        assertEquals(listOf(1, 2, 3), ja.candidateLengths("食べる", 0, 16).toList())
    }

    @Test
    fun `dictionary language falls back to japanese`() {
        assertEquals("ja", Languages.codeOfDictionary(null))
        assertEquals("en", Languages.codeOfDictionary("en"))
        assertEquals("en", Languages.codeOfDictionary("en-US"))
        assertEquals(EnglishLanguage, Languages.byCode("en"))
        assertEquals(JapaneseLanguage, Languages.byCode("xx"))
    }
}
