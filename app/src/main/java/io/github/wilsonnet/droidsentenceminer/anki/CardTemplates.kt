package io.github.wilsonnet.droidsentenceminer.anki

import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import io.github.wilsonnet.droidsentenceminer.dict.TermResult
import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.Languages

/**
 * Templates of the note types this app creates, built from the card's contents: a template that
 * names a field the note type lacks shows an error on the card, so with fewer contents (simple
 * cards, other languages) only the lines whose fields exist are written. All Japanese contents
 * give exactly [Templates.FRONT] / [Templates.BACK], the templates from before languages existed.
 */
object CardTemplates {
    private const val GENDER_SPAN = """{{#Gender}}<span class="gender">{{Gender}}</span> {{/Gender}}"""
    private val BACK_CONTENTS =
        listOf(CardFields.MEANING, CardFields.SENTENCE, CardFields.IMAGE, CardFields.AUDIO, CardFields.SOURCE)

    /** The question side for a note type with [fields]. */
    fun front(fields: List<String>): String {
        if (fields == CardFields.ORDER) return Templates.FRONT
        val gender = if (CardFields.GENDER in fields) GENDER_SPAN else ""
        val pronunciation = listOf(CardFields.READING, CardFields.IPA, CardFields.WORD_AUDIO).filter { it in fields }
        val lines = mutableListOf("", div(cssClass = "expression", content = gender + "{{Expression}}"))
        val placeholders = pronunciation.joinToString(" ") { "{{$it}}" }
        if (pronunciation.isNotEmpty()) lines += div(cssClass = "reading", content = placeholders)
        if (CardFields.PITCH_GRAPH in fields) lines += div(cssClass = "pitch", content = "{{PitchGraph}}")
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    /** The answer side for a note type with [fields]. */
    fun back(fields: List<String>): String {
        if (fields.containsAll(BACK_CONTENTS)) return Templates.BACK
        val media = listOf(CardFields.IMAGE, CardFields.AUDIO).filter { it in fields }
        val lines = mutableListOf("", "{{FrontSide}}", """<hr id="answer">""")
        if (CardFields.MEANING in fields) lines += div(cssClass = "meaning", content = "{{Meaning}}")
        if (CardFields.SENTENCE in fields) lines += div(cssClass = "sentence", content = "{{Sentence}}")
        if (media.isNotEmpty()) lines += div(cssClass = "media", content = media.joinToString("") { "{{$it}}" })
        if (CardFields.SOURCE in fields) lines += div(cssClass = "source", content = "{{Source}}")
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    private fun div(cssClass: String, content: String): String = """<div class="$cssClass">$content</div>"""
}

/** Which contents a card carries ([fields]); the rest are left empty. */
class CardContents(private val fields: List<String>) {
    /** [value] when [field] is on the card, else "". */
    fun text(field: String, value: String): String = if (field in fields) value else ""

    /** [build]'s result when [field] is on the card, else "" (and [build] doesn't run). */
    inline fun text(field: String, build: () -> String): String = if (has(field)) build() else ""

    /** Whether [field] is on the card. */
    fun has(field: String): Boolean = field in fields

    /** Language-aware helpers for card contents. */
    companion object {
        /**
         * [language]'s contents a card carries: all of them without a custom [fieldMap] (note type
         * field → content, "" = *Nothing*), else only the ones some field receives; the word always,
         * the screenshot only with [includeImage].
         */
        fun forMapping(language: LanguageModule, fieldMap: Map<String, String>?, includeImage: Boolean): List<String> {
            val received = fieldMap?.run { values.toSet() }
            val disabled = CardFields.forLanguage(language).filter { received != null && it !in received }.toSet()
            val noImage = if (includeImage) emptySet() else setOf(CardFields.IMAGE)
            return CardFields.enabled(language = language, disabled = disabled + noImage)
        }

        /** The study language's article for the first of [terms] whose tags give one (German der/die/das). */
        fun gender(terms: List<TermResult>): String {
            val language = Languages.current
            val article =
                terms.firstNotNullOfOrNull { term ->
                    val tags = term.definitionTags.split(" ").filter { it.isNotBlank() }
                    language.nounArticle(tags.toSet())
                }
            return article.orEmpty()
        }
    }
}

/** The "How cards are created" text of Settings → Anki, for the study language. */
fun cardsExplanation(): String {
    val language = Languages.current
    val fields = CardFields.forLanguage(language).joinToString(", ") { CardFields.label(it) }
    return "${language.displayName} cards have these fields: $fields. Set a field to Nothing above to leave that content out. " +
        "Deck, note type and tags are kept per study language. " +
        "Other note types are matched by field name (Anime Cards, Lapis, Yomitan-style), or by the mapping above. " +
        "A note type named \"${DsmPrefs.defaultModel(language.code)}\" is created automatically if it does not exist."
}
