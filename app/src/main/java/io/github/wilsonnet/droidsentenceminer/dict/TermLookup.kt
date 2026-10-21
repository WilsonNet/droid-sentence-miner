package io.github.wilsonnet.droidsentenceminer.dict

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    }

    private data class FormSource(
        val position: Int,
        val length: Int,
        val surface: String,
        val conditions: Long,
        val reasons: List<String>,
    )

    suspend fun scan(text: String, maxLength: Int = MAX_TERM_LENGTH): List<ScannedWord> = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext emptyList()

        val dicts = dictionariesByPriority()
        if (dicts.isEmpty()) return@withContext emptyList()

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
        if (formSources.isEmpty()) return@withContext emptyList()

        val termsByForm = queryTermsByForms(formSources.keys.toList())

        data class Hit(val length: Int, val term: TermResult)

        val hitsByPosition = HashMap<Int, MutableMap<Int, MutableList<TermResult>>>()
        for ((form, sources) in formSources) {
            val rows = termsByForm[form] ?: continue
            for (row in rows) {
                val dict = dicts[row.dictId] ?: continue
                for (source in sources) {
                    val expected = InflectionType.parseRules(row.rules)
                    if (!InflectionType.conditionsMatch(source.conditions, expected)) continue
                    val exactExpression = row.expression == source.surface
                    val term = TermResult(
                        dictId = row.dictId,
                        dictionaryTitle = dict.title,
                        dictionaryPriority = dict.priority,
                        expression = row.expression,
                        reading = row.reading,
                        definitionTags = row.definitionTags,
                        rules = row.rules,
                        score = row.score,
                        glossaryJson = row.glossaryJson,
                        sequence = row.sequence,
                        termTags = row.termTags,
                        matchedForm = form,
                        deinflectionReasons = source.reasons,
                    )
                    if (!exactExpression && source.conditions != 0L && expected != 0L) {
                        // deinflected match must be compatible with the term rules (already checked above)
                    }
                    val positionMap = hitsByPosition.getOrPut(source.position) { HashMap() }
                    positionMap.getOrPut(source.length) { mutableListOf() }.add(term)
                }
            }
        }
        if (hitsByPosition.isEmpty()) return@withContext emptyList()

        // Longest-match-wins walk.
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
            val ranked = rankTerms(surface, terms)
            words += ScannedWord(
                text = surface,
                start = position,
                end = position + chosenLength,
                results = ranked,
            )
            position += chosenLength
        }
        words
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

        val termsByForm = queryTermsByForms(formConditions.keys.toList())
        val results = mutableListOf<TermResult>()
        val deinflectionReasons = linkedSetOf<String>()
        for ((form, rows) in termsByForm) {
            val uses = formConditions[form] ?: continue
            for (row in rows) {
                val dict = dicts[row.dictId] ?: continue
                val expected = InflectionType.parseRules(row.rules)
                val matchingUse = uses.firstOrNull { (conditions, _) ->
                    InflectionType.conditionsMatch(conditions, expected)
                } ?: continue
                deinflectionReasons += matchingUse.second
                results += TermResult(
                    dictId = row.dictId,
                    dictionaryTitle = dict.title,
                    dictionaryPriority = dict.priority,
                    expression = row.expression,
                    reading = row.reading,
                    definitionTags = row.definitionTags,
                    rules = row.rules,
                    score = row.score,
                    glossaryJson = row.glossaryJson,
                    sequence = row.sequence,
                    termTags = row.termTags,
                    matchedForm = form,
                    deinflectionReasons = matchingUse.second,
                )
            }
        }

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
        return db.dictionaryDao().getEnabled().associateBy { it.id }
    }

    private suspend fun queryTermsByForms(forms: List<String>): Map<String, List<TermEntity>> {
        if (forms.isEmpty()) return emptyMap()
        val result = HashMap<String, MutableList<TermEntity>>()
        forms.chunked(DB_CHUNK).forEach { chunk ->
            val rows = db.termDao().findByForms(chunk)
            for (row in rows) {
                result.getOrPut(row.expression) { mutableListOf() }.add(row)
                if (row.reading.isNotBlank() && row.reading != row.expression) {
                    result.getOrPut(row.reading) { mutableListOf() }.add(row)
                }
            }
        }
        return result
    }

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
