package io.github.wilsonnet.droidsentenceminer.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryDeinflectionsTest {
    private val mangeait by lazy {
        term(expression = "mangeait", glossary = """[["manger",["third-person singular imperfect indicative"]]]""")
    }
    private val manger by lazy { term(expression = "manger", glossary = """["to eat"]""", sequence = 2L) }

    private fun term(expression: String, glossary: String, sequence: Long = 1L, reasons: List<String> = emptyList()) =
        TermResult(
            dictId = 1L,
            dictionaryTitle = "wty-fr-en",
            dictionaryPriority = 0,
            expression = expression,
            reading = expression,
            definitionTags = "",
            rules = "v",
            score = 0,
            glossaryJson = glossary,
            sequence = sequence,
            termTags = "",
            matchedForm = expression,
            deinflectionReasons = reasons,
        )

    @Test
    fun `only pure pointer entries point to lemmas`() {
        assertEquals(
            listOf(DictionaryDeinflections.Pointer(lemma = "manger", forms = listOf("third-person singular imperfect indicative"))),
            DictionaryDeinflections.pointers(mangeait),
        )
        assertTrue(DictionaryDeinflections.pointers(manger).isEmpty())
    }

    @Test
    fun `pointers are replaced in place by their lemmas, without repeats`() {
        val deinflected = manger.copy(deinflectionReasons = listOf("imperfect"))
        val replaced = DictionaryDeinflections.replace(terms = listOf(mangeait, deinflected), lemmas = mapOf("manger" to listOf(manger)))
        assertEquals(1, replaced.size)
        assertEquals("manger", replaced.single().expression)
        assertEquals(listOf("third-person singular imperfect indicative"), replaced.single().deinflectionReasons)
    }

    @Test
    fun `a pointer whose lemma has no entry stays`() {
        val suis = term(expression = "suis", glossary = """[["être",["first-person singular present indicative"]]]""")
        assertEquals(listOf(suis), DictionaryDeinflections.replace(terms = listOf(suis), lemmas = emptyMap()))
    }
}
