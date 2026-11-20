package io.github.wilsonnet.droidsentenceminer.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class WiktionaryPageAudioTest {
    private val prefix = "LL-Q150 (fra)"

    private fun page(vararg files: String): String =
        files.joinToString(prefix = """{"query":{"pages":{"1":{"title":"x","images":[""", postfix = "]}}}}") {
            """{"ns":6,"title":"Fichier:$it"}"""
        }

    @Test
    fun `picks exact recordings of the word, the language's own file first`() {
        val json =
            page(
                "En-us-chat.ogg",
                "Fr-chat.ogg",
                "LL-Q14185 (oci)-Davidgrosclaude-chat.wav",
                "LL-Q150 (fra)-Adem M. (BiblioCanet66)-chat.wav",
                "LL-Q150 (fra)-Poslovitch-appeler un chat un chat.wav",
                "France flag.svg",
            )
        assertEquals(
            listOf("File:Fr-chat.ogg", "File:LL-Q150 (fra)-Adem M. (BiblioCanet66)-chat.wav"),
            WiktionaryPageAudio.parseTitles(json = json, language = "fr", linguaLibrePrefix = prefix, term = "chat"),
        )
    }

    @Test
    fun `hyphenated speakers and regional variants come last, apostrophes match both ways`() {
        val json =
            page(
                "Fr-manger-fr-FR-Paris.ogg",
                "LL-Q150 (fra)-Lepticed7-homme-grenouille.wav",
                "LL-Q150 (fra)-Jérémy-Günther-Heinz Jähnick-manger.wav",
                "LL-Q150 (fra)-Ash Crow-manger.wav",
            )
        assertEquals(
            listOf(
                "File:LL-Q150 (fra)-Ash Crow-manger.wav",
                "File:LL-Q150 (fra)-Jérémy-Günther-Heinz Jähnick-manger.wav",
                "File:Fr-manger-fr-FR-Paris.ogg",
            ),
            WiktionaryPageAudio.parseTitles(json = json, language = "fr", linguaLibrePrefix = prefix, term = "manger"),
        )
        val apostrophes = page("LL-Q150 (fra)-Lepticed7-aujourd’hui.wav")
        assertEquals(
            listOf("File:LL-Q150 (fra)-Lepticed7-aujourd’hui.wav"),
            WiktionaryPageAudio.parseTitles(json = apostrophes, language = "fr", linguaLibrePrefix = prefix, term = "aujourd'hui"),
        )
    }

    @Test
    fun `page url uses the language's wiktionary`() {
        assertEquals(
            "https://fr.wiktionary.org/w/api.php?action=query&format=json&prop=images&imlimit=max&redirects=1&titles=pomme%20de%20terre",
            WiktionaryPageAudio.pageUrl(language = "fr", term = "pomme de terre"),
        )
    }
}
