package io.github.wilsonnet.droidsentenceminer.anki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder

/**
 * The word-audio files a page of the study language's own Wiktionary embeds
 * ([io.github.wilsonnet.droidsentenceminer.lang.WordAudioSource.WIKTIONARY_PAGE]): the French
 * Wiktionary's "chat" page lists `Fr-chat.ogg` and dozens of `LL-Q150 (fra)-<speaker>-chat.wav`
 * files, while a Commons search for them ranks phrases first. Pure Kotlin, unit tested.
 */
object WiktionaryPageAudio {
    private const val RANK_PLAIN = 0
    private const val RANK_LINGUA_LIBRE = 1
    private const val RANK_HYPHENATED_SPEAKER = 2
    private const val RANK_SUFFIXED = 3
    private val AUDIO = Regex("""[.](ogg|oga|wav|flac|mp3|opus)$""", RegexOption.IGNORE_CASE)

    /** MediaWiki API request for the files on [term]'s page of the [language] Wiktionary. */
    fun pageUrl(language: String, term: String): String =
        "https://$language.wiktionary.org/w/api.php?action=query&format=json&prop=images&imlimit=max&redirects=1&titles=" +
            URLEncoder.encode(term.replace(oldChar = '’', newChar = '\''), "UTF-8").replace(oldValue = "+", newValue = "%20")

    /**
     * Commons titles (`File:…`) of the page's recordings of exactly [term], best first: the
     * language's own `Fr-chat.ogg`, then Lingua Libre files, then speaker names with a hyphen (whose
     * split is ambiguous: `X-homme-grenouille`), then regional variants (`Fr-manger-fr-FR-Paris.ogg`).
     */
    fun parseTitles(json: String, language: String, linguaLibrePrefix: String?, term: String): List<String> {
        val prefix = language.replaceFirstChar { it.uppercaseChar() }
        val word = Regex.escape(term.replace(oldChar = '’', newChar = '\''))
        val plain = Regex("""${Regex.escape(prefix)}-$word(-[0-9]+)?""", RegexOption.IGNORE_CASE)
        val suffixed = Regex("""${Regex.escape(prefix)}-$word-.+""", RegexOption.IGNORE_CASE)
        val lingua = linguaLibrePrefix?.let { Regex("""${Regex.escape(it)}-([^-]+)-$word""", RegexOption.IGNORE_CASE) }
        val hyphenated = linguaLibrePrefix?.let { Regex("""${Regex.escape(it)}-(.+)-$word""", RegexOption.IGNORE_CASE) }
        val ranked =
            fileNames(json).asSequence().mapNotNull { name ->
                val stem = AUDIO.replace(input = name, replacement = "").replace(oldChar = '’', newChar = '\'')
                val rank =
                    when {
                        !AUDIO.containsMatchIn(name) -> null
                        plain.matches(stem) -> RANK_PLAIN
                        lingua?.matches(stem) == true -> RANK_LINGUA_LIBRE
                        hyphenated?.matches(stem) == true -> RANK_HYPHENATED_SPEAKER
                        suffixed.matches(stem) -> RANK_SUFFIXED
                        else -> null
                    }
                rank?.let { "File:$name" to it }
            }
        val best = ranked.sortedBy { it.second }.map { it.first }
        return best.distinct().toList()
    }

    // File names without their (localized: "Fichier:", "Datei:") namespace.
    private fun fileNames(json: String): List<String> {
        val query = runCatching { Json.parseToJsonElement(json).jsonObject["query"] as? JsonObject }.getOrNull()
        val pages = (query?.get("pages") as? JsonObject)?.values.orEmpty()
        val images = pages.flatMap { page -> ((page as? JsonObject)?.get("images") as? JsonArray).orEmpty() }
        return images.mapNotNull { image ->
            val title = ((image as? JsonObject)?.get("title") as? JsonPrimitive)?.content
            title?.substringAfter(':')
        }
    }
}
