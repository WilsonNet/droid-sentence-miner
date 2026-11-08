package io.github.wilsonnet.droidsentenceminer.anki

import android.util.Log
import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.Languages
import io.github.wilsonnet.droidsentenceminer.lang.WordAudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
    suspend fun fetch(term: String, reading: String, customUrl: String? = null, budgetMs: Long = BUDGET_MS): Clip? =
        withContext(Dispatchers.IO) {
            val deadline = android.os.SystemClock.elapsedRealtime() + budgetMs
            val read = reading.ifBlank { term }
            val attempts =
                buildList<Pair<String, () -> List<String>>> {
                    customUrl?.takeIf { it.isNotBlank() }?.let { template -> add("custom" to { listOf(WordAudioSources.customUrl(template, term, read)) }) }
                    // Only the study language's own sources (LanguageModule.wordAudioSources).
                    val language = Languages.current
                    language.wordAudioSources.forEach { source ->
                        add(source.id to { urls(source = source, language = language, term = term, read = read) })
                    }
                }
            for ((source, urls) in attempts) {
                if (android.os.SystemClock.elapsedRealtime() > deadline) break
                val candidates =
                    runCatching { urls() }
                        .onFailure { Log.w(TAG, "word audio source $source failed: $it") }
                        .getOrDefault(emptyList())
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
        val request = Request.Builder().url(url)
        // Wikimedia answers 403 to clients that don't identify themselves (okhttp's default agent).
        if (url.contains("wikimedia.org")) request.header("User-Agent", COMMONS_UA)
        client.newCall(request.build()).execute().use { response ->
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

    private fun urls(source: WordAudioSource, language: LanguageModule, term: String, read: String): List<String> {
        val prefix = language.linguaLibrePrefix
        return when (source) {
            WordAudioSource.JAPANESE_POD_101 -> listOf(WordAudioSources.jpod101Url(term, read))
            WordAudioSource.JAPANESE_POD_101_SEARCH -> languagePod101(term, read)
            WordAudioSource.JISHO -> jisho(term, read)
            WordAudioSource.WIKTIONARY -> wiktionary(language.code, term)
            WordAudioSource.LINGUA_LIBRE -> if (prefix == null) emptyList() else linguaLibre(prefix, term)
        }
    }

    // Commons files named `En-us-word.ogg`, `En-uk-word.ogg`… (Yomitan's wiktionary source), US first.
    private fun wiktionary(language: String, term: String): List<String> =
        commonsAudio(WordAudioSources.commonsSearchUrl(language, term)) { search ->
            WordAudioSources.parseCommonsTitles(json = search, language = language, term = term).take(WIKTIONARY_FILES)
        }

    // Lingua Libre recordings, `File:LL-Q1860 (eng)-<speaker>-<word>.wav`.
    private fun linguaLibre(prefix: String, term: String): List<String> =
        commonsAudio(WordAudioSources.linguaLibreSearchUrl(prefix, term)) { search ->
            WordAudioSources.parseLinguaLibreTitles(json = search, prefix = prefix, term = term).take(LINGUA_LIBRE_FILES)
        }

    // Commons search → file titles picked by [titles] → their download URLs (empty when anything is missing).
    private fun commonsAudio(searchUrl: String, titles: (String) -> List<String>): List<String> {
        val picked = commonsJson(searchUrl)?.let(titles).orEmpty()
        val info = picked.takeIf { it.isNotEmpty() }?.let { commonsJson(WordAudioSources.commonsInfoUrl(it)) }
        return info?.let { WordAudioSources.parseCommonsUrls(it, picked) }.orEmpty()
    }

    private fun commonsJson(url: String): String? =
        client.newCall(Request.Builder().url(url).header("User-Agent", COMMONS_UA).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }

    private fun jisho(term: String, reading: String): List<String> {
        val url = "https://jisho.org/search/" + URLEncoder.encode(term, "UTF-8").replace("+", "%20")
        val html = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        return listOfNotNull(WordAudioSources.parseJisho(html, term, reading))
    }

    companion object {
        private const val TAG = "DSM-WordAudio"
        private const val WIKTIONARY_FILES = 4
        private const val LINGUA_LIBRE_FILES = 3

        /** Wikimedia asks API clients to identify themselves. */
        private const val COMMONS_UA = "DroidSentenceMiner/1.0 (https://github.com/WilsonNet/droid-sentence-miner)"

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
    private const val UTF_8 = "UTF-8"
    private const val COMMONS_SEARCH = "https://commons.wikimedia.org/w/api.php?action=query&format=json&list=search&srnamespace=6"
    private val REGION_ORDER = listOf("-us-", "-uk-")
    private val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "opus", "m4a", "wav", "aac", "flac")

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

    /** Commons full-text search for `<Lang>(-xx)?-<term>N.ogg` file names (Yomitan's query). */
    fun commonsSearchUrl(language: String, term: String): String {
        val prefix = language.replaceFirstChar { it.uppercaseChar() }
        val pattern = "intitle:/$prefix(-[a-z]{2})?-${escapeRegex(term)}[0-9]*[.]ogg/i"
        return "$COMMONS_SEARCH&srlimit=10&srsearch=" + URLEncoder.encode(pattern, UTF_8)
    }

    private fun escapeRegex(text: String): String =
        buildString {
            text.forEach { c ->
                if (!c.isLetterOrDigit() && c != ' ') append('\\')
                append(c)
            }
        }

    /**
     * File titles from a Commons search that really are [term] in [language] (the search is fuzzy),
     * US recordings first, then UK, then the rest.
     */
    fun parseCommonsTitles(json: String, language: String, term: String): List<String> {
        val exact =
            Regex(
                "File:${Regex.escape(language)}(-[a-z]{2})?-${Regex.escape(term)}[0-9]*[.]ogg",
                RegexOption.IGNORE_CASE,
            )
        return searchTitles(json).filter { exact.matches(it) }.sortedBy { regionRank(it) }
    }

    private fun regionRank(title: String): Int {
        val region = REGION_ORDER.indexOfFirst { title.contains(it, ignoreCase = true) }
        return if (region < 0) REGION_ORDER.size else region
    }

    /** Commons search for Lingua Libre files of [term] by any speaker ([prefix] = `LL-Q1860 (eng)`). */
    fun linguaLibreSearchUrl(prefix: String, term: String): String {
        val query = URLEncoder.encode("""intitle:"$prefix" intitle:"$term"""", UTF_8)
        return "$COMMONS_SEARCH&srlimit=20&srsearch=$query"
    }

    /** Search hits that are exactly [term] by any Lingua Libre speaker. */
    fun parseLinguaLibreTitles(json: String, prefix: String, term: String): List<String> {
        val exact =
            Regex(
                "File:${Regex.escape(prefix)}-[^-]+-${Regex.escape(term)}[.](wav|ogg|flac)",
                RegexOption.IGNORE_CASE,
            )
        return searchTitles(json).filter { exact.matches(it) }
    }

    private fun searchTitles(json: String): List<String> {
        val hits = queryObject(json)?.get("search") as? JsonArray
        return hits.orEmpty().mapNotNull { ((it as? JsonObject)?.get("title") as? JsonPrimitive)?.content }
    }

    private fun queryObject(json: String): JsonObject? =
        runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()?.get("query") as? JsonObject

    /** Commons `imageinfo` request for the download URLs of [titles]. */
    fun commonsInfoUrl(titles: List<String>): String =
        "https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url&titles=" +
            URLEncoder.encode(titles.joinToString("|"), UTF_8)

    /** Download URLs from a Commons imageinfo answer, in [titles] order. */
    fun parseCommonsUrls(json: String, titles: List<String>): List<String> {
        val pages = queryObject(json)?.get("pages") as? JsonObject
        val byTitle =
            pages.orEmpty().values.mapNotNull { page ->
                val o = page as? JsonObject
                val info = (o?.get("imageinfo") as? JsonArray)?.firstOrNull() as? JsonObject
                val url = (info?.get("url") as? JsonPrimitive)?.run { content.substringBefore('?') }
                val title = (o?.get("title") as? JsonPrimitive)?.content
                if (url == null || title == null) null else title to url
            }.toMap()
        return titles.mapNotNull { byTitle[it] }
    }

    /** File extension of an audio URL, `mp3` when unknown. */
    fun extension(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "mp3").lowercase()
        return if (ext in AUDIO_EXTENSIONS) ext else "mp3"
    }

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
