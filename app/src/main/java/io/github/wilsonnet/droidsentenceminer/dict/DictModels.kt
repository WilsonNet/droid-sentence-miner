package io.github.wilsonnet.droidsentenceminer.dict

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Parsed index.json of a Yomitan dictionary. */
data class DictionaryIndex(
    val title: String,
    val revision: String,
    val format: Int = 3,
    val version: Int? = null,
    val author: String? = null,
    val url: String? = null,
    val description: String? = null,
    val attribution: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val sequenced: Boolean? = null,
    val frequencyMode: String? = null,
    val tagMeta: Map<String, TagMeta> = emptyMap(),
) {
    data class TagMeta(
        val category: String = "",
        val order: Int = 0,
        val notes: String = "",
        val score: Int = 0,
    )
}

/** One term bank entry (format 3): [expression, reading, definitionTags, rules, score, glossary, sequence, termTags] */
data class TermEntry(
    val expression: String,
    val reading: String,
    val definitionTags: String = "",
    val rules: String = "",
    val score: Int = 0,
    val glossaryJson: String = "[]",
    val sequence: Long = 0L,
    val termTags: String = "",
)

/** One kanji bank entry (format 3): [character, onyomi, kunyomi, tags, meanings, stats] */
data class KanjiEntry(
    val character: String,
    val onyomi: String = "",
    val kunyomi: String = "",
    val tags: String = "",
    val meaningsJson: String = "[]",
    val statsJson: String = "{}",
)

data class TagEntry(
    val name: String,
    val category: String = "",
    val order: Int = 0,
    val notes: String = "",
    val score: Int = 0,
)

data class TermMetaEntry(
    val expression: String,
    val mode: String,
    val dataJson: String = "{}",
)

data class KanjiMetaEntry(
    val character: String,
    val mode: String,
    val dataJson: String = "{}",
)

/** A dictionary row stored in the database. */
data class DictionaryInfo(
    val id: Long,
    val title: String,
    val revision: String,
    val author: String?,
    val description: String?,
    val attribution: String?,
    val sourceLanguage: String?,
    val targetLanguage: String?,
    val priority: Int,
    val enabled: Boolean,
    val termCount: Long,
    val kanjiCount: Long,
    val styles: String?,
    val zipPath: String?,
)

/** A dictionary lookup hit. */
data class TermResult(
    val dictId: Long,
    val dictionaryTitle: String,
    val dictionaryPriority: Int,
    val expression: String,
    val reading: String,
    val definitionTags: String,
    val rules: String,
    val score: Int,
    val glossaryJson: String,
    val sequence: Long,
    val termTags: String,
    val matchedForm: String,
    val deinflectionReasons: List<String> = emptyList(),
)

/** Kanji lookup hit. */
data class KanjiResult(
    val dictId: Long,
    val dictionaryTitle: String,
    val character: String,
    val onyomi: String,
    val kunyomi: String,
    val tags: String,
    val meaningsJson: String,
    val statsJson: String,
)

/** Definition/frequency metadata for a term. */
data class TermMetaResult(
    val dictId: Long,
    val dictionaryTitle: String,
    val expression: String,
    val mode: String,
    val dataJson: String,
)

/** A word that was found inside scanned text. */
data class ScannedWord(
    val text: String,
    val start: Int,
    val end: Int,
    val results: List<TermResult>,
)

internal fun JsonElement?.asStringOrNull(): String? {
    val primitive = this?.jsonPrimitive ?: return null
    return primitive.contentOrNull
        ?: primitive.booleanOrNull?.toString()
        ?: primitive.longOrNull?.toString()
        ?: primitive.doubleOrNull?.toString()
        ?: primitive.intOrNull?.toString()
}

internal fun JsonObject.string(key: String): String? = this[key].asStringOrNull()

internal fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

internal fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

internal fun JsonObject.stringMap(key: String): Map<String, String> {
    val obj = this[key] as? JsonObject ?: return emptyMap()
    return obj.mapValues { (_, v) -> v.asStringOrNull() ?: "" }
}

internal fun JsonElement.asListOrNull(): JsonArray? = this as? JsonArray

internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonPrimitive.stringOrNull(): String? = contentOrNull
