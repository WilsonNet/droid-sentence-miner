package io.github.wilsonnet.droidsentenceminer.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A resolved AI endpoint: the provider preset plus what the user set (key, model, custom URL).
 *
 * @property provider The preset.
 * @property baseUrl API root, no trailing slash.
 * @property protocol Wire format (the preset's, or the user's choice for a custom endpoint).
 * @property apiKey Empty for keyless local servers.
 * @property model Model id as the API names it.
 */
data class AiConfig(
    val provider: AiProvider,
    val baseUrl: String,
    val protocol: AiProtocol,
    val apiKey: String,
    val model: String,
)

/**
 * A piece of a streamed answer.
 *
 * @property text Answer text to append.
 * @property isThinking The model is reasoning (no answer text yet): the panel says so.
 * @property error A provider error sent inside the stream.
 */
data class AiDelta(val text: String = "", val isThinking: Boolean = false, val error: String? = null)

/** Request URLs, headers and bodies for each [AiProtocol] (responses: [AiStream]). Pure: unit tested. */
object AiWire {
    /** Identifies this app to providers (OpenCode asks for a client-specific agent). */
    const val USER_AGENT = "droid-sentence-miner"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val MAX_TOKENS = 2048
    private const val ROLE = "role"
    private const val MESSAGES = "messages"
    private const val OPENCODE_HOST = "opencode.ai/"
    private val json = Json { ignoreUnknownKeys = true }

    /** Where the streamed answer is requested. */
    fun streamUrl(config: AiConfig): String {
        val url =
            when (config.protocol) {
                AiProtocol.OPENAI_CHAT -> "${config.baseUrl}/chat/completions"
                AiProtocol.ANTHROPIC -> "${config.baseUrl}/messages"
                AiProtocol.GEMINI -> "${config.baseUrl}/models/${config.model}:streamGenerateContent?alt=sse"
            }
        return url
    }

    /** Where the model list is read. */
    fun modelsUrl(config: AiConfig): String = config.provider.modelsUrl ?: "${config.baseUrl}/models"

    /** Auth and protocol headers; [session] identifies one popup conversation (OpenCode routes by it). */
    fun headers(config: AiConfig, session: String): Map<String, String> {
        val headers = mutableMapOf("User-Agent" to USER_AGENT)
        val key = config.apiKey
        when (config.protocol) {
            AiProtocol.OPENAI_CHAT -> {
                if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
            }

            AiProtocol.ANTHROPIC -> {
                headers["x-api-key"] = key
                headers["anthropic-version"] = ANTHROPIC_VERSION
            }

            AiProtocol.GEMINI -> {
                headers["x-goog-api-key"] = key
            }
        }
        // OpenCode refuses requests without it; custom endpoints pointing at opencode.ai need it too.
        if (config.provider.hasSessionHeader || OPENCODE_HOST in config.baseUrl) headers["x-opencode-session"] = "ses_$session"
        return headers
    }

    /** The streaming request for one user [prompt]; the provider's [AiProvider.extraBody] fields are added. */
    fun requestBody(config: AiConfig, prompt: String): String {
        val user = JsonPrimitive("user")
        val model = "model" to JsonPrimitive(config.model)
        val stream = "stream" to JsonPrimitive(true)
        val message = JsonObject(mapOf(ROLE to user, "content" to JsonPrimitive(prompt)))
        val fields =
            when (config.protocol) {
                AiProtocol.OPENAI_CHAT -> {
                    mapOf(model, stream, MESSAGES to JsonArray(listOf(message)))
                }

                AiProtocol.ANTHROPIC -> {
                    mapOf(
                        model,
                        stream,
                        "max_tokens" to JsonPrimitive(MAX_TOKENS),
                        MESSAGES to JsonArray(listOf(message)),
                    )
                }

                AiProtocol.GEMINI -> {
                    val part = JsonObject(mapOf("text" to JsonPrimitive(prompt)))
                    val content = JsonObject(mapOf(ROLE to user, "parts" to JsonArray(listOf(part))))
                    mapOf("contents" to JsonArray(listOf(content)))
                }
            }
        val extra =
            config.provider.extraBody?.let {
                runCatching { json.parseToJsonElement(it) }.getOrNull()
            } as? JsonObject
        return JsonObject(fields + extra.orEmpty()).toString()
    }
}
