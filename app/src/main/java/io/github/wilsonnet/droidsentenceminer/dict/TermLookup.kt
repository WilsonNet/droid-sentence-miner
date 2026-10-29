package io.github.wilsonnet.droidsentenceminer.dict

import androidx.room.InvalidationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Yomitan-style lookup engine.
 *
 * - [scan] walks a sentence with longest-match-wins semantics, using the Japanese
 *   deinflector to find dictionary forms for inflected words.
 * - [lookup] resolves a single word (used by the popup and by tap-to-look-up).
 *
 * The algorithm is a Kotlin port of the matching strategy used by Yomitan / Yomihon:
 * generate deinflection candidates for every substring, batch-query the database,
 * then pick the longest valid match at each position.
 *
 * Both read term rows without their glossary ([TermHead]) first; [lookup] then fetches the
 * glossaries of the rows that matched by id, [scan] never does. With big monolingual
 * dictionaries installed a line scan matches thousands of rows of kilobytes each.
 */
class TermLookup(
    private val db: DictDatabase,
) {
    data class LookupDetails(
        val surface: String,
        val terms: List<TermResult>,
        val frequencies: List<TermMetaResult>,
        val kanji: List<KanjiResult>,
        val deinflection: List<String> = emptyList(),
    ) {
        val primary: TermResult? get() = terms.firstOrNull()
        val primaryDictionary: String? get() = primary?.dictionaryTitle
        val baseForm: String get() = primary?.expression ?: surface
        val reading: String? get() = terms.firstOrNull { it.reading.isNotBlank() }?.reading

        /**
         * Yomitan's "group" results: one entry per headword (term, reading, inflection chain), with
         * the definitions of every dictionary for it, in the order the headwords first appear. Each
         * entry gets its own add button, and its card only that headword's reading and glossary.
         */
        val entries: List<Entry> by lazy {
            terms.groupBy { Triple(it.expression, kanaKey(it.reading.ifBlank { it.expression }), it.deinflectionReasons) }
                .values
                .map { group -> Entry(group.first().expression, group.firstOrNull { it.reading.isNotBlank() }?.reading.orEmpty(), group) }
        }

        /** These details narrowed to entry [index]: what a card for that headword is built from. */
        fun forEntry(index: Int): LookupDetails {
            val entry = entries.getOrNull(index) ?: return this
            if (entries.size == 1) return this
            return copy(terms = entry.terms, frequencies = frequencies.filter { it.expression == entry.expression })
        }
    }

    data class Entry(val expression: String, val reading: String, val terms: List<TermResult>)

    /** What the last uncached [scan] cost (debug API `/lookup_bench`). */
    data class ScanStats(
        val forms: Int,
        val rows: Int,
        val deinflectMs: Double,
        val queryMs: Double,
        val totalMs: Double,
    )

    @Volatile
    var lastScanStats: ScanStats? = null
        private set

    private data class FormSource(
        val position: Int,
        val length: Int,
        val surface: String,
        val conditions: Long,
        val reasons: List<String>,
    )

    private data class ScanKey(val text: String, val maxLength: Int)

    // A tap scans its whole line (wordAt), the tap re-read scans it again, and neighbouring taps
    // hit the same subtitle: keep the last lines. Cleared on any dictionary write; a result
    // computed while a write happened (the generation moved on) is not stored.
    private val scanCache = LruCache<ScanKey, List<ScannedWord>>(SCAN_CACHE_SIZE)
    private val tagCache = ConcurrentHashMap<Long, Map<String, String>>()

    @Volatile
    private var enabledCache: Map<Long, DictionaryEntity>? = null

    @Volatile
    private var cacheGeneration = 0L

    private val observing = AtomicBoolean(false)

    /**
     * Every import, update, delete, enable or reorder writes the `dictionaries` row last
     * (`updateCounts` after the terms and tags), so watching that table alone keeps the caches
     * right without a per-row trigger on `terms`. Registered on first use, off the main thread:
     * `addObserver` blocks while Room installs its trigger.
     */
    private fun ensureObserving() {
        if (!observing.compareAndSet(false, true)) return
        db.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(arrayOf("dictionaries")) {
                override fun onInvalidated(tables: Set<String>) = invalidateCaches()
            },
        )
    }

    /** Drops every cache; Room calls it whenever the dictionaries table is written. */
    fun invalidateCaches() {
        cacheGeneration++
        scanCache.clear()
        tagCache.clear()
        enabledCache = null
    }

    /**
     * Words of [text], longest match first at each position. [ScannedWord.results] are ranked but
     * carry no glossary, sequence or term tags (empty / 0): callers only need the ranges and headwords; use
     * [lookup] for definitions. Cached per text until a dictionary table changes.
     */
    suspend fun scan(text: String, maxLength: Int = MAX_TERM_LENGTH): List<ScannedWord> = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext emptyList()
        val key = ScanKey(text, maxLength)
        scanCache[key]?.let { return@withContext it }
        val generation = cacheGeneration
        val words = scanUncached(text, maxLength)
        if (generation == cacheGeneration) scanCache[key] = words
        words
    }

    private suspend fun scanUncached(text: String, maxLength: Int): List<ScannedWord> {
        val started = System.nanoTime()
        val dicts = dictionariesByPriority()
        if (dicts.isEmpty()) return emptyList()

        // form -> uses of that form across the text
        val formSources = HashMap<String, MutableList<FormSource>>(1024)
        for (position in text.indices) {
            if (!isLookupable(text[position])) continue
            val max = minOf(maxLength, text.length - position)
            for (length in 1..max) {
                val end = position + length
                if (!isLookupable(text[end - 1])) continue
                val surface = text.substring(position, end)
                val candidates = JapaneseDeinflector.deinflect(surface)
                for (candidate in candidates) {
                    if (candidate.term.isEmpty()) continue
                    formSources.getOrPut(candidate.term) { mutableListOf() }
                        .add(
                            FormSource(
                                position = position,
                                length = length,
                                surface = surface,
                                conditions = candidate.conditions,
                                reasons = candidate.reasons.toList(),
                            ),
                        )
                }
            }
        }
        if (formSources.isEmpty()) return emptyList()

        val deinflected = System.nanoTime()
        val headsByForm = queryHeadsByForms(formSources.keys)
        val queried = System.nanoTime()

        val hitsByPosition = HashMap<Int, MutableMap<Int, MutableList<TermResult>>>()
        for ((form, sources) in formSources) {
            val rows = headsByForm[form] ?: continue
            for (row in rows) {
                val dict = dicts[row.dictId] ?: continue
                val expected = InflectionType.parseRules(row.rules)
                for (source in sources) {
                    if (!InflectionType.conditionsMatch(source.conditions, expected)) continue
                    val term = row.toResult(dict, body = null, matchedForm = form, reasons = source.reasons)
                    val positionMap = hitsByPosition.getOrPut(source.position) { HashMap() }
                    positionMap.getOrPut(source.length) { mutableListOf() }.add(term)
                }
            }
        }
        val words = if (hitsByPosition.isEmpty()) emptyList() else longestMatches(text, maxLength, hitsByPosition)
        val done = System.nanoTime()
        lastScanStats = ScanStats(
            forms = formSources.size,
            rows = headsByForm.values.sumOf { it.size },
            deinflectMs = (deinflected - started) / 1e6,
            queryMs = (queried - deinflected) / 1e6,
            totalMs = (done - started) / 1e6,
        )
        return words
    }

    /** Longest-match-wins walk over the hits (position -> length -> terms). */
    private fun longestMatches(
        text: String,
        maxLength: Int,
        hitsByPosition: Map<Int, Map<Int, List<TermResult>>>,
    ): List<ScannedWord> {
        val words = mutableListOf<ScannedWord>()
        var position = 0
        while (position < text.length) {
            val lengthMap = hitsByPosition[position]
            var chosenLength = 0
            if (lengthMap != null) {
                for (length in minOf(maxLength, text.length - position) downTo 1) {
                    val terms = lengthMap[length] ?: continue
                    if (terms.isNotEmpty()) {
                        chosenLength = length
                        break
                    }
                }
            }
            if (chosenLength == 0) {
                position++
                continue
            }
            val terms = lengthMap!![chosenLength]!!
            val surface = text.substring(position, position + chosenLength)
            if (isNoiseMatch(surface, terms)) {
                position++
                continue
            }
            words += ScannedWord(
                text = surface,
                start = position,
                end = position + chosenLength,
                results = rankTerms(surface, terms),
            )
            position += chosenLength
        }
        return words
    }

    /**
     * The range to look up for a tap on [text] at [index]: the scanned word covering that
     * character (tapping ル in ハトヤホテル gives ホテル), else the longest word starting there,
     * else the character alone.
     */
    suspend fun wordAt(text: String, index: Int): IntRange {
        scan(text).firstOrNull { index >= it.start && index < it.end }?.let { return it.start until it.end }
        val from = scan(text.substring(index, minOf(text.length, index + MAX_TERM_LENGTH))).firstOrNull { it.start == 0 }
        return index until index + (from?.text?.length ?: 1)
    }

    suspend fun lookup(word: String, allowDeinflection: Boolean = true): LookupDetails = withContext(Dispatchers.Default) {
        val dicts = dictionariesByPriority()
        if (dicts.isEmpty() || word.isBlank()) {
            return@withContext LookupDetails(surface = word, terms = emptyList(), frequencies = emptyList(), kanji = emptyList())
        }

        val candidates = if (allowDeinflection) {
            JapaneseDeinflector.deinflect(word)
        } else {
            listOf(Candidate(term = word, conditions = 0L))
        }

        val formConditions = HashMap<String, MutableList<Pair<Long, List<String>>>>()
        for (candidate in candidates) {
            if (candidate.term.isEmpty()) continue
            formConditions.getOrPut(candidate.term) { mutableListOf() }
                .add(candidate.conditions to candidate.reasons.toList())
        }

        val headsByForm = queryHeadsByForms(formConditions.keys)
        // Rows whose rules fit one of the candidates; only their glossaries are read.
        val matches = mutableListOf<HeadMatch>()
        val deinflectionReasons = linkedSetOf<String>()
        for ((form, rows) in headsByForm) {
            val uses = formConditions[form] ?: continue
            for (row in rows) {
                val dict = dicts[row.dictId] ?: continue
                val expected = InflectionType.parseRules(row.rules)
                val matchingUse = uses.firstOrNull { (conditions, _) ->
                    InflectionType.conditionsMatch(conditions, expected)
                } ?: continue
                deinflectionReasons += matchingUse.second
                matches += HeadMatch(row, dict, form, matchingUse.second)
            }
        }
        val bodies = queryBodies(matches.map { it.head.id })
        val results = matches.map { it.head.toResult(it.dict, bodies[it.head.id], it.form, it.reasons) }

        val ranked = rankTerms(word, results)
        val frequencies = lookupTermMeta(formConditions.keys.toList(), dicts)
        val kanji = lookupKanji(word.filter { isKanji(it) }.map { it.toString() }.distinct())

        LookupDetails(
            surface = word,
            terms = ranked,
            frequencies = frequencies,
            kanji = kanji,
            deinflection = deinflectionReasons.filter { it.isNotBlank() },
        )
    }

    private class HeadMatch(val head: TermHead, val dict: DictionaryEntity, val form: String, val reasons: List<String>)

    suspend fun lookupKanji(characters: List<String>): List<KanjiResult> = withContext(Dispatchers.Default) {
        if (characters.isEmpty()) return@withContext emptyList()
        val dicts = dictionariesByPriority()
        if (dicts.isEmpty()) return@withContext emptyList()
        val rows = db.kanjiDao().findByCharacters(characters)
        rows.mapNotNull { row ->
            val dict = dicts[row.dictId] ?: return@mapNotNull null
            KanjiResult(
                dictId = row.dictId,
                dictionaryTitle = dict.title,
                character = row.character,
                onyomi = row.onyomi,
                kunyomi = row.kunyomi,
                tags = row.tags,
                meaningsJson = row.meaningsJson,
                statsJson = row.statsJson,
            )
        }
    }

    private suspend fun dictionariesByPriority(): Map<Long, DictionaryEntity> {
        enabledCache?.let { return it }
        ensureObserving()
        val generation = cacheGeneration
        val loaded = db.dictionaryDao().getEnabled().associateBy { it.id }
        if (generation == cacheGeneration) enabledCache = loaded
        return loaded
    }

    /** Heads of every enabled row whose expression or reading is one of [forms], keyed by that form. */
    private suspend fun queryHeadsByForms(forms: Collection<String>): Map<String, List<TermHead>> {
        if (forms.isEmpty()) return emptyMap()
        val result = HashMap<String, MutableList<TermHead>>()
        forms.chunked(TERM_CHUNK).forEach { chunk ->
            for (row in db.termDao().findHeadsByForms(chunk)) {
                result.getOrPut(row.expression) { mutableListOf() }.add(row)
                if (row.reading.isNotBlank() && row.reading != row.expression) {
                    result.getOrPut(row.reading) { mutableListOf() }.add(row)
                }
            }
        }
        return result
    }

    private suspend fun queryBodies(ids: List<Long>): Map<Long, TermBody> {
        if (ids.isEmpty()) return emptyMap()
        val result = HashMap<Long, TermBody>(ids.size * 2)
        ids.distinct().chunked(DB_CHUNK).forEach { chunk ->
            db.termDao().findBodies(chunk).forEach { result[it.id] = it }
        }
        return result
    }

    /** [body] null: a scan hit, which carries no glossary, sequence or term tags. */
    private fun TermHead.toResult(dict: DictionaryEntity, body: TermBody?, matchedForm: String, reasons: List<String>) = TermResult(
        dictId = dictId,
        dictionaryTitle = dict.title,
        dictionaryPriority = dict.priority,
        expression = expression,
        reading = reading,
        definitionTags = definitionTags,
        rules = rules,
        score = score,
        glossaryJson = body?.glossaryJson.orEmpty(),
        sequence = body?.sequence ?: 0L,
        termTags = body?.termTags.orEmpty(),
        matchedForm = matchedForm,
        deinflectionReasons = reasons,
    )

    private suspend fun lookupTermMeta(forms: List<String>, dicts: Map<Long, DictionaryEntity>): List<TermMetaResult> {
        if (forms.isEmpty()) return emptyList()
        val result = mutableListOf<TermMetaResult>()
        forms.chunked(DB_CHUNK).forEach { chunk ->
            db.metaDao().findTermMeta(chunk).forEach { row ->
                val dict = dicts[row.dictId] ?: return@forEach
                result += TermMetaResult(
                    dictId = row.dictId,
                    dictionaryTitle = dict.title,
                    expression = row.expression,
                    mode = row.mode,
                    dataJson = row.dataJson,
                )
            }
        }
        return result
    }

    /** Tag name -> category per dictionary (only categorized tags), cached until the tags table changes. */
    suspend fun tagCategories(dictIds: Collection<Long>): Map<Long, Map<String, String>> {
        if (dictIds.isEmpty()) return emptyMap()
        ensureObserving()
        val missing = dictIds.filter { !tagCache.containsKey(it) }
        val loaded = if (missing.isEmpty()) {
            emptyMap()
        } else {
            val generation = cacheGeneration
            val rows = db.tagDao().categorized(missing).groupBy { it.dictId }
                .mapValues { (_, tags) -> tags.associate { it.name to it.category } }
            if (generation == cacheGeneration) missing.forEach { tagCache[it] = rows[it].orEmpty() }
            rows
        }
        return dictIds.associateWith { loaded[it] ?: tagCache[it].orEmpty() }.filterValues { it.isNotEmpty() }
    }

    private fun rankTerms(surface: String, terms: List<TermResult>): List<TermResult> {
        return terms
            .distinctBy { "${it.dictId}:${it.expression}:${it.reading}:${it.sequence}:${it.definitionTags}" }
            .sortedWith(
                compareBy<TermResult> { it.dictionaryPriority }
                    .thenByDescending { if (it.expression == surface) 1 else 0 }
                    .thenByDescending { if (it.reading == surface) 1 else 0 }
                    .thenBy { it.deinflectionReasons.size }
                    .thenByDescending { it.score },
            )
    }

    /**
     * Single kana (particles, okurigana) and pure grammatical entries make the
     * auto-scanner too noisy with full dictionaries such as Jitendex.
     */
    private fun isNoiseMatch(surface: String, terms: List<TermResult>): Boolean {
        if (surface.length != 1) return false
        if (isKanji(surface[0])) return false
        val tagSets = terms.map { term ->
            term.definitionTags.split(" ").filter { it.isNotBlank() }.toSet()
        }
        if (tagSets.isEmpty()) return true
        val grammatical = setOf("prt", "cop", "aux", "aux-v", "aux-adj", "conj", "int")
        return tagSets.all { tags -> tags.isNotEmpty() && tags.all { it in grammatical } }
    }

    companion object {
        private const val MAX_TERM_LENGTH = 16
        private const val DB_CHUNK = 800

        /** Forms per terms query: bound twice (expression IN, reading IN), and old SQLite allows 999 args. */
        private const val TERM_CHUNK = 450
        private const val SCAN_CACHE_SIZE = 32

        fun isKanji(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

        fun isKana(c: Char): Boolean {
            val script = Character.UnicodeScript.of(c.code)
            return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
        }

        /** Characters that can be part of a Japanese search term. */
        fun isLookupable(c: Char): Boolean {
            if (isKanji(c) || isKana(c)) return true
            return c == 'ー' || c == '々' || c == '〻' || c == 'ゝ' || c == 'ゞ' || c == 'ヽ' || c == 'ヾ'
        }

        fun containsJapanese(text: String): Boolean = text.any { isKanji(it) || isKana(it) }
    }
}

/** Katakana folded to hiragana, so トル and とる group together (Yomitan's reading normalizer). */
internal fun kanaKey(reading: String): String = buildString(reading.length) {
    for (c in reading) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
}

/** A small thread-safe LRU map (android.util.LruCache is a stub in JVM unit tests). */
internal class LruCache<K, V>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size
}
