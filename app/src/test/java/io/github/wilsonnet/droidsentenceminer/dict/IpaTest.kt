package io.github.wilsonnet.droidsentenceminer.dict

import org.junit.Assert.assertEquals
import org.junit.Test

class IpaTest {
    @Test
    fun `parses wty ipa rows, US first`() {
        val data = """{"reading":"dictionary","transcriptions":[{"ipa":"/ˈdɪk.ʃə.nə.ɹi/","tags":["🏴"]},{"ipa":"/ˈdɪk.ʃəˌnɛ.ɹi/","tags":["🇺🇸"]}]}"""
        val rows = listOf(TermMetaResult(1, "wty-en-en-ipa", "dictionary", "ipa", data))
        val ipa = Ipa.of(rows, "Dictionary")
        assertEquals("/ˈdɪk.ʃəˌnɛ.ɹi/", ipa.first().ipa)
        assertEquals("🇺🇸 /ˈdɪk.ʃəˌnɛ.ɹi/  ·  🏴 /ˈdɪk.ʃə.nə.ɹi/", Ipa.line(ipa))
        assertEquals(emptyList<Ipa.Transcription>(), Ipa.parse("not json"))
    }

    @Test
    fun `sniffs a dictionary's language from its first bank`() {
        assertEquals("ja", YomitanIndexParser.languageOfSample("""[["食べる","たべる","v1","v1",0,["to eat"]],""".repeat(5)))
        assertEquals("en", YomitanIndexParser.languageOfSample("""[["software","","n","n",0,["computer programs"]],""".repeat(10)))
        assertEquals(null, YomitanIndexParser.languageOfSample("[[1,2,3]]"))
    }
}
