package io.github.wilsonnet.droidsentenceminer.anki

import android.util.Base64
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AnkiConnect (HTTP JSON API) integration. Works with desktop Anki via the
 * AnkiConnect add-on, and with any device that can reach the host.
 */
class AnkiConnectBridge(
    private val prefs: DsmPrefs,
) : AnkiBridge {

    override val id: String = "ankiconnect"
    override val displayName: String = "AnkiConnect"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private suspend fun url(): String = normalizeUrl(prefs.ankiConnectUrl.first())

    override suspend fun isAvailable(): Boolean = testConnection() is AnkiResult.Success

    override suspend fun testConnection(): AnkiResult<String> = withContext(Dispatchers.IO) {
        when (val response = invoke("version")) {
            is AnkiResult.Success -> {
                val version = response.value.jsonPrimitive.contentOrNull ?: "?"
                AnkiResult.Success("AnkiConnect $version at ${url()}")
            }
            is AnkiResult.Error -> response
            AnkiResult.NotAvailable -> AnkiResult.NotAvailable
        }
    }

    override suspend fun deckNames(): AnkiResult<List<String>> = withContext(Dispatchers.IO) {
        when (val response = invoke("deckNames")) {
            is AnkiResult.Success -> AnkiResult.Success(response.value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull })
            is AnkiResult.Error -> response
            AnkiResult.NotAvailable -> AnkiResult.NotAvailable
        }
    }

    override suspend fun modelNames(): AnkiResult<List<String>> = withContext(Dispatchers.IO) {
        when (val response = invoke("modelNames")) {
            is AnkiResult.Success -> AnkiResult.Success(response.value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull })
            is AnkiResult.Error -> response
            AnkiResult.NotAvailable -> AnkiResult.NotAvailable
        }
    }

    override suspend fun ensureDeck(name: String): AnkiResult<Unit> = withContext(Dispatchers.IO) {
        val existing = deckNames()
        if (existing is AnkiResult.Success && name in existing.value) return@withContext AnkiResult.Success(Unit)
        when (val response = invoke("createDeck", buildJsonObject { put("deck", name) })) {
            is AnkiResult.Success -> AnkiResult.Success(Unit)
            is AnkiResult.Error -> response
            AnkiResult.NotAvailable -> AnkiResult.NotAvailable
        }
    }

    override suspend fun ensureModel(name: String): AnkiResult<Unit> = withContext(Dispatchers.IO) {
        val existing = modelNames()
        if (existing is AnkiResult.Success && name in existing.value) return@withContext AnkiResult.Success(Unit)
        val params = buildJsonObject {
            put("modelName", name)
            put("inOrderFields", buildJsonArray { CardFields.ORDER.forEach { add(JsonPrimitive(it)) } })
            put("css", AnkiConnectTemplates.CSS)
            put("isCloze", false)
            put(
                "cardTemplates",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("Name", "Recognition")
                            put("Front", AnkiConnectTemplates.FRONT)
                            put("Back", AnkiConnectTemplates.BACK)
                        },
                    )
                },
            )
        }
        when (val response = invoke("createModel", params)) {
            is AnkiResult.Success -> AnkiResult.Success(Unit)
            is AnkiResult.Error -> response
            AnkiResult.NotAvailable -> AnkiResult.NotAvailable
        }
    }

    override suspend fun addNote(card: AnkiCard, deck: String, model: String): AnkiResult<String> = withContext(Dispatchers.IO) {
        ensureDeck(deck)
        ensureModel(model)

        val imageField = card.imageBytes?.let { bytes ->
            storeMedia(card.imageName ?: "dsm_image.png", bytes)?.let { "<img src=\"$it\">" }
        }
        val audioField = card.audioBytes?.let { bytes ->
            storeMedia(card.audioName ?: "dsm_audio.mp3", bytes)?.let { "[sound:$it]" }
        }
        val wordAudioField = card.wordAudioBytes?.let { bytes ->
            storeMedia(card.wordAudioName ?: "dsm_word.mp3", bytes)?.let { "[sound:$it]" }
        }

        val valueMap = mapOf(
            CardFields.EXPRESSION to card.expression,
            CardFields.READING to card.reading,
            CardFields.MEANING to card.meaningHtml,
            // Our note type bolds the word; other note types (Anime Cards, Lapis...) get the sentence as
                // plain text like Yomitan's {sentence}.
                CardFields.SENTENCE to CardHtml.sentenceFor(model, card),
            CardFields.IMAGE to (imageField ?: ""),
            CardFields.AUDIO to (audioField ?: ""),
            CardFields.SOURCE to (card.sourceTitle ?: ""),
            CardFields.WORD_AUDIO to (wordAudioField ?: ""),
            CardFields.PITCH_GRAPH to card.pitchGraphHtml,
        )
        val modelFields = modelFieldNames(model) ?: CardFields.ORDER
        val mappedFields = AnkiFieldMapper.mapToNamedFields(modelFields, valueMap)
        val fields = buildJsonObject {
            mappedFields.forEach { (name, value) -> put(name, value) }
        }

        val params = buildJsonObject {
            put(
                "note",
                buildJsonObject {
                    put("deckName", deck)
                    put("modelName", model)
                    put("fields", fields)
                    put(
                        "options",
                        buildJsonObject {
                            put("allowDuplicate", false)
                            put("duplicateScope", "deck")
                        },
                    )
                    put(
                        "tags",
                        buildJsonArray { card.tags.forEach { add(JsonPrimitive(it)) } },
                    )
                },
            )
        }

        when (val response = invoke("addNote", params)) {
            is AnkiResult.Success -> {
                val noteId = response.value.jsonPrimitive.longOrNull
                AnkiResult.Success(noteId?.toString() ?: "ok")
            }
            is AnkiResult.Error -> {
                if (response.message.contains("duplicate", ignoreCase = true)) {
                    AnkiResult.Error("Already in Anki")
                } else {
                    response
                }
            }
            AnkiResult.NotAvailable -> AnkiResult.NotAvailable
        }
    }

    override suspend fun isDuplicate(word: String, deck: String, model: String): AnkiResult<Boolean> = withContext(Dispatchers.IO) {
        val sanitized = word.replace("\"", "")
        // addNote checks duplicates on the note type's first field within the deck; ask the same.
        val firstField = modelFieldNames(model)?.firstOrNull() ?: CardFields.EXPRESSION
        val query = "deck:\"$deck\" note:\"$model\" \"$firstField:$sanitized\""
        val params = buildJsonObject { put("query", query) }
        when (val response = invoke("findNotes", params)) {
            is AnkiResult.Success -> AnkiResult.Success(response.value.jsonArray.isNotEmpty())
            is AnkiResult.Error -> response
            AnkiResult.NotAvailable -> AnkiResult.NotAvailable
        }
    }

    private suspend fun modelFieldNames(model: String): List<String>? {
        val params = buildJsonObject { put("modelName", model) }
        return when (val response = invoke("modelFieldNames", params)) {
            is AnkiResult.Success -> response.value.jsonArray
                .mapNotNull { it.jsonPrimitive.contentOrNull }
                .takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private suspend fun storeMedia(filename: String, bytes: ByteArray): String? {
        val params = buildJsonObject {
            put("filename", filename)
            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        return when (val response = invoke("storeMediaFile", params)) {
            is AnkiResult.Success -> response.value.jsonPrimitive.contentOrNull
            else -> null
        }
    }

    private suspend fun invoke(action: String, params: JsonObject = buildJsonObject {}): AnkiResult<JsonElement> {
        val payload = buildJsonObject {
            put("action", action)
            put("version", 6)
            put("params", params)
        }
        val request = Request.Builder()
            .url(url())
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return AnkiResult.Error("AnkiConnect HTTP ${response.code}")
                }
                val body = response.body?.string() ?: return AnkiResult.Error("Empty AnkiConnect response")
                val obj = json.parseToJsonElement(body).jsonObject
                val error = obj["error"]?.jsonPrimitive?.contentOrNull
                if (error != null && error != "null") {
                    AnkiResult.Error(error)
                } else {
                    AnkiResult.Success(obj["result"] ?: JsonPrimitive(""))
                }
            }
        } catch (e: Exception) {
            AnkiResult.Error("AnkiConnect unreachable: ${e.message}")
        }
    }

    private fun normalizeUrl(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) value = DsmPrefs.DEFAULT_CONNECT_URL
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        return value.trimEnd('/')
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Picks the active Anki backend based on settings. */
class AnkiClient(
    private val ankiDroid: AnkiDroidBridge,
    private val ankiConnect: AnkiConnectBridge,
    private val prefs: DsmPrefs,
) {
    suspend fun bridge(): AnkiBridge {
        return when (prefs.ankiProvider.first()) {
            DsmPrefs.PROVIDER_ANKICONNECT -> ankiConnect
            else -> ankiDroid
        }
    }

    suspend fun isDuplicate(word: String): AnkiResult<Boolean> {
        val snapshot = prefs.snapshot()
        return bridge().isDuplicate(word, snapshot.ankiDeck, snapshot.ankiModel)
    }

    suspend fun fallback(): AnkiBridge = if (prefs.ankiProvider.first() == DsmPrefs.PROVIDER_ANKICONNECT) ankiDroid else ankiConnect

    suspend fun addNote(card: AnkiCard): AnkiResult<String> {
        val snapshot = prefs.snapshot()
        val primary = bridge()
        val result = primary.addNote(card, snapshot.ankiDeck, snapshot.ankiModel)
        if (result is AnkiResult.Success) return result
        if (result is AnkiResult.NotAvailable || (result is AnkiResult.Error && result.message.contains("unreachable"))) {
            val other = fallback()
            if (other.isAvailable()) {
                val fallbackResult = other.addNote(card, snapshot.ankiDeck, snapshot.ankiModel)
                if (fallbackResult is AnkiResult.Success) return fallbackResult
            }
        }
        return result
    }
}
