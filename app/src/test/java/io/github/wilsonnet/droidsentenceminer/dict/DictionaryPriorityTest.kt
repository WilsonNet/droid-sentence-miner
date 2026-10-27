package io.github.wilsonnet.droidsentenceminer.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryPriorityTest {

    @Test
    fun `identifies name dictionaries accurately`() {
        assertTrue(RecommendedDictionaries.isNameDictionary("JMnedict [2026-10-26]"))
        assertTrue(RecommendedDictionaries.isNameDictionary("JMnedict"))
        assertTrue(RecommendedDictionaries.isNameDictionary("jmnedict"))
        assertTrue(RecommendedDictionaries.isNameDictionary("Japanese Names (EN)"))

        assertFalse(RecommendedDictionaries.isNameDictionary("Jitendex.org [2026-10-03]"))
        assertFalse(RecommendedDictionaries.isNameDictionary("JMdict English"))
        assertFalse(RecommendedDictionaries.isNameDictionary("KANJIDIC English"))
        assertFalse(RecommendedDictionaries.isNameDictionary(RecommendedDictionaries.KANJIUM_TITLE))
    }

    @Test
    fun `recommended dictionaries contains jmnedict at the bottom`() {
        val last = RecommendedDictionaries.ALL.last()
        assertEquals("jmnedict", last.id)
        assertEquals("JMnedict", last.name)
        assertTrue(last.url.endsWith("JMnedict.zip"))
        assertTrue(RecommendedDictionaries.isNameDictionary(last.name))
    }

    @Test
    fun `baseDictionaryTitle strips version and date brackets`() {
        assertEquals("JMnedict", YomitanImporter.baseDictionaryTitle("JMnedict [2026-10-26]"))
        assertEquals("Jitendex.org", YomitanImporter.baseDictionaryTitle("Jitendex.org [2026-10-03]"))
        assertEquals("Simple Title", YomitanImporter.baseDictionaryTitle("Simple Title"))
    }
}
