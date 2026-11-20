package io.github.wilsonnet.droidsentenceminer.dict

/**
 * Yomitan's "deinflection definitions": Wiktionary dictionaries (wty) list inflected forms as entries
 * whose whole glossary is `["manger", ["third-person singular imperfect indicative"]]` — a pointer
 * to the lemma, not a definition. Like Yomitan, a lookup follows the pointer and shows the lemma's
 * entry with that inflection instead, so "suis" finds être (no suffix rule gets there) and a card
 * mined from "mangeait" is a card for manger. Pure Kotlin, unit tested.
 */
object DictionaryDeinflections {
    /**
     * A lemma an entry points to.
     *
     * @property lemma the dictionary form ("manger").
     * @property forms the inflections that lead from it to the looked-up word.
     */
    data class Pointer(val lemma: String, val forms: List<String>)

    /** The lemmas [term] only points to; empty for an entry with definitions of its own. */
    fun pointers(term: TermResult): List<Pointer> {
        val entries = GlossaryParser.parse(term.glossaryJson)
        val isPointer = entries.isNotEmpty() && entries.all { it.isDeinflection }
        val pointers =
            entries.mapNotNull { entry ->
                val lemma = (entry.content.firstOrNull() as? GlossaryContent.Text)?.text
                lemma?.let { Pointer(lemma = it, forms = entry.deinflectionForms) }
            }
        return if (isPointer) pointers else emptyList()
    }

    /**
     * [terms] with every pointer entry replaced, where it stands, by the entries of its lemmas
     * ([lemmas], by lemma) carrying the pointer's inflections; a pointer whose lemma has no entry
     * stays. An entry already listed (the suffix rules found manger too) is not repeated.
     */
    fun replace(terms: List<TermResult>, lemmas: Map<String, List<TermResult>>): List<TermResult> {
        val seen = HashSet<String>()
        val out = ArrayList<TermResult>(terms.size)
        for (term in byAffinity(terms)) {
            val expanded = expand(term = term, lemmas = lemmas)
            expanded.filter { seen.add(key(it)) }.forEach { out += it }
        }
        return out
    }

    private fun expand(term: TermResult, lemmas: Map<String, List<TermResult>>): List<TermResult> {
        val resolved =
            pointers(term).flatMap { pointer ->
                lemmas[pointer.lemma].orEmpty().map { lemma ->
                    // A chain keeps every step: allées → plural feminine of allé → past participle of aller.
                    lemma.copy(deinflectionReasons = term.deinflectionReasons + pointer.forms + lemma.deinflectionReasons)
                }
            }
        return resolved.ifEmpty { listOf(term) }
    }

    // Pointer rows of one headword, the one whose lemma keeps its pronoun first: "s'appelle" points at both
    // s'appeler and appeler, and s'appeler is the reading. Entries with definitions stay ahead of pointers.
    private fun byAffinity(terms: List<TermResult>): List<TermResult> {
        val groupStart = HashMap<String, Int>()
        terms.forEachIndexed { index, term -> groupStart.putIfAbsent("${term.dictId}:${term.expression}", index) }
        val byGroup = compareBy<TermResult> { term -> groupStart.getValue("${term.dictId}:${term.expression}") }
        return terms.sortedWith(byGroup.thenByDescending(::affinity))
    }

    // Definitions first (2), then pointers whose lemma keeps a leading pronoun or particle like the word's
    // ("s'appelle", "m'appelle" → s'appeler: 1), then the rest in dictionary order (suis → être, suivre: 0).
    private fun affinity(term: TermResult): Int {
        val lemmas = pointers(term).map { it.lemma }
        // m'appelle → s'appeler too: the clitic changes person, but the lemma keeps one.
        val isLeadKept = leadingWord(term.expression) != null && lemmas.any { leadingWord(it) != null }
        return when {
            lemmas.isEmpty() -> 2
            isLeadKept -> 1
            else -> 0
        }
    }

    // "s'" of "s'appelle", "se " of "se lave"; null for a single plain word.
    private fun leadingWord(text: String): String? {
        val end = text.indexOfFirst { it == '\'' || it == ' ' }
        return if (end > 0) text.take(end + 1) else null
    }

    private fun key(term: TermResult): String = "${term.dictId}:${term.expression}:${term.reading}:${term.sequence}"
}
