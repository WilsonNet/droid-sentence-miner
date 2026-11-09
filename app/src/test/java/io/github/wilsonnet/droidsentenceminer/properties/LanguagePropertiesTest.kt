package io.github.wilsonnet.droidsentenceminer.properties

import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants every language module must keep, whatever the text: checked on generated input for
 * all of [Languages.ALL], so a new language gets them for free.
 */
class LanguagePropertiesTest {
    // Words from every script the modules read, plus separators and OCR junk.
    private val words =
        listOf(
            "Hund", "Mädchen", "gehen", "Straße", "E-Mail", "look", "up", "dogs'", "it’s", "running",
            "日本語", "食べる", "ない", "カタカナ", "123", "ÄÖÜ", "x",
        )
    private val separators = listOf(" ", "  ", "-", ", ", ". ", "\n", "!", "„", "“", "　")
    private val pieces = Arb.list(gen = Arb.element(words + separators), range = 1..12)
    private val texts = arbitrary { pieces.bind().joinToString("") }
    private val languages = Arb.element(Languages.ALL)

    private fun lengthsAt(
        language: LanguageModule,
        text: String,
        position: Int,
        max: Int = language.maxTermLength,
    ): List<Int> {
        val lengths = language.candidateLengths(text = text, position = position, maxLength = max)
        return lengths.toList()
    }

    @Test
    fun `candidate lengths are increasing, positive and stay inside the text and the limit`() {
        runTest {
            checkAll(languages, texts, Arb.int(1..64)) { language, text, max ->
                for (position in text.indices) {
                    val lengths = lengthsAt(language = language, text = text, position = position, max = max)
                    assertEquals("$language '$text'@$position", lengths.sorted().distinct(), lengths)
                    val isInside =
                        lengths.all { length ->
                            length > 0 && length <= max && position + length <= text.length
                        }
                    assertTrue(isInside)
                }
            }
        }
    }

    @Test
    fun `spaced languages only start candidates at word starts`() {
        runTest {
            checkAll(Arb.element(Languages.ALL.filter { it.usesSpaces }), texts) { language, text ->
                for (position in 1 until text.length) {
                    val isMidWord = text[position - 1].isLetterOrDigit() && text[position].isLetterOrDigit()
                    if (isMidWord) assertTrue(lengthsAt(language = language, text = text, position = position).isEmpty())
                }
            }
        }
    }

    @Test
    fun `deinflection keeps the surface and never yields empty terms`() {
        runTest {
            checkAll(languages, Arb.element(words)) { language, word ->
                val terms = language.deinflect(word).map { candidate -> candidate.term }
                val folded = word.replace(oldChar = '’', newChar = '\'')
                assertTrue("$language keeps '$word'", word in terms || folded in terms)
                assertTrue(terms.none { term -> term.isEmpty() })
            }
        }
    }

    @Test
    fun `ocr normalization is idempotent and joining an empty row is the identity`() {
        runTest {
            checkAll(languages, texts) { language, text ->
                val once = language.normalizeOcr(text)
                assertEquals(once, language.normalizeOcr(once))
                assertEquals(text, language.joinRows(previous = text, next = ""))
                assertEquals(text, language.joinRows(previous = "", next = text))
            }
        }
    }

    @Test
    fun `text scores are numbers for arbitrary unicode`() {
        runTest {
            checkAll(languages, Arb.string(0..40)) { language, text ->
                assertTrue(!language.textScore(text).isNaN())
            }
        }
    }
}
