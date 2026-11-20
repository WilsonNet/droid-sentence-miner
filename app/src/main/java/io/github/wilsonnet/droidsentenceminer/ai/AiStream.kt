package io.github.wilsonnet.droidsentenceminer.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Reads what each [AiProtocol] sends back: stream events, model lists, error bodies. Pure: unit tested. */
object AiStream {
    private const val MODELS_PREFIX = "models/"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * One server-sent-events `data:` payload as a delta; null for events without text (pings,
     * `[DONE]`, usage). Errors sent inside the stream come back as [AiDelta.error].
     */
    fun parseEvent(protocol: AiProtocol, data: String): AiDelta? {
        val element = if (data == "[DONE]") null else runCatching { json.parseToJsonElement(data) }.getOrNull()
        val obj = element as? JsonObject
        val error = obj?.let { errorMessage(it) }
        val delta =
            when {
                obj == null -> null
                error != null -> AiDelta(error = error)
                protocol == AiProtocol.OPENAI_CHAT -> openAiDelta(obj)
                protocol == AiProtocol.ANTHROPIC -> anthropicDelta(obj)
                else -> geminiDelta(obj)
            }
        return delta
    }

    /** Model ids from a model-list response, sorted; chat models only where the API says. */
    fun parseModels(protocol: AiProtocol, body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        val items = (root?.get(if (protocol == AiProtocol.GEMINI) "models" else "data") as? JsonArray).orEmpty()
        val ids =
            items.mapNotNull { item ->
                val obj = item as? JsonObject
                if (obj == null || !isChatModel(protocol = protocol, model = obj)) null else modelId(protocol = protocol, model = obj)
            }
        return ids.distinct().sorted()
    }

    /** The error a non-2xx response body carries (`{"error": {"message": …}}` in every protocol). */
    fun errorBody(body: String): String? {
        val obj = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        return obj?.let { errorMessage(it) }
    }

    private fun isChatModel(protocol: AiProtocol, model: JsonObject): Boolean {
        val methods = (model["supportedGenerationMethods"] as? JsonArray).orEmpty().map { it.string() }
        val type = model["type"].string()
        return if (protocol == AiProtocol.GEMINI) "generateContent" in methods else type == null || type == "chat" || type == "model"
    }

    private fun modelId(protocol: AiProtocol, model: JsonObject): String? {
        val raw = if (protocol == AiProtocol.GEMINI) model["name"].string() else model["id"].string()
        return raw?.removePrefix(MODELS_PREFIX)
    }

    private fun errorMessage(obj: JsonObject): String? {
        val error = obj["error"]
        val message =
            when (error) {
                is JsonObject -> error["message"].string() ?: error.toString()
                is JsonPrimitive -> error.contentOrNull
                is JsonArray, null -> null
            }
        return message
    }

    // OpenAI: choices[0].delta.content; reasoning models stream reasoning_content (or reasoning) first.
    private fun openAiDelta(obj: JsonObject): AiDelta? {
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val delta = choice?.get("delta") as? JsonObject
        val text = delta?.get("content").string().orEmpty()
        val reasoning = delta?.get("reasoning_content").string() ?: delta?.get("reasoning").string()
        return when {
            text.isNotEmpty() -> AiDelta(text = text)
            !reasoning.isNullOrEmpty() -> AiDelta(isThinking = true)
            else -> null
        }
    }

    // Anthropic: content_block_delta with text_delta (answer) or thinking_delta.
    private fun anthropicDelta(obj: JsonObject): AiDelta? {
        val delta = obj["delta"] as? JsonObject
        val type = delta?.get("type").string()
        return when {
            obj["type"].string() != "content_block_delta" -> null
            type == "text_delta" -> AiDelta(text = delta?.get("text").string().orEmpty())
            type == "thinking_delta" -> AiDelta(isThinking = true)
            else -> null
        }
    }

    // Gemini: candidates[0].content.parts[].text; parts marked "thought" are reasoning.
    private fun geminiDelta(obj: JsonObject): AiDelta? {
        val candidate = (obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val parts = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val answer = parts.filterIsInstance<JsonObject>().filter { it["thought"].boolean() != true }
        val text = answer.joinToString("") { it["text"].string().orEmpty() }
        return when {
            text.isNotEmpty() -> AiDelta(text = text)
            parts.isNotEmpty() -> AiDelta(isThinking = true)
            else -> null
        }
    }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement?.boolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
}
