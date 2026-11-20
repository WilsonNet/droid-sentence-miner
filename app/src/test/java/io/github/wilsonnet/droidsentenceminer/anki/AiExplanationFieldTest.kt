package io.github.wilsonnet.droidsentenceminer.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AiExplanationFieldTest {
    private val card = AnkiCard(expression = "食べる", reading = "", meaningHtml = "", sentence = "", sentenceHtml = "")

    @Test
    fun `an ai answer is mapped only when there is one`() {
        assertFalse(CardFields.AI_EXPLANATION in card.mappedFields)
        assertEquals(card.fields + CardFields.AI_EXPLANATION, card.copy(aiExplanation = "<div>x</div>").mappedFields)
    }

    @Test
    fun `a Grammar field receives the ai answer`() {
        val fields = listOf("Word", "Glossary", "Grammar")
        val values =
            mapOf(CardFields.EXPRESSION to "食べる", CardFields.MEANING to "to eat", CardFields.AI_EXPLANATION to "<div>ai</div>")
        val canonicals = listOf(CardFields.EXPRESSION, CardFields.MEANING, CardFields.AI_EXPLANATION)
        val named = AnkiFieldMapper.mapToNamedFields(fieldNames = fields, values = values, canonicals = canonicals)
        assertEquals("<div>ai</div>", named["Grammar"])
        assertEquals("to eat", named["Glossary"])
    }

    @Test
    fun `without an answer a Grammar field stays empty`() {
        val fields = listOf("Word", "Grammar")
        val array = AnkiFieldMapper.mapToArray(fieldNames = fields, values = mapOf(CardFields.EXPRESSION to "食べる"))
        assertEquals("", array[1])
    }

    @Test
    fun `a custom mapping can send the answer anywhere`() {
        val fields = listOf("Word", "Notes")
        val values = mapOf(CardFields.EXPRESSION to "食べる", CardFields.AI_EXPLANATION to "ai")
        val custom = mapOf("Word" to CardFields.EXPRESSION, "Notes" to CardFields.AI_EXPLANATION)
        assertEquals(listOf("食べる", "ai"), AnkiFieldMapper.mapToArray(fieldNames = fields, values = values, custom = custom).toList())
        assertEquals("AI explanation", CardFields.label(CardFields.AI_EXPLANATION))
    }
}
