package org.tatrman.kantheon.pythia.revise

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.pythia.v1.ConfidenceKind
import org.tatrman.kantheon.pythia.v1.Hypothesis

/**
 * Stage 3.3 T2 — heuristic explained-variance: a capped sum of supported
 * hypotheses' explanatory power, surfaced as a heuristic ConfidenceInfo caveat;
 * the cap holds when the raw sum exceeds 1.0; no signal → no confidence.
 */
class ExplainedVarianceSpec :
    StringSpec({

        fun hyp(power: Double) = Hypothesis.newBuilder().setEstimatedExplanatoryPower(power).build()

        "the capped sum feeds a heuristic ConfidenceInfo with a caveat" {
            val c = ExplainedVariance.confidence(listOf(hyp(0.5), hyp(0.2)))!!
            c.kind shouldBe ConfidenceKind.CONFIDENCE_HEURISTIC
            c.score shouldBeExactly 0.7
            c.caveatsList.single().contains("approximate") shouldBe true
        }

        "the cap holds when the raw sum exceeds 1.0" {
            ExplainedVariance.compute(listOf(hyp(0.7), hyp(0.6))) shouldBeExactly 1.0
        }

        "no explanatory signal yields no confidence (procedural)" {
            ExplainedVariance.confidence(listOf(hyp(0.0))).shouldBeNull()
        }
    })
