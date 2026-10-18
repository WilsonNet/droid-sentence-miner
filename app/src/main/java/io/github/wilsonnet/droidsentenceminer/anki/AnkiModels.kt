package io.github.wilsonnet.droidsentenceminer.anki

import io.github.wilsonnet.droidsentenceminer.dict.GlossaryHtml
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup

/** Data needed to create one Anki note. */
data class AnkiCard(
    val expression: String,
    val reading: String,
    val meaningHtml: String,
    val sentence: String,
    val sentenceHtml: String,
    val imageBytes: ByteArray? = null,
    val imageName: String? = null,
    val audioBytes: ByteArray? = null,
    val audioName: String? = null,
    /** Pronunciation of the word itself (JapanesePod101/Jisho), separate from the sentence clip. */
    val wordAudioBytes: ByteArray? = null,
    val wordAudioName: String? = null,
    /** Yomitan-style pitch accent SVG(s) for the reading; empty without a pitch dictionary. */
    val pitchGraphHtml: String = "",
    val sourceTitle: String? = null,
    val tags: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

sealed interface AnkiResult<out T> {
    data class Success<T>(val value: T) : AnkiResult<T>
    data class Error(val message: String) : AnkiResult<Nothing>
    data object NotAvailable : AnkiResult<Nothing>
}

interface AnkiBridge {
    val id: String
    val displayName: String
    suspend fun isAvailable(): Boolean
    suspend fun testConnection(): AnkiResult<String>
    suspend fun deckNames(): AnkiResult<List<String>>
    suspend fun modelNames(): AnkiResult<List<String>>
    suspend fun ensureDeck(name: String): AnkiResult<Unit>
    suspend fun ensureModel(name: String): AnkiResult<Unit>
    suspend fun addNote(card: AnkiCard, deck: String, model: String): AnkiResult<String>
    suspend fun isDuplicate(word: String, deck: String): AnkiResult<Boolean>
}

object CardFields {
    const val EXPRESSION = "Expression"
    const val READING = "Reading"
    const val MEANING = "Meaning"
    const val SENTENCE = "Sentence"
    const val IMAGE = "Image"
    const val AUDIO = "Audio"
    const val SOURCE = "Source"
    const val WORD_AUDIO = "WordAudio"
    const val PITCH_GRAPH = "PitchGraph"

    val ORDER = listOf(EXPRESSION, READING, MEANING, SENTENCE, IMAGE, AUDIO, SOURCE, WORD_AUDIO, PITCH_GRAPH)
}

object CardHtml {
    fun sentenceFor(model: String, card: AnkiCard): String =
        if (model == io.github.wilsonnet.droidsentenceminer.core.DsmPrefs.DEFAULT_MODEL) card.sentenceHtml
        else GlossaryHtml.escape(card.sentence)

    /** Bold the target word inside the OCR sentence. */
    fun highlightSentence(sentence: String, word: String): String {
        val escaped = GlossaryHtml.escape(sentence)
        if (word.isBlank()) return escaped
        val escapedWord = GlossaryHtml.escape(word)
        val index = escaped.indexOf(escapedWord)
        if (index < 0) return escaped
        return escaped.substring(0, index) +
            "<b class=\"target\">" + escapedWord + "</b>" +
            escaped.substring(index + escapedWord.length)
    }

    /** Wrapped like Yomitan's glossary so note types styled for Yomitan cards render it the same. */
    fun meaning(entriesHtml: String, frequencyHtml: String? = null): String {
        val sb = StringBuilder("<div style=\"text-align: left;\" class=\"yomitan-glossary\">")
        if (!frequencyHtml.isNullOrBlank()) {
            sb.append("<div class=\"frequency\">").append(frequencyHtml).append("</div>")
        }
        sb.append(entriesHtml)
        sb.append("</div>")
        return sb.toString()
    }

    fun frequencyLine(lookup: TermLookup.LookupDetails): String? {
        val freqs = lookup.frequencies.filter { it.mode == "freq" }
        if (freqs.isEmpty()) return null
        val parts = freqs.take(3).map { meta ->
            val readable = runCatching {
                val element = kotlinx.serialization.json.Json.parseToJsonElement(meta.dataJson)
                when (element) {
                    is kotlinx.serialization.json.JsonPrimitive -> element.content
                    is kotlinx.serialization.json.JsonObject ->
                        element["displayValue"]?.toString()?.trim('"')
                            ?: element["value"]?.toString()?.trim('"')
                            ?: ""
                    else -> ""
                }
            }.getOrDefault("")
            if (readable.isBlank()) null else "${meta.dictionaryTitle}: $readable"
        }.filterNotNull()
        return parts.joinToString(" · ").ifBlank { null }
    }
}
