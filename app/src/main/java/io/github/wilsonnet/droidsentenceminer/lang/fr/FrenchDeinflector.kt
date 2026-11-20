package io.github.wilsonnet.droidsentenceminer.lang.fr

import io.github.wilsonnet.droidsentenceminer.lang.Candidate
import io.github.wilsonnet.droidsentenceminer.lang.fr.FrenchLanguage.ADJ
import io.github.wilsonnet.droidsentenceminer.lang.fr.FrenchLanguage.ADV
import io.github.wilsonnet.droidsentenceminer.lang.fr.FrenchLanguage.NOUN
import io.github.wilsonnet.droidsentenceminer.lang.fr.FrenchLanguage.VERB

/**
 * French deinflection. Elided words are stripped first (l'homme → homme, qu'il → il, j'aime →
 * aime), then a fallback for regular endings: the Wiktionary dictionaries (wty) list inflected
 * forms as entries of their own ("mangeait" → imperfect of manger, "chevaux" → plural of cheval),
 * so these rules only cover -er/-ir/-re conjugations, participles, noun plurals and adjective
 * agreement for words wty doesn't list.
 */
internal object FrenchDeinflector {
    private const val MIN_STEM = 2
    private const val CONJUGATED = "conjugated"
    private const val PARTICIPLE = "past participle"
    private const val ELIDED = "elision"
    private const val PRONOMINAL = "pronominal"

    // Longest first: "jusqu'" before "qu'".
    private val ELISIONS =
        listOf("jusqu'", "lorsqu'", "puisqu'", "quoiqu'", "presqu'", "qu'", "l'", "d'", "j'", "m'", "t'", "s'", "n'", "c'")

    private data class Rule(val suffix: String, val replacement: String, val pos: Long, val reason: String)

    private const val PRESENT =
        "e>er es>er ent>er ons>er ez>er eons>er çons>cer elle>eler elles>eler ellent>eler ette>eter ettes>eter ettent>eter " +
            "is>ir it>ir issons>ir issez>ir issent>ir isse>ir " +
            "s>re ds>dre d>dre t>re ons>re ez>re ent>re"
    private const val IMPERFECT =
        "ais>er ait>er ions>er iez>er aient>er eais>er eait>er eaient>er çais>cer çait>cer çaient>cer " +
            "issais>ir issait>ir issions>ir issiez>ir issaient>ir ais>re ait>re aient>re"
    private const val FUTURE =
        "erai>er eras>er era>er erons>er erez>er eront>er erais>er erait>er erions>er eriez>er eraient>er " +
            "irai>ir iras>ir ira>ir irons>ir irez>ir iront>ir irais>ir irait>ir iraient>ir " +
            "rai>re ras>re ra>re rons>re rez>re ront>re rais>re rait>re raient>re"
    private const val AGREEMENT =
        "e> s> es> x> euse>eux euses>eux ive>if ives>if elle>el elles>el enne>en ennes>en " +
            "ette>et ettes>et ère>er ères>er aux>al ale>al ales>al"

