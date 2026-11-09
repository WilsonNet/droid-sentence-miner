package io.github.wilsonnet.droidsentenceminer.lang.en

import io.github.wilsonnet.droidsentenceminer.lang.Candidate
import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.LanguagePack
import io.github.wilsonnet.droidsentenceminer.lang.WordAudioSource
import java.text.Normalizer

/**
 * English: words separated by spaces, scanning only at word edges (single words and phrases of
 * up to [MAX_WORDS] words, so "look up" and "ice cream" match), case folding and a small
 * suffix deinflector (plurals, past, -ing, comparatives, -ly) in the spirit of Yomitan's
 * `english-transforms`. Dictionaries: Wiktionary via wty (`RecommendedDictionaries`).
 *
 * Kept deliberately small; irregular forms (went, children) are entries of their own in
 * Wiktionary ("past tense of go"), so they need no table here.
 */
object EnglishLanguage : LanguageModule {
    override val code = "en"
    override val displayName = "English"
    override val nativeName = "English"
    override val sample = "Read English"
    override val usesSpaces = true
    override val supportsMeikiOcr = false
    override val maxTermLength = 48
    override val linguaLibrePrefix = "LL-Q1860 (eng)"
    override val wordAudioSources = listOf(WordAudioSource.WIKTIONARY, WordAudioSource.LINGUA_LIBRE)

    /**
     * PaddleOCR PP-OCRv5 mobile detector + PP-OCRv6 small recognizer (Apache-2.0). On English
     * game/subtitle text: CER 4.5 %, WER 4.4 % vs ML Kit's 6.0 % / 12.2 % (dsm-ocr-eval skill).
     * Until it is installed English is read by the bundled ML Kit model.
     */
    override val pack = LanguagePack(
        language = "en",
        version = 1,
        name = "English OCR models",
        description = "PaddleOCR (PP-OCRv5 detector + PP-OCRv6 recognizer): about half the word errors of the " +
            "built-in reader on game and subtitle text.",
        url = "https://github.com/WilsonNet/droid-sentence-miner/releases/download/lang-en-1/dsm-lang-en-1.zip",
        sha256 = "068d48a7590871c297bd17a41774c7ad7ea53d4f8c59ff905638211b77b17813",
        sizeMb = 23.1,
    )

    const val MAX_WORDS = 5

    // Part-of-speech bits a deinflected form must have (Yomitan rules "n", "v", "adj", "adv").
    const val NOUN = 1L
    const val VERB = 2L
    const val ADJ = 4L
    const val ADV = 8L

    override fun isTargetChar(c: Char): Boolean =
        c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

