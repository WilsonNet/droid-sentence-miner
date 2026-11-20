package io.github.wilsonnet.droidsentenceminer.dict

import io.github.wilsonnet.droidsentenceminer.lang.LanguageModule
import io.github.wilsonnet.droidsentenceminer.lang.LookupFeature

/**
 * A step run on a lookup's results, after the dictionary query: what a [LookupFeature] adds to the
 * engine. Steps get a [Context] with the looked-up word, its sentence and two lookups of other
 * words, so they can bring in more entries (a lemma, a compound's parts) without knowing the database.
 */
fun interface LookupStep {
    /** [details] with this step applied. */
    suspend fun apply(details: TermLookup.LookupDetails, context: Context): TermLookup.LookupDetails

    /**
     * What a step may use.
     *
     * @property word the looked-up surface.
     * @property sentence the text around it ("" when the caller has none).
     * @property language the study language (its hooks: clause forms, compound splits).
     * @property exact looks up another word as written.
     * @property deinflected looks up another word through the language's deinflection.
     */
    data class Context(
        val word: String,
        val sentence: String,
        val language: LanguageModule,
        val exact: suspend (String) -> TermLookup.LookupDetails,
        val deinflected: suspend (String) -> TermLookup.LookupDetails,
    )
}

/** The [LookupStep]s behind each [LookupFeature], in the order they run. */
object LookupSteps {
    // Form-of entries pointing at form-of entries: allées → allé → aller.
    private const val FORM_OF_DEPTH = 2

    // Steps that add entries run first, so FORM_OF_ENTRIES also resolves what they brought in.
    private val ORDER =
        listOf(
            LookupFeature.SEPARABLE_VERBS to SeparableVerbStep,
            LookupFeature.SPLIT_COMPOUNDS to CompoundStep,
            LookupFeature.FORM_OF_ENTRIES to FormOfStep(depth = FORM_OF_DEPTH),
        )

    /** The post-lookup steps [features] turn on, in run order. */
    fun forFeatures(features: Set<LookupFeature>): List<LookupStep> {
        val steps = ORDER.filter { (feature, _) -> feature in features }
        return steps.map { (_, step) -> step }
    }

    /**
     * [LookupFeature.FORM_OF_ENTRIES]: pointer entries replaced by their lemmas' entries
     * ([DictionaryDeinflections]), [depth] levels deep.
     */
    class FormOfStep(private val depth: Int) : LookupStep {
        override suspend fun apply(
            details: TermLookup.LookupDetails,
            context: LookupStep.Context,
        ): TermLookup.LookupDetails = follow(details = details, exact = context.exact, depth = depth)

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

    /**
     * [LookupFeature.SEPARABLE_VERBS]: the verb joined with its clause-final particle
     * ([LanguageModule.clauseForms]), first, when the dictionary knows it.
     */
    object SeparableVerbStep : LookupStep {
        private const val REASON = "separable verb"

        override suspend fun apply(
            details: TermLookup.LookupDetails,
            context: LookupStep.Context,
        ): TermLookup.LookupDetails {
            val forms = context.language.clauseForms(word = context.word, sentence = context.sentence)
            val joined =
                forms.flatMap { form ->
                    context.deinflected(form).terms.map { term ->
                        term.copy(deinflectionReasons = term.deinflectionReasons + REASON)
                    }
                }
            return if (joined.isEmpty()) details else merged(details = details, first = joined, context = context)
        }
    }

    /**
     * [LookupFeature.SPLIT_COMPOUNDS]: a word without entries shown as its head and modifier
     * ([LanguageModule.compoundSplits]), the first cut whose two parts both have entries.
     */
    object CompoundStep : LookupStep {
        override suspend fun apply(
            details: TermLookup.LookupDetails,
            context: LookupStep.Context,
        ): TermLookup.LookupDetails {
            val parts = if (details.terms.isEmpty()) firstSplit(context) else emptyList()
            return if (parts.isEmpty()) details else merged(details = details, first = parts, context = context)
        }

        // Head entries then modifier entries of the first cut where both parts are known; empty if none.
        private suspend fun firstSplit(context: LookupStep.Context): List<TermResult> {
            for ((modifier, head) in context.language.compoundSplits(context.word)) {
                val heads = context.deinflected(head).terms
                val modifiers = if (heads.isEmpty()) emptyList() else context.deinflected(modifier).terms
                if (modifiers.isNotEmpty()) {
                    val reason = "compound: ${modifiers.first().expression} + ${heads.first().expression}"
                    return (heads + modifiers).map { it.copy(deinflectionReasons = it.deinflectionReasons + reason) }
                }
            }
            return emptyList()
        }
    }

    /** [first] ahead of [details]' entries, with the meta (IPA, frequencies) of their expressions. */
    internal suspend fun merged(
        details: TermLookup.LookupDetails,
        first: List<TermResult>,
        context: LookupStep.Context,
    ): TermLookup.LookupDetails {
        val expressions = first.map { it.expression }.toSet()
        val meta = expressions.flatMap { context.exact(it).frequencies }
        val terms =
            (first + details.terms).distinctBy { term ->
                "${term.dictId}:${term.expression}:${term.reading}:${term.glossaryJson}"
            }
        return details.copy(
            terms = terms,
            frequencies = (meta + details.frequencies).distinct(),
            deinflection = terms.firstOrNull()?.deinflectionReasons.orEmpty(),
        )
    }
}
