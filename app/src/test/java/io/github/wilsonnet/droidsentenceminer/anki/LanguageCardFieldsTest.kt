package io.github.wilsonnet.droidsentenceminer.anki

import io.github.wilsonnet.droidsentenceminer.lang.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageCardFieldsTest {
    private val ja = Languages.byCode("ja")
    private val en = Languages.byCode("en")
    private val de = Languages.byCode("de")

    @Test
    fun `japanese keeps the historic fields`() {
        assertEquals(CardFields.ORDER, CardFields.forLanguage(ja))
    }

    @Test
    fun `english has IPA and no pitch graph, german adds gender`() {
        val english = CardFields.forLanguage(en)
        assertTrue(CardFields.IPA in english)
        assertFalse(CardFields.READING in english)
        assertFalse(CardFields.PITCH_GRAPH in english)
        val german = CardFields.forLanguage(de)
        assertEquals(listOf(CardFields.EXPRESSION, CardFields.GENDER, CardFields.IPA), german.take(3))
        assertFalse(CardFields.PITCH_GRAPH in german)
    }

    @Test
    fun `a Reading field takes the IPA, a pitch field stays empty for english`() {
        val fields = listOf("Word", "Reading", "Glossary", "Sentence", "Graph", "Audio")
        val mapping = AnkiFieldMapper.canonicalToModelField(fieldNames = fields, canonicals = CardFields.forLanguage(en))
        assertEquals("Reading", mapping[CardFields.IPA])
        assertFalse(CardFields.PITCH_GRAPH in mapping)
        val japanese = AnkiFieldMapper.canonicalToModelField(fieldNames = fields, canonicals = CardFields.forLanguage(ja))
        assertEquals("Graph", japanese[CardFields.PITCH_GRAPH])
    }

    @Test
    fun `german article field and nouns' genders`() {
        val fields = listOf("Word", "Article", "IPA", "Meaning")
        val mapping = AnkiFieldMapper.canonicalToModelField(fieldNames = fields, canonicals = CardFields.forLanguage(de))
        assertEquals("Article", mapping[CardFields.GENDER])
        assertEquals("IPA", mapping[CardFields.IPA])
        assertEquals("der", de.nounArticle(setOf("n", "masc", "strong")))
        assertEquals("der/das", de.nounArticle(setOf("masc", "neut")))
        assertEquals(null, de.nounArticle(setOf("n")))
        assertEquals(null, en.nounArticle(setOf("masc")))
    }

    @Test
    fun `templates follow the fields`() {
        assertEquals(Templates.FRONT, CardTemplates.front(CardFields.forLanguage(ja)))
        val german = CardTemplates.front(CardFields.forLanguage(de))
        assertTrue("{{Gender}}" in german && "{{IPA}}" in german && "PitchGraph" !in german)
        assertFalse("{{Gender}}" in CardTemplates.front(CardFields.forLanguage(en)))
    }

    @Test
    fun `simple cards keep only the chosen contents`() {
        val disabled = CardFields.forLanguage(ja).toSet() - setOf(CardFields.SENTENCE, CardFields.MEANING)
        val simple = CardFields.enabled(ja, disabled)
        assertEquals(listOf(CardFields.EXPRESSION, CardFields.MEANING, CardFields.SENTENCE), simple)
        val front = CardTemplates.front(simple)
        val back = CardTemplates.back(simple)
        assertFalse("{{Reading}}" in front || "{{PitchGraph}}" in front)
        assertTrue("{{Meaning}}" in back && "{{Sentence}}" in back && "{{Audio}}" !in back)
        assertEquals(Templates.BACK, CardTemplates.back(CardFields.ORDER))
        val noteFields = listOf("Word", "Reading", "Glossary", "Sentence")
        val mapping = AnkiFieldMapper.canonicalToModelField(fieldNames = noteFields, canonicals = simple)
        assertFalse(CardFields.READING in mapping)
    }

    @Test
    fun `a field mapped to Nothing leaves its content out`() {
        assertEquals(CardFields.forLanguage(de), CardContents.forMapping(language = de, fieldMap = null, includeImage = true))
        val simple =
            mapOf("Front" to CardFields.EXPRESSION, "Back" to CardFields.MEANING, "Context" to CardFields.SENTENCE, "Notes" to "")
        assertEquals(
            listOf(CardFields.EXPRESSION, CardFields.MEANING, CardFields.SENTENCE),
            CardContents.forMapping(language = de, fieldMap = simple, includeImage = true),
        )
        val noWord = mapOf("Back" to CardFields.MEANING)
        val japanese = CardContents.forMapping(language = ja, fieldMap = noWord, includeImage = true)
        assertEquals(listOf(CardFields.EXPRESSION, CardFields.MEANING), japanese)
        assertFalse(CardFields.IMAGE in CardContents.forMapping(language = ja, fieldMap = null, includeImage = false))
    }
}
