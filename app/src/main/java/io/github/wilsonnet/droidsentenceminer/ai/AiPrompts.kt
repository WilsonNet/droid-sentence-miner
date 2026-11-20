package io.github.wilsonnet.droidsentenceminer.ai

import kotlinx.serialization.Serializable

/**
 * A prompt the lookup popup can run. Built-in prompts can be edited but keep their [original], so
 * Settings can always restore them; the user's own prompts have none and can be deleted.
 *
 * @property id Stable id (built-ins: fixed names; the user's: random).
 * @property name Shown on the popup's chip and in Settings.
 * @property template The text sent, with [AiPromptRenderer.PLACEHOLDERS] filled in.
 * @property original The shipped template of a built-in prompt; null for the user's own.
 */
@Serializable
data class AiPrompt(val id: String, val name: String, val template: String, val original: String? = null) {
    /** Whether this prompt ships with the app (restorable, not deletable). */
    val isBuiltIn: Boolean get() = original != null

    /** Whether a built-in prompt's text differs from the shipped one. */
    val isModified: Boolean get() = original != null && template.trim() != original.trim()
}

/** The prompts that ship with the app. */
object AiPrompts {
    /** Id of the default prompt (grammar breakdown focused on the word). */
    const val GRAMMAR_ID = "grammar"

    private val GRAMMAR =
        listOf(
            "You are a {language} tutor helping an English-speaking learner.",
            "",
            "Sentence: {sentence}",
            "Word: {word} (dictionary form: {lemma})",
            "",
            "Break down the grammar of the sentence, focused on how \"{word}\" is used:",
            "1. What {word} does here: its form (conjugation, inflection, particle) and why that form.",
            "2. The grammar patterns around it, each with a short literal gloss.",
            "3. A natural English translation of the whole sentence.",
            "",
            "Answer in English, under 150 words, in Markdown with short bullets and **bold** {language} words.",
            "No introduction or closing remarks.",
        ).joinToString("\n")

    private val TRANSLATE =
        listOf(
            "Translate this {language} sentence into English: {sentence}",
            "",
            "Give a natural translation, then a literal one, then one line on any nuance the natural",
            "translation loses. Use Markdown and **bold** for \"{word}\" and its English equivalent. Be brief.",
        ).joinToString("\n")

    private val NUANCE =
        listOf(
            "Explain the {language} word \"{lemma}\" ({reading}) as used in: {sentence}",
            "",
            "- Its meaning in this sentence and its register (casual, formal, written…).",
            "- How it differs from one or two close synonyms.",
            "- Two short example sentences with English translations.",
            "",
            "Answer in English, in Markdown, under 150 words.",
        ).joinToString("\n")

    /** Built-in prompts, in the order the popup shows them. */
    val BUILT_IN: List<AiPrompt> =
        listOf(
            AiPrompt(id = GRAMMAR_ID, name = "Grammar", template = GRAMMAR, original = GRAMMAR),
            AiPrompt(id = "translate", name = "Translate", template = TRANSLATE, original = TRANSLATE),
            AiPrompt(id = "nuance", name = "Word nuance", template = NUANCE, original = NUANCE),
        )

    /**
     * The prompt list: built-ins with the user's [edits] (id → template) applied, then the user's
     * [custom] prompts.
     */
    fun merge(edits: Map<String, String>, custom: List<AiPrompt>): List<AiPrompt> {
        val builtIns = BUILT_IN.map { prompt -> edits[prompt.id]?.let { prompt.copy(template = it) } ?: prompt }
        return builtIns + custom.map { it.copy(original = null) }
    }
}

/**
 * What a prompt is filled with for one lookup.
 *
 * @property word The text the user tapped, as it appears in the sentence.
 * @property lemma The dictionary form the lookup found.
 * @property reading Kana reading or IPA; may be empty.
 * @property sentence The sentence the word was tapped in.
 * @property definition The first dictionary definition, as plain text; may be empty.
 * @property language The study language's English name.
 */
data class AiLookupContext(
    val word: String,
    val lemma: String,
    val reading: String,
    val sentence: String,
    val definition: String,
    val language: String,
)

/** Fills a prompt template's placeholders. */
object AiPromptRenderer {
    /** Placeholders a template may use, with what Settings says each one becomes. */
    val PLACEHOLDERS: List<Pair<String, String>> =
        listOf(
            "{word}" to "the tapped word",
            "{sentence}" to "its sentence",
            "{lemma}" to "dictionary form",
            "{reading}" to "reading / IPA",
            "{definition}" to "first definition",
            "{language}" to "study language",
        )

    /** [template] with every placeholder replaced; a missing sentence falls back to the word. */
    fun render(template: String, context: AiLookupContext): String {
        val values =
            mapOf(
                "{word}" to context.word,
                "{sentence}" to context.sentence.ifBlank { context.word },
                "{lemma}" to context.lemma.ifBlank { context.word },
                "{reading}" to context.reading,
                "{definition}" to context.definition,
                "{language}" to context.language,
            )
        return values.entries.fold(template) { text, (placeholder, value) ->
            text.replace(oldValue = placeholder, newValue = value)
        }
    }
}
