package io.github.wilsonnet.droidsentenceminer.lang.kit

/**
 * Parts of speech as condition bits, parsed from the Yomitan `rules` column the way Wiktionary
 * (wty) dictionaries fill it ("n", "v", "adj", "adv"). For languages whose deinflection only needs
 * to know the part of speech a suffix rule produces.
 */
object PosMask {
    /** Nouns and names (rule "n"). */
    const val NOUN = 1L

    /** Verbs (rule "v"). */
    const val VERB = 2L

    /** Adjectives (rule "adj"). */
    const val ADJ = 4L

    /** Adverbs (rule "adv"). */
    const val ADV = 8L

    /** A row's `rules` string as part-of-speech bits (0 when it names none). */
    fun parse(rules: String): Long {
        var mask = 0L
        for (rule in rules.split(' ', ',')) {
            val bit =
                when (rule.trim()) {
                    "n", "noun", "name" -> NOUN
                    "v", "verb", "vt", "vi" -> VERB
                    "adj", "adjective" -> ADJ
                    "adv", "adverb" -> ADV
                    else -> 0L
                }
            mask = mask or bit
        }
        return mask
    }

    /** Whether a row with [mask] fits a candidate with [conditions]; unknown parts of speech fit anything. */
    fun matches(conditions: Long, mask: Long): Boolean = conditions == 0L || mask == 0L || mask and conditions != 0L
}
