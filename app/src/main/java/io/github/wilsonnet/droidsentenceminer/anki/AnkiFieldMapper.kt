package io.github.wilsonnet.droidsentenceminer.anki

/**
 * Maps Droid Sentence Miner card content onto arbitrary Anki note types.
 *
 * Two strategies are used:
 *  1. Field-name matching with common aliases (Anime Cards, Yomitan-style
 *     note types, our own "Droid Sentence Miner" type, ...).
 *  2. A compact two-field layout (Front/Back) for plain Basic note types.
 *
 * Our canonical fields remain the fallback when nothing matches by name.
 */
object AnkiFieldMapper {

    private val aliases: Map<String, List<String>> = mapOf(
        CardFields.EXPRESSION to listOf("expression", "word", "vocab", "vocabulary", "term", "targetword", "key"),
        CardFields.READING to listOf("reading", "wordreading", "kana", "hiragana", "furigana"),
        CardFields.MEANING to listOf("meaning", "glossary", "definition", "definitions", "english", "translation", "explanation"),
        CardFields.SENTENCE to listOf("contextsentence", "sentence", "context", "example", "examplesentence", "sentencejp", "jpsentence"),
        CardFields.IMAGE to listOf("image", "picture", "snapshot", "screenshot", "photo"),
        CardFields.AUDIO to listOf("audio", "sentenceaudio", "voice", "sound", "audioclip"),
        CardFields.SOURCE to listOf("source", "origin", "title", "url"),
        CardFields.WORD_AUDIO to listOf("wordaudio", "expressionaudio", "vocabaudio", "vocabularyaudio", "termaudio", "audioword"),
    )

    /** Fields that unambiguously hold the sentence clip (preferred over a plain "Audio"). */
    private val sentenceAudioNames = setOf("sentenceaudio", "sentaudio", "audiosentence", "sentencevoice", "sentencesound")

    private fun normalize(fieldName: String): String =
        fieldName.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** canonical field name -> model field name, null when no field matches. */
    fun canonicalToModelField(fieldNames: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (canonical in CardFields.ORDER) {
            // Aliases are in preference order. Anime Cards: "Context Sentence" is the back-side context;
            // filling "Sentence" would turn the word card into a sentence card.
            val match = (aliases[canonical] ?: emptyList()).firstNotNullOfOrNull { alias ->
                fieldNames.firstOrNull { normalize(it) == alias && it !in result.values }
            }
            if (match != null) result[canonical] = match
        }
        // "Audio" + "Sentence Audio" (Anime Cards style): Audio is the word, the other the sentence.
        val sentenceField = fieldNames.firstOrNull { normalize(it) in sentenceAudioNames }
        if (sentenceField != null) {
            val plainAudio = fieldNames.firstOrNull { normalize(it) == "audio" }
            result[CardFields.AUDIO] = sentenceField
            if (result[CardFields.WORD_AUDIO] == null && plainAudio != null) result[CardFields.WORD_AUDIO] = plainAudio
        }
        return result
    }

    /** Without a word-audio field the pronunciation rides along in the reading (a play button next to it). */
    private fun withWordAudioFallback(mapping: Map<String, String>, values: Map<String, String>): Map<String, String> {
        val wordAudio = values[CardFields.WORD_AUDIO].orEmpty()
        if (wordAudio.isBlank() || mapping.containsKey(CardFields.WORD_AUDIO)) return values
        val host = if (mapping.containsKey(CardFields.READING)) CardFields.READING else CardFields.EXPRESSION
        return values + (host to (values[host].orEmpty() + " " + wordAudio).trim())
    }

    /** Ordered field values for the AnkiDroid content provider. */
    fun mapToArray(fieldNames: List<String>, values: Map<String, String>): Array<String> {
        val mapping = canonicalToModelField(fieldNames)
        if (mapping.isNotEmpty()) {
            val filled = withWordAudioFallback(mapping, values)
            return fieldNames.map { name ->
                val canonical = mapping.entries.firstOrNull { it.value == name }?.key
                canonical?.let { filled[it] } ?: ""
            }.toTypedArray()
        }
        if (fieldNames.size == 2) {
            return twoFieldLayout(values)
        }
        return fieldNames.mapIndexed { index, _ ->
            values[CardFields.ORDER.getOrNull(index)] ?: ""
        }.toTypedArray()
    }

    /** Field name -> value map for AnkiConnect (only fields the model knows about). */
    fun mapToNamedFields(fieldNames: List<String>, values: Map<String, String>): Map<String, String> {
        val mapping = canonicalToModelField(fieldNames)
        if (mapping.isNotEmpty()) {
            val filled = withWordAudioFallback(mapping, values)
            return mapping.entries.associate { (canonical, modelField) ->
                modelField to (filled[canonical] ?: "")
            }
        }
        if (fieldNames.size == 2) {
            val two = twoFieldLayout(values)
            return fieldNames.mapIndexed { index, name -> name to (two.getOrNull(index) ?: "") }.toMap()
        }
        return CardFields.ORDER.mapNotNull { canonical ->
            values[canonical]?.let { canonical to it }
        }.toMap()
    }

    private fun twoFieldLayout(values: Map<String, String>): Array<String> {
        val front = buildString {
            append("<div class=\"expression\"><b>").append(values[CardFields.EXPRESSION]).append("</b></div>")
            values[CardFields.READING]?.takeIf { it.isNotBlank() }?.let {
                append("<div class=\"reading\">").append(it).append("</div>")
            }
            values[CardFields.WORD_AUDIO]?.takeIf { it.isNotBlank() }?.let { append(it) }
            values[CardFields.SENTENCE]?.takeIf { it.isNotBlank() }?.let {
                append("<div class=\"sentence\">").append(it).append("</div>")
            }
        }
        val back = buildString {
            values[CardFields.MEANING]?.takeIf { it.isNotBlank() }?.let { append(it).append("<hr>") }
            values[CardFields.IMAGE]?.takeIf { it.isNotBlank() }?.let { append(it).append("<br>") }
            values[CardFields.AUDIO]?.takeIf { it.isNotBlank() }?.let { append(it).append("<br>") }
            values[CardFields.SOURCE]?.takeIf { it.isNotBlank() }?.let {
                append("<div class=\"source\">").append(it).append("</div>")
            }
        }
        return arrayOf(front, back)
    }
}
