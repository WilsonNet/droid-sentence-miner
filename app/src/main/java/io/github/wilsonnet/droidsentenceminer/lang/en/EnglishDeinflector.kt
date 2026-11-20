package io.github.wilsonnet.droidsentenceminer.lang.en

import io.github.wilsonnet.droidsentenceminer.lang.Candidate
import io.github.wilsonnet.droidsentenceminer.lang.kit.CandidateSet
import io.github.wilsonnet.droidsentenceminer.lang.kit.CaseForms
import io.github.wilsonnet.droidsentenceminer.lang.kit.PosMask
import io.github.wilsonnet.droidsentenceminer.lang.kit.SuffixRules

/**
 * English suffix deinflection (plurals, 3rd person, possessives, past, -ing, comparatives, -ly) in
 * the spirit of Yomitan's `english-transforms`. Irregular forms (went, children) are entries of
 * their own in Wiktionary ("past tense of go"), so they need no table here.
 */
internal object EnglishDeinflector {
    private const val POSSESSIVE = "possessive"
    private const val PAST = "past"
    private const val ING = "-ing"
    private const val COMPARATIVE = "comparative"
    private const val SUPERLATIVE = "superlative"

    // Final consonants English doubles before a suffix: stopped → stop, running → run, bigger → big.
    private const val DOUBLED = "bdgklmnprtvz"

    private val RULES =
        SuffixRules.combine(
            SuffixRules.group(pos = PosMask.NOUN, reason = "plural", spec = "s> es> ves>f ves>fe"),
            SuffixRules.group(pos = PosMask.NOUN, reason = "plural", spec = "ies>y", minStem = 1),
            SuffixRules.group(pos = PosMask.VERB, reason = "3rd person", spec = "s> es>"),
            SuffixRules.group(pos = PosMask.VERB, reason = "3rd person", spec = "ies>y", minStem = 1),
            SuffixRules.group(pos = PosMask.NOUN, reason = POSSESSIVE, spec = "'s> s'>s", minStem = 1),
            SuffixRules.group(pos = PosMask.VERB, reason = PAST, spec = "ed> ed>e d> cked>c"),
            SuffixRules.group(pos = PosMask.VERB, reason = PAST, spec = "ied>y", minStem = 1),
            SuffixRules.group(pos = PosMask.VERB, reason = ING, spec = "ing> ing>e cking>c"),
            SuffixRules.group(pos = PosMask.VERB, reason = ING, spec = "ying>ie", minStem = 1),
            SuffixRules.group(pos = PosMask.ADJ, reason = COMPARATIVE, spec = "er> er>e r>"),
            SuffixRules.group(pos = PosMask.ADJ, reason = COMPARATIVE, spec = "ier>y", minStem = 1),
            SuffixRules.group(pos = PosMask.ADJ, reason = SUPERLATIVE, spec = "est> st>"),
            SuffixRules.group(pos = PosMask.ADJ, reason = SUPERLATIVE, spec = "iest>y", minStem = 1),
            SuffixRules.group(pos = PosMask.ADJ, reason = "adverb", spec = "ly> ly>le ally>al"),
            SuffixRules.group(pos = PosMask.ADJ, reason = "adverb", spec = "ily>y", minStem = 1),
            doubled(pos = PosMask.VERB, reason = PAST, suffix = "ed"),
            doubled(pos = PosMask.VERB, reason = ING, suffix = "ing"),
            doubled(pos = PosMask.ADJ, reason = COMPARATIVE, suffix = "er"),
            doubled(pos = PosMask.ADJ, reason = SUPERLATIVE, suffix = "est"),
        )

    // "bbed>b dded>d …": the doubled consonant before [suffix] undone.
    private fun doubled(pos: Long, reason: String, suffix: String): SuffixRules {
        val spec = DOUBLED.map { c -> "$c$c$suffix>$c" }.joinToString(" ")
        return SuffixRules.group(pos = pos, reason = reason, spec = spec, minStem = 1)
    }

    /** Dictionary-form candidates for [surface], the surface itself (in each case form) first. */
    fun deinflect(surface: String): List<Candidate> {
        if (surface.isBlank()) return emptyList()
        val out = CandidateSet()
        for (form in CaseForms.spellings(text = surface.replace(oldChar = '’', newChar = '\''), capitalized = false)) {
            out.add(term = form, pos = 0L, reasons = emptyList())
            val words = form.split(' ')
            if (words.size == 1) {
                wordForms(form).forEach { (term, pos, reasons) -> out.add(term = term, pos = pos, reasons = reasons) }
            } else {
                addPhraseForms(out = out, form = form, words = words)
            }
        }
        return out.toList()
    }

    // Phrasal verbs inflect the first word (looked up → look up), noun phrases the last (ice creams).
    private fun addPhraseForms(out: CandidateSet, form: String, words: List<String>) {
        val rest = form.substringAfter(' ')
        val head = form.substringBeforeLast(' ')
        for ((term, pos, reasons) in wordForms(words.first())) {
            if (pos == PosMask.VERB) out.add(term = "$term $rest", pos = 0L, reasons = reasons)
        }
        for ((term, pos, reasons) in wordForms(words.last())) {
            if (pos == PosMask.NOUN) out.add(term = "$head $term", pos = 0L, reasons = reasons)
        }
    }

    // One word's dictionary forms, two rules deep after a possessive (dogs' → dogs → dog).
    private fun wordForms(word: String): List<Triple<String, Long, List<String>>> {
        val results = mutableListOf<Triple<String, Long, List<String>>>()
        for (form in RULES.apply(word)) {
            results += Triple(form.term, form.pos, listOf(form.reason))
            if (form.reason == POSSESSIVE) {
                RULES.apply(form.term).forEach { inner ->
                    results += Triple(inner.term, inner.pos, listOf(inner.reason, POSSESSIVE))
                }
            }
        }
        return results
    }
}
