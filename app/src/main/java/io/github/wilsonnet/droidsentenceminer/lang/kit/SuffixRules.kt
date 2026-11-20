package io.github.wilsonnet.droidsentenceminer.lang.kit

/**
 * A table of suffix rules ("parlons" → parler: suffix "ons" replaced by "er", a verb, "conjugated"),
 * written compactly as `"suffix>replacement"` pairs per part of speech and reason ([group]).
 */
class SuffixRules(
    /** Every rule, in the order they are tried. */
    val rules: List<Rule>,
) {
    /**
     * One rule.
     *
     * @property suffix what the inflected word ends with.
     * @property replacement what replaces it in the dictionary form.
     * @property pos the dictionary form's part of speech ([PosMask] bits).
     * @property reason the inflection's name, shown in the popup.
     * @property minStem letters that must remain before the suffix.
     */
    data class Rule(
        val suffix: String,
        val replacement: String,
        val pos: Long,
        val reason: String,
        val minStem: Int = DEFAULT_MIN_STEM,
    )

    /**
     * One rule applied.
     *
     * @property term the candidate dictionary form.
     * @property pos its part of speech ([PosMask] bits).
     * @property reason the rule's reason.
     */
    data class Form(val term: String, val pos: Long, val reason: String)

    /** Every rule's result for [word], in table order. */
    fun apply(word: String): List<Form> = rules.mapNotNull { rule -> applyRule(rule = rule, word = word) }

    /** Only the rules for part of speech [pos]. */
    fun only(pos: Long): SuffixRules = SuffixRules(rules.filter { it.pos == pos })

    /** Concatenation, in order. */
    operator fun plus(other: SuffixRules): SuffixRules = SuffixRules(rules + other.rules)

    /** Factories. */
    companion object {
        /** Letters that must remain before a suffix unless a rule says otherwise. */
        const val DEFAULT_MIN_STEM = 2

        /** Rules from `"suffix>replacement suffix>replacement"` ("e> en>" drop the suffix). */
        fun group(pos: Long, reason: String, spec: String, minStem: Int = DEFAULT_MIN_STEM): SuffixRules {
            val pairs = spec.trim().split(Regex("""\s+"""))
            val rules =
                pairs.map { pair ->
                    Rule(
                        suffix = pair.substringBefore('>'),
                        replacement = pair.substringAfter('>'),
                        pos = pos,
                        reason = reason,
                        minStem = minStem,
                    )
                }
            return SuffixRules(rules)
        }

        /** Several groups as one table. */
        fun combine(vararg groups: SuffixRules): SuffixRules = SuffixRules(groups.flatMap { it.rules })

        /** [rule] undone on [word], or null when it doesn't apply. */
        fun applyRule(rule: Rule, word: String): Form? {
            val stem = word.dropLast(rule.suffix.length)
            val hasLetter = stem.any { it.isLetter() }
            val isApplicable = word.endsWith(rule.suffix, ignoreCase = true) && stem.length >= rule.minStem && hasLetter
            return if (isApplicable) Form(term = stem + rule.replacement, pos = rule.pos, reason = rule.reason) else null
        }
    }
}
