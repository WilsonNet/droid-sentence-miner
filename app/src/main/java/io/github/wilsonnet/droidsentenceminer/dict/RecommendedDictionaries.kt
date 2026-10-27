package io.github.wilsonnet.droidsentenceminer.dict

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Downloadable Yomitan dictionaries offered as one-click installs. */
data class RecommendedDictionary(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val approximateSizeMb: Double,
    val kind: Kind = Kind.YOMITAN_ZIP,
    /** Remote Yomitan `index.json` URL for checking whether a newer revision is available. */
    val indexUrl: String? = null,
) {
    enum class Kind {
        YOMITAN_ZIP,

        /** Kanjium `accents.txt` (term, reading, downstep positions), converted to Yomitan pitch metadata. */
        KANJIUM_ACCENTS,
    }
}

/** An available update for an installed dictionary (checked via Yomitan's `indexUrl`). */
data class DictionaryUpdate(
    val dictId: Long,
    val installedTitle: String,
    val installedRevision: String,
    val latestTitle: String,
    val latestRevision: String,
    val downloadUrl: String,
)

object RecommendedDictionaries {
    val ALL = listOf(
        RecommendedDictionary(
            id = "jitendex",
            name = "Jitendex",
            description = "Japanese → English. The best free Yomitan dictionary: rich formatting and examples. Recommended.",
            url = "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip",
            approximateSizeMb = 37.0,
            indexUrl = "https://jitendex.org/static/yomitan.json",
        ),
        RecommendedDictionary(
            id = "jmdict",
            name = "JMdict English",
            description = "The classic Japanese → English dictionary generated from EDRDG's JMdict.",
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english.zip",
            approximateSizeMb = 15.6,
        ),
        RecommendedDictionary(
            id = "kanjidic",
            name = "KANJIDIC English",
            description = "Kanji readings, meanings, stroke counts and grades.",
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/KANJIDIC_english.zip",
            approximateSizeMb = 0.7,
        ),
        RecommendedDictionary(
            id = PITCH_ID,
            name = "Kanjium pitch accents",
            description = "Pitch accent for 124,000 words (CC BY-SA 4.0), drawn into the card's pitch graph " +
                "field (e.g. Anime Cards \"Graph\") like Yomitan does.",
            url = "https://raw.githubusercontent.com/mifunetoshiro/kanjium/master/data/source_files/raw/accents.txt",
            approximateSizeMb = 3.2,
            kind = RecommendedDictionary.Kind.KANJIUM_ACCENTS,
        ),
        RecommendedDictionary(
            id = "jmnedict",
            name = "JMnedict",
            description = "Japanese names dictionary: people, places, companies, and work titles from EDRDG's JMnedict.",
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.zip",
            approximateSizeMb = 10.9,
            indexUrl = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.json",
        ),
    )

    const val PITCH_ID = "kanjium"
    const val KANJIUM_TITLE = "Kanjium Pitch Accents"

    /** True when a dictionary contains proper names (people, places, works) rather than standard vocabulary. */
    fun isNameDictionary(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("jmnedict") || t.contains("name dictionary") || t.contains("japanese names")
    }
}

/** Streams dictionary downloads to disk with progress reporting. */
class DictDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun download(
        url: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "DroidSentenceMiner").build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw YomitanImporter.ImportException("Download failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw YomitanImporter.ImportException("Empty download")
                val total = body.contentLength().takeIf { it > 0 }
                var downloaded = 0L
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            destination
        } catch (e: CancellationException) {
            destination.delete()
            throw e
        } catch (e: YomitanImporter.ImportException) {
            destination.delete()
            throw e
        } catch (e: Exception) {
            destination.delete()
            throw YomitanImporter.ImportException("Download failed: ${e.message}", e)
        }
    }

    /** Fetches a remote Yomitan `index.json` (e.g. `https://jitendex.org/static/yomitan.json`). */
    suspend fun fetchIndex(url: String): DictionaryIndex? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "DroidSentenceMiner").build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string() ?: return@use null
                YomitanIndexParser.parse(text)
            }
        }.getOrNull()
    }

    /**
     * Checks installed dictionaries for updates using either their `index.json` `indexUrl`
     * (Yomitan's update protocol) or the known `indexUrl` on [RecommendedDictionaries].
     */
    suspend fun checkUpdates(dictionaries: List<DictionaryEntity>): Map<Long, DictionaryUpdate> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<Long, DictionaryUpdate>()
        for (dict in dictionaries) {
            val localIndex = DictArchive.index(dict)
            val matchedRec = RecommendedDictionaries.ALL.firstOrNull {
                it.indexUrl != null && dict.title.contains(it.name, ignoreCase = true)
            }
            val indexUrl = localIndex?.indexUrl ?: matchedRec?.indexUrl ?: continue
            val remote = fetchIndex(indexUrl) ?: continue
            val downloadUrl = remote.downloadUrl ?: localIndex?.downloadUrl ?: matchedRec?.url ?: continue
            if (isNewerRevision(dict.revision, remote.revision)) {
                result[dict.id] = DictionaryUpdate(
                    dictId = dict.id,
                    installedTitle = dict.title,
                    installedRevision = dict.revision,
                    latestTitle = remote.title,
                    latestRevision = remote.revision,
                    downloadUrl = downloadUrl,
                )
            }
        }
        result
    }

    companion object {
        /**
         * True when [remote] is newer than [installed]. Compares dot/dash-separated numeric
         * segments when both revisions are numeric (e.g. `2026.10.03.0` vs `2026.10.25.0`),
         * falling back to string inequality so any changed revision is offered as an update.
         */
        fun isNewerRevision(installed: String, remote: String): Boolean {
            val a = installed.trim()
            val b = remote.trim()
            if (a.isEmpty() || b.isEmpty() || a == b) return false
            val partsA = a.split('.', '-', '_').map { it.toLongOrNull() }
            val partsB = b.split('.', '-', '_').map { it.toLongOrNull() }
            if (partsA.all { it != null } && partsB.all { it != null }) {
                val max = maxOf(partsA.size, partsB.size)
                for (i in 0 until max) {
                    val va = partsA.getOrNull(i) ?: 0L
                    val vb = partsB.getOrNull(i) ?: 0L
                    if (vb != va) return vb > va
                }
                return false
            }
            return true
        }
    }
}
