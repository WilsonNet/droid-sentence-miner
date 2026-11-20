package io.github.wilsonnet.droidsentenceminer.lang.de

/**
 * German word formation the dictionary can't list: separable verbs whose particle the sentence
 * moved to the end of the clause ("Ich stehe früh auf" → aufstehen) and compound nouns
 * ("Fensterputzmittel" → Fensterputz + Mittel). Pure Kotlin, unit tested.
 */
internal object GermanCompounds {
    private const val MIN_PART = 3
    private const val MIN_COMPOUND = 6
    private const val MAX_SPLITS = 30

    // Particles of separable verbs (aufstehen, ankommen, zurückgeben …), as they stand alone at a clause end.
    private val PARTICLES =
        setOf(
            "ab", "an", "auf", "aus", "bei", "dar", "durch", "ein", "entgegen", "fest", "fort", "her", "heraus",
            "herein", "herum", "hin", "hinaus", "hinein", "los", "mit", "nach", "nieder", "über", "um", "unter",
            "vor", "vorbei", "weg", "weiter", "wieder", "zu", "zurück", "zusammen",
        )

    // Linking letters between compound parts: Arbeit-s-platz, Hund-e-hütte, Blume-n-topf, Kind-er-garten.
    private val LINKERS = listOf("s", "es", "n", "en", "e", "er", "ens")
    private val CLAUSE_END = Regex("""[,.;:!?…»«"„“()]""")
    private val TOKEN = Regex("""\p{L}+""")

    /**
     * "stehe" in "Ich stehe jeden Tag früh auf." → ["aufstehe"]: the particle that ends the clause
     * after [word], prefixed to it (the deinflector and dictionary decide whether it is a verb).
     */
    fun clauseForms(word: String, sentence: String): List<String> {
        val at = wordIndex(word = word, sentence = sentence)
        val isWord = at >= 0 && word.all { it.isLetter() }
        val isVerbLike = isWord && (word.first().isLowerCase() || isClauseStart(sentence = sentence, at = at))
        if (!isVerbLike) return emptyList()
        val clause = sentence.substring(at + word.length).split(CLAUSE_END).first()
        val last = TOKEN.findAll(clause).lastOrNull()
        val particle = last?.run { value.lowercase() }
        return if (particle != null && particle in PARTICLES) listOf(particle + word.lowercase()) else emptyList()
    }

    /**
     * Modifier/head cuts of [word], the longest head first: "Arbeitsplatz" → (Arbeits, Platz),
     * (Arbeit, Platz), …; the modifier also as a verb ("Fahr" → fahren in Fahrrad).
     */
    fun compoundSplits(word: String): List<Pair<String, String>> {
        val isCandidate = word.length >= MIN_COMPOUND && word.all { it.isLetter() }
        if (!isCandidate) return emptyList()
        val splits = ArrayList<Pair<String, String>>()
        for (cut in MIN_PART..word.length - MIN_PART) {
            val head = word.substring(cut).replaceFirstChar { it.uppercaseChar() }
            for (modifier in modifiers(word.substring(startIndex = 0, endIndex = cut))) {
                splits += modifier to head
            }
        }
        return splits.distinct().take(MAX_SPLITS)
    }

    // The modifier as written, without a linking letter, and as a verb stem's infinitive.
    private fun modifiers(part: String): List<String> {
        val linkers = LINKERS.filter { linker -> part.endsWith(linker) && part.length - linker.length >= MIN_PART }
        val unlinked = linkers.map { linker -> part.dropLast(linker.length) }
        val verb = part.lowercase() + "en"
        return (listOf(part) + unlinked + verb).distinct()
    }

    // Where [word] stands in [sentence] as a whole word, -1 if it doesn't.
    private fun wordIndex(word: String, sentence: String): Int {
        val match = Regex("""(?<!\p{L})""" + Regex.escape(word) + """(?!\p{L})""").find(sentence)
        return match?.run { range.first } ?: -1
    }

    // Imperatives and questions start with the verb: "Steh auf!", "Kommst du mit?".
    private fun isClauseStart(sentence: String, at: Int): Boolean = sentence.take(at).none { it.isLetter() }
}
