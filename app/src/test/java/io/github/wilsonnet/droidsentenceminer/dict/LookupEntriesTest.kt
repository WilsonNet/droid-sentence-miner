package io.github.wilsonnet.droidsentenceminer.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LookupEntriesTest {

    private fun term(dict: Long, expression: String, reading: String, gloss: String, reasons: List<String> = emptyList()) =
        TermResult(dict, "dict$dict", dict.toInt(), expression, reading, "", "", 0, gloss, 0, "", expression, reasons)

    private fun meta(expression: String) = TermMetaResult(1, "freq", expression, "freq", "1")

    private val details = TermLookup.LookupDetails(
        surface = "取り",
        terms = listOf(
            term(1, "取る", "とる", "take", listOf("masu stem")),
            term(1, "取り", "とり", "taking"),
            term(2, "取る", "トル", "to take", listOf("masu stem")),
            term(2, "取り", "とり", "removal"),
        ),
        frequencies = listOf(meta("取る"), meta("取り")),
        kanji = emptyList(),
    )

    @Test
    fun `groups by headword across dictionaries, first appearance first`() {
        val entries = details.entries
        assertEquals(listOf("取る" to "とる", "取り" to "とり"), entries.map { it.expression to it.reading })
        // Katakana reading folds into the hiragana one, like Yomitan's reading normalizer.
        assertEquals(listOf("take", "to take"), entries[0].terms.map { it.glossaryJson })
        assertEquals(listOf("taking", "removal"), entries[1].terms.map { it.glossaryJson })
    }

    @Test
    fun `a card for one entry carries only that headword`() {
        val second = details.forEntry(1)
        assertEquals("取り", second.baseForm)
        assertEquals("とり", second.reading)
        assertEquals(listOf("taking", "removal"), second.terms.map { it.glossaryJson })
        assertEquals(listOf("取り"), second.frequencies.map { it.expression })
    }

    @Test
    fun `a single entry is left as is`() {
        val one = details.copy(terms = details.terms.filter { it.expression == "取り" })
        assertSame(one, one.forEntry(0))
    }

    @Test
    fun `a dictionary picked for the card narrows the entry to it`() {
        val picked = details.forEntry(1).withDictionary(2)
        assertEquals(listOf("removal"), picked.terms.map { it.glossaryJson })
        assertEquals("取り", picked.baseForm)
        // No pick, or a dictionary without this headword: unchanged.
        val entry = details.forEntry(1)
        assertSame(entry, entry.withDictionary(null))
        assertSame(entry, entry.withDictionary(9))
    }
}
