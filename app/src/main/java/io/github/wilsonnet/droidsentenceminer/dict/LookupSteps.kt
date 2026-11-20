package io.github.wilsonnet.droidsentenceminer.dict

import io.github.wilsonnet.droidsentenceminer.lang.LookupFeature

/**
 * A step run on a lookup's results, after the dictionary query: what a [LookupFeature] adds to the
 * engine. Steps get [exact], an exact (non-deinflected) lookup of another word, so they can bring in
 * more entries (a lemma, a compound's parts) without knowing the database.
 */
fun interface LookupStep {
    /** [details] with this step applied. */
    suspend fun apply(
        details: TermLookup.LookupDetails,
        exact: suspend (String) -> TermLookup.LookupDetails,
    ): TermLookup.LookupDetails
}

/** The [LookupStep]s behind each [LookupFeature], in the order they run. */
object LookupSteps {
    // Form-of entries pointing at form-of entries: allées → allé → aller.
    private const val FORM_OF_DEPTH = 2

    /** The post-lookup steps [features] turn on, in run order. */
    fun forFeatures(features: Set<LookupFeature>): List<LookupStep> =
        listOfNotNull(FormOfStep(depth = FORM_OF_DEPTH).takeIf { LookupFeature.FORM_OF_ENTRIES in features })

    /**
     * [LookupFeature.FORM_OF_ENTRIES]: pointer entries replaced by their lemmas' entries
     * ([DictionaryDeinflections]), [depth] levels deep.
     */
    class FormOfStep(private val depth: Int) : LookupStep {
        override suspend fun apply(
            details: TermLookup.LookupDetails,
            exact: suspend (String) -> TermLookup.LookupDetails,
        ): TermLookup.LookupDetails = follow(details = details, exact = exact, depth = depth)

        private suspend fun follow(
            details: TermLookup.LookupDetails,
            exact: suspend (String) -> TermLookup.LookupDetails,
            depth: Int,
        ): TermLookup.LookupDetails {
            val pointers = details.terms.flatMap { DictionaryDeinflections.pointers(it) }
            val lemmas = pointers.map { it.lemma }.toSet()
            if (depth == 0 || lemmas.isEmpty()) return details
            val found =
                lemmas.associateWith { lemma ->
                    follow(details = exact(lemma), exact = exact, depth = depth - 1)
                }
            val lemmaTerms =
                found.mapValues { (_, lemma) ->
                    lemma.terms.filter { DictionaryDeinflections.pointers(it).isEmpty() }
                }
            val terms = DictionaryDeinflections.replace(terms = details.terms, lemmas = lemmaTerms)
            // The header's inflection is the first entry's: "concours" (a noun) must not show concourir's.
            return details.copy(
                terms = terms,
                frequencies = (details.frequencies + found.values.flatMap { it.frequencies }).distinct(),
                deinflection = terms.firstOrNull()?.deinflectionReasons.orEmpty(),
            )
        }
    }
}
