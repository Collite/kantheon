package org.tatrman.kantheon.pythia.revise

import org.tatrman.kantheon.pythia.v1.Hypothesis

/** A scored hypothesis (the prioritisation output). */
data class HypScore(
    val hypId: String,
    val score: Double,
    val rationale: String,
)

/**
 * Hypothesis prioritisation (Stage 3.2 T1). The score is
 * `confidence × explanatory × (1/cost) × diagnostic × novelty` (design decision
 * #10). Floors guard against divide-by-zero (zero cost) and degenerate inputs.
 * The LLM tie-breaker fires ONLY when the top-2 are within 10% of each other.
 */
object Prioritisation {
    private const val EPS = 1e-6

    fun score(
        hyp: Hypothesis,
        cost: Double,
        novelty: Double,
    ): Double {
        // The [0,1] factors are clamped (not just floored) so a stray out-of-range or
        // non-finite LLM value can't dominate or NaN-poison the ranking; cost is floored
        // away from zero; novelty is bounded [0,1] too. A non-finite score → 0 (de-ranked).
        val confidence = finite(hyp.confidence).coerceIn(EPS, 1.0)
        val explanatory = finite(hyp.estimatedExplanatoryPower).coerceIn(EPS, 1.0)
        val diagnostic = finite(hyp.diagnosticPower).coerceIn(EPS, 1.0)
        val invCost = 1.0 / finite(cost).coerceAtLeast(EPS)
        val nov = finite(novelty).coerceIn(0.0, 1.0)
        val score = confidence * explanatory * invCost * diagnostic * nov
        return if (score.isFinite()) score else 0.0
    }

    private fun finite(v: Double): Double = if (v.isFinite()) v else 0.0

    /** Rank hypotheses by score, descending. */
    fun prioritize(
        hypotheses: List<Hypothesis>,
        costOf: (Hypothesis) -> Double = { 1.0 },
        noveltyOf: (Hypothesis) -> Double = { 1.0 },
    ): List<HypScore> =
        hypotheses
            .map { HypScore(it.id, score(it, costOf(it), noveltyOf(it)), "scored") }
            .sortedByDescending { it.score }

    /** Whether the top-2 scores are within 10% (the only case the LLM tie-breaker fires). */
    fun topTwoWithinTenPercent(ranked: List<HypScore>): Boolean {
        if (ranked.size < 2) return false
        val top = ranked[0].score
        val second = ranked[1].score
        if (top <= 0.0) return false
        return (top - second) / top <= 0.10
    }
}
