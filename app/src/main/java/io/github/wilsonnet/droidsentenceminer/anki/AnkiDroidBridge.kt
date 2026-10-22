package io.github.wilsonnet.droidsentenceminer.anki

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AnkiDroid integration through the official AnkiDroid API (AddContentApi).
 * Cards, images and audio are all supported when AnkiDroid >= 2.16 is installed.
 */
class AnkiDroidBridge(private val context: Context) : AnkiBridge {

    override val id: String = "ankidroid"
    override val displayName: String = "AnkiDroid"

    private val api: AddContentApi by lazy { AddContentApi(context) }

    private val ankiPackage: String? get() = AddContentApi.getAnkiDroidPackageName(context)

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        ankiPackage != null && hasPermission()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, AddContentApi.READ_WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED

    override suspend fun testConnection(): AnkiResult<String> = withContext(Dispatchers.IO) {
        if (ankiPackage == null) return@withContext AnkiResult.NotAvailable
        if (!hasPermission()) return@withContext AnkiResult.Error(PERMISSION_HINT)
        runCatching {
            val decks = api.deckList.orEmpty().size
            val models = api.modelList.orEmpty().size
            val version = runCatching { api.apiHostSpecVersion.toString() }.getOrDefault("?")
            AnkiResult.Success("AnkiDroid connected (api spec $version, $decks decks, $models note types)")
        }.getOrElse { AnkiResult.Error(it.message ?: "AnkiDroid error") }
    }

