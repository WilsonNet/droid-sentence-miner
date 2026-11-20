package io.github.wilsonnet.droidsentenceminer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptsTest {
    private val context =
        AiLookupContext(
            word = "食べられない",
            lemma = "食べる",
            reading = "たべる",
            sentence = "もう食べられない",
            definition = "to eat",
            language = "Japanese",
        )

    @Test
    fun `the default prompt is a grammar breakdown focused on the word`() {
        val grammar = AiPrompts.BUILT_IN.first()
        assertEquals(AiPrompts.GRAMMAR_ID, grammar.id)
        val text = AiPromptRenderer.render(template = grammar.template, context = context)
        assertTrue(text.contains("Sentence: もう食べられない"))
        assertTrue(text.contains("Word: 食べられない (dictionary form: 食べる)"))
        assertTrue(text.contains("Japanese tutor"))
        assertFalse(text.contains("{"))
    }

    @Test
    fun `every placeholder is filled`() {
        val template = AiPromptRenderer.PLACEHOLDERS.joinToString(" ") { it.first }
        assertEquals("食べられない もう食べられない 食べる たべる to eat Japanese", AiPromptRenderer.render(template = template, context = context))
        val bare = context.copy(sentence = "", lemma = "")
        assertEquals("食べられない 食べられない", AiPromptRenderer.render(template = "{sentence} {lemma}", context = bare))
    }

    @Test
    fun `edited built-ins keep their original and can be restored`() {
        val prompts = AiPrompts.merge(edits = mapOf(AiPrompts.GRAMMAR_ID to "Explain {word}"), custom = emptyList())
        val grammar = prompts.first()
        assertTrue(grammar.isBuiltIn && grammar.isModified)
        assertEquals("Explain {word}", grammar.template)
        assertEquals(AiPrompts.BUILT_IN.first().template, grammar.original)
        assertFalse(grammar.copy(template = grammar.original.orEmpty()).isModified)
    }

    @Test
    fun `own prompts follow the built-ins and are never restorable`() {
        val own = AiPrompt(id = "own-1", name = "Mine", template = "x", original = "sneaky")
        val prompts = AiPrompts.merge(edits = emptyMap(), custom = listOf(own))
        assertEquals(AiPrompts.BUILT_IN.size + 1, prompts.size)
        assertFalse(prompts.last().isBuiltIn)
    }
}
