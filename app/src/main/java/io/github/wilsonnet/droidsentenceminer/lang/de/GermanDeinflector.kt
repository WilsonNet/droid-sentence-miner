package io.github.wilsonnet.droidsentenceminer.lang.de

import io.github.wilsonnet.droidsentenceminer.lang.Candidate
import io.github.wilsonnet.droidsentenceminer.lang.kit.CandidateSet
import io.github.wilsonnet.droidsentenceminer.lang.kit.CaseForms
import io.github.wilsonnet.droidsentenceminer.lang.kit.PosMask
import io.github.wilsonnet.droidsentenceminer.lang.kit.SuffixRules

/**
 * German deinflection, a fallback: the Wiktionary dictionaries (wty) list most inflected forms as
 * entries of their own ("ging" → past of gehen, "Häuser" → plural of Haus), so these rules only
 * cover regular endings — conjugation, weak/strong participles (also with separable prefixes:
 * "aufgemacht" → aufmachen), zu-infinitives, adjective declension, comparative and superlative,
 * and noun plural/case endings. Separated prefixes ("Ich stehe früh auf") are not joined.
 */
internal object GermanDeinflector {
    private const val MIN_STEM = 2
    private const val MAX_SEPARABLE_PREFIX = 7
    private const val GE_LENGTH = 2
    private const val DECLINED = "declined"
    private const val CONJUGATED = "conjugated"
    private const val PARTICIPLE = "past participle"
    private const val PLURAL_CASE = "plural/case"

    // One undone inflection chain: the dictionary form, its part of speech and the reasons.
    private data class Form(val term: String, val pos: Long, val reasons: List<String>)

    private val RULES =
        SuffixRules.combine(
            SuffixRules.group(pos = PosMask.VERB, reason = CONJUGATED, spec = "e>en st>en est>en t>en et>en le>eln re>ern st>n t>n"),
            SuffixRules.group(
                pos = PosMask.VERB,
                reason = "past",
                spec = "te>en test>en ten>en tet>en ete>en etest>en eten>en etet>en te>n test>n ten>n tet>n",
            ),
            SuffixRules.group(pos = PosMask.VERB, reason = "present participle", spec = "end>en nd>n"),
            SuffixRules.group(pos = PosMask.NOUN, reason = PLURAL_CASE, spec = "e> en> n> er> s> es> ns> ens> nen>"),
            SuffixRules.group(pos = PosMask.ADJ, reason = DECLINED, spec = "e> en> em> er> es>"),
            SuffixRules.group(pos = PosMask.ADJ, reason = "comparative", spec = "er>"),
            SuffixRules.group(pos = PosMask.ADJ, reason = "superlative", spec = "st> est>"),
        )

    // Rules that may follow an adjective ending: schnelleren → schneller → schnell.
    private val AFTER_DECLINED =
        SuffixRules(
            RULES.rules.filter { rule ->
                rule.reason !in setOf(DECLINED, PLURAL_CASE, CONJUGATED)
            },
        )

    /** Dictionary-form candidates for [surface], the surface itself (in each case form) first. */
    fun deinflect(surface: String): List<Candidate> {
        if (surface.isBlank()) return emptyList()
        val out = CandidateSet()
        for (form in CaseForms.spellings(surface.replace(oldChar = '’', newChar = '\''))) {
            out.add(term = form, pos = 0L, reasons = emptyList())
            val words = form.split(' ')
            if (words.size == 1) {
                wordForms(form).forEach { out.add(term = it.term, pos = it.pos, reasons = it.reasons) }
            } else {
                // Phrases inflect their first word ("ging nach Hause" is listed as is).
                val rest = form.substringAfter(' ')
                wordForms(words.first())
                    .filter { it.pos == PosMask.VERB }
                    .forEach { out.add(term = "${it.term} $rest", pos = 0L, reasons = it.reasons) }
            }
        }
        return out.toList()
    }

    private fun wordForms(word: String): List<Form> {
        val results = mutableListOf<Form>()
        for (form in RULES.apply(word)) {
            results += Form(term = form.term, pos = form.pos, reasons = listOf(form.reason))
            if (form.reason == DECLINED) results += afterDeclension(form.term)
        }
        participles(word).forEach { results += Form(term = it, pos = PosMask.VERB, reasons = listOf(PARTICIPLE)) }
        zuInfinitive(word)?.let { results += Form(term = it, pos = PosMask.VERB, reasons = listOf("zu-infinitive")) }
        return results
    }

    // A declined form's comparative, superlative or participle: schnelleren, schnellsten, gemachte.
    private fun afterDeclension(stem: String): List<Form> {
        val degrees =
            AFTER_DECLINED.apply(stem).map { form ->
                Form(term = form.term, pos = form.pos, reasons = listOf(form.reason, DECLINED))
            }
        val participles =
            participles(stem).map { term ->
                Form(term = term, pos = PosMask.VERB, reasons = listOf(PARTICIPLE, DECLINED))
            }
        return degrees + participles
    }

    // gemacht → machen, gearbeitet → arbeiten, gefahren → fahren, aufgemacht → aufmachen (strong
    // participles with a vowel change, like mitgenommen, are dictionary entries of their own).
    private fun participles(word: String): List<String> {
        val lower = word.lowercase()
        val out = mutableListOf<String>()
        var at = lower.indexOf("ge")
        while (at in 0..MAX_SEPARABLE_PREFIX) {
            participleStem(word.substring(at + GE_LENGTH))?.let { out += word.take(at) + it }
            at = lower.indexOf("ge", at + 1)
        }
        return out
    }

    private fun participleStem(rest: String): String? {
        val infinitive =
            when {
                rest.endsWith("et") -> rest.dropLast(2) + "en"
                rest.endsWith("t") -> rest.dropLast(1) + "en"
                rest.endsWith("en") -> rest
                else -> null
            }
        return infinitive?.takeIf { it.length - 2 >= MIN_STEM }
    }

    // anzufangen → anfangen: "zu" between a separable prefix and the infinitive.
    private fun zuInfinitive(word: String): String? {
        val at = word.lowercase().indexOf("zu", startIndex = MIN_STEM)
        val isInfinitive = at in MIN_STEM..MAX_SEPARABLE_PREFIX && word.endsWith("n") && word.length - at - GE_LENGTH > MIN_STEM
        return if (isInfinitive) word.removeRange(startIndex = at, endIndex = at + GE_LENGTH) else null
    }
}