    override fun isLookupable(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’'

    override fun candidateLengths(text: String, position: Int, maxLength: Int): IntArray {
        if (!text[position].isLetterOrDigit()) return EMPTY
        if (position > 0 && isWordChar(text[position - 1])) return EMPTY
        val limit = minOf(text.length, position + maxLength)
        val lengths = ArrayList<Int>(MAX_WORDS + 2)
        var i = position
        var words = 0
        while (i < limit && words < MAX_WORDS) {
            // One word: letters/digits with inner apostrophes.
            while (i < limit && isWordChar(text[i])) i++
            if (i < text.length && i >= limit && isWordChar(text[i])) break // word runs past the limit
            // Trailing apostrophes ("dogs'") stay part of the surface; the deinflector strips them.
            if (text[i - 1].isLetterOrDigit() || text[i - 1] == '\'' || text[i - 1] == '’') lengths += i - position
            words++
            if (i >= limit) break
            val sep = text[i]
            if ((sep == ' ' || sep == '-') && i + 1 < limit && text[i + 1].isLetterOrDigit()) i++ else break
        }
        return lengths.toIntArray()
    }

    private data class Rule(val suffix: String, val replacement: String, val pos: Long, val reason: String, val minStem: Int = 2)

    private val DOUBLED = "bdgklmnprtvz".toList()

    private val RULES: List<Rule> = buildList {
        add(Rule("s", "", NOUN, "plural"))
        add(Rule("s", "", VERB, "3rd person"))
        add(Rule("es", "", NOUN, "plural"))
        add(Rule("es", "", VERB, "3rd person"))
        add(Rule("ies", "y", NOUN, "plural", minStem = 1))
        add(Rule("ies", "y", VERB, "3rd person", minStem = 1))
        add(Rule("ves", "f", NOUN, "plural"))
        add(Rule("ves", "fe", NOUN, "plural"))
        add(Rule("'s", "", NOUN, "possessive", minStem = 1))
        add(Rule("s'", "s", NOUN, "possessive", minStem = 1))
        add(Rule("ed", "", VERB, "past"))
        add(Rule("ed", "e", VERB, "past"))
        add(Rule("d", "", VERB, "past"))
        add(Rule("ied", "y", VERB, "past", minStem = 1))
        add(Rule("cked", "c", VERB, "past"))
        add(Rule("ing", "", VERB, "-ing"))
        add(Rule("ing", "e", VERB, "-ing"))
        add(Rule("ying", "ie", VERB, "-ing", minStem = 1))
        add(Rule("cking", "c", VERB, "-ing"))
        add(Rule("er", "", ADJ, "comparative"))
        add(Rule("er", "e", ADJ, "comparative"))
        add(Rule("r", "", ADJ, "comparative"))
        add(Rule("ier", "y", ADJ, "comparative", minStem = 1))
        add(Rule("est", "", ADJ, "superlative"))
        add(Rule("st", "", ADJ, "superlative"))
        add(Rule("iest", "y", ADJ, "superlative", minStem = 1))
        add(Rule("ly", "", ADJ, "adverb"))
        add(Rule("ily", "y", ADJ, "adverb", minStem = 1))
        add(Rule("ly", "le", ADJ, "adverb"))
        add(Rule("ally", "al", ADJ, "adverb"))
        // Doubled final consonant: stopped → stop, running → run, bigger → big.
        for (c in DOUBLED) {
            add(Rule("$c${c}ed", "$c", VERB, "past", minStem = 1))
            add(Rule("$c${c}ing", "$c", VERB, "-ing", minStem = 1))
            add(Rule("$c${c}er", "$c", ADJ, "comparative", minStem = 1))
            add(Rule("$c${c}est", "$c", ADJ, "superlative", minStem = 1))
        }
    }

    override fun deinflect(surface: String): List<Candidate> {
        if (surface.isBlank()) return emptyList()
        val base = surface.replace('’', '\'')
        val out = LinkedHashMap<Pair<String, Long>, Candidate>()
        fun add(term: String, conditions: Long, reasons: List<String>) {
            if (term.isEmpty()) return
            out.putIfAbsent(term to conditions, Candidate(term, conditions, ArrayDeque(reasons), hasAppliedRule = reasons.isNotEmpty()))
        }
        val forms = linkedSetOf(base, base.lowercase(), base.replaceFirstChar { it.lowercaseChar() })
        for (form in forms) {
            add(form, 0L, emptyList())
            val words = form.split(' ')
            if (words.size == 1) {
                for ((term, pos, reasons) in deinflectWord(form)) add(term, pos, reasons)
            } else {
                // Phrasal verbs inflect the first word (looked up → look up), noun phrases the last (ice creams).
                val rest = form.substringAfter(' ')
                for ((term, pos, reasons) in deinflectWord(words.first())) if (pos == VERB) add("$term $rest", 0L, reasons)
                val head = form.substringBeforeLast(' ')
                for ((term, pos, reasons) in deinflectWord(words.last())) if (pos == NOUN) add("$head $term", 0L, reasons)
            }
        }
        return out.values.toList()
    }

    /** One word's dictionary forms (two rules deep: dogs' → dog). */
    private fun deinflectWord(word: String): List<Triple<String, Long, List<String>>> {
        val results = mutableListOf<Triple<String, Long, List<String>>>()
        fun apply(term: String, reasons: List<String>, depth: Int) {
            for (rule in RULES) {
                if (!term.endsWith(rule.suffix, ignoreCase = true)) continue
                val stem = term.dropLast(rule.suffix.length)
                if (stem.length < rule.minStem || stem.none { it.isLetter() }) continue
                val next = stem + rule.replacement
                val chain = listOf(rule.reason) + reasons
                results += Triple(next, rule.pos, chain)
                if (depth < 1 && rule.reason == "possessive") apply(next, chain, depth + 1)
            }
        }
        apply(word, emptyList(), 0)
        return results
    }

    /** Unknown or missing parts of speech (phrases, symbols) accept any deinflection. */
    override fun termMatches(conditions: Long, ruleMask: Long): Boolean =
        conditions == 0L || ruleMask == 0L || (ruleMask and conditions) != 0L

    override fun ruleMask(rules: String): Long {
        var mask = 0L
        for (rule in rules.split(' ', ',')) {
            mask = mask or when (rule.trim()) {
                "n", "noun", "name" -> NOUN
                "v", "verb", "vt", "vi" -> VERB
                "adj", "adjective" -> ADJ
                "adv", "adverb" -> ADV
                else -> 0L
            }
        }
        return mask
    }

    override fun textScore(text: String): Double {
        if (text.isBlank()) return 0.0
        var letters = 0
        var latin = 0
        var good = 0
        var bad = 0
        for (ch in text) {
            when {
                ch == '�' -> bad += 4
                ch.isWhitespace() -> Unit
                ch.isLetterOrDigit() -> {
                    good++
                    letters++
                    if (isTargetChar(ch)) latin++
                }
                else -> good++
            }
        }
        if (letters == 0) return 0.0
        val penalty = bad.toDouble() / (good + bad).coerceAtLeast(1)
        return latin.toDouble() / letters * 10.0 + good * 0.01 - penalty * 20.0
    }

    override fun looksLikeText(text: String): Boolean =
        text.count { isTargetChar(it) } >= 3 && textScore(text) >= 3.0

    override fun normalizeOcr(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        t = t.replace(' ', ' ').replace('　', ' ')
        t = t.replace(SPACES, " ")
        t = t.replace(SPACE_BEFORE_PUNCT, "")
        return t.trim()
    }

    /** A line ending in a hyphen continues the word; anything else gets a space. */
    override fun joinRows(previous: String, next: String): String = when {
        previous.isEmpty() -> next
        next.isEmpty() -> previous
        previous.last() == '-' || previous.last().isWhitespace() -> previous + next
        else -> "$previous $next"
    }

    private val EMPTY = IntArray(0)
    private val SPACES = Regex("[ \\t]+")
    private val SPACE_BEFORE_PUNCT = Regex(" (?=[,.!?;:])")
}
