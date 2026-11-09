package io.github.wilsonnet.droidsentenceminer.properties

import io.github.wilsonnet.droidsentenceminer.anki.AnkiFieldMapper
import io.github.wilsonnet.droidsentenceminer.anki.CardFields
import io.github.wilsonnet.droidsentenceminer.anki.CardImageSize
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Field mapping and card sizing invariants over generated note types and settings. */
class AnkiPropertiesTest {
    // Field names seen in real note types (Anime Cards, Lapis, Yomitan, Basic) plus noise.
    private val fieldNames =
        listOf(
            "Word", "Expression", "Reading", "Glossary", "Meaning", "Sentence", "Context Sentence", "Picture", "Image",
            "Audio", "Sentence Audio", "Word Audio", "Graph", "Pitch Accent", "IPA", "Pronunciation", "Article",
            "Gender", "Front", "Back", "Notes", "Source", "Hint", "Frequency",
        )
    private val allContents = CardFields.ORDER + CardFields.IPA + CardFields.GENDER
    private val rawNoteTypes = Arb.list(gen = Arb.element(fieldNames), range = 1..12)
    private val noteTypes = arbitrary { rawNoteTypes.bind().distinct() }
    private val disabledSets = Arb.set(gen = Arb.element(allContents), range = 0..5)
    private val languages = Arb.element(Languages.ALL)

    @Test
    fun `mapping uses each note field once and only the card's contents`() {
        runTest {
            checkAll(noteTypes, languages, disabledSets) { names, language, disabled ->
                val canonicals = CardFields.enabled(language = language, disabled = disabled)
                val mapping = AnkiFieldMapper.canonicalToModelField(fieldNames = names, canonicals = canonicals)
                assertEquals("no note field filled twice: $mapping", mapping.values.size, mapping.values.toSet().size)
                assertTrue(mapping.values.all { field -> field in names })
                val isEnabledOnly = mapping.keys.all { field -> field in canonicals }
                assertTrue("only enabled contents: $mapping vs $canonicals", isEnabledOnly)
            }
        }
    }

    @Test
    fun `mapped arrays line up with the note type`() {
        runTest {
            checkAll(noteTypes, languages) { names, language ->
                val canonicals = CardFields.forLanguage(language)
                val values = canonicals.associateWith { field -> "<$field>" }
                val array = AnkiFieldMapper.mapToArray(fieldNames = names, values = values, canonicals = canonicals)
                assertEquals(names.size, array.size)
            }
        }
    }

    @Test
    fun `enabled fields always keep the word and the language's order`() {
        runTest {
            checkAll(languages, Arb.set(gen = Arb.element(allContents), range = 0..6)) { language, disabled ->
                val all = CardFields.forLanguage(language)
                val enabled = CardFields.enabled(language = language, disabled = disabled)
                assertEquals(CardFields.EXPRESSION, enabled.first())
                assertEquals(enabled, all.filter { field -> field in enabled })
            }
        }
    }

    @Test
    fun `card pictures are never upscaled and keep their aspect ratio`() {
        runTest {
            checkAll(genA = Arb.int(1..8000), genB = Arb.int(1..8000), genC = Arb.int(0..2000)) { width, height, max ->
                val (w, h) = CardImageSize.fit(width = width, height = height, maxHeight = max)
                assertTrue(w <= width && h <= height)
                if (max > 0) assertTrue(h <= max || h == height)
                if (h != height) assertTrue(abs(w - width.toDouble() / height * h) <= 1.0)
            }
        }
    }
}
