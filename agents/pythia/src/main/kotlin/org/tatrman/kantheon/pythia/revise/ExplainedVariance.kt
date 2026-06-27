package org.tatrman.kantheon.pythia.revise

import org.tatrman.kantheon.pythia.v1.ConfidenceInfo
import org.tatrman.kantheon.pythia.v1.ConfidenceKind
import org.tatrman.kantheon.pythia.v1.Hypothesis
import kotlin.math.min

/**
 * Heuristic explained-variance (Stage 3.3 T2, design §3 / plan §5): a **capped
 * sum** of `estimated_explanatory_power` over the SUPPORTED hypotheses, surfaced
 * as a `ConfidenceInfo` caveat explicitly labelled heuristic (the honest
 * `model.decompose.variance` is v1.5 backlog). Capped at 1.0.
 */
object ExplainedVariance {
    fun compute(supportedHypotheses: List<Hypothesis>): Double =
        min(
            1.0,
            // Per-hyp powers are LLM-authored: drop non-finite and clamp away negatives
            // so the capped sum can't go NaN/negative and silently corrupt the confidence.
            supportedHypotheses.sumOf {
                it.estimatedExplanatoryPower.takeIf { p -> p.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
            },
        )

    /** A heuristic ConfidenceInfo, or null when there is no explanatory signal (procedural). */
    fun confidence(supportedHypotheses: List<Hypothesis>): ConfidenceInfo? {
        val ev = compute(supportedHypotheses)
        if (ev <= 0.0) return null
        return ConfidenceInfo
            .newBuilder()
            .setKind(ConfidenceKind.CONFIDENCE_HEURISTIC)
            .setScore(ev)
            .addCaveats(
                "explained variance is approximate (heuristic), not exact — consider a Metis decomposition follow-up",
            ).build()
    }
}
