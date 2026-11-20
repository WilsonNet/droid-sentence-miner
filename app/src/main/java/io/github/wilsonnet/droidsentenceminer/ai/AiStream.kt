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
    private const val FREE_SUFFIX = ":free"
    private const val CONTENT = "content"

    // Gemini lists speech, image and tool models that also "generateContent" but can't answer a text prompt.
    private val NOT_CHAT =
        Regex("""tts|image|transcribe|embedding|robotics|computer-use|customtools|lyria|banana|deep-research""")
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

    /**
     * A whole (non-streamed) answer: OpenAI `choices[0].message.content`, Anthropic `content[].text`,
     * Gemini `candidates[0].content.parts[].text`; null when [body] is none of these.
     */
    fun parseWhole(protocol: AiProtocol, body: String): AiDelta? {
        val obj = runCatching { json.parseToJsonElement(body.trim()) }.getOrNull() as? JsonObject
        val error = obj?.let { errorMessage(it) }
        val text =
            when {
                obj == null -> {
                    null
                }

                protocol == AiProtocol.OPENAI_CHAT -> {
                    val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                    (choice?.get("message") as? JsonObject)?.get(CONTENT).string()
                }

                protocol == AiProtocol.ANTHROPIC -> {
                    (obj[CONTENT] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().joinToString(
                        "",
                    ) { it["text"].string().orEmpty() }
                }

                else -> {
                    geminiDelta(obj)?.text
                }
            }
        return if (error != null) AiDelta(error = error) else text?.let { AiDelta(text = it) }
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
        // Free models first (OpenRouter's ":free"), then by name.
        return ids.distinct().sortedWith(compareBy({ !it.endsWith(FREE_SUFFIX) }, { it }))
    }

    /** The error a non-2xx response body carries (`{"error": {"message": …}}` in every protocol). */
    fun errorBody(body: String): String? {
        val obj = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        return obj?.let { errorMessage(it) }
    }

    private fun isChatModel(protocol: AiProtocol, model: JsonObject): Boolean {
        val methods = (model["supportedGenerationMethods"] as? JsonArray).orEmpty().map { it.string() }
        val type = model["type"].string()
        val isChat = if (protocol == AiProtocol.GEMINI) "generateContent" in methods else type == null || type == "chat" || type == "model"
        val name = model["name"].string() ?: model["id"].string().orEmpty()
        return isChat && !NOT_CHAT.containsMatchIn(name)
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
        val text = delta?.get(CONTENT).string().orEmpty()
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
        val parts = ((candidate?.get(CONTENT) as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val answer =
            parts.filterIsInstance<JsonObject>().filter { part ->
                (part["thought"] as? JsonPrimitive)?.booleanOrNull != true
            }
        val text = answer.joinToString("") { it["text"].string().orEmpty() }
        return when {
            text.isNotEmpty() -> AiDelta(text = text)
            parts.isNotEmpty() -> AiDelta(isThinking = true)
            else -> null
        }
    }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
