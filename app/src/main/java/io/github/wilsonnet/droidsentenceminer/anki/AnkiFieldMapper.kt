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

    private val aliases: Map<String, Set<String>> = mapOf(
        CardFields.EXPRESSION to setOf("expression", "word", "vocab", "vocabulary", "term", "targetword", "key"),
        CardFields.READING to setOf("reading", "wordreading", "kana", "hiragana", "furigana"),
        CardFields.MEANING to setOf("meaning", "glossary", "definition", "definitions", "english", "translation", "explanation"),
        CardFields.SENTENCE to setOf("sentence", "contextsentence", "context", "example", "examplesentence", "sentencejp", "jpsentence"),
        CardFields.IMAGE to setOf("image", "picture", "snapshot", "screenshot", "photo"),
        CardFields.AUDIO to setOf("audio", "sentenceaudio", "voice", "sound", "audioclip"),
        CardFields.SOURCE to setOf("source", "origin", "title", "url"),
    )

    private fun normalize(fieldName: String): String =
        fieldName.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** canonical field name -> model field name, null when no field matches. */
    fun canonicalToModelField(fieldNames: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (canonical in CardFields.ORDER) {
            val aliasSet = aliases[canonical] ?: emptySet()
            val match = fieldNames.firstOrNull { normalize(it) in aliasSet }
            if (match != null) result[canonical] = match
        }
        return result
    }

    /** Ordered field values for the AnkiDroid content provider. */
    fun mapToArray(fieldNames: List<String>, values: Map<String, String>): Array<String> {
        val mapping = canonicalToModelField(fieldNames)
        if (mapping.isNotEmpty()) {
            return fieldNames.map { name ->
                val canonical = mapping.entries.firstOrNull { it.value == name }?.key
                canonical?.let { values[it] } ?: ""
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
            return mapping.entries.associate { (canonical, modelField) ->
                modelField to (values[canonical] ?: "")
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
