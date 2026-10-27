package io.github.wilsonnet.droidsentenceminer.dict

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * Imports Yomitan/Yomichan dictionaries (format 3 and legacy) into the local database.
 *
 * Supported banks:
 *  - index.json
 *  - term_bank_N.json
 *  - kanji_bank_N.json
 *  - tag_bank_N.json
 *  - term_meta_bank_N.json (frequency, pitch, ...)
 *  - kanji_meta_bank_N.json
 */
class YomitanImporter(
    private val context: Context,
    private val db: DictDatabase,
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Progress(
        val title: String?,
        val stage: String,
        val current: Long = 0,
        val total: Long? = null,
        val done: Boolean = false,
    )

    class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    suspend fun import(uri: Uri, onProgress: (Progress) -> Unit = {}): DictionaryEntity = withContext(Dispatchers.IO) {
        val tempZip = newTempZip()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempZip.outputStream().use { output -> input.copyTo(output) }
            } ?: throw ImportException("Could not open the selected file")
        } catch (e: Exception) {
            tempZip.delete()
            throw if (e is ImportException) e else ImportException("Could not read the selected file", e)
        }
        importZip(tempZip, onProgress)
    }

    /** Imports a dictionary zip already on disk (used by the recommended-dictionary downloader). */
    /** Installs a recommended dictionary file of either supported kind. */
    suspend fun importRecommended(
        dictionary: RecommendedDictionary,
        file: File,
        onProgress: (Progress) -> Unit = {},
    ): DictionaryEntity = when (dictionary.kind) {
        RecommendedDictionary.Kind.YOMITAN_ZIP -> import(file, onProgress)
        RecommendedDictionary.Kind.KANJIUM_ACCENTS -> importKanjiumAccents(file, onProgress)
    }

    /**
     * Kanjium's `accents.txt` becomes an ordinary pitch dictionary: one Yomitan-format `term_meta`
     * "pitch" row per word, so lookups and card graphs treat it like any imported pitch dictionary.
     */
    suspend fun importKanjiumAccents(file: File, onProgress: (Progress) -> Unit = {}): DictionaryEntity = withContext(Dispatchers.IO) {
        val title = RecommendedDictionaries.KANJIUM_TITLE
        val old = db.dictionaryDao().findByTitle(title)
        val preservedPriority = old?.priority
        if (old != null) {
            db.metaDao().deleteTermMetaByDict(old.id)
            db.dictionaryDao().delete(old.id)
        }
        val priority = preservedPriority ?: determineInitialPriority(title)
        val dictId = db.dictionaryDao().insert(
            DictionaryEntity(
                title = title,
                revision = "kanjium-master",
                author = "Uros O. (mifunetoshiro/kanjium)",
                description = "Pitch accent mora positions from the Kanjium database.",
                attribution = "The pitch accent notation, verb particle data, phonetics, homonyms and other additions " +
                    "or modifications to EDICT, KANJIDIC or KRADFILE were provided by Uros O. through his free " +
                    "database. CC BY-SA 4.0.",
                sourceLanguage = "ja",
                priority = priority,
            ),
        )
        try {
            val batch = ArrayList<TermMetaEntity>(2000)
            var count = 0L
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val (term, data) = PitchAccent.fromKanjiumLine(line) ?: continue
                    batch += TermMetaEntity(dictId = dictId, expression = term, mode = "pitch", dataJson = data)
                    if (batch.size >= 2000) {
                        db.metaDao().insertTermMeta(batch)
                        count += batch.size
                        batch.clear()
                        onProgress(Progress(title, "Importing pitch accents", count))
                    }
                }
            }
            db.metaDao().insertTermMeta(batch)
            count += batch.size
            db.dictionaryDao().updateCounts(dictId, count, 0)
            onProgress(Progress(title, "Done", count, count, done = true))
            db.dictionaryDao().getAll().first { it.id == dictId }
        } catch (e: Throwable) {
            db.metaDao().deleteTermMetaByDict(dictId)
            db.dictionaryDao().delete(dictId)
            throw e
        }
    }

    suspend fun import(file: File, onProgress: (Progress) -> Unit = {}): DictionaryEntity = withContext(Dispatchers.IO) {
        val tempZip = newTempZip()
        try {
            file.copyTo(tempZip, overwrite = true)
        } catch (e: Exception) {
            tempZip.delete()
            throw ImportException("Could not copy the dictionary file", e)
        }
        importZip(tempZip, onProgress)
    }

    private fun newTempZip(): File {
        val dir = File(context.filesDir, "dicts").apply { mkdirs() }
        return File(dir, "import-${System.currentTimeMillis()}.zip")
    }

    private suspend fun importZip(tempZip: File, onProgress: (Progress) -> Unit): DictionaryEntity = withContext(Dispatchers.IO) {
        try {
            ZipFile(tempZip).use { zip ->
                val indexJson = zip.readEntryText("index.json")
                    ?: throw ImportException("Not a Yomitan dictionary: index.json is missing")
                val index = parseIndex(indexJson)
                val styles = zip.readEntryText("styles.css")?.takeIf { it.isNotBlank() }

                // Replace a dictionary with the same title (Yomitan semantics), or an earlier
                // dated revision of the same dictionary family (e.g. "Jitendex.org [2026-09-01]"
                // upgraded to "Jitendex.org [2026-10-03]"), preserving its priority and enabled state.
                val existing = findExistingForImport(index.title)
                val preservedPriority = existing?.priority
                val enabled = existing?.enabled ?: true
                if (existing != null) {
                    db.termDao().deleteByDict(existing.id)
                    db.kanjiDao().deleteByDict(existing.id)
                    db.tagDao().deleteByDict(existing.id)
                    db.metaDao().deleteTermMetaByDict(existing.id)
                    db.metaDao().deleteKanjiMetaByDict(existing.id)
                    db.dictionaryDao().delete(existing.id)
                    existing.zipPath?.let { runCatching { File(it).delete() } }
                    DictArchive.invalidate(existing.id)
                    runCatching { File(context.cacheDir, "dict-images/${existing.id}").deleteRecursively() }
                }

                val priority = preservedPriority ?: determineInitialPriority(index.title)
                val dictId = db.dictionaryDao().insert(
                    DictionaryEntity(
                        title = index.title,
                        revision = index.revision,
                        format = index.format,
                        version = index.version,
                        author = index.author,
                        description = index.description,
                        attribution = index.attribution,
                        sourceLanguage = index.sourceLanguage,
                        targetLanguage = index.targetLanguage,
                        sequenced = index.sequenced,
                        frequencyMode = index.frequencyMode,
                        priority = priority,
                        enabled = enabled,
                        styles = styles,
                        zipPath = tempZip.absolutePath,
                    ),
                )

                try {
                    importTerms(zip, dictId, onProgress)
                    importKanji(zip, dictId, onProgress)
                    importTags(zip, dictId)
                    importTermMeta(zip, dictId)
                    importKanjiMeta(zip, dictId)

                    val termCount = db.termDao().countByDict(dictId)
                    val kanjiCount = db.kanjiDao().countByDict(dictId)
                    db.dictionaryDao().updateCounts(dictId, termCount, kanjiCount)

                    onProgress(Progress(index.title, "Done", termCount, termCount, done = true))

                    db.dictionaryDao().getAll().first { it.id == dictId }
                } catch (e: Throwable) {
                    db.termDao().deleteByDict(dictId)
                    db.kanjiDao().deleteByDict(dictId)
                    db.tagDao().deleteByDict(dictId)
                    db.metaDao().deleteTermMetaByDict(dictId)
                    db.metaDao().deleteKanjiMetaByDict(dictId)
                    db.dictionaryDao().delete(dictId)
                    throw e
                }
            }
        } catch (e: CancellationException) {
            tempZip.delete()
            throw e
        } catch (e: ImportException) {
            tempZip.delete()
            throw e
        } catch (e: Exception) {
            tempZip.delete()
            throw ImportException("Import failed: ${e.message}", e)
        }
    }

    private suspend fun importTerms(zip: ZipFile, dictId: Long, onProgress: (Progress) -> Unit) {
        val banks = zip.bankEntries("term_bank")
        var total = 0L
        for (bank in banks) {
            coroutineContext.ensureActive()
            val rows = parseTermBank(zip, bank.name)
            rows.chunked(INSERT_CHUNK).forEach { chunk ->
                coroutineContext.ensureActive()
                db.termDao().insertAll(chunk.map { it.copy(dictId = dictId) })
            }
            total += rows.size
            onProgress(Progress(null, "Importing terms", total))
        }
    }

    private suspend fun importKanji(zip: ZipFile, dictId: Long, onProgress: (Progress) -> Unit) {
        val banks = zip.bankEntries("kanji_bank")
        var total = 0L
        for (bank in banks) {
            coroutineContext.ensureActive()
            val rows = parseKanjiBank(zip, bank.name)
            rows.chunked(INSERT_CHUNK).forEach { chunk ->
                coroutineContext.ensureActive()
                db.kanjiDao().insertAll(chunk.map { it.copy(dictId = dictId) })
            }
            total += rows.size
            onProgress(Progress(null, "Importing kanji", total))
        }
    }

    private suspend fun importTags(zip: ZipFile, dictId: Long) {
        val rows = mutableListOf<TagEntity>()
        for (bank in zip.bankEntries("tag_bank")) {
            val array = zip.readEntryJsonArray(bank.name) ?: continue
            for (item in array) {
                val arr = item as? JsonArray ?: continue
                rows += TagEntity(
                    dictId = dictId,
                    name = arr.str(0),
                    category = arr.str(1),
                    order = arr.int(2) ?: 0,
                    notes = arr.str(3),
                    score = arr.int(4) ?: 0,
                )
            }
        }
        rows.chunked(INSERT_CHUNK).forEach { db.tagDao().insertAll(it) }
    }

    private suspend fun importTermMeta(zip: ZipFile, dictId: Long) {
        val rows = mutableListOf<TermMetaEntity>()
        for (bank in zip.bankEntries("term_meta_bank")) {
            val array = zip.readEntryJsonArray(bank.name) ?: continue
            for (item in array) {
                val arr = item as? JsonArray ?: continue
                val expression = arr.str(0)
                if (expression.isBlank()) continue
                rows += TermMetaEntity(
                    dictId = dictId,
                    expression = expression,
                    mode = arr.str(1),
                    dataJson = arr.getOrNull(2)?.toString() ?: "{}",
                )
            }
        }
        rows.chunked(INSERT_CHUNK).forEach { db.metaDao().insertTermMeta(it) }
    }

    private suspend fun importKanjiMeta(zip: ZipFile, dictId: Long) {
        val rows = mutableListOf<KanjiMetaEntity>()
        for (bank in zip.bankEntries("kanji_meta_bank")) {
            val array = zip.readEntryJsonArray(bank.name) ?: continue
            for (item in array) {
                val arr = item as? JsonArray ?: continue
                val character = arr.str(0)
                if (character.isBlank()) continue
                rows += KanjiMetaEntity(
                    dictId = dictId,
                    character = character,
                    mode = arr.str(1),
                    dataJson = arr.getOrNull(2)?.toString() ?: "{}",
                )
            }
        }
        rows.chunked(INSERT_CHUNK).forEach { db.metaDao().insertKanjiMeta(it) }
    }

    private fun parseTermBank(zip: ZipFile, entry: String): List<TermEntity> {
        val array = zip.readEntryJsonArray(entry) ?: return emptyList()
        val result = ArrayList<TermEntity>(array.size)
        for (item in array) {
            val arr = item as? JsonArray ?: continue
            val expression = arr.str(0)
            if (expression.isBlank()) continue
            result += TermEntity(
                dictId = 0,
                expression = expression,
                reading = arr.str(1),
                definitionTags = arr.str(2),
                rules = arr.str(3),
                score = arr.int(4) ?: 0,
                glossaryJson = arr.getOrNull(5)?.toString() ?: "[]",
                sequence = arr.long(6) ?: 0L,
                termTags = arr.str(7),
            )
        }
        return result
    }

    private fun parseKanjiBank(zip: ZipFile, entry: String): List<KanjiEntity> {
        val array = zip.readEntryJsonArray(entry) ?: return emptyList()
        val result = ArrayList<KanjiEntity>(array.size)
        for (item in array) {
            val arr = item as? JsonArray ?: continue
            val character = arr.str(0)
            if (character.isBlank()) continue
            result += KanjiEntity(
                dictId = 0,
                character = character,
                onyomi = arr.str(1),
                kunyomi = arr.str(2),
                tags = arr.str(3),
                meaningsJson = arr.getOrNull(4)?.toString() ?: "[]",
                statsJson = arr.getOrNull(5)?.toString() ?: "{}",
            )
        }
        return result
    }

    fun parseIndex(jsonString: String): DictionaryIndex = YomitanIndexParser.parse(jsonString)

    /**
     * Finds an installed dictionary that [newTitle] should replace: either an exact title match,
     * or the same base title when a trailing `[...]` date/version tag is stripped (e.g.
     * `Jitendex.org [2026-09-01]` → `Jitendex.org [2026-10-03]`).
     */
    private suspend fun findExistingForImport(newTitle: String): DictionaryEntity? {
        db.dictionaryDao().findByTitle(newTitle)?.let { return it }
        val newBase = baseDictionaryTitle(newTitle)
        return db.dictionaryDao().getAll().firstOrNull { baseDictionaryTitle(it.title).equals(newBase, ignoreCase = true) }
    }

    /**
     * Determines initial priority for a newly installed dictionary.
     * Name dictionaries (such as JMnedict) start at the bottom of the list.
     * General vocabulary dictionaries are inserted above any name dictionaries so that
     * proper names do not shadow general words.
     */
    internal suspend fun determineInitialPriority(title: String): Int {
        val all = db.dictionaryDao().getAll()
        if (all.isEmpty()) return 0

        val isName = RecommendedDictionaries.isNameDictionary(title)
        if (isName) {
            return (db.dictionaryDao().maxPriority() ?: -1) + 1
        }

        val nameDicts = all.filter { RecommendedDictionaries.isNameDictionary(it.title) }
        if (nameDicts.isEmpty()) {
            return (db.dictionaryDao().maxPriority() ?: -1) + 1
        }

        val minNamePriority = nameDicts.minOf { it.priority }
        for (d in all.filter { it.priority >= minNamePriority }) {
            db.dictionaryDao().updatePriority(d.id, d.priority + 1)
        }
        return minNamePriority
    }

    companion object {
        private const val INSERT_CHUNK = 4000

        fun baseDictionaryTitle(title: String): String =
            title.replace(Regex("\\s*\\[[^\\]]*]\\s*$"), "").trim()
    }
}

