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

    /**
     * A dictionary image (structured-content `img` or the legacy `{"type":"image"}` glossary item).
     *
     * In a dictionary file, [width]/[height] are the size the author wants it shown at; Yomitan's
     * importer moves them to `preferredWidth`/`preferredHeight` and stores the file's real size as
     * width/height. We keep the file's JSON as is, so [displayWidth]/[displayHeight] are the
     * preferred size, and a missing one comes from the real size when the browser loads the image.
     */
    data class Image(
        val path: String,
        val alt: String? = null,
        val title: String? = null,
        val width: Double? = null,
        val height: Double? = null,
        val sizeUnits: String? = null,
        val preferredWidth: Double? = null,
        val preferredHeight: Double? = null,
        val description: String? = null,
        val pixelated: Boolean? = null,
        val imageRendering: String? = null,
        val appearance: String? = null,
        val background: Boolean? = null,
        val collapsed: Boolean? = null,
        val collapsible: Boolean? = null,
        val verticalAlign: String? = null,
        val border: String? = null,
        val borderRadius: String? = null,
    ) : GlossaryContent {
        /** Database-format data (Yomitan export): preferred* is the display size, width/height the file's. */
        private val hasPreferred: Boolean get() = preferredWidth != null || preferredHeight != null
        private val realAspect: Double? get() = if (width != null && height != null && width > 0 && height > 0) height / width else null

        val displayWidth: Double?
            get() = if (hasPreferred) {
                preferredWidth ?: realAspect?.let { preferredHeight!! / it }
            } else {
                width
            }?.takeIf { it > 0 }

        val displayHeight: Double?
            get() = if (hasPreferred) {
                preferredHeight ?: realAspect?.let { preferredWidth!! * it }
            } else {
                height
            }?.takeIf { it > 0 }
    }

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
                if (obj.string("path") == null) return GlossaryEntry(emptyList())
                GlossaryEntry(listOf(parseImage(obj)))
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
        if (tag == "img") return parseImage(obj)
        val excluded = setOf(
            "tag", "content", "data", "style", "type", "path", "src", "alt",
            "width", "height", "sizeUnits", "appearance", "background", "collapsible", "collapsed",
        )
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

    /** Every image property Yomitan's importer keeps (`_createImageData`); it drops `data` too. */
    private fun parseImage(obj: JsonObject): GlossaryContent.Image = GlossaryContent.Image(
        path = obj.string("path") ?: obj.string("src") ?: "",
        alt = obj.string("alt"),
        title = obj.string("title"),
        width = obj.double("width"),
        height = obj.double("height"),
        sizeUnits = obj.string("sizeUnits"),
        preferredWidth = obj.double("preferredWidth"),
        preferredHeight = obj.double("preferredHeight"),
        description = obj.string("description"),
        pixelated = obj.boolean("pixelated"),
        imageRendering = obj.string("imageRendering"),
        appearance = obj.string("appearance"),
        background = obj.boolean("background"),
        collapsed = obj.boolean("collapsed"),
        collapsible = obj.boolean("collapsible"),
        verticalAlign = obj.string("verticalAlign"),
        border = obj.string("border"),
        borderRadius = obj.string("borderRadius"),
    )
}
