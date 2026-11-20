package io.github.wilsonnet.droidsentenceminer.ai

/**
 * The wire format of an AI provider's API. Almost every provider speaks one of these three, so a new
 * provider is usually a new [AiProvider] row, not new code.
 *
 * @property label Shown in Settings.
 */
enum class AiProtocol(val label: String) {
    /** OpenAI Chat Completions (`POST {base}/chat/completions`, `GET {base}/models`): the de facto standard. */
    OPENAI_CHAT("OpenAI-compatible"),

    /** Anthropic Messages (`POST {base}/messages`, `x-api-key`, `anthropic-version`). */
    ANTHROPIC("Anthropic Messages"),

    /** Google Gemini (`POST {base}/models/{model}:streamGenerateContent?alt=sse`, `x-goog-api-key`). */
    GEMINI("Google Gemini"),
}

/**
 * One AI provider preset: where its API lives and how to talk to it.
 *
 * @property id Stable id stored in preferences.
 * @property name Shown in Settings.
 * @property protocol The API's wire format.
 * @property baseUrl API root without a trailing slash (empty for [AiProviders.CUSTOM]: the user's own).
 * @property keyUrl Where the user creates a key; null when the provider needs none.
 * @property note One line under the provider in Settings (free tier, local, …).
 * @property defaultModel Model picked before the user chooses one; empty: the first listed model.
 * @property modelsUrl Model list when it isn't `{baseUrl}/models`.
 * @property hasSessionHeader Sends `x-opencode-session` (OpenCode routes and caches by it).
 * @property extraBody JSON object fields added to every request (provider-specific switches, e.g. no reasoning).
 * @property modelHints Parts of model ids to prefer when the chosen model stops working ([AiModels.pick]).
 */
data class AiProvider(
    val id: String,
    val name: String,
    val protocol: AiProtocol,
    val baseUrl: String,
    val keyUrl: String?,
    val note: String = "",
    val defaultModel: String = "",
    val modelsUrl: String? = null,
    val hasSessionHeader: Boolean = false,
    val extraBody: String? = null,
    val modelHints: List<String> = AiModels.GENERIC_HINTS,
) {
    /** Whether requests need an API key (local servers don't). */
    val isKeyNeeded: Boolean get() = keyUrl != null || id == AiProviders.CUSTOM_ID

    /** Whether the user types the base URL (custom endpoints, local servers on another host). */
    val hasEditableUrl: Boolean get() = id == AiProviders.CUSTOM_ID || id == AiProviders.OLLAMA_ID
}

/** The provider presets, in the order Settings lists them. */
object AiProviders {
    /** Id of the user-defined endpoint. */
    const val CUSTOM_ID = "custom"

    /** Id of the local Ollama server. */
    const val OLLAMA_ID = "ollama"

    /** Google's Gemini API: free tier with a Google account, so it leads the list. */
    val GEMINI =
        AiProvider(
            id = "gemini",
            name = "Google Gemini",
            protocol = AiProtocol.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            keyUrl = "https://aistudio.google.com/apikey",
            note = "Free tier · key from Google AI Studio",
            // An alias that follows new releases; Flash proper answered 503 (busy) on the free tier in testing.
            defaultModel = "gemini-flash-lite-latest",
            modelHints = listOf("flash-lite-latest", "flash-latest", "flash-lite", "flash"),
        )

    /** A user-defined endpoint speaking any [AiProtocol]. */
    val CUSTOM =
        AiProvider(
            id = CUSTOM_ID,
            name = "Custom endpoint",
            protocol = AiProtocol.OPENAI_CHAT,
            baseUrl = "",
            keyUrl = null,
            note = "Any OpenAI, Anthropic or Gemini compatible API",
        )

