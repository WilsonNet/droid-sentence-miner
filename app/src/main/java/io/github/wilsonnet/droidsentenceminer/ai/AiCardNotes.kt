package io.github.wilsonnet.droidsentenceminer.ai

import android.content.Context
import io.github.wilsonnet.droidsentenceminer.anki.AnkiCard
import io.github.wilsonnet.droidsentenceminer.anki.CardFields
import java.util.concurrent.ConcurrentHashMap

/**
 * AI answers generated in a lookup popup, waiting for the card mined from it. Mining never asks
 * the AI itself: only an answer the user already read goes on the card, where Settings → AI says.
 */
object AiCardNotes {
    private const val TTL_MS = 30 * 60 * 1000L

    // Expression → the last answer generated for it.
    private val notes = ConcurrentHashMap<String, Note>()

    /** Keeps a finished popup [answer] (Markdown) for the next card of [expression]. */
    fun remember(expression: String, answer: String) {
        if (expression.isNotBlank() && answer.isNotBlank()) notes[expression] = Note(answer, System.currentTimeMillis())
    }

    /** Forgets [expression]'s answer (regenerating, or the answer was discarded). */
    fun forget(expression: String) {
        notes.remove(expression)
    }

    /** [card] with its expression's recent answer added where the settings put it; unchanged without one. */
    suspend fun applyTo(context: Context, card: AnkiCard): AnkiCard {
        val settings = AiPrefs(context).current()
        val note = notes[card.expression]?.takeIf { System.currentTimeMillis() - it.createdAt < TTL_MS }
        val html = note?.let { AiMarkdown.toHtml(it.markdown) }
        val hasGlossary = CardFields.MEANING in card.fields && card.meaningHtml.isNotBlank()
        val result =
            when {
                html == null || !settings.isEnabled -> card
                settings.placement == AiCardPlacement.OWN_FIELD -> card.copy(aiExplanation = html)
                settings.placement == AiCardPlacement.AFTER_GLOSSARY && hasGlossary -> card.copy(meaningHtml = card.meaningHtml + html)
                settings.placement == AiCardPlacement.BEFORE_GLOSSARY && hasGlossary -> card.copy(meaningHtml = html + card.meaningHtml)
                else -> card
            }
        return result
    }

    private data class Note(val markdown: String, val createdAt: Long)
}
