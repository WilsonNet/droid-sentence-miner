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
)

object RecommendedDictionaries {
    val ALL = listOf(
        RecommendedDictionary(
            id = "jitendex",
            name = "Jitendex",
            description = "Japanese → English. The best free Yomitan dictionary: rich formatting, examples, pitch accent. Recommended.",
            url = "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip",
            approximateSizeMb = 37.0,
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
    )
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
}
