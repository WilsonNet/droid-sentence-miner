package io.github.wilsonnet.droidsentenceminer.lang

import io.github.wilsonnet.droidsentenceminer.lang.de.GermanLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GermanLanguageTest {
    private val de = GermanLanguage

    private fun forms(surface: String): Set<String> = de.deinflect(surface).map { it.term }.toSet()

    private fun has(surface: String, term: String, pos: Long): Boolean =
        de.deinflect(surface).any { candidate -> candidate.term == term && candidate.conditions == pos }

    private fun verb(surface: String, infinitive: String): Boolean =
        has(surface = surface, term = infinitive, pos = GermanLanguage.VERB)

    private fun adjective(surface: String, base: String) = has(surface = surface, term = base, pos = GermanLanguage.ADJ)

    private fun noun(surface: String, base: String) = has(surface = surface, term = base, pos = GermanLanguage.NOUN)

    private fun lengths(text: String, position: Int): List<Int> {
        val lengths = de.candidateLengths(text = text, position = position, maxLength = de.maxTermLength)
        return lengths.toList()
    }

    private fun join(previous: String, next: String) = de.joinRows(previous = previous, next = next)

    @Test
    fun `scans whole words, phrases and hyphenated compounds`() {
        val text = "Die Mädchen gehen nach Hause."
        assertEquals(listOf(3, 11, 17, 22), lengths(text = text, position = 0)) // up to four words
        assertEquals(listOf(7, 13, 18, 24), lengths(text = text, position = 4)) // Mädchen …
        assertEquals(emptyList<Int>(), lengths(text = text, position = 5)) // mid-word
        assertEquals(listOf(1, 6), lengths(text = "E-Mail schreiben", position = 0).take(2))
    }

    @Test
    fun `keeps noun capitals and folds sentence starts and all caps`() {
        assertTrue("Hund" in forms("Hund"))
        assertTrue("die" in forms("Die"))
        assertTrue("Hund" in forms("HUND"))
        assertTrue("gehen" in forms("Gehen"))
    }

    @Test
    fun `deinflects regular verbs`() {
        assertTrue(verb(surface = "machst", infinitive = "machen"))
        assertTrue(verb(surface = "macht", infinitive = "machen"))
        assertTrue(verb(surface = "machte", infinitive = "machen"))
        assertTrue(verb(surface = "arbeitete", infinitive = "arbeiten"))
        assertTrue(verb(surface = "sammle", infinitive = "sammeln"))
        assertTrue(verb(surface = "sammelte", infinitive = "sammeln"))
        assertTrue(verb(surface = "laufend", infinitive = "laufen"))
    }

    @Test
    fun `deinflects participles and zu-infinitives with separable prefixes`() {
        assertTrue(verb(surface = "gemacht", infinitive = "machen"))
        assertTrue(verb(surface = "gearbeitet", infinitive = "arbeiten"))
        assertTrue(verb(surface = "gefahren", infinitive = "fahren"))
        assertTrue(verb(surface = "aufgemacht", infinitive = "aufmachen"))
        assertTrue(verb(surface = "anzufangen", infinitive = "anfangen"))
        assertTrue(verb(surface = "gemachte", infinitive = "machen")) // declined participle
    }

    @Test
    fun `deinflects adjectives and nouns`() {
        assertTrue(adjective(surface = "schönen", base = "schön"))
        assertTrue(adjective(surface = "schneller", base = "schnell"))
        assertTrue(adjective(surface = "schnelleren", base = "schnell"))
        assertTrue(adjective(surface = "schnellsten", base = "schnell"))
        assertTrue(noun(surface = "Hunde", base = "Hund"))
        assertTrue(noun(surface = "Hundes", base = "Hund"))
        assertTrue(noun(surface = "Lehrerinnen", base = "Lehrerin"))
    }

    @Test
    fun `phrases inflect their first word`() {
        assertTrue("ging nach Hause" in forms("ging nach Hause"))
        assertTrue("machen mit" in forms("macht mit"))
    }

    @Test
    fun `parts of speech filter deinflections`() {
        assertTrue(de.termMatches(conditions = GermanLanguage.VERB, ruleMask = de.ruleMask("v")))
        assertFalse(de.termMatches(conditions = GermanLanguage.VERB, ruleMask = de.ruleMask("n")))
        assertTrue(de.termMatches(conditions = 0L, ruleMask = de.ruleMask("adj")))
    }

    @Test
    fun `ocr helpers handle umlauts, low quotes and line-end hyphens`() {
        assertTrue(de.isTargetChar('ä') && de.isTargetChar('ß') && de.isTargetChar('Ü'))
        assertEquals("„Na gut“, sagte er.", de.normalizeOcr(",,Na gut“ , sagte  er."))
        assertEquals("Wörterbuch", join(previous = "Wör-", next = "terbuch"))
        assertEquals("Nord-Ostsee", join(previous = "Nord-", next = "Ostsee"))
        assertEquals("Haus- und Gartenbau", join(previous = "Haus-", next = "und Gartenbau"))
        assertEquals("ich gehe", join(previous = "ich", next = "gehe"))
        assertTrue(de.looksLikeText("Schöne Grüße"))
        assertFalse(de.looksLikeText("12:30"))
    }

    @Test
    fun `is registered and uses only its own word audio`() {
        assertEquals(GermanLanguage, Languages.byCode("de"))
        assertEquals("de", Languages.codeOfDictionary("de-DE"))
        assertFalse(WordAudioSource.JAPANESE_POD_101 in de.wordAudioSources)
        assertTrue(WordAudioSource.WIKTIONARY in de.wordAudioSources)
    }
}
