package io.github.wilsonnet.droidsentenceminer.dict

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * IPA pronunciations from Yomitan `term_meta` rows with mode `ipa` (e.g. Wiktionary's
 * `wty-en-en-ipa`): `{"reading": "…", "transcriptions": [{"ipa": "/…/", "tags": ["🇺🇸"]}]}`.
 * Shown under the headword in the popup and used as the card's reading for languages without
 * kana readings. Pure Kotlin, unit tested.
 */
object Ipa {
    data class Transcription(val ipa: String, val tags: List<String>)

    fun parse(dataJson: String): List<Transcription> = runCatching {
        val array = Json.parseToJsonElement(dataJson).jsonObject["transcriptions"]?.jsonArray ?: return emptyList()
        array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val ipa = o["ipa"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val tags = o["tags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            Transcription(ipa, tags)
        }
    }.getOrDefault(emptyList())

    /** [expression]'s transcriptions across the details' IPA rows (case-insensitive), distinct, US first. */
    fun of(frequencies: List<TermMetaResult>, expression: String): List<Transcription> =
        frequencies.filter { it.mode == "ipa" && it.expression.equals(expression, ignoreCase = true) }
            .flatMap { parse(it.dataJson) }
            .distinctBy { it.ipa }
            .sortedBy { if (it.tags.any { t -> t == "🇺🇸" || t.equals("US", true) || t.contains("General American", true) }) 0 else 1 }

    /** One line for the popup: up to [max] transcriptions with their region tags. */
    fun line(transcriptions: List<Transcription>, max: Int = 3): String =
        transcriptions.take(max).joinToString("  ·  ") { t -> (t.tags.take(2).joinToString(" ") + " " + t.ipa).trim() }
}
