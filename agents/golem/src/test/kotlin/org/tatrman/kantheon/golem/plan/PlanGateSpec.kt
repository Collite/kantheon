package org.tatrman.kantheon.golem.plan

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.kantheon.golem.v1.PlanSource

private fun planAt(
    confidence: Double,
    losing: String? = null,
): MiniPlan {
    val b = MiniPlan.newBuilder().setSource(PlanSource.PATTERN).setConfidence(confidence)
    if (losing != null) b.losingPlanSummary = losing
    return b.build()
}

class PlanGateSpec :
    StringSpec({

        "confidence at/above the auto threshold executes cleanly" {
            val d = gatePlan(planAt(0.97))
            d.shouldBeInstanceOf<GateDecision.Execute>()
            d.warning shouldBe null
        }

        "the warn band executes with a Rule-6 warning" {
            val d = gatePlan(planAt(0.90))
            d.shouldBeInstanceOf<GateDecision.Execute>()
            d.warning shouldContain "high-confidence threshold"
        }

        "the low band executes with a low-confidence warning" {
            val d = gatePlan(planAt(0.70))
            d.shouldBeInstanceOf<GateDecision.Execute>()
            d.warning shouldContain "low plan confidence"
        }

        "below the clarification floor returns Clarify" {
            val d = gatePlan(planAt(0.50))
            d.shouldBeInstanceOf<GateDecision.Clarify>()
            d.confidence shouldBe 0.50
        }

        "losing_plan_summary is surfaced on the decision" {
            val d = gatePlan(planAt(0.97, losing = "free-sql was the runner-up"))
            d.shouldBeInstanceOf<GateDecision.Execute>()
            d.losingPlanSummary shouldBe "free-sql was the runner-up"
        }

        "thresholds are configurable" {
            val strict = GateThresholds(auto = 0.99, warn = 0.9, clarify = 0.8)
            gatePlan(planAt(0.95), strict).shouldBeInstanceOf<GateDecision.Execute>().warning shouldContain "below"
            gatePlan(planAt(0.75), strict).shouldBeInstanceOf<GateDecision.Clarify>()
        }
    })
