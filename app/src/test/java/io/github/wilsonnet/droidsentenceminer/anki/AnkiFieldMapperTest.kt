package io.github.wilsonnet.droidsentenceminer.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiFieldMapperTest {

    private val values = mapOf(
        CardFields.EXPRESSION to "継承",
        CardFields.READING to "けいしょう",
        CardFields.MEANING to "inheritance, succession",
        CardFields.SENTENCE to "フリーダは…継承した",
        CardFields.IMAGE to "<img src=x.webp>",
        CardFields.AUDIO to "[sound:x.mp3]",
        CardFields.SOURCE to "Droid Sentence Miner",
    )

    @Test
    fun `maps the Anime Cards note type by alias`() {
        val model = listOf(
            "Word", "Graph", "Reading", "Glossary", "Context Sentence",
            "Word Audio", "Audio", "Picture", "Sentence",
        )
        val mapped = AnkiFieldMapper.mapToArray(model, values)
        assertEquals("継承", mapped[0])
        assertEquals("", mapped[1]) // Graph has no equivalent yet
        assertEquals("けいしょう", mapped[2])
        assertEquals("inheritance, succession", mapped[3])
        assertEquals("フリーダは…継承した", mapped[4])
        assertEquals("", mapped[5]) // Word Audio: none in this value set
        assertEquals("[sound:x.mp3]", mapped[6])
        assertEquals("<img src=x.webp>", mapped[7])
        assertEquals("", mapped[8])
    }

    @Test
    fun `maps our own note type by exact names`() {
        val mapped = AnkiFieldMapper.mapToArray(CardFields.ORDER, values)
        assertEquals(CardFields.ORDER.size, mapped.size)
        assertEquals("継承", mapped[0])
        assertEquals("[sound:x.mp3]", mapped[CardFields.ORDER.indexOf(CardFields.AUDIO)])
    }

    @Test
    fun `basic two field layout combines content`() {
        val mapped = AnkiFieldMapper.mapToArray(listOf("Front", "Back"), values)
        assertEquals(2, mapped.size)
        assertEquals(true, mapped[0].contains("継承"))
        assertEquals(true, mapped[0].contains("けいしょう"))
        assertEquals(true, mapped[0].contains("フリーダは…継承した"))
        assertEquals(true, mapped[1].contains("inheritance, succession"))
        assertEquals(true, mapped[1].contains("<img src=x.webp>"))
        assertEquals(true, mapped[1].contains("[sound:x.mp3]"))
    }

    @Test
    fun `unknown model falls back to positional canonical order`() {
        val mapped = AnkiFieldMapper.mapToArray(listOf("A", "B", "C"), values)
        assertEquals("継承", mapped[0])
        assertEquals("けいしょう", mapped[1])
        assertEquals("inheritance, succession", mapped[2])
    }

    @Test
    fun `named mapping for AnkiConnect only includes known fields`() {
        val model = listOf("Word", "Glossary", "Context Sentence", "Audio", "Picture", "Unknown Field")
        val named = AnkiFieldMapper.mapToNamedFields(model, values)
        assertEquals("継承", named["Word"])
        assertEquals("inheritance, succession", named["Glossary"])
        assertEquals("フリーダは…継承した", named["Context Sentence"])
        assertEquals("[sound:x.mp3]", named["Audio"])
        assertEquals("<img src=x.webp>", named["Picture"])
        assertEquals(false, named.containsKey("Unknown Field"))
    }

    @Test
    fun animeCardsStyleAudioIsWordAudioWhenSentenceAudioExists() {
        val fields = listOf("Front", "Back", "Audio", "Picture", "Reading", "Sentence", "Sentence Audio")
        val mapping = AnkiFieldMapper.canonicalToModelField(fields)
        assertEquals("Sentence Audio", mapping[CardFields.AUDIO])
        assertEquals("Audio", mapping[CardFields.WORD_AUDIO])
    }

    @Test
    fun dedicatedWordAudioFieldIsUsed() {
        val mapping = AnkiFieldMapper.canonicalToModelField(listOf("Expression", "ExpressionAudio", "SentenceAudio"))
        assertEquals("ExpressionAudio", mapping[CardFields.WORD_AUDIO])
        assertEquals("SentenceAudio", mapping[CardFields.AUDIO])
    }

    @Test
    fun wordAudioFallsBackIntoReadingWithoutAField() {
        val fields = listOf("Expression", "Reading", "Meaning", "Sentence", "Image", "Audio", "Source")
        val values = mapOf(CardFields.READING to "こし", CardFields.WORD_AUDIO to "[sound:w.mp3]", CardFields.AUDIO to "[sound:s.m4a]")
        val array = AnkiFieldMapper.mapToArray(fields, values)
        assertEquals("こし [sound:w.mp3]", array[1])
        assertEquals("[sound:s.m4a]", array[5])
    }

    @Test
    fun userAnimeCardsLayoutMapsEveryField() {
        val fields = listOf("Word", "Graph", "Reading", "Glossary", "Context Sentence", "Word Audio", "Audio", "Picture", "Sentence")
        val mapping = AnkiFieldMapper.canonicalToModelField(fields)
        assertEquals("Word", mapping[CardFields.EXPRESSION])
        assertEquals("Reading", mapping[CardFields.READING])
        assertEquals("Glossary", mapping[CardFields.MEANING])
        assertEquals("Context Sentence", mapping[CardFields.SENTENCE])
        assertEquals("Word Audio", mapping[CardFields.WORD_AUDIO])
        assertEquals("Audio", mapping[CardFields.AUDIO])
        assertEquals("Picture", mapping[CardFields.IMAGE])
    }
}
