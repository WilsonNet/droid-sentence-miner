package io.github.wilsonnet.droidsentenceminer.lang

import io.github.wilsonnet.droidsentenceminer.lang.de.GermanLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GermanCompoundsTest {
    private val de = GermanLanguage

    private fun forms(word: String, sentence: String) = de.clauseForms(word = word, sentence = sentence)

    @Test
    fun `a clause-final particle joins its verb`() {
        assertEquals(listOf("aufstehe"), forms(word = "stehe", sentence = "Ich stehe jeden Tag früh auf."))
        assertEquals(listOf("mitkommst"), forms(word = "Kommst", sentence = "Kommst du heute mit?"))
        assertEquals(listOf("zurückgibt"), forms(word = "gibt", sentence = "Er gibt mir das Buch zurück, dann geht er."))
    }

    @Test
    fun `a particle that is not at the clause end is a preposition`() {
        assertEquals(emptyList<String>(), forms(word = "stehe", sentence = "Ich stehe auf dem Tisch."))
        assertEquals(emptyList<String>(), forms(word = "kommt", sentence = "Er kommt aus Berlin."))
        assertEquals(emptyList<String>(), forms(word = "Hund", sentence = "Der Hund läuft weg."))
    }

    @Test
    fun `compounds split into modifier and head, longest head first`() {
        val splits = de.compoundSplits("Arbeitsplatz")
        assertTrue(Pair(first = "Arbeits", second = "Platz") in splits)
        assertTrue(Pair(first = "Arbeit", second = "Platz") in splits)
        assertEquals("Eitsplatz", splits.first().second) // the longest head is tried first
        assertTrue(Pair(first = "fahren", second = "Rad") in de.compoundSplits("Fahrrad"))
        assertEquals(emptyList<Pair<String, String>>(), de.compoundSplits("Haus"))
    }

    @Test
    fun `german offers its lookup options and other languages none`() {
        val features = de.lookupOptions.map { it.feature }
        assertEquals(listOf(LookupFeature.SEPARABLE_VERBS, LookupFeature.SPLIT_COMPOUNDS), features)
        listOf("ja", "en", "fr").forEach { code -> assertTrue(Languages.byCode(code).lookupOptions.isEmpty()) }
        val active = Languages.activeLookupFeatures(de)
        assertTrue(LookupFeature.SEPARABLE_VERBS in active && LookupFeature.FORM_OF_ENTRIES in active)
    }
}
