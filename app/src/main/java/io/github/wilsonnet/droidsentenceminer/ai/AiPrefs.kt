package io.github.wilsonnet.droidsentenceminer.ai

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.wilsonnet.droidsentenceminer.core.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * How the popup's AI button answers.
 *
 * @property id Stored in preferences.
 */
enum class AiMode(val id: String) {
    /** Hands the prompt to an installed AI app (Gemini, Claude, ChatGPT…): no key, no cost. */
    APP("app"),

    /** Asks the provider's API with the user's key and shows the answer in the popup. */
    API("api"),
}

/**
 * Where an AI answer generated in the popup goes on the card mined from it.
 *
 * @property id Stored in preferences.
 * @property label Shown in Settings.
 */
enum class AiCardPlacement(val id: String, val label: String) {
    /** Never on cards. */
    OFF("off", "Not added"),

    /** Below the dictionary glossary, in the same field. */
    AFTER_GLOSSARY("after", "After the glossary"),

    /** Above the dictionary glossary, in the same field. */
    BEFORE_GLOSSARY("before", "Before the glossary"),

    /** A field of its own: one named Grammar / AI Explanation, or the one picked in Anki → Fields. */
    OWN_FIELD("field", "In its own field"),
}

/**
 * Everything the AI feature is set to.
 *
 * @property isEnabled The master switch: off hides every AI control.
 * @property mode App hand-off or API.
 * @property provider The API provider.
 * @property apiKey The provider's key, decrypted; empty when not set.
 * @property model The provider's model; empty: the preset's default.
 * @property baseUrl The provider's base URL (the user's for custom endpoints and Ollama).
 * @property protocol The provider's protocol (the user's choice for a custom endpoint).
 * @property appPackage The AI app to hand prompts to; null: the first installed.
 * @property prompts Built-ins (with edits) and the user's own prompts.
 * @property defaultPromptId The prompt the AI button runs first.
 * @property placement Where answers go on cards.
 */
data class AiSettings(
    val isEnabled: Boolean,
    val mode: AiMode,
    val provider: AiProvider,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val protocol: AiProtocol,
    val appPackage: String?,
    val prompts: List<AiPrompt>,
    val defaultPromptId: String,
    val placement: AiCardPlacement,
) {
    /** The API endpoint, or null while it lacks a key/model/URL. */
    val config: AiConfig?
        get() {
            val isReady = baseUrl.isNotBlank() && model.isNotBlank() && (apiKey.isNotBlank() || !provider.isKeyNeeded)
            val config =
                AiConfig(provider = provider, baseUrl = baseUrl.trimEnd('/'), protocol = protocol, apiKey = apiKey, model = model)
            return config.takeIf { isReady }
        }

    /** The default prompt (the first one if it was deleted). */
    val defaultPrompt: AiPrompt get() = prompts.firstOrNull { it.id == defaultPromptId } ?: prompts.first()
}