/** Parses Yomitan index.json. Kept separate so it can be unit tested on the JVM. */
object YomitanIndexParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(jsonString: String): DictionaryIndex {
        val obj = json.parseToJsonElement(jsonString).jsonObject
        val title = obj.string("title") ?: throw YomitanImporter.ImportException("Missing title in index.json")
        val revision = obj.string("revision") ?: "1"
        val tagMeta = (obj["tagMeta"] as? JsonObject)?.mapValues { (_, v) ->
            val meta = v as? JsonObject
            DictionaryIndex.TagMeta(
                category = meta?.string("category") ?: "",
                order = meta?.int("order") ?: 0,
                notes = meta?.string("notes") ?: "",
                score = meta?.int("score") ?: 0,
            )
        } ?: emptyMap()

        return DictionaryIndex(
            title = title,
            revision = revision,
            format = obj.int("format") ?: 3,
            version = obj.int("version"),
            author = obj.string("author"),
            url = obj.string("url"),
            description = obj.string("description"),
            attribution = obj.string("attribution"),
            sourceLanguage = obj.string("sourceLanguage"),
            targetLanguage = obj.string("targetLanguage"),
            sequenced = obj.boolean("sequenced"),
            frequencyMode = obj.string("frequencyMode"),
            isUpdatable = obj.boolean("isUpdatable"),
            indexUrl = obj.string("indexUrl"),
            downloadUrl = obj.string("downloadUrl"),
            tagMeta = tagMeta,
        )
    }
}

internal fun ZipFile.bankEntries(prefix: String): List<java.util.zip.ZipEntry> {
    return entries().toList()
        .filter { it.name.startsWith("$prefix") && it.name.endsWith(".json") }
        .sortedBy { entry ->
            Regex("(\\d+)").find(entry.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
}

internal fun ZipFile.readEntryText(name: String): String? {
    val entry = getEntry(name) ?: entries().toList().firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: return null
    return getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
}

internal fun ZipFile.readEntryJsonArray(name: String): JsonArray? {
    val text = readEntryText(name) ?: return null
    return runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull()
}

private fun JsonArray.str(index: Int): String {
    val element = getOrNull(index) ?: return ""
    return when (element) {
        is JsonPrimitive -> element.contentOrNull ?: element.content
        else -> ""
    }
}

private fun JsonArray.int(index: Int): Int? = (getOrNull(index) as? JsonPrimitive)?.intOrNull

private fun JsonArray.long(index: Int): Long? {
    val primitive = getOrNull(index) as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
}
