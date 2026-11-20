package io.github.wilsonnet.droidsentenceminer.ai

import android.content.Context
import io.github.wilsonnet.droidsentenceminer.dict.GlossaryHtml
import io.github.wilsonnet.droidsentenceminer.dict.GlossaryParser
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import kotlinx.coroutines.flow.Flow

/**
 * The AI feature's entry point (one per app, in `AppContainer`): settings, the API client, the
 * lookup context prompts are filled with. Answers waiting for a card: [AiCardNotes].
 */
class AiService(context: Context) {
    /** Settings. */
    val prefs = AiPrefs(context)

    /** API client. */
    val client = AiClient()

    /** What [AiPromptRenderer] fills a prompt with for a popup's lookup. */
    fun lookupContext(word: String, sentence: String, details: TermLookup.LookupDetails?): AiLookupContext {
        val glossary = details?.run { primary?.glossaryJson }
        val entries = GlossaryParser.parse(glossary)
        val html = if (entries.isEmpty()) "" else GlossaryHtml.render(entries = entries, includeImages = false)
        val definition =
            html
                .replace(TAG, " ")
                .replace(SPACES, " ")
                .trim()
                .take(MAX_DEFINITION_CHARS)
        val lemma = details?.run { baseForm.ifBlank { word } } ?: word
        return AiLookupContext(
            word = word,
            lemma = lemma,
            reading = details?.reading.orEmpty(),
            sentence = sentence.replace(oldValue = "\n", newValue = "").trim(),
            definition = definition,
            language = Languages.current.displayName,
        )
    }

    /** Streams [prompt]'s answer from [config]; [session] stays the same for one popup. */
    fun ask(config: AiConfig, prompt: String, session: String): Flow<AiDelta> =
        client.stream(config = config, prompt = prompt, session = session)

    private companion object {
        const val MAX_DEFINITION_CHARS = 400
        val TAG = Regex("<[^>]*>")
        val SPACES = Regex("""\s+""")
    }
}