    /** Every preset; [CUSTOM] last. */
    val ALL: List<AiProvider> =
        listOf(
            GEMINI,
            AiProvider(
                id = "openrouter",
                name = "OpenRouter",
                protocol = AiProtocol.OPENAI_CHAT,
                baseUrl = "https://openrouter.ai/api/v1",
                keyUrl = "https://openrouter.ai/settings/keys",
                note = "Hundreds of models, some free (:free)",
                // OpenRouter's router over the free models: works with a fresh key, no model to pick.
                defaultModel = "openrouter/free",
                modelHints = listOf("openrouter/free", ":free"),
            ),
            AiProvider(
                id = "groq",
                name = "Groq",
                protocol = AiProtocol.OPENAI_CHAT,
                baseUrl = "https://api.groq.com/openai/v1",
                keyUrl = "https://console.groq.com/keys",
                note = "Free tier · very fast",
            ),
            AiProvider(
                id = "cerebras",
                name = "Cerebras",
                protocol = AiProtocol.OPENAI_CHAT,
                baseUrl = "https://api.cerebras.ai/v1",
                keyUrl = "https://cloud.cerebras.ai",
                note = "Free tier · very fast",
            ),
            AiProvider(
                id = "ollama-cloud",
                name = "Ollama Cloud",
                protocol = AiProtocol.OPENAI_CHAT,
                baseUrl = "https://ollama.com/v1",
                keyUrl = "https://ollama.com/settings/keys",
                // A key needs no card, but API answers did (HTTP 402 on every model with a fresh key, 2026-11).
                note = "Open models · API answers need credits",
                defaultModel = "glm-5.3-flash",
            ),
            AiProvider(
                id = "openai",
                name = "OpenAI",
                protocol = AiProtocol.OPENAI_CHAT,
                baseUrl = "https://api.openai.com/v1",
                keyUrl = "https://platform.openai.com/api-keys",
            ),
            AiProvider(
                id = "anthropic",
                name = "Anthropic Claude",
                protocol = AiProtocol.ANTHROPIC,
                baseUrl = "https://api.anthropic.com/v1",
                keyUrl = "https://console.anthropic.com/settings/keys",
            ),
            AiProvider(
                id = "deepseek",
                name = "DeepSeek",
                protocol = AiProtocol.OPENAI_CHAT,
                baseUrl = "https://api.deepseek.com/v1",
                keyUrl = "https://platform.deepseek.com/api_keys",
            ),
            AiProvider(
                id = "mistral",
                name = "Mistral",
                protocol = AiProtocol.OPENAI_CHAT,
                baseUrl = "https://api.mistral.ai/v1",
                keyUrl = "https://console.mistral.ai/api-keys",
                note = "Free tier",
            ),
            AiProvider(
                id = "xai",
                name = "xAI Grok",
                protocol = AiProtocol.OPENAI_CHAT,
                baseUrl = "https://api.x.ai/v1",
                keyUrl = "https://console.x.ai",
            ),
            AiProvider(
                id = "opencode-go",
                name = "OpenCode Go",
                protocol = AiProtocol.OPENAI_CHAT,
                baseUrl = "https://opencode.ai/inference/go/openai/v1",
                keyUrl = "https://opencode.ai/auth",
                note = "Subscription · open models",
                defaultModel = "deepseek-v4.1-flash",
                modelHints = listOf("deepseek-v4", "flash", "glm"),
                modelsUrl = "https://opencode.ai/zen/go/v1/models",
                hasSessionHeader = true,
                // Explanations don't need reasoning: first text in ~4.6 s instead of ~8.5 s (deepseek-v4.1-flash).
                extraBody = """{"thinking":{"type":"disabled"}}""",
            ),
            AiProvider(
                id = OLLAMA_ID,
                name = "Ollama (local)",
                protocol = AiProtocol.OPENAI_CHAT,
                baseUrl = "http://192.168.0.2:11434/v1",
                keyUrl = null,
                note = "Your own computer · no key",
            ),
            CUSTOM,
        )

    /** The preset with [id]; unknown ids fall back to [GEMINI]. */
    fun byId(id: String?): AiProvider = ALL.firstOrNull { it.id == id } ?: GEMINI
}
