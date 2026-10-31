package io.github.wilsonnet.droidsentenceminer.anki

import android.content.Context
import android.graphics.Bitmap
import io.github.wilsonnet.droidsentenceminer.audio.AudioSegment
import io.github.wilsonnet.droidsentenceminer.audio.M4aEncoder
import io.github.wilsonnet.droidsentenceminer.core.DsmPrefs
import io.github.wilsonnet.droidsentenceminer.core.GlossaryMode
import io.github.wilsonnet.droidsentenceminer.dict.DictArchive
import io.github.wilsonnet.droidsentenceminer.dict.DictDatabase
import io.github.wilsonnet.droidsentenceminer.dict.DictMedia
import io.github.wilsonnet.droidsentenceminer.dict.GlossaryHtml
import io.github.wilsonnet.droidsentenceminer.dict.GlossaryParser
import io.github.wilsonnet.droidsentenceminer.dict.PitchAccent
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup
import io.github.wilsonnet.droidsentenceminer.dict.TermResult
import io.github.wilsonnet.droidsentenceminer.textlog.TextLineEntity
import io.github.wilsonnet.droidsentenceminer.textlog.TextLog
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        /** Word audio already being fetched (see [mineLine]); null: fetched here, alongside the glossary. */
        wordAudioFetch: Deferred<WordAudio.Clip?>? = null,
    ): AnkiCard = withContext(Dispatchers.Default) {
        val snapshot = prefs.snapshot()
        val resolved = details ?: lookup.lookup(word)
        val wordAudioJob = wordAudioFetch ?: async { fetchWordAudio(word, resolved) }
        val dicts = db.dictionaryDao().getEnabled().associateBy { it.id }

        val sb = StringBuilder()
        var currentDictId = -1L
        val mode = prefs.glossaryMode.first()
        val terms = cardTerms(resolved.terms, mode)
        val tagCategories = lookup.tagCategories(terms.map { it.dictId }.toSet())
        // Glossary images become Anki media; the card's <img src> is the media file name.
        val glossaryMedia = LinkedHashMap<String, GlossaryMedia>()
        var mediaBytes = 0L
        for (term in terms) {
            if (mode == GlossaryMode.ALL && term.dictId != currentDictId) {
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
            val dict = dicts[term.dictId]
            GlossaryHtml.renderTo(
                sb,
                entries,
                imageUrl = imageUrl@{ path ->
                    dict ?: return@imageUrl null
                    val media = DictMedia.file(context, dict, path) ?: return@imageUrl null
                    val name = DictMedia.ankiName(dict, path, media)
                    if (name !in glossaryMedia) {
                        val size = media.file.length()
                        if (glossaryMedia.size >= MAX_GLOSSARY_MEDIA || mediaBytes + size > MAX_GLOSSARY_MEDIA_BYTES) return@imageUrl null
                        val bytes = runCatching { media.file.readBytes() }.getOrNull() ?: return@imageUrl null
                        glossaryMedia[name] = GlossaryMedia(name, bytes, media.mimeType)
                        mediaBytes += bytes.size
                    }
                    name
                },
                inlineImageStyles = true,
                dictionary = term.dictionaryTitle,
            )
        }
        val dictStyles = terms.mapNotNull { dicts[it.dictId] }.distinctBy { it.id }
            .mapNotNull { DictArchive.styles(context, it)?.takeIf { s -> s.isNotBlank() } }
            .joinToString("\n")
            .take(20_000)
            .ifBlank { null }

        val reading = resolved.reading.orEmpty()
        val expression = resolved.baseForm.ifBlank { word }
        val pitchGraph = pitchGraphs(resolved, word)
        val tAudio = android.os.SystemClock.elapsedRealtime()
        val wordAudio = wordAudioJob.await()
        android.util.Log.i(TAG, "「$expression」: waited ${android.os.SystemClock.elapsedRealtime() - tAudio} ms for word audio (${wordAudio?.source})")
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
            meaningHtml = CardHtml.meaning(sb.toString(), CardHtml.frequencyLine(resolved).takeUnless { mode == GlossaryMode.FIRST }, dictStyles),
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
            glossaryMedia = glossaryMedia.values.toList(),
        )
    }

    /** The word's pronunciation for the card (null when turned off or not found in time). */
    private suspend fun fetchWordAudio(word: String, details: TermLookup.LookupDetails): WordAudio.Clip? {
        if (!prefs.wordAudio.first()) return null
        val t0 = android.os.SystemClock.elapsedRealtime()
        val expression = details.baseForm.ifBlank { word }
        return wordAudioFetcher.fetch(expression, details.reading.orEmpty(), prefs.wordAudioCustomUrl.first()).also {
            android.util.Log.i(TAG, "「$expression」: word audio fetch ${android.os.SystemClock.elapsedRealtime() - t0} ms (${it?.source})")
        }
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
        wordAudioFetch: Deferred<WordAudio.Clip?>? = null,
    ): AnkiResult<String> = withContext(Dispatchers.IO) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val card = buildCard(word, sentence, image, details, audio, wordAudioFetch)
        val snapshot = prefs.snapshot()
        val t1 = android.os.SystemClock.elapsedRealtime()

        val result = if (addToAnki) {
            ankiClient.addNote(card)
        } else {
            AnkiResult.Success("saved")
        }
        android.util.Log.i(TAG, "「$word」: build card ${t1 - t0} ms, add to Anki ${android.os.SystemClock.elapsedRealtime() - t1} ms → $result")

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
     * voice clip cut from the replay buffer. [audio] overrides the clip (e.g. after editing);
     * [edits] from the card editor override both the sentence and the clip.
     */
    suspend fun mineLine(
        line: TextLineEntity,
        word: String,
        details: TermLookup.LookupDetails? = null,
        audio: AudioSegment? = null,
        includeAudio: Boolean = true,
        edits: CardEdits? = null,
    ): AnkiResult<String> = coroutineScope {
        val t0 = android.os.SystemClock.elapsedRealtime()
        // Word audio is network-bound and the clip encode CPU-bound: start the fetch first so the
        // two overlap (encoding a few seconds of audio took 1.6 s with Nightshade running).
        val resolved = details ?: lookup.lookup(word)
        val wordAudioFetch = async(Dispatchers.IO) { fetchWordAudio(word, resolved) }
        val segment = when {
            edits != null -> edits.audio
            !includeAudio -> null
            audio != null -> audio
            prefs.audioEnabled.first() -> textLog.clip(line)?.segment
            else -> null
        }
        if (!staleClipsSwept) {
            staleClipsSwept = true
            withContext(Dispatchers.IO) {
                context.cacheDir.listFiles { f -> f.name.startsWith("line-") && (f.name.endsWith(".m4a") || f.name.endsWith(".wav")) }
                    ?.forEach { it.delete() }
            }
        }
        val encoded = segment?.let {
            withContext(Dispatchers.IO) {
                val file = java.io.File(context.cacheDir, "line-${line.id}-${System.currentTimeMillis()}.m4a")
                try {
                    M4aEncoder.encode(it, file)
                } finally {
                    // The card carries the bytes; the files were left behind, one per mined card.
                    file.delete()
                    java.io.File(file.parentFile, file.nameWithoutExtension + ".wav").delete()
                }
            }
        }
        val t1 = android.os.SystemClock.elapsedRealtime()
        val image = textLog.screenshot(line)
        val t2 = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i(TAG, "line ${line.id}: clip+encode ${t1 - t0} ms (${segment?.durationMs ?: 0} ms audio), screenshot ${t2 - t1} ms")
        val result = mine(word, edits?.sentence ?: line.text, image, resolved, audio = encoded, wordAudioFetch = wordAudioFetch)
        if (result is AnkiResult.Success) withContext(Dispatchers.IO) { textLog.dao.markMined(line.id) }
        result
    }

    /** Clip files earlier versions left in the cache are deleted on the first card mined. */
    @Volatile private var staleClipsSwept = false

    /** The line's sentence audio as [mineLine] would attach it, for the card editor to trim. */
    suspend fun lineAudio(line: TextLineEntity): AudioSegment? =
        if (prefs.audioEnabled.first()) textLog.clip(line)?.segment else null

    /**
     * Builds the HTML body shown in the popup WebView. Each dictionary header says whether that
     * dictionary goes on the card ([GlossaryMode.FIRST] keeps only an entry's first one).
     */
    suspend fun buildPopupHtml(word: String, details: TermLookup.LookupDetails, darkMode: Boolean): String = withContext(Dispatchers.Default) {
        val dicts = db.dictionaryDao().getEnabled().associateBy { it.id }
        val tagCategories = lookup.tagCategories(details.terms.map { it.dictId }.toSet())
        val mode = prefs.glossaryMode.first()
        val sb = StringBuilder()
        val entries = details.entries
        if (entries.size > 1) {
            entries.forEachIndexed { index, entry ->
                sb.append("<div class=\"dsm-entry\"><div class=\"dsm-entry-head\">")
                sb.append("<span class=\"dsm-headword\">").append(GlossaryHtml.escape(entry.expression)).append("</span>")
                if (entry.reading.isNotBlank() && entry.reading != entry.expression) {
                    sb.append("<span class=\"dsm-reading\">").append(GlossaryHtml.escape(entry.reading)).append("</span>")
                }
                sb.append("<span class=\"dsm-actions\"><button class=\"dsm-add\" data-index=\"").append(index).append("\">+ Anki</button>")
                sb.append("<button class=\"dsm-edit\" data-index=\"").append(index).append("\" aria-label=\"Edit card\">").append(EDIT_ICON).append("</button></span></div>")
                entry.terms.first().deinflectionReasons.takeIf { it.isNotEmpty() }?.let {
                    sb.append("<div class=\"gloss-deinflection\">").append(GlossaryHtml.escape(it.joinToString(" « "))).append("</div>")
                }
                pitchGraphs(details.forEntry(index)).takeIf { it.isNotEmpty() }?.let {
                    sb.append("<div class=\"dsm-pitch\">").append(it).append("</div>")
                }
                CardHtml.frequencyLine(details.forEntry(index))?.let {
                    sb.append("<div class=\"gloss-header\"><span class=\"gloss-frequency\">").append(GlossaryHtml.escape(it)).append("</span></div>")
                }
                appendTerms(sb, entry.terms, dicts, tagCategories, cardDicts(entry.terms, mode))
                sb.append("</div>")
            }
        } else {
            appendTerms(sb, details.terms, dicts, tagCategories, cardDicts(details.terms, mode))
        }
        val frequencies = CardHtml.frequencyLine(details).takeIf { entries.size <= 1 }
        val freqHtml = if (frequencies != null) {
            "<div class=\"gloss-header\"><span class=\"gloss-frequency\">${GlossaryHtml.escape(frequencies)}</span></div>"
        } else {
            ""
        } + (pitchGraphs(details).takeIf { entries.size <= 1 && it.isNotEmpty() }?.let { "<div class=\"dsm-pitch\">$it</div>" } ?: "")
        val dictStyles = details.terms.mapNotNull { dicts[it.dictId] }.distinctBy { it.id }
            .mapNotNull { DictArchive.styles(context, it)?.takeIf { s -> s.isNotBlank() } }
            .joinToString("\n")
            .take(20_000)
        GlossaryHtml.wrapDocument(freqHtml + sb.toString(), styles = POPUP_CSS + "\n" + dictStyles, darkMode = darkMode)
    }

    /** Yomitan's pitch-accent graphs for the looked-up word: the card's pitch field and the popup. */
    private fun pitchGraphs(details: TermLookup.LookupDetails, word: String = details.baseForm): String {
        val expression = details.baseForm.ifBlank { word }
        val reading = details.reading.orEmpty().ifBlank { expression }
        return PitchAccent.ankiGraphs(
            reading,
            details.frequencies.filter { it.mode == "pitch" && it.expression == expression }.mapNotNull { PitchAccent.parse(it.dataJson) },
        )
    }

    private fun appendTerms(
        sb: StringBuilder,
        terms: List<TermResult>,
        dicts: Map<Long, io.github.wilsonnet.droidsentenceminer.dict.DictionaryEntity>,
        tagCategories: Map<Long, Map<String, String>>,
        onCard: Set<Long>,
    ) {
        var currentDictId = -1L
        for (term in terms) {
            if (term.dictId != currentDictId) {
                currentDictId = term.dictId
                if (term.dictId in onCard) {
                    sb.append("<div class=\"gloss-dictionary\" data-card=\"1\">")
                    sb.append(GlossaryHtml.escape(term.dictionaryTitle))
                    sb.append("<span class=\"dsm-on-card\">on card</span></div>")
                } else {
                    sb.append("<div class=\"gloss-dictionary\" data-card=\"0\">")
                    sb.append(GlossaryHtml.escape(term.dictionaryTitle))
                    sb.append("</div>")
                }
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
            val dict = dicts[term.dictId]
            GlossaryHtml.renderTo(
                sb,
                entries,
                // Served lazily by the WebView (DictMedia.intercept): no zip reads while building the page.
                imageUrl = { path -> dict?.let { DictMedia.url(it, path) } },
                includeImages = true,
                dictionary = term.dictionaryTitle,
            )
        }
    }

    companion object {
        /** Stage timings of every mined card (clip, card build, word audio, Anki add). */
        private const val TAG = "DSM-Mine"

        /** The terms a card built from [terms] holds under [mode]. */
        fun cardTerms(terms: List<TermResult>, mode: GlossaryMode): List<TermResult> =
            if (mode == GlossaryMode.FIRST) terms.filter { it.dictId == terms.firstOrNull()?.dictId } else terms

        fun cardDicts(terms: List<TermResult>, mode: GlossaryMode): Set<Long> = cardTerms(terms, mode).mapTo(HashSet()) { it.dictId }

        /** Per card: enough for picture dictionaries, bounded so one huge entry can't make a 50 MB note. */
        const val MAX_GLOSSARY_MEDIA = 30
        const val MAX_GLOSSARY_MEDIA_BYTES = 12L * 1024 * 1024

        /**
         * Entry headers with their add/edit buttons (states set by the popup, see WebViewBlock), and
         * the "on card" mark on dictionary headers.
         */
        /** Material's "edit" icon, as on the popup's own edit button. */
        private const val EDIT_ICON = "<svg viewBox=\"0 0 24 24\" width=\"1.1em\" height=\"1.1em\" fill=\"currentColor\">" +
            "<path d=\"M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z\"/></svg>"

        private const val POPUP_CSS = """
.dsm-entry { margin-top: 10px; padding-top: 8px; border-top: 1px solid rgba(128,128,128,0.35); }
.dsm-entry:first-of-type { margin-top: 0; padding-top: 0; border-top: none; }
.dsm-entry-head { display: flex; align-items: baseline; gap: 8px; }
.dsm-headword { font-size: 1.3em; font-weight: 700; }
.dsm-reading { opacity: 0.75; }
.dsm-actions { margin-left: auto; align-self: center; display: flex; gap: 2px; flex-shrink: 0; }
.dsm-add, .dsm-edit { border: none; padding: 5px 14px; font: inherit; font-size: 0.9em;
  background: rgba(128,128,128,0.25); color: inherit; }
.dsm-add { border-radius: 16px 4px 4px 16px; }
.dsm-edit { border-radius: 4px 16px 16px 4px; padding: 5px 11px; display: flex; align-items: center; }
.dsm-add[data-state=added] + .dsm-edit, .dsm-add[data-state=in-anki] + .dsm-edit, .dsm-add[data-state=mining] + .dsm-edit { display: none; }
.dsm-add[data-state=added], .dsm-add[data-state=in-anki], .dsm-add[data-state=mining] { border-radius: 16px; }
.dsm-add[data-state=mining] { opacity: 0.7; }
.dsm-add[data-state=added] { background: #2E7D32; color: #fff; }
.dsm-add[data-state=in-anki] { background: transparent; opacity: 0.7; }
.dsm-add[data-state=failed] { background: #B3261E; color: #fff; }
.dsm-pitch { margin: 2px 0 4px; font-size: 1.1em; }
.dsm-pitch svg { margin-right: 6px; }
.gloss-dictionary { display: flex; align-items: center; gap: 8px; }
.gloss-dictionary[data-card="0"] { opacity: 0.6; }
.dsm-on-card { font-size: 0.75em; font-weight: 600; padding: 1px 8px; border-radius: 10px;
  background: rgba(128,128,128,0.18); color: var(--accent); }
"""
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
