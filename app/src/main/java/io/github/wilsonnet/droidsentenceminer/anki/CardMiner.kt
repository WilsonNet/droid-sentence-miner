package io.github.wilsonnet.droidsentenceminer.anki

import android.content.Context
import android.graphics.Bitmap
import io.github.wilsonnet.droidsentenceminer.audio.AudioSegment
import io.github.wilsonnet.droidsentenceminer.audio.M4aEncoder
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import io.github.wilsonnet.droidsentenceminer.dict.DictArchive
import io.github.wilsonnet.droidsentenceminer.dict.DictDatabase
import io.github.wilsonnet.droidsentenceminer.dict.GlossaryHtml
import io.github.wilsonnet.droidsentenceminer.dict.GlossaryParser
import io.github.wilsonnet.droidsentenceminer.dict.PitchAccent
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup
import io.github.wilsonnet.droidsentenceminer.textlog.TextLineEntity
import io.github.wilsonnet.droidsentenceminer.textlog.TextLog
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
    private val textLog: TextLog,
    private val wordAudioFetcher: WordAudio = WordAudio(),
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
        val dicts = db.dictionaryDao().getEnabled().associateBy { it.id }

        val sb = StringBuilder()
        var currentDictId = -1L
        val firstOnly = prefs.glossaryFirstDictionary.first()
        val terms = if (firstOnly) resolved.terms.filter { it.dictId == resolved.terms.firstOrNull()?.dictId } else resolved.terms
        val tagCategories = lookup.tagCategories(terms.map { it.dictId }.toSet())
        for (term in terms) {
            if (!firstOnly && term.dictId != currentDictId) {
                currentDictId = term.dictId
                sb.append("<div class=\"gloss-dictionary\">")
                sb.append(GlossaryHtml.escape(term.dictionaryTitle))
                sb.append("</div>")
            }
            val tags = term.definitionTags.split(" ").filter { it.isNotBlank() }.take(8)
            if (tags.isNotEmpty()) {
                sb.append("<div class=\"gloss-header\">")
                val categories = tagCategories[term.dictId].orEmpty()
                tags.forEach { tag ->
                    val cat = categories[tag]?.let { " data-category=\"${GlossaryHtml.escape(it)}\"" } ?: ""
                    sb.append("<span class=\"gloss-tag\"$cat>").append(GlossaryHtml.escape(tag)).append("</span>")
                }
                sb.append("</div>")
            }
            val entries = GlossaryParser.parse(term.glossaryJson)
            sb.append(GlossaryHtml.render(entries, includeImages = false))
        }
        val dictStyles = terms.mapNotNull { dicts[it.dictId] }.distinctBy { it.id }
            .mapNotNull { DictArchive.styles(context, it)?.takeIf { s -> s.isNotBlank() } }
            .joinToString("\n")
            .take(20_000)
            .ifBlank { null }

        val reading = resolved.reading.orEmpty()
        val expression = resolved.baseForm.ifBlank { word }
        val pitchGraph = PitchAccent.ankiGraphs(
            reading.ifBlank { expression },
            resolved.frequencies.filter { it.mode == "pitch" && it.expression == expression }.mapNotNull { PitchAccent.parse(it.dataJson) },
        )
        val wordAudio = if (prefs.wordAudio.first()) {
            wordAudioFetcher.fetch(expression, reading, prefs.wordAudioCustomUrl.first())
        } else {
            null
        }
        val tags = snapshot.ankiTags.split(Regex("[,\\s]+")).filter { it.isNotBlank() }

        val imageBytes = image?.takeIf { snapshot.ankiIncludeImage }?.let { bitmap ->
            val (w, h) = CardImageSize.fit(bitmap.width, bitmap.height, snapshot.ankiImageMaxHeight)
            val scaled = if (w != bitmap.width || h != bitmap.height) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
            try {
                ByteArrayOutputStream().use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)
                    output.toByteArray()
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }

        AnkiCard(
            expression = resolved.baseForm.ifBlank { word },
            reading = reading,
            meaningHtml = CardHtml.meaning(sb.toString(), CardHtml.frequencyLine(resolved).takeUnless { firstOnly }, dictStyles),
            sentence = sentence,
            sentenceHtml = CardHtml.highlightSentence(sentence, resolved.baseForm.ifBlank { word }),
            imageBytes = imageBytes,
            imageName = "dsm_${sanitizeForFilename(resolved.baseForm.ifBlank { word })}.jpg",
            audioBytes = audio?.bytes,
            audioName = audio?.let { "dsm_${sanitizeForFilename(resolved.baseForm.ifBlank { word })}.${it.extension}" },
            pitchGraphHtml = pitchGraph,
            wordAudioBytes = wordAudio?.bytes,
            wordAudioName = wordAudio?.let { "dsm_${sanitizeForFilename(expression)}_pron.${it.extension}" },
            sourceTitle = "Droid Sentence Miner (screen OCR)",
            tags = tags,
        )
    }

    /** Yomitan's duplicate tag: the looked-up word's base form already has a note. Null if unknown. */
    suspend fun isInAnki(word: String, details: TermLookup.LookupDetails?): Boolean? = withContext(Dispatchers.IO) {
        val expression = details?.baseForm?.ifBlank { word } ?: word
        (ankiClient.isDuplicate(expression) as? AnkiResult.Success)?.value
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

    /**
     * GSM's flow: mine [word] from a logged line, attaching that line's screenshot and its
     * voice clip cut from the replay buffer. [audio] overrides the clip (e.g. after editing).
     */
    suspend fun mineLine(
        line: TextLineEntity,
        word: String,
        details: TermLookup.LookupDetails? = null,
        audio: AudioSegment? = null,
        includeAudio: Boolean = true,
    ): AnkiResult<String> {
        val segment = when {
            !includeAudio -> null
            audio != null -> audio
            prefs.audioEnabled.first() -> textLog.clip(line)?.segment
            else -> null
        }
        val encoded = segment?.let {
            withContext(Dispatchers.IO) {
                M4aEncoder.encode(it, java.io.File(context.cacheDir, "line-${line.id}-${System.currentTimeMillis()}.m4a"))
            }
        }
        val image = textLog.screenshot(line)
        val result = mine(word, line.text, image, details, audio = encoded)
        if (result is AnkiResult.Success) withContext(Dispatchers.IO) { textLog.dao.markMined(line.id) }
        return result
    }

    /** Builds the HTML body shown in the popup WebView. */
    suspend fun buildPopupHtml(word: String, details: TermLookup.LookupDetails, darkMode: Boolean): String = withContext(Dispatchers.Default) {
        val dicts = db.dictionaryDao().getEnabled().associateBy { it.id }
        val tagCategories = lookup.tagCategories(details.terms.map { it.dictId }.toSet())
        val sb = StringBuilder()
        val entries = details.entries
        if (entries.size > 1) {
            sb.append(ENTRY_STYLE)
            entries.forEachIndexed { index, entry ->
                sb.append("<div class=\"dsm-entry\"><div class=\"dsm-entry-head\">")
                sb.append("<span class=\"dsm-headword\">").append(GlossaryHtml.escape(entry.expression)).append("</span>")
                if (entry.reading.isNotBlank() && entry.reading != entry.expression) {
                    sb.append("<span class=\"dsm-reading\">").append(GlossaryHtml.escape(entry.reading)).append("</span>")
                }
                sb.append("<button class=\"dsm-add\" data-index=\"").append(index).append("\">+ Anki</button></div>")
                entry.terms.first().deinflectionReasons.takeIf { it.isNotEmpty() }?.let {
                    sb.append("<div class=\"gloss-deinflection\">").append(GlossaryHtml.escape(it.joinToString(" « "))).append("</div>")
                }
                CardHtml.frequencyLine(details.forEntry(index))?.let {
                    sb.append("<div class=\"gloss-header\"><span class=\"gloss-frequency\">").append(GlossaryHtml.escape(it)).append("</span></div>")
                }
                appendTerms(sb, entry.terms, dicts, tagCategories)
                sb.append("</div>")
            }
        } else {
            appendTerms(sb, details.terms, dicts, tagCategories)
        }
        val frequencies = CardHtml.frequencyLine(details).takeIf { entries.size <= 1 }
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

    private fun appendTerms(
        sb: StringBuilder,
        terms: List<io.github.wilsonnet.droidsentenceminer.dict.TermResult>,
        dicts: Map<Long, io.github.wilsonnet.droidsentenceminer.dict.DictionaryEntity>,
        tagCategories: Map<Long, Map<String, String>>,
    ) {
        var currentDictId = -1L
        for (term in terms) {
            if (term.dictId != currentDictId) {
                currentDictId = term.dictId
                sb.append("<div class=\"gloss-dictionary\">")
                sb.append(GlossaryHtml.escape(term.dictionaryTitle))
                sb.append("</div>")
            }
            val tags = term.definitionTags.split(" ").filter { it.isNotBlank() }.take(10)
            if (tags.isNotEmpty()) {
                sb.append("<div class=\"gloss-header\">")
                val categories = tagCategories[term.dictId].orEmpty()
                tags.forEach { tag ->
                    val cat = categories[tag]?.let { " data-category=\"${GlossaryHtml.escape(it)}\"" } ?: ""
                    sb.append("<span class=\"gloss-tag\"$cat>").append(GlossaryHtml.escape(tag)).append("</span>")
                }
                sb.append("</div>")
            }
            val entries = GlossaryParser.parse(term.glossaryJson)
            sb.append(
                GlossaryHtml.render(
                    entries,
                    imageUrl = { path -> dicts[term.dictId]?.let { DictArchive.imageFile(context, it, path)?.let { f -> "file://${f.absolutePath}" } } },
                    includeImages = true,
                ),
            )
        }
    }

    private companion object {
        /** Entry headers with their add buttons (states set by the popup, see WebViewBlock). */
        const val ENTRY_STYLE = """<style>
.dsm-entry { margin-top: 10px; padding-top: 8px; border-top: 1px solid rgba(128,128,128,0.35); }
.dsm-entry:first-of-type { margin-top: 0; padding-top: 0; border-top: none; }
.dsm-entry-head { display: flex; align-items: baseline; gap: 8px; }
.dsm-headword { font-size: 1.3em; font-weight: 700; }
.dsm-reading { opacity: 0.75; }
.dsm-add { margin-left: auto; align-self: center; border: none; border-radius: 16px; padding: 5px 14px;
  font: inherit; font-size: 0.9em; background: rgba(128,128,128,0.25); color: inherit; }
.dsm-add[data-state=mining] { opacity: 0.7; }
.dsm-add[data-state=added] { background: #2E7D32; color: #fff; }
.dsm-add[data-state=in-anki] { background: transparent; opacity: 0.7; }
.dsm-add[data-state=failed] { background: #B3261E; color: #fff; }
</style>"""
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
