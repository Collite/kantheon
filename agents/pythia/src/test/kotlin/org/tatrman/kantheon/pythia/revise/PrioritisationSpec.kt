package org.tatrman.kantheon.pythia.revise

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.pythia.v1.Hypothesis

/**
 * Stage 3.2 T1 — the scoring formula (confidence × explanatory × 1/cost ×
 * diagnostic × novelty), the top-2-within-10% tie-break gate, and divide-by-zero
 * safety on degenerate inputs.
 */
class PrioritisationSpec :
    StringSpec({

        fun hyp(
            id: String,
            confidence: Double,
            explanatory: Double,
            diagnostic: Double,
        ) = Hypothesis
            .newBuilder()
            .setId(id)
            .setConfidence(confidence)
            .setEstimatedExplanatoryPower(explanatory)
            .setDiagnosticPower(diagnostic)
            .build()

        "ranks hypotheses by score descending" {
            val strong = hyp("A", 0.9, 0.8, 0.7)
            val weak = hyp("B", 0.3, 0.3, 0.3)
            Prioritisation.prioritize(listOf(weak, strong)).map { it.hypId } shouldBe listOf("A", "B")
        }

        "the tie-break gate fires only when the top-2 are within 10%" {
            // near-tie: A and B almost equal → within 10%
            val nearTie = Prioritisation.prioritize(listOf(hyp("A", 0.80, 0.80, 0.80), hyp("B", 0.79, 0.80, 0.80)))
            Prioritisation.topTwoWithinTenPercent(nearTie) shouldBe true
            // clear winner → not within 10%
            val clear = Prioritisation.prioritize(listOf(hyp("A", 0.9, 0.9, 0.9), hyp("B", 0.2, 0.2, 0.2)))
            Prioritisation.topTwoWithinTenPercent(clear) shouldBe false
        }

        "degenerate inputs (zero cost, zero novelty) do not divide by zero" {
            val h = hyp("A", 0.5, 0.5, 0.5)
            // zero cost → floored, finite score
            Prioritisation.score(h, cost = 0.0, novelty = 1.0).isFinite() shouldBe true
            Prioritisation.score(h, cost = 0.0, novelty = 1.0) shouldBeGreaterThan 0.0
            // zero novelty → score 0 (no div-by-zero)
            Prioritisation.score(h, cost = 1.0, novelty = 0.0) shouldBe 0.0
        }

        "non-finite / out-of-range LLM inputs can't poison the ranking" {
            // A NaN confidence is treated as the floor (finite, negligible) rather than
            // corrupting sort order; an out-of-range power is clamped into [0,1].
            val nan = hyp("A", Double.NaN, 0.5, 0.5)
            val nanScore = Prioritisation.score(nan, cost = 1.0, novelty = 1.0)
            nanScore.isFinite() shouldBe true
            val huge = hyp("B", 1.0, 50.0, 1.0) // explanatory clamped to 1.0
            Prioritisation.score(huge, cost = 1.0, novelty = 1.0).isFinite() shouldBe true
            // a NaN-bearing hypothesis never out-ranks a healthy one
            Prioritisation.prioritize(listOf(nan, hyp("C", 0.6, 0.6, 0.6))).first().hypId shouldBe "C"
        }
    })
