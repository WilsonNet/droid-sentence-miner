package io.github.wilsonnet.droidsentenceminer.dict

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Glossary content model for Yomitan dictionaries.
 *
 * A glossary can be a plain string, a list of strings, or "structured content"
 * objects ({ type: "structured-content", content: [...] }) such as those used by
 * Jitendex. It can also contain image definitions and cross-reference arrays.
 */
sealed interface GlossaryContent {
    data class Text(val text: String) : GlossaryContent

    data class Element(
        val tag: String,
        val children: List<GlossaryContent>,
        val properties: Map<String, String> = emptyMap(),
        val style: Map<String, String> = emptyMap(),
        val data: Map<String, String> = emptyMap(),
    ) : GlossaryContent

    data class Image(
        val path: String,
        val alt: String? = null,
        val title: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) : GlossaryContent

    data object LineBreak : GlossaryContent
}

data class GlossaryEntry(
    val content: List<GlossaryContent>,
    val isDeinflection: Boolean = false,
    val deinflectionForms: List<String> = emptyList(),
)

object GlossaryParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String?): List<GlossaryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        return parseElement(element)
    }

    private fun parseElement(element: JsonElement): List<GlossaryEntry> {
        return when (element) {
            is JsonArray -> element.mapNotNull { parseSingle(it) }
            is JsonNull -> emptyList()
            else -> listOfNotNull(parseSingle(element))
        }
    }

    private fun parseSingle(element: JsonElement): GlossaryEntry? {
        return when (element) {
            is JsonPrimitive -> {
                if (element.isString) GlossaryEntry(listOf(GlossaryContent.Text(element.content)))
                else null
            }
            is JsonArray -> {
                // Deinflection entry: ["食べる", ["past", "polite"]]
                val first = element.firstOrNull()?.jsonPrimitive?.contentOrNull
                val forms = (element.getOrNull(1) as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()
                if (first != null && forms.isNotEmpty()) {
                    GlossaryEntry(
                        content = listOf(GlossaryContent.Text(first)),
                        isDeinflection = true,
                        deinflectionForms = forms,
                    )
                } else {
                    null
                }
            }
            is JsonObject -> parseObject(element)
            else -> null
        }
    }

    private fun parseObject(obj: JsonObject): GlossaryEntry? {
        return when (obj.string("type")) {
            "text" -> GlossaryEntry(listOf(GlossaryContent.Text(obj.string("text").orEmpty())))
            "image" -> {
                val path = obj.string("path") ?: return GlossaryEntry(emptyList())
                GlossaryEntry(
                    listOf(
                        GlossaryContent.Image(
                            path = path,
                            alt = obj.string("alt"),
                            title = obj.string("title"),
                            width = obj.int("width"),
                            height = obj.int("height"),
                        ),
                    ),
                )
            }
            "structured-content" -> {
                val content = obj["content"]
                GlossaryEntry(parseContent(content))
            }
            else -> null
        }
    }

    fun parseContent(element: JsonElement?): List<GlossaryContent> {
        if (element == null || element is JsonNull) return emptyList()
        return when (element) {
            is JsonArray -> element.flatMap { parseContent(it) }
            is JsonPrimitive -> if (element.isString) listOf(GlossaryContent.Text(element.content)) else emptyList()
            is JsonObject -> {
                if (element.string("tag") == "br") {
                    listOf(GlossaryContent.LineBreak)
                } else {
                    listOf(parseStructuredObject(element))
                }
            }
        }
    }

    private fun parseStructuredObject(obj: JsonObject): GlossaryContent {
        val tag = obj.string("tag") ?: obj.string("type") ?: "div"
        if (tag == "img") {
            return GlossaryContent.Image(
                path = obj.string("path") ?: obj.string("src") ?: "",
                alt = obj.string("alt"),
                title = obj.string("title"),
                width = obj.int("width"),
                height = obj.int("height"),
            )
        }
        val excluded = setOf("tag", "content", "data", "style", "type", "path", "src", "alt", "title", "width", "height")
        val properties = obj.filterKeys { it !in excluded }
            .mapValues { (_, v) -> v.asStringOrNull() ?: "" }
            .filterValues { it.isNotEmpty() }
        return GlossaryContent.Element(
            tag = tag,
            children = parseContent(obj["content"]),
            properties = properties,
            style = obj.stringMap("style"),
            data = obj.stringMap("data"),
        )
    }
}
