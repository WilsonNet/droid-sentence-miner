package io.github.wilsonnet.droidsentenceminer.lang.ja

import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.LanguagePack

import io.github.wilsonnet.droidsentenceminer.lang.Candidate
import io.github.wilsonnet.droidsentenceminer.dict.TermLookup
import java.text.Normalizer

/** Japanese: no spaces, every substring is a candidate, Yomitan's deinflection rules. */
object JapaneseLanguage : LanguageModule {
    override val code = "ja"
    override val displayName = "Japanese"
    override val nativeName = "日本語"
    override val sample = "日本語を読む"
    override val usesSpaces = false
    override val supportsMeikiOcr = true
    override val japaneseWordAudio = true
    override val maxTermLength = 16

    override fun isTargetChar(c: Char): Boolean = JapaneseTextScore.isJapanese(c)

    override fun isLookupable(c: Char): Boolean = TermLookup.isJapaneseLookupable(c)

    override fun candidateLengths(text: String, position: Int, maxLength: Int): IntArray {
        val max = minOf(maxLength, text.length - position)
        var count = 0
        val out = IntArray(max.coerceAtLeast(0))
        for (length in 1..max) {
            if (isLookupable(text[position + length - 1])) out[count++] = length
        }
        return if (count == out.size) out else out.copyOf(count)
    }

    override fun deinflect(surface: String): List<Candidate> = JapaneseDeinflector.deinflect(surface)

    override fun ruleMask(rules: String): Long = InflectionType.parseRules(rules)

    override fun termMatches(conditions: Long, ruleMask: Long): Boolean = InflectionType.termMatches(conditions, ruleMask)

    override fun isRubyChar(c: Char): Boolean = TermLookup.isKana(c)

    override fun textScore(text: String): Double = JapaneseTextScore.score(text)

    override fun looksLikeText(text: String): Boolean {
        val japanese = text.count { JapaneseTextScore.isJapanese(it) }
        return japanese >= 2 && JapaneseTextScore.score(text) >= 3.0
    }

    override fun normalizeOcr(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        t = t.replace(' ', ' ')
            .replace('　', ' ')
        t = t.map { ch ->
            when (ch) {
                '－', '‐', '‑', '‒', '–', '—', '―' -> 'ー'
                '～', '〜' -> '〜'
                '!' -> '！'
                '?' -> '？'
                else -> ch
            }
        }.joinToString("")
        t = t.replace(SPACES, " ")
        // Drop spaces between Japanese characters / around Japanese punctuation.
        t = t.replace(SPACE_BETWEEN, "")
        t = t.replace(SPACE_AFTER, "")
        t = t.replace(SPACE_BEFORE, "")
        return t.trim()
    }

    /** Japanese wraps without spaces; keep one only between two Latin words. */
    override fun joinRows(previous: String, next: String): String {
        if (previous.isEmpty()) return next
        if (next.isEmpty()) return previous
        val prev = previous.last()
        val first = next.first()
        val needSpace = prev.isLetterOrDigit() && !JapaneseTextScore.isJapanese(prev) &&
            first.isLetterOrDigit() && !JapaneseTextScore.isJapanese(first)
        return if (needSpace) "$previous $next" else previous + next
    }

    private const val JP_CHARS = "\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}。、！？「」『』（）…・ー〜"
    private val SPACES = Regex("[ \\t]+")
    private val SPACE_BETWEEN = Regex("(?<=[$JP_CHARS]) (?=[$JP_CHARS])")
    private val SPACE_AFTER = Regex("(?<=[$JP_CHARS]) ")
    private val SPACE_BEFORE = Regex(" (?=[$JP_CHARS])")
}
