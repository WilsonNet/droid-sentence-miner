package io.github.wilsonnet.droidsentenceminer.lang.kit

import io.github.wilsonnet.droidsentenceminer.lang.Candidate

/** Deinflection candidates in the order found, one per (term, part of speech): the first reasons win. */
class CandidateSet {
    private val out = LinkedHashMap<Pair<String, Long>, Candidate>()

    /** Adds [term] as a [pos] candidate (0 = any part of speech) reached through [reasons]. */
    fun add(term: String, pos: Long, reasons: List<String>) {
        if (term.isEmpty()) return
        val candidate =
            Candidate(term = term, conditions = pos, reasons = ArrayDeque(reasons), hasAppliedRule = reasons.isNotEmpty())
        out.putIfAbsent(term to pos, candidate)
    }

    /** Everything added, in order. */
    fun toList(): List<Candidate> = out.values.toList()
}
