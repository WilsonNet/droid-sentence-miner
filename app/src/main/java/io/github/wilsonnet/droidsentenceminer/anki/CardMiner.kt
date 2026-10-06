package io.github.wilsonnet.droidsentenceminer.anki

import android.content.Context
import android.graphics.Bitmap
import io.github.wilsonnet.droidsentenceminer.audio.M4aEncoder
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import io.github.wilsonnet.droidsentenceminer.dict.DictArchive
import io.github.wilsonnet.droidsentenceminer.dict.DictDatabase
import io.github.wilsonnet.droidsentenceminer.dict.GlossaryHtml
import io.github.wilsonnet.droidsentenceminer.dict.GlossaryParser
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Builds Anki cards from a lookup result and stores them in the mining history.
 */
class CardMiner(
    private val context: Context,
    private val db: DictDatabase,
    private val lookup: TermLookup,
    private val ankiClient: AnkiClient,
    private val prefs: DsmPrefs,
) {

    suspend fun buildCard(
        word: String,
        sentence: String,
        image: Bitmap? = null,
        details: TermLookup.LookupDetails? = null,
        audio: M4aEncoder.EncodedAudio? = null,
    ): AnkiCard = withContext(Dispatchers.Default) {
        val snapshot = prefs.snapshot()
        val resolved = details ?: lookup.lookup(word)

        val sb = StringBuilder()
        var currentDictId = -1L
        for (term in resolved.terms) {
            if (term.dictId != currentDictId) {
                currentDictId = term.dictId
                sb.append("<div class=\"gloss-dictionary\">")
                sb.append(GlossaryHtml.escape(term.dictionaryTitle))
                sb.append("</div>")
            }
            sb.append("<div class=\"gloss-header\">")
            term.definitionTags.split(" ").filter { it.isNotBlank() }.take(8).forEach {
                sb.append("<span class=\"gloss-tag\">").append(GlossaryHtml.escape(it)).append("</span>")
            }
            sb.append("</div>")
            val entries = GlossaryParser.parse(term.glossaryJson)
            sb.append(GlossaryHtml.render(entries, includeImages = false))
        }

        val reading = resolved.reading.orEmpty()
        val tags = snapshot.ankiTags.split(Regex("[,\\s]+")).filter { it.isNotBlank() }

        val imageBytes = image?.takeIf { snapshot.ankiIncludeImage }?.let { bitmap ->
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
                output.toByteArray()
            }
        }

        AnkiCard(
            expression = resolved.baseForm.ifBlank { word },
            reading = reading,
            meaningHtml = CardHtml.meaning(sb.toString(), CardHtml.frequencyLine(resolved)),
            sentence = sentence,
            sentenceHtml = CardHtml.highlightSentence(sentence, resolved.baseForm.ifBlank { word }),
            imageBytes = imageBytes,
            imageName = "dsm_${sanitizeForFilename(resolved.baseForm.ifBlank { word })}.jpg",
            audioBytes = audio?.bytes,
            audioName = audio?.let { "dsm_${sanitizeForFilename(resolved.baseForm.ifBlank { word })}.${it.extension}" },
            sourceTitle = "Droid Sentence Miner (screen OCR)",
            tags = tags,
        )
    }

    suspend fun mine(
        word: String,
        sentence: String,
        image: Bitmap? = null,
        details: TermLookup.LookupDetails? = null,
        addToAnki: Boolean = true,
        audio: M4aEncoder.EncodedAudio? = null,
    ): AnkiResult<String> = withContext(Dispatchers.IO) {
        val card = buildCard(word, sentence, image, details, audio)
        val snapshot = prefs.snapshot()

        val result = if (addToAnki) {
            ankiClient.addNote(card)
        } else {
            AnkiResult.Success("saved")
        }

        val snippet = card.meaningHtml.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(300)
        db.minedCardDao().insert(
            io.github.wilsonnet.droidsentenceminer.dict.MinedCardEntity(
                word = card.expression,
                reading = card.reading,
                sentence = sentence,
                meaningSnippet = snippet,
                dictionaryTitle = details?.primaryDictionary,
                imagePath = null,
                timestamp = System.currentTimeMillis(),
                ankiDeck = snapshot.ankiDeck,
                ankiModel = snapshot.ankiModel,
                addedToAnki = result is AnkiResult.Success,
            ),
        )
        result
    }

    /** Builds the HTML body shown in the popup WebView. */
    suspend fun buildPopupHtml(word: String, details: TermLookup.LookupDetails, darkMode: Boolean): String = withContext(Dispatchers.Default) {
        val dicts = db.dictionaryDao().getEnabled().associateBy { it.id }
        val sb = StringBuilder()
        var currentDictId = -1L
        for (term in details.terms) {
            if (term.dictId != currentDictId) {
                currentDictId = term.dictId
                sb.append("<div class=\"gloss-dictionary\">")
                sb.append(GlossaryHtml.escape(term.dictionaryTitle))
                sb.append("</div>")
            }
            sb.append("<div class=\"gloss-header\">")
            term.definitionTags.split(" ").filter { it.isNotBlank() }.take(10).forEach {
                sb.append("<span class=\"gloss-tag\">").append(GlossaryHtml.escape(it)).append("</span>")
            }
            sb.append("</div>")
            val entries = GlossaryParser.parse(term.glossaryJson)
            sb.append(
                GlossaryHtml.render(
                    entries,
                    imageUrl = { path -> dicts[term.dictId]?.let { DictArchive.imageFile(context, it, path)?.let { f -> "file://${f.absolutePath}" } } },
                    includeImages = true,
                ),
            )
        }
        val frequencies = CardHtml.frequencyLine(details)
        val freqHtml = if (frequencies != null) {
            "<div class=\"gloss-header\"><span class=\"gloss-frequency\">${GlossaryHtml.escape(frequencies)}</span></div>"
        } else {
            ""
        }
        val dictStyles = details.terms.mapNotNull { dicts[it.dictId] }.distinctBy { it.id }
            .mapNotNull { DictArchive.styles(context, it)?.takeIf { s -> s.isNotBlank() } }
            .joinToString("\n")
            .take(20_000)
        GlossaryHtml.wrapDocument(freqHtml + sb.toString(), styles = dictStyles.ifBlank { null }, darkMode = darkMode)
    }

    private fun sanitizeForFilename(text: String): String {
        val sanitized = text.take(12).replace(Regex("[^A-Za-z0-9._-]"), "_")
        // Words written purely in Japanese would collapse to underscores; use a stable id instead.
        return if (sanitized.count { it != '_' } >= 2) {
            sanitized
        } else {
            "word_${text.hashCode().toUInt().toString(16)}"
        }
    }
}