/** The AI settings (DataStore; API keys encrypted by [AiKeyStore], one per provider). */
@Suppress("TooManyFunctions") // One setter per setting, like DsmPrefs.
class AiPrefs(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val editsSerializer = MapSerializer(String.serializer(), String.serializer())
    private val promptsSerializer = ListSerializer(AiPrompt.serializer())

    /** The current settings. */
    val settings: Flow<AiSettings> = context.dataStore.data.map { read(it) }

    /** Turns every AI feature on or off. */
    suspend fun setEnabled(isEnabled: Boolean) {
        edit { it[ENABLED] = isEnabled }
    }

    /** App hand-off or API. */
    suspend fun setMode(mode: AiMode) {
        edit { it[MODE] = mode.id }
    }

    /** The API provider. */
    suspend fun setProvider(provider: AiProvider) {
        edit { it[PROVIDER] = provider.id }
    }

    /** [provider]'s API key (encrypted at rest; empty removes it). */
    suspend fun setApiKey(provider: AiProvider, key: String) {
        edit { it[perProvider(KEY, provider)] = AiKeyStore.encrypt(key.trim()) }
    }

    /** [provider]'s model. */
    suspend fun setModel(provider: AiProvider, model: String) {
        edit { it[perProvider(MODEL, provider)] = model.trim() }
    }

    /** [provider]'s base URL (custom endpoints and local servers). */
    suspend fun setBaseUrl(provider: AiProvider, url: String) {
        edit { it[perProvider(URL, provider)] = url.trim() }
    }

    /** The custom endpoint's protocol. */
    suspend fun setCustomProtocol(protocol: AiProtocol) {
        edit { it[CUSTOM_PROTOCOL] = protocol.name }
    }

    /** The AI app prompts are handed to. */
    suspend fun setAppPackage(packageName: String) {
        edit { it[APP] = packageName }
    }

    /** The prompt the AI button runs first. */
    suspend fun setDefaultPrompt(id: String) {
        edit { it[DEFAULT_PROMPT] = id }
    }

    /** Where answers go on cards. */
    suspend fun setPlacement(placement: AiCardPlacement) {
        edit { it[PLACEMENT] = placement.id }
    }

    /** Saves [prompt] (a built-in's edit, or a new/changed own prompt); returns it with its id. */
    suspend fun savePrompt(prompt: AiPrompt): AiPrompt {
        val saved = if (prompt.id.isBlank()) prompt.copy(id = "own-" + UUID.randomUUID().toString().take(ID_CHARS)) else prompt
        edit { prefs ->
            if (saved.isBuiltIn) {
                val edits = readEdits(prefs).toMutableMap()
                if (saved.isModified) edits[saved.id] = saved.template else edits.remove(saved.id)
                prefs[PROMPT_EDITS] = json.encodeToString(editsSerializer, edits)
            } else {
                val custom = readCustom(prefs)
                val index = custom.indexOfFirst { it.id == saved.id }
                val updated = if (index < 0) custom + saved else custom.toMutableList().also { it[index] = saved }
                prefs[CUSTOM_PROMPTS] = json.encodeToString(promptsSerializer, updated)
            }
        }
        return saved
    }

    /** Deletes the user's prompt [id] (built-ins can only be restored, see [AiPrompt.original]). */
    suspend fun deletePrompt(id: String) {
        edit { prefs ->
            prefs[CUSTOM_PROMPTS] = json.encodeToString(promptsSerializer, readCustom(prefs).filter { it.id != id })
        }
    }

    /** The settings right now. */
    suspend fun current(): AiSettings = settings.first()

    private suspend fun edit(change: (MutablePreferences) -> Unit) {
        context.dataStore.edit { change(it) }
    }

    private fun read(prefs: Preferences): AiSettings {
        val provider = AiProviders.byId(prefs[PROVIDER])
        val key = AiKeyStore.decrypt(prefs[perProvider(KEY, provider)])
        val customProtocol =
            AiProtocol.entries.firstOrNull {
                it.name == prefs[CUSTOM_PROTOCOL]
            } ?: AiProtocol.OPENAI_CHAT
        val prompts = AiPrompts.merge(edits = readEdits(prefs), custom = readCustom(prefs))
        // Not chosen yet: the API once a key is saved, else an installed app.
        val chosenMode = AiMode.entries.firstOrNull { it.id == prefs[MODE] }
        val mode = chosenMode ?: if (key.isNotBlank()) AiMode.API else AiMode.APP
        val placement =
            AiCardPlacement.entries.firstOrNull {
                it.id == prefs[PLACEMENT]
            } ?: AiCardPlacement.AFTER_GLOSSARY
        return AiSettings(
            // Opt-in: off until the user turns it on (Home's AI card or Settings).
            isEnabled = prefs[ENABLED] == true,
            mode = mode,
            provider = provider,
            apiKey = key,
            model = prefs[perProvider(MODEL, provider)]?.takeIf { it.isNotBlank() } ?: provider.defaultModel,
            baseUrl = prefs[perProvider(URL, provider)]?.takeIf { it.isNotBlank() } ?: provider.baseUrl,
            protocol = if (provider.id == AiProviders.CUSTOM_ID) customProtocol else provider.protocol,
            appPackage = prefs[APP],
            prompts = prompts,
            defaultPromptId = prefs[DEFAULT_PROMPT] ?: AiPrompts.GRAMMAR_ID,
            placement = placement,
        )
    }

    private fun readEdits(prefs: Preferences): Map<String, String> {
        val stored = prefs[PROMPT_EDITS] ?: return emptyMap()
        return runCatching { json.decodeFromString(editsSerializer, stored) }.getOrDefault(emptyMap())
    }

    private fun readCustom(prefs: Preferences): List<AiPrompt> {
        val stored = prefs[CUSTOM_PROMPTS] ?: return emptyList()
        return runCatching { json.decodeFromString(promptsSerializer, stored) }.getOrDefault(emptyList())
    }

    private fun perProvider(key: String, provider: AiProvider) = stringPreferencesKey("${key}_${provider.id}")

    private companion object {
        const val ID_CHARS = 8
        const val KEY = "ai_key"
        const val MODEL = "ai_model"
        const val URL = "ai_url"
        val ENABLED = booleanPreferencesKey("ai_enabled")
        val MODE = stringPreferencesKey("ai_mode")
        val PROVIDER = stringPreferencesKey("ai_provider")
        val CUSTOM_PROTOCOL = stringPreferencesKey("ai_custom_protocol")
        val APP = stringPreferencesKey("ai_app")
        val DEFAULT_PROMPT = stringPreferencesKey("ai_default_prompt")
        val PLACEMENT = stringPreferencesKey("ai_card_placement")
        val PROMPT_EDITS = stringPreferencesKey("ai_prompt_edits")
        val CUSTOM_PROMPTS = stringPreferencesKey("ai_custom_prompts")
    }
}