    override suspend fun deckNames(): AnkiResult<List<String>> = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext AnkiResult.NotAvailable
        runCatching { AnkiResult.Success(api.deckList.orEmpty().values.sorted()) }
            .getOrElse { AnkiResult.Error(it.message ?: "Could not list decks") }
    }

    override suspend fun modelNames(): AnkiResult<List<String>> = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext AnkiResult.NotAvailable
        runCatching { AnkiResult.Success(api.modelList.orEmpty().values.sorted()) }
            .getOrElse { AnkiResult.Error(it.message ?: "Could not list note types") }
    }

    override suspend fun ensureDeck(name: String): AnkiResult<Unit> = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext AnkiResult.NotAvailable
        runCatching {
            val existing = api.deckList.orEmpty().entries.firstOrNull { it.value == name }
            if (existing == null) api.addNewDeck(name)
            AnkiResult.Success(Unit)
        }.getOrElse { AnkiResult.Error(it.message ?: "Could not create deck") }
    }

    override suspend fun ensureModel(name: String): AnkiResult<Unit> = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext AnkiResult.NotAvailable
        runCatching {
            val existing = api.modelList.orEmpty().entries.firstOrNull { it.value == name }
            if (existing == null) {
                api.addNewCustomModel(
                    name = name,
                    fields = CardFields.ORDER.toTypedArray(),
                    cards = arrayOf("Recognition"),
                    qfmt = arrayOf(Templates.FRONT),
                    afmt = arrayOf(Templates.BACK),
                    css = Templates.CSS,
                    did = null,
                    sortf = 0,
                )
            }
            AnkiResult.Success(Unit)
        }.getOrElse { AnkiResult.Error(it.message ?: "Could not create note type") }
    }

    override suspend fun addNote(card: AnkiCard, deck: String, model: String): AnkiResult<String> = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext AnkiResult.NotAvailable
        try {
            ensureDeck(deck)
            ensureModel(model)

            val deckId = api.deckList.orEmpty().entries.firstOrNull { it.value == deck }?.key
                ?: return@withContext AnkiResult.Error("Deck '$deck' not found")
            val modelId = api.modelList.orEmpty().entries.firstOrNull { it.value == model }?.key
                ?: return@withContext AnkiResult.Error("Note type '$model' not found")

            val imageHtml = card.imageBytes?.let { bytes ->
                addMedia(bytes, card.imageName ?: "dsm_image.png", "image")
            }
            val audioHtml = card.audioBytes?.let { bytes ->
                addMedia(bytes, card.audioName ?: "dsm_audio.mp3", "audio")
            }
            val wordAudioHtml = card.wordAudioBytes?.let { bytes ->
                addMedia(bytes, card.wordAudioName ?: "dsm_word.mp3", "audio")
            }

            val values = mapOf(
                CardFields.EXPRESSION to card.expression,
                CardFields.READING to card.reading,
                CardFields.MEANING to card.meaningHtml,
                // Our note type bolds the word; other note types (Anime Cards, Lapis...) get the sentence as
                // plain text like Yomitan's {sentence}.
                CardFields.SENTENCE to CardHtml.sentenceFor(model, card),
                CardFields.IMAGE to (imageHtml ?: ""),
                CardFields.AUDIO to (audioHtml ?: ""),
                CardFields.SOURCE to (card.sourceTitle ?: ""),
                CardFields.WORD_AUDIO to (wordAudioHtml ?: ""),
                CardFields.PITCH_GRAPH to card.pitchGraphHtml,
            )
            val fieldNames = api.getFieldList(modelId)?.toList() ?: CardFields.ORDER
            val fields = AnkiFieldMapper.mapToArray(fieldNames, values)
            val tags = card.tags.toSet()

            if (api.findDuplicateNotes(modelId, card.expression).isNotEmpty()) {
                return@withContext AnkiResult.Error("Already in Anki")
            }

            // Note: current AnkiDroid API takes (modelId, deckId) in this order.
            var noteId = api.addNote(modelId = modelId, deckId = deckId, fields = fields, tags = tags)

            // Some AnkiDroid versions cannot insert notes into a note type that was just
            // created through the API ("No such notetype" from the Rust backend). Retry
            // with the built-in Basic note type so the card is still created.
            if (noteId == null) {
                android.util.Log.w(
                    "DSM-Anki",
                    "addNote failed with modelId=$modelId deckId=$deckId fields=${fields.size} " +
                        "models=${api.modelList.orEmpty()}",
                )
                val basicId = api.modelList.orEmpty().entries
                    .firstOrNull { it.value == BASIC_MODEL }?.key
                if (basicId != null && basicId != modelId) {
                    val basicFields = AnkiFieldMapper.mapToArray(
                        api.getFieldList(basicId)?.toList() ?: listOf("Front", "Back"),
                        values,
                    )
                    noteId = api.addNote(modelId = basicId, deckId = deckId, fields = basicFields, tags = tags)
                    if (noteId != null) {
                        return@withContext AnkiResult.Success("$noteId (Basic note type fallback)")
                    }
                    android.util.Log.w("DSM-Anki", "Basic fallback also failed (basicId=$basicId)")
                } else {
                    android.util.Log.w("DSM-Anki", "no Basic fallback available (basicId=$basicId)")
                }
            }

            noteId
                ?: return@withContext AnkiResult.Error("AnkiDroid refused the note (is the API enabled?)")
            AnkiResult.Success(noteId.toString())
        } catch (e: Exception) {
            AnkiResult.Error(e.message ?: "AnkiDroid error")
        }
    }

    override suspend fun isDuplicate(word: String, deck: String, model: String): AnkiResult<Boolean> = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext AnkiResult.NotAvailable
        runCatching {
            // Same check addNote refuses duplicates with: the note type's first field, collection-wide.
            val modelId = api.modelList.orEmpty().entries.firstOrNull { it.value == model }?.key
            if (modelId == null) {
                AnkiResult.Success(false)
            } else {
                AnkiResult.Success(api.findDuplicateNotes(modelId, word).isNotEmpty())
            }
        }.getOrElse { AnkiResult.Error(it.message ?: "AnkiDroid error") }
    }

    private fun addMedia(bytes: ByteArray, filename: String, mimeType: String): String? {
        val dir = File(context.cacheDir, "anki-media").apply { mkdirs() }
        val file = File(dir, filename.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        file.writeBytes(bytes)
        val authority = "${context.packageName}.files"
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        ankiPackage?.let { pkg ->
            context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return api.addMediaFromUri(uri, file.name, mimeType)
    }

    companion object {
        const val PERMISSION_HINT =
            "AnkiDroid permission missing. Enable 'AnkiDroid API' in AnkiDroid settings and grant access from Droid Sentence Miner."
        const val BASIC_MODEL = "Basic"
    }
}

object Templates {
    const val FRONT = """
<div class="expression">{{Expression}}</div>
<div class="reading">{{Reading}} {{WordAudio}}</div>
<div class="pitch">{{PitchGraph}}</div>
"""

    const val BACK = """
{{FrontSide}}
<hr id="answer">
<div class="meaning">{{Meaning}}</div>
<div class="sentence">{{Sentence}}</div>
<div class="media">{{Image}}{{Audio}}</div>
<div class="source">{{Source}}</div>
"""

    const val CSS = """
.card {
  font-family: -apple-system, "Noto Sans JP", "Hiragino Sans", sans-serif;
  font-size: 20px; text-align: left; color: #1a1a1a; background: #fdfdfd;
}
.expression { font-size: 30px; font-weight: 600; }
.reading { color: #666; margin-bottom: 8px; }
.meaning { margin: 10px 0; }
.meaning .gloss-tag, .meaning .gloss-frequency { display: inline-block; padding: 1px 6px;
  border-radius: 6px; background: rgba(128,128,128,0.18); font-size: 0.8em; margin-right: 4px; }
.sentence { margin: 10px 0; font-size: 22px; }
.sentence b.target { color: #1B5E4A; }
.media img { max-width: 100%; }
.source { color: #888; font-size: 0.8em; }
"""
}

/** AnkiConnect note model/creation payload. */
object AnkiConnectTemplates {
    const val CSS = Templates.CSS
    const val FRONT = Templates.FRONT
    const val BACK = Templates.BACK
}
