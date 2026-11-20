package io.github.wilsonnet.droidsentenceminer.lang.kit

import io.github.wilsonnet.droidsentenceminer.lang.Languages
import io.github.wilsonnet.droidsentenceminer.lang.LookupFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupKitTest {
    @Test
    fun `spaced scanner stops at word ends and phrase limits`() {
        val scanner = SpacedScanner(maxWords = 2)
        assertEquals(listOf(3, 9), scanner.candidateLengths(text = "ice cream cone", position = 0, maxLength = 64).toList())
        assertEquals(emptyList<Int>(), scanner.candidateLengths(text = "ice cream", position = 1, maxLength = 64).toList())
        val possessive = SpacedScanner(maxWords = 1, isTrailingApostropheAllowed = true)
        val plain = SpacedScanner(maxWords = 1)
        assertEquals(listOf(5), possessive.candidateLengths(text = "dogs' toys", position = 0, maxLength = 64).toList())
        assertEquals(emptyList<Int>(), plain.candidateLengths(text = "dogs' toys", position = 0, maxLength = 64).toList())
    }

    @Test
    fun `suffix rules respect stems and parts of speech`() {
        val rules = SuffixRules.group(pos = PosMask.VERB, reason = "past", spec = "ed> ied>y")
        assertEquals(listOf("walk"), rules.apply("walked").map { it.term })
        assertTrue(rules.apply("ed").isEmpty())
        assertNull(SuffixRules.applyRule(rule = rules.rules.first(), word = "red"))
        val verbs = rules.only(PosMask.VERB)
        val nouns = rules.only(PosMask.NOUN)
        assertEquals(PosMask.VERB, verbs.rules.first().pos)
        assertTrue(nouns.rules.isEmpty())
    }

    @Test
    fun `part of speech masks`() {
        assertEquals(PosMask.NOUN or PosMask.VERB, PosMask.parse("n v"))
        assertTrue(PosMask.matches(conditions = 0L, mask = PosMask.NOUN))
        assertTrue(PosMask.matches(conditions = PosMask.VERB, mask = 0L))
        assertFalse(PosMask.matches(conditions = PosMask.VERB, mask = PosMask.NOUN))
    }

    @Test
    fun `case forms, candidate sets, text helpers and row joins`() {
        assertEquals(setOf("HUND", "hUND", "hund", "Hund"), CaseForms.spellings("HUND"))
        assertEquals(setOf("The", "the"), CaseForms.spellings(text = "The", capitalized = false))
        val set = CandidateSet()
        set.add(term = "go", pos = PosMask.VERB, reasons = listOf("past"))
        set.add(term = "go", pos = PosMask.VERB, reasons = listOf("other"))
        set.add(term = "", pos = 0L, reasons = emptyList())
        val reasons = set.toList().single().reasons
        assertEquals(listOf("past"), reasons.toList())
        assertEquals("Hi, you!", LatinText.normalizeSpacing("Hi ,  you !"))
        assertEquals("Oui !", LatinText.normalizeSpacing(text = "Oui !", noSpaceBefore = ",."))
        assertTrue(LatinText.looksLikeText("hello there"))
        assertFalse(LatinText.looksLikeText("12:30"))
        assertEquals("Wörterbuch", RowJoiner.join(previous = "Wör-", next = "terbuch") { RowJoiner.Hyphen.JOIN })
        assertEquals("a b", RowJoiner.join(previous = "a", next = "b") { RowJoiner.Hyphen.JOIN })
    }

    @Test
    fun `languages pick their lookup features`() {
        val japanese = Languages.byCode("ja").lookupFeatures
        assertTrue(LookupFeature.SKIP_GRAMMAR_NOISE in japanese && LookupFeature.KANJI_INFO in japanese)
        assertFalse(LookupFeature.FORM_OF_ENTRIES in japanese)
        listOf("en", "de", "fr").forEach { code ->
            assertEquals(LookupFeature.WIKTIONARY, Languages.byCode(code).lookupFeatures)
        }
    }
}
