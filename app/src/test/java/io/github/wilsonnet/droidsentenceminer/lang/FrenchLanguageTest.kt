package io.github.wilsonnet.droidsentenceminer.lang

import io.github.wilsonnet.droidsentenceminer.lang.fr.FrenchLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrenchLanguageTest {
    private val fr = FrenchLanguage

    private fun forms(surface: String): Set<String> = fr.deinflect(surface).map { it.term }.toSet()

    private fun has(surface: String, term: String, pos: Long): Boolean =
        fr.deinflect(surface).any { candidate -> candidate.term == term && candidate.conditions == pos }

    private fun verb(surface: String, infinitive: String): Boolean =
        has(surface = surface, term = infinitive, pos = FrenchLanguage.VERB)

    private fun adjective(surface: String, base: String) = has(surface = surface, term = base, pos = FrenchLanguage.ADJ)

    private fun noun(surface: String, base: String) = has(surface = surface, term = base, pos = FrenchLanguage.NOUN)

    private fun lengths(text: String, position: Int): List<Int> {
        val lengths = fr.candidateLengths(text = text, position = position, maxLength = fr.maxTermLength)
        return lengths.toList()
    }

    private fun join(previous: String, next: String) = fr.joinRows(previous = previous, next = next)

    @Test
    fun `scans whole words, elided words and hyphenated forms`() {
        val text = "L'homme mange une pomme de terre."
        assertEquals(listOf(7, 13, 17, 23), lengths(text = text, position = 0)) // L'homme …
        assertEquals(emptyList<Int>(), lengths(text = text, position = 2)) // after the apostrophe: inside "L'homme"
        assertEquals(listOf(5, 8, 14), lengths(text = text, position = 18)) // pomme de terre
        assertEquals(listOf(3, 6), lengths(text = "Est-ce vrai", position = 0).take(2))
    }

    @Test
    fun `strips elided articles and pronouns`() {
        assertTrue("homme" in forms("l'homme"))
        assertTrue("homme" in forms("L’homme"))
        assertTrue("il" in forms("qu'il"))
        assertTrue("à" in forms("jusqu'à"))
        assertTrue("aujourd'hui" in forms("aujourd’hui"))
        assertTrue(verb(surface = "j'aime", infinitive = "aimer"))
        assertTrue(verb(surface = "n'arrive", infinitive = "arriver"))
    }

    @Test
    fun `folds sentence starts and all caps`() {
        assertTrue("le" in forms("Le"))
        assertTrue("bonjour" in forms("BONJOUR"))
        assertTrue("Paris" in forms("Paris"))
    }

    @Test
    fun `deinflects regular verbs`() {
        assertTrue(verb(surface = "parlons", infinitive = "parler"))
        assertTrue(verb(surface = "parlaient", infinitive = "parler"))
        assertTrue(verb(surface = "parlerons", infinitive = "parler"))
        assertTrue(verb(surface = "parlée", infinitive = "parler"))
        assertTrue(verb(surface = "mangeons", infinitive = "manger"))
        assertTrue(verb(surface = "commençait", infinitive = "commencer"))
        assertTrue(verb(surface = "finissons", infinitive = "finir"))
        assertTrue(verb(surface = "finissaient", infinitive = "finir"))
        assertTrue(verb(surface = "vendu", infinitive = "vendre"))
        assertTrue(verb(surface = "attends", infinitive = "attendre"))
        assertTrue(verb(surface = "regardant", infinitive = "regarder"))
    }

    @Test
    fun `deinflects nouns and adjectives`() {
        assertTrue(noun(surface = "maisons", base = "maison"))
        assertTrue(noun(surface = "chevaux", base = "cheval"))
        assertTrue(noun(surface = "bateaux", base = "bateau"))
        assertTrue(adjective(surface = "petites", base = "petit"))
        assertTrue(adjective(surface = "heureuse", base = "heureux"))
        assertTrue(adjective(surface = "actives", base = "actif"))
        assertTrue(adjective(surface = "naturelle", base = "naturel"))
        assertTrue(adjective(surface = "dernière", base = "dernier"))
    }

    @Test
    fun `phrases inflect their first word`() {
        assertTrue("pomme de terre" in forms("pomme de terre"))
        assertTrue("aller voir" in forms("allait voir"))
    }

    @Test
    fun `parts of speech filter deinflections`() {
        assertTrue(fr.termMatches(conditions = FrenchLanguage.VERB, ruleMask = fr.ruleMask("v")))
        assertFalse(fr.termMatches(conditions = FrenchLanguage.VERB, ruleMask = fr.ruleMask("n")))
        assertTrue(fr.termMatches(conditions = 0L, ruleMask = fr.ruleMask("adj")))
    }

    @Test
    fun `ocr helpers handle accents, guillemets, elision spacing and line-end hyphens`() {
        assertTrue(fr.isTargetChar('é') && fr.isTargetChar('œ') && fr.isTargetChar('Ç'))
        assertEquals("« Bonjour ! » dit l'homme, qu’il voit.", fr.normalizeOcr("<< Bonjour ! >> dit l' homme , qu ’il voit."))
        assertEquals("C'est l'été.", fr.normalizeOcr("C`est l 'été."))
        assertEquals("Il a un sac.", fr.normalizeOcr("Il a un sac."))
        assertEquals("connaissance", join(previous = "connais-", next = "sance"))
        assertEquals("dit-il", join(previous = "dit-", next = "il"))
        assertEquals("peut-être", join(previous = "peut-", next = "être"))
        assertEquals("Saint-Denis", join(previous = "Saint-", next = "Denis"))
        assertEquals("je vais", join(previous = "je", next = "vais"))
        assertTrue(fr.looksLikeText("Ça va très bien"))
        assertFalse(fr.looksLikeText("12:30"))
    }

    @Test
    fun `noun genders become un or une`() {
        assertEquals("un", fr.nounArticle(setOf("n", "masc")))
        assertEquals("une", fr.nounArticle(setOf("fem")))
        assertEquals("un/une", fr.nounArticle(setOf("masc", "fem")))
        assertEquals(null, fr.nounArticle(setOf("v")))
    }

    @Test
    fun `is registered and uses only its own word audio`() {
        assertEquals(FrenchLanguage, Languages.byCode("fr"))
        assertEquals("fr", Languages.codeOfDictionary("fr-CA"))
        assertFalse(WordAudioSource.JAPANESE_POD_101 in fr.wordAudioSources)
        assertEquals(WordAudioSource.WIKTIONARY_PAGE, fr.wordAudioSources.first())
    }
}
