package io.github.wilsonnet.droidsentenceminer.lang

/**
 * A dictionary-form candidate from a language's deinflector: [conditions] are that language's
 * part-of-speech bits (see [LanguageModule.ruleMask]), [reasons] the inflections undone.
 */
data class Candidate(
    val term: String,
    val conditions: Long,
    val reasons: ArrayDeque<String> = ArrayDeque(),
    val alternateReasonChains: Set<List<String>> = emptySet(),
    val canBeFinal: Boolean = true,
    val hasAppliedRule: Boolean = false, // Tracks whether any rule has been applied to reach this candidate
)
