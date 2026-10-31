package io.github.wilsonnet.droidsentenceminer.anki

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Word pronunciation audio, fetched like Yomitan does for Japanese
 * (`getRequiredAudioSourceList('ja')` in yomitan/ext/js/media/audio-downloader.js):
 * JapanesePod101 direct link → JapanesePod101 dictionary search → Jisho, first hit wins.
 * An optional custom URL template (Yomitan's "custom" source, e.g. a local audio server)
 * is tried first.
 */
class WordAudio(private val client: OkHttpClient = defaultClient) {

    data class Clip(val bytes: ByteArray, val extension: String, val source: String)

    /**
     * The first valid clip from the sources in Yomitan's order. Gives up after [budgetMs] in total:
     * a word with no audio anywhere tries every source, and a slow site held the card for 15+ s.
     */
    suspend fun fetch(term: String, reading: String, customUrl: String? = null, budgetMs: Long = BUDGET_MS): Clip? = withContext(Dispatchers.IO) {
        val deadline = android.os.SystemClock.elapsedRealtime() + budgetMs
        val read = reading.ifBlank { term }
        val attempts = buildList<Pair<String, () -> List<String>>> {
            customUrl?.takeIf { it.isNotBlank() }?.let { template -> add("custom" to { listOf(WordAudioSources.customUrl(template, term, read)) }) }
            add("jpod101" to { listOf(WordAudioSources.jpod101Url(term, read)) })
            add("languagepod101" to { languagePod101(term, read) })
            add("jisho" to { jisho(term, read) })
        }
        for ((source, urls) in attempts) {
            if (android.os.SystemClock.elapsedRealtime() > deadline) break
            val candidates = runCatching { urls() }.getOrDefault(emptyList())
            for (url in candidates) {
                if (android.os.SystemClock.elapsedRealtime() > deadline) break
                val bytes = runCatching { download(url) }.getOrNull() ?: continue
                if (!WordAudioSources.isValid(source, bytes)) continue
                return@withContext Clip(bytes, WordAudioSources.extension(url), source)
            }
        }
        null
    }

    private fun download(url: String): ByteArray? {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val type = response.header("Content-Type").orEmpty()
            if (type.startsWith("text/")) return null
            return response.body?.bytes()
        }
    }

    private fun languagePod101(term: String, reading: String): List<String> {
        val body = FormBody.Builder()
            .add("post", "dictionary_reference")
            .add("match_type", "exact")
            .add("search_query", term)
            .add("vulgar", "true")
            .build()
        val request = Request.Builder()
            .url("https://www.japanesepod101.com/learningcenter/reference/dictionary_post")
            .post(body)
            .build()
        val html = client.newCall(request).execute().use { it.body?.string().orEmpty() }
        return WordAudioSources.parseLanguagePod101(html, term, reading)
    }

    private fun jisho(term: String, reading: String): List<String> {
        val url = "https://jisho.org/search/" + URLEncoder.encode(term, "UTF-8").replace("+", "%20")
        val html = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        return listOfNotNull(WordAudioSources.parseJisho(html, term, reading))
    }

    companion object {
        /** Total time a card waits for word audio; it is added without it after that. */
        const val BUDGET_MS = 8_000L

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                // One request can't outlast the budget by much.
                .callTimeout(5, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}

/** URL building, HTML parsing and validation for [WordAudio]. Pure Kotlin, unit tested. */
object WordAudioSources {

    /** SHA-256 of JapanesePod101's "this audio is not available" clip (same constant as Yomitan). */
    private const val JPOD101_INVALID = "ae6398b5a27bc8c0a771df6c907ade794be15518174773c58c7c7ddd17098906"

    fun jpod101Url(term: String, reading: String): String {
        // Kana-only words are looked up by reading alone, like Yomitan.
        val kanaOnly = term == reading && term.all { isKana(it) }
        val params = buildList {
            if (!kanaOnly && term.isNotEmpty()) add("kanji=" + enc(term))
            if (reading.isNotEmpty()) add("kana=" + enc(reading))
        }
        return "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?" + params.joinToString("&")
    }

    fun customUrl(template: String, term: String, reading: String): String =
        template.replace("{term}", enc(term)).replace("{reading}", enc(reading)).replace("{language}", "ja")

    /** Audio URLs of result rows whose reading matches (`dc-result-row` / `dc-vocab_kana`). */
    fun parseLanguagePod101(html: String, term: String, reading: String): List<String> {
        val urls = LinkedHashSet<String>()
        // Rows are `class="... dc-result-row"`; don't split on `dc-result-row__checkbox` etc.
        for (row in html.split(Regex("class=\"[^\"]*\\bdc-result-row(?=[\\s\"])")).drop(1)) {
            val src = Regex("<source[^>]+src=\"([^\"]+)\"").find(row)?.groupValues?.get(1) ?: continue
            val kana = Regex("dc-vocab_kana[^>]*>([^<]*)<").find(row)?.groupValues?.get(1)?.trim() ?: continue
            if (reading != term && kana != reading) continue
            urls += absolute(src, "https://www.japanesepod101.com")
        }
        return urls.toList()
    }

    /** Jisho marks audio as `<audio id="audio_{term}:{reading}"><source src=...>`. */
    fun parseJisho(html: String, term: String, reading: String): String? {
        val start = html.indexOf("id=\"audio_$term:$reading\"").takeIf { it >= 0 } ?: return null
        val src = Regex("<source[^>]+src=\"([^\"]+)\"").find(html, start)?.groupValues?.get(1) ?: return null
        return absolute(src, "https://jisho.org")
    }

    fun isValid(source: String, bytes: ByteArray): Boolean {
        if (bytes.size < 1_000) return false
        if (source == "jpod101" && sha256(bytes) == JPOD101_INVALID) return false
        return true
    }

    fun extension(url: String): String =
        url.substringBefore('?').substringAfterLast('.', "mp3").lowercase().takeIf { it in setOf("mp3", "ogg", "opus", "m4a", "wav", "aac") } ?: "mp3"

    private fun absolute(src: String, base: String): String = when {
        src.startsWith("//") -> "https:$src"
        src.startsWith("/") -> base + src
        else -> src
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun isKana(c: Char) = c.code in 0x3041..0x309F || c.code in 0x30A0..0x30FF
}
