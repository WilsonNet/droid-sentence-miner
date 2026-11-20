package io.github.wilsonnet.droidsentenceminer.lang.fr

import io.github.wilsonnet.droidsentenceminer.lang.Candidate
import io.github.wilsonnet.droidsentenceminer.lang.kit.CandidateSet
import io.github.wilsonnet.droidsentenceminer.lang.kit.CaseForms
import io.github.wilsonnet.droidsentenceminer.lang.kit.PosMask
import io.github.wilsonnet.droidsentenceminer.lang.kit.SuffixRules

/**
 * French deinflection. Elided words are stripped first (l'homme → homme, qu'il → il, j'aime →
 * aime), then a fallback for regular endings: the Wiktionary dictionaries (wty) list inflected
 * forms as entries of their own ("mangeait" → imperfect of manger, "chevaux" → plural of cheval),
 * so these rules only cover -er/-ir/-re conjugations, participles, noun plurals and adjective
 * agreement for words wty doesn't list. Pronominal verbs and unaccented capitals: [FrenchForms].
 */
internal object FrenchDeinflector {
    private const val ELIDED = "elision"
    private const val PRONOMINAL = "pronominal"

    // Longest first: "jusqu'" before "qu'".
    private val ELISIONS =
        listOf("jusqu'", "lorsqu'", "puisqu'", "quoiqu'", "presqu'", "qu'", "l'", "d'", "j'", "m'", "t'", "s'", "n'", "c'")

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

    private val RULES =
        SuffixRules.combine(
            SuffixRules.group(pos = PosMask.VERB, reason = "conjugated", spec = PRESENT),
            SuffixRules.group(pos = PosMask.VERB, reason = "imperfect", spec = IMPERFECT),
            SuffixRules.group(pos = PosMask.VERB, reason = "future/conditional", spec = FUTURE),
            SuffixRules.group(
                pos = PosMask.VERB,
                reason = "simple past",
                spec = "ai>er as>er a>er âmes>er âtes>er èrent>er ea>er ît>ir irent>ir",
            ),
            SuffixRules.group(
                pos = PosMask.VERB,
                reason = "past participle",
                spec = "é>er ée>er és>er ées>er i>ir ie>ir ies>ir u>re ue>re us>re ues>re",
            ),
            SuffixRules.group(pos = PosMask.VERB, reason = "present participle", spec = "ant>er eant>er çant>cer issant>ir ant>re"),
            SuffixRules.group(pos = PosMask.NOUN, reason = "plural", spec = "s> x> aux>al"),
            SuffixRules.group(pos = PosMask.ADJ, reason = "agreement", spec = AGREEMENT),
        )
    private val VERB_RULES = RULES.only(PosMask.VERB)

    /** Dictionary-form candidates for [surface], the surface itself (in each case form) first. */
    fun deinflect(surface: String): List<Candidate> {
        if (surface.isBlank()) return emptyList()
        val out = CandidateSet()
        val text = surface.replace(oldChar = '’', newChar = '\'')
        for (form in CaseForms.spellings(text)) {
            addWithRules(out = out, form = form, reasons = emptyList())
            val bare = withoutElision(form)
            if (bare != null) addWithRules(out = out, form = bare, reasons = listOf(ELIDED))
            for ((verb, isReflexive) in FrenchForms.pronominal(form)) {
                addPronominal(out = out, verb = verb, isReflexive = isReflexive)
            }
        }
        FrenchForms.accentVariants(text).forEach { out.add(term = it, pos = 0L, reasons = emptyList()) }
        return out.toList()
    }

    // "se lève" / "me lève" / "s'appelle" → "se lever" / "s'appeler": the verb's rules, with the reflexive pronoun.
    private fun addPronominal(out: CandidateSet, verb: String, isReflexive: Boolean) {
        if (!isReflexive) out.add(term = FrenchForms.withReflexive(verb), pos = PosMask.VERB, reasons = listOf(PRONOMINAL))
        for (form in VERB_RULES.apply(verb)) {
            // No reason listed: ranking puts fewer reasons first, and "m'appelle" means s'appeler more than appeler.
            FrenchForms.stemVariants(form.term).forEach {
                out.add(term = FrenchForms.withReflexive(it), pos = PosMask.VERB, reasons = emptyList())
            }
        }
    }

    // The form itself, then its inflections undone (phrases: their first word only, "allait voir" → aller voir).
    private fun addWithRules(out: CandidateSet, form: String, reasons: List<String>) {
        out.add(term = form, pos = 0L, reasons = reasons)
        val first = form.substringBefore(' ')
        val rest = form.substring(first.length)
        val isPhrase = rest.isNotEmpty()
        val rules = if (isPhrase) VERB_RULES else RULES
        for (lemma in rules.apply(first)) {
            val terms = if (lemma.pos == PosMask.VERB) FrenchForms.stemVariants(lemma.term) else listOf(lemma.term)
            val pos = if (isPhrase) 0L else lemma.pos
            for (term in terms) {
                out.add(term = term + rest, pos = pos, reasons = reasons + lemma.reason)
            }
        }
    }

    // l'homme → homme; null when the word has no elided prefix or nothing follows it.
    private fun withoutElision(form: String): String? {
        val prefix = ELISIONS.firstOrNull { form.startsWith(prefix = it, ignoreCase = true) } ?: return null
        val rest = form.substring(prefix.length)
        return rest.takeIf { it.isNotEmpty() && it.first().isLetter() }
    }
}
