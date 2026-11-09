package io.github.wilsonnet.droidsentenceminer.lang.de

import io.github.wilsonnet.droidsentenceminer.lang.Candidate
import io.github.wilsonnet.droidsentenceminer.lang.de.GermanLanguage.ADJ
import io.github.wilsonnet.droidsentenceminer.lang.de.GermanLanguage.ADV
import io.github.wilsonnet.droidsentenceminer.lang.de.GermanLanguage.NOUN
import io.github.wilsonnet.droidsentenceminer.lang.de.GermanLanguage.VERB

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
    private const val PAST = "past"
    private const val PARTICIPLE = "past participle"
    private const val PLURAL_CASE = "plural/case"

    private data class Rule(val suffix: String, val replacement: String, val pos: Long, val reason: String)

    // One undone inflection chain: the dictionary form, its part of speech and the reasons.
    private data class Form(val term: String, val pos: Long, val reasons: List<String>)

    private val RULES: List<Rule> =
        rules(pos = VERB, reason = CONJUGATED, spec = "e>en st>en est>en t>en et>en le>eln re>ern st>n t>n") +
            rules(pos = VERB, reason = PAST, spec = "te>en test>en ten>en tet>en ete>en etest>en eten>en etet>en te>n test>n ten>n tet>n") +
            rules(pos = VERB, reason = "present participle", spec = "end>en nd>n") +
            rules(pos = NOUN, reason = PLURAL_CASE, spec = "e> en> n> er> s> es> ns> ens> nen>") +
            rules(pos = ADJ, reason = DECLINED, spec = "e> en> em> er> es>") +
            rules(pos = ADJ, reason = "comparative", spec = "er>") +
            rules(pos = ADJ, reason = "superlative", spec = "st> est>")

    // Rules that may follow an adjective ending: schnelleren → schneller → schnell.
    private val AFTER_DECLINED = RULES.filter { it.reason !in setOf(DECLINED, PLURAL_CASE, CONJUGATED) }

    // "suffix>replacement" pairs, one part of speech and reason per group.
    private fun rules(pos: Long, reason: String, spec: String): List<Rule> {
        val pairs = spec.split(' ')
        return pairs.map { pair ->
            Rule(suffix = pair.substringBefore('>'), replacement = pair.substringAfter('>'), pos = pos, reason = reason)
        }
    }

    /** Dictionary-form candidates for [surface], the surface itself (in each case form) first. */
    fun deinflect(surface: String): List<Candidate> {
        if (surface.isBlank()) return emptyList()
        val out = LinkedHashMap<Pair<String, Long>, Candidate>()
        for (form in caseForms(surface.replace(oldChar = '’', newChar = '\''))) {
            add(out = out, form = Form(term = form, pos = 0L, reasons = emptyList()))
            val words = form.split(' ')
            if (words.size == 1) {
                wordForms(form).forEach { add(out = out, form = it) }
            } else {
                // Phrases inflect their first word ("ging nach Hause" is listed as is).
                val rest = form.substringAfter(' ')
                wordForms(words.first())
                    .filter { it.pos == VERB }
                    .forEach { add(out = out, form = Form(term = "${it.term} $rest", pos = 0L, reasons = it.reasons)) }
            }
        }
        return out.values.toList()
    }

    private fun add(out: MutableMap<Pair<String, Long>, Candidate>, form: Form) {
        if (form.term.isEmpty()) return
        val candidate =
            Candidate(
                term = form.term,
                conditions = form.pos,
                reasons = ArrayDeque(form.reasons),
                hasAppliedRule = form.reasons.isNotEmpty(),
            )
        out.putIfAbsent(form.term to form.pos, candidate)
    }

    // As written, first letter lowered (sentence start), all lowered, and Capitalized (ALL-CAPS text).
    private fun caseForms(text: String): Set<String> {
        val capitalized = text.lowercase().replaceFirstChar { it.uppercaseChar() }
        return linkedSetOf(text, text.replaceFirstChar { it.lowercaseChar() }, text.lowercase(), capitalized)
    }

    private fun wordForms(word: String): List<Form> {
        val results = mutableListOf<Form>()
        for (rule in RULES) {
            val next = applyRule(rule = rule, word = word) ?: continue
            results += Form(term = next, pos = rule.pos, reasons = listOf(rule.reason))
            if (rule.reason == DECLINED) results += afterDeclension(next)
        }
        participles(word).forEach { results += Form(term = it, pos = VERB, reasons = listOf(PARTICIPLE)) }
        zuInfinitive(word)?.let { results += Form(term = it, pos = VERB, reasons = listOf("zu-infinitive")) }
        return results
    }

    // A declined form's comparative, superlative or participle: schnelleren, schnellsten, gemachte.
    private fun afterDeclension(stem: String): List<Form> {
        val degrees =
            AFTER_DECLINED.mapNotNull { rule ->
                applyRule(rule = rule, word = stem)?.let { term ->
                    Form(term = term, pos = rule.pos, reasons = listOf(rule.reason, DECLINED))
                }
            }
        val participles =
            participles(stem).map { term ->
                Form(term = term, pos = VERB, reasons = listOf(PARTICIPLE, DECLINED))
            }
        return degrees + participles
    }

    private fun applyRule(rule: Rule, word: String): String? {
        val stem = word.dropLast(rule.suffix.length)
        val hasLetter = stem.any { it.isLetter() }
        val isApplicable = word.endsWith(rule.suffix, ignoreCase = true) && stem.length >= MIN_STEM && hasLetter
        return if (isApplicable) stem + rule.replacement else null
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

    /** A dictionary row's Yomitan `rules` ("n", "v", "adj", "adv") as part-of-speech bits. */
    @Suppress("FunctionSignature") // Multi-line body: ktlint's function-signature and multiline-expression-wrapping disagree (dsm-lint).
    fun ruleMask(rules: String): Long =
        rules.split(' ', ',').fold(0L) { mask, rule ->
            val bit =
                when (rule.trim()) {
                    "n", "noun", "name" -> NOUN
                    "v", "verb", "vt", "vi" -> VERB
                    "adj", "adjective" -> ADJ
                    "adv", "adverb" -> ADV
                    else -> 0L
                }
            mask or bit
        }
}
