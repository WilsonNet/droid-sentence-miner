package io.github.wilsonnet.droidsentenceminer.lang.fr

/**
 * French surface variants for [FrenchDeinflector]: pronominal verbs ("je me lève" → se lever,
 * "s'appelle" → s'appeler) and the accents ALL-CAPS text leaves out ("ETE" → été, "ECOLE" →
 * école). Pure Kotlin, unit tested.
 */
internal object FrenchForms {
    private const val MAX_ACCENTED = 3
    private const val MAX_VARIANTS = 48
    private const val MIN_CAPS = 1
    private const val MAX_CAPS = 14

    // Reflexive pronouns before a verb: written out ("me lève") or elided ("m'appelle").
    private val REFLEXIVE_WORDS = listOf("se ", "me ", "te ")
    private val REFLEXIVE_ELIDED = listOf("s'", "m'", "t'")
    private val ACCENTS =
        mapOf('e' to "éèê", 'a' to "àâ", 'u' to "ùû", 'i' to "îï", 'o' to "ô", 'c' to "ç")

    /**
     * The verb after a reflexive pronoun in [form], paired with whether the pronoun already is the
     * dictionary's "se"/"s'" (then the form itself is looked up as is); empty when there is none.
     */
    fun pronominal(form: String): List<Pair<String, Boolean>> {
        val lower = form.lowercase()
        val word = REFLEXIVE_WORDS.firstOrNull { lower.startsWith(it) }
        val elided = REFLEXIVE_ELIDED.firstOrNull { lower.startsWith(it) }
        val prefix = word ?: elided
        val verb = prefix?.let { form.substring(it.length) }
        val isVerb = verb != null && verb.isNotEmpty() && verb.first().isLetter() && ' ' !in verb
        val isReflexive = prefix == "se " || prefix == "s'"
        return if (isVerb && verb != null) listOf(verb to isReflexive) else emptyList()
    }

    /**
     * An -er infinitive and its stem-changed spellings: the present of lever, répéter and acheter
     * writes è in the stem ("lève", "répète", "achète"), so "lèver" also gives lever and répéter.
     */
    fun stemVariants(infinitive: String): List<String> {
        val at = infinitive.lastIndexOf('è')
        val isStemChange = infinitive.endsWith("er") && at in 0 until infinitive.length - 2
        if (!isStemChange) return listOf(infinitive)
        val variants =
            listOf("e", "é").map { plain ->
                infinitive.replaceRange(startIndex = at, endIndex = at + 1, replacement = plain)
            }
        return listOf(infinitive) + variants
    }

    /** "se " + [verb], or "s'" before a vowel or mute h (s'appeler, s'habiller). */
    fun withReflexive(verb: String): String {
        val first = verb.firstOrNull()?.lowercaseChar()
        val isElided = first != null && first in "aeiouyhéèêàâîïôû"
        return if (isElided) "s'$verb" else "se $verb"
    }

    /**
     * Lower-case spellings of an unaccented ALL-CAPS word with up to [MAX_ACCENTED] accents put
     * back, fewest first ("ETE" → été, ète, …); empty for anything else.
     */
    fun accentVariants(text: String): List<String> {
        val isPlainCaps = text.all { c -> c.isLetter() && c.isUpperCase() && c.code < 'À'.code }
        val isAllCaps = text.length in MIN_CAPS..MAX_CAPS && isPlainCaps
        if (!isAllCaps) return emptyList()
        val lower = text.lowercase()
        val slots = lower.indices.filter { lower[it] in ACCENTS }
        val variants = LinkedHashSet<String>()
        var level = listOf(lower)
        repeat(MAX_ACCENTED) {
            level = level.flatMap { word -> accentOnce(word = word, slots = slots) }.distinct()
            for (variant in level) {
                if (variants.size < MAX_VARIANTS) variants += variant
            }
        }
        return variants.toList()
    }

    // Every spelling of [word] with one more of its plain [slots] accented.
    private fun accentOnce(word: String, slots: List<Int>): List<String> =
        slots.filter { word[it] in ACCENTS }.flatMap { at ->
            ACCENTS.getValue(word[at]).map { accented ->
                word.replaceRange(startIndex = at, endIndex = at + 1, replacement = accented.toString())
            }
        }
}