    private val RULES: List<Rule> =
        listOf(
            rules(pos = VERB, reason = CONJUGATED, spec = PRESENT),
            rules(pos = VERB, reason = "imperfect", spec = IMPERFECT),
            rules(pos = VERB, reason = "future/conditional", spec = FUTURE),
            rules(pos = VERB, reason = "simple past", spec = "ai>er as>er a>er âmes>er âtes>er èrent>er ea>er ît>ir irent>ir"),
            rules(pos = VERB, reason = PARTICIPLE, spec = "é>er ée>er és>er ées>er i>ir ie>ir ies>ir u>re ue>re us>re ues>re"),
            rules(pos = VERB, reason = "present participle", spec = "ant>er eant>er çant>cer issant>ir ant>re"),
            rules(pos = NOUN, reason = "plural", spec = "s> x> aux>al"),
            rules(pos = ADJ, reason = "agreement", spec = AGREEMENT),
        ).flatten()

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
        val text = surface.replace(oldChar = '’', newChar = '\'')
        for (form in caseForms(text)) {
            addWithRules(out = out, form = form, reasons = emptyList())
            val bare = withoutElision(form)
            if (bare != null) addWithRules(out = out, form = bare, reasons = listOf(ELIDED))
            for ((verb, isReflexive) in FrenchForms.pronominal(form)) {
                addPronominal(out = out, verb = verb, isReflexive = isReflexive)
            }
        }
        FrenchForms.accentVariants(text).forEach { add(out = out, term = it, pos = 0L, reasons = emptyList()) }
        return out.values.toList()
    }

    // "se lève" / "me lève" / "s'appelle" → "se lever" / "s'appeler": the verb's rules, with the reflexive pronoun.
    private fun addPronominal(out: MutableMap<Pair<String, Long>, Candidate>, verb: String, isReflexive: Boolean) {
        if (!isReflexive) add(out = out, term = FrenchForms.withReflexive(verb), pos = VERB, reasons = listOf(PRONOMINAL))
        for (rule in RULES.filter { it.pos == VERB }) {
            val lemma = applyRule(rule = rule, word = verb) ?: continue
            // No reason listed: ranking puts fewer reasons first, and "m'appelle" means s'appeler more than appeler.
            FrenchForms.stemVariants(lemma).forEach {
                add(out = out, term = FrenchForms.withReflexive(it), pos = VERB, reasons = emptyList())
            }
        }
    }

    // The form itself, then its inflections undone (phrases: their first word only, "allait voir" → aller voir).
    private fun addWithRules(out: MutableMap<Pair<String, Long>, Candidate>, form: String, reasons: List<String>) {
        add(out = out, term = form, pos = 0L, reasons = reasons)
        val first = form.substringBefore(' ')
        val rest = form.substring(first.length)
        val isPhrase = rest.isNotEmpty()
        // Phrases only conjugate their first word.
        val rules = if (isPhrase) RULES.filter { it.pos == VERB } else RULES
        for (rule in rules) {
            val lemma = applyRule(rule = rule, word = first) ?: continue
            val terms = if (rule.pos == VERB) FrenchForms.stemVariants(lemma) else listOf(lemma)
            val pos = if (isPhrase) 0L else rule.pos
            for (term in terms) {
                add(out = out, term = term + rest, pos = pos, reasons = reasons + rule.reason)
            }
        }
    }

    private fun add(out: MutableMap<Pair<String, Long>, Candidate>, term: String, pos: Long, reasons: List<String>) {
        if (term.isEmpty()) return
        val candidate =
            Candidate(term = term, conditions = pos, reasons = ArrayDeque(reasons), hasAppliedRule = reasons.isNotEmpty())
        out.putIfAbsent(term to pos, candidate)
    }

    // l'homme → homme; null when the word has no elided prefix or nothing follows it.
    private fun withoutElision(form: String): String? {
        val prefix = ELISIONS.firstOrNull { form.startsWith(prefix = it, ignoreCase = true) } ?: return null
        val rest = form.substring(prefix.length)
        return rest.takeIf { it.isNotEmpty() && it.first().isLetter() }
    }

    // As written, first letter lowered (sentence start), all lowered, and Capitalized (ALL-CAPS text).
    private fun caseForms(text: String): Set<String> {
        val capitalized = text.lowercase().replaceFirstChar { it.uppercaseChar() }
        return linkedSetOf(text, text.replaceFirstChar { it.lowercaseChar() }, text.lowercase(), capitalized)
    }

    private fun applyRule(rule: Rule, word: String): String? {
        val stem = word.dropLast(rule.suffix.length)
        val hasLetter = stem.any { it.isLetter() }
        val isApplicable = word.endsWith(rule.suffix, ignoreCase = true) && stem.length >= MIN_STEM && hasLetter
        return if (isApplicable) stem + rule.replacement else null
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
