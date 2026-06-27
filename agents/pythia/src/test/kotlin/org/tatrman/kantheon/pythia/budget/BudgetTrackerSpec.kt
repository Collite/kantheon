package org.tatrman.kantheon.pythia.budget

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.pythia.clients.GatewayClient
import org.tatrman.kantheon.pythia.v1.BudgetPolicy
import org.tatrman.kantheon.pythia.v1.Constraints

/**
 * Stage 2.3 T5 — the budget ladder (75 warn / 90 ASK-if-policy / 100 graceful /
 * 110 hard), project-and-reserve, and the `/budget-decision CONTINUE` ceiling raise.
 */
class BudgetTrackerSpec :
    StringSpec({

        fun constraints(maxUsd: Double = 1.0) = Constraints.newBuilder().setMaxLlmCostUsd(maxUsd).build()

        "the ladder crosses warn → ASK → graceful → hard as cost accrues (policy ASK)" {
            val tracker = BudgetTracker(constraints(), BudgetPolicy.BUDGET_ASK)
            tracker.record(usd = 0.75) shouldBe LadderAction.WARN // 75%
            tracker.record(usd = 0.15) shouldBe LadderAction.ASK // 90%, policy ASK → park
            tracker.record(usd = 0.10) shouldBe LadderAction.HALT_GRACEFULLY // 100%
            tracker.record(usd = 0.10) shouldBe LadderAction.HARD_STOP // 110%
        }

        "policy CONTINUE downgrades the 90% rung from ASK to WARN" {
            val tracker = BudgetTracker(constraints(), BudgetPolicy.BUDGET_CONTINUE)
            tracker.record(usd = 0.90) shouldBe LadderAction.WARN
        }

        "project-and-reserve lands on the rung the next batch would push the spend to" {
            val tracker = BudgetTracker(constraints(), BudgetPolicy.BUDGET_ASK, GatewayClient(mapOf("strong" to 0.45)))
            tracker.record(usd = 0.5) shouldBe LadderAction.OK // 50%
            val projected = tracker.projectBatch("strong", 1) // 0.45
            tracker.ladderIfReserved(projected) shouldBe LadderAction.ASK // (0.5+0.45)/1.0 = 95% → ASK
        }

        "a /budget-decision CONTINUE raises the ceiling so the run proceeds past 100%" {
            val tracker = BudgetTracker(constraints(), BudgetPolicy.BUDGET_ASK)
            tracker.record(usd = 1.0) shouldBe LadderAction.HALT_GRACEFULLY
            tracker.raiseCeiling(0.5) // ceiling → 1.5
            tracker.ladder() shouldBe LadderAction.OK // 1.0/1.5 ≈ 67% → below the 75% warn rung
        }

        "the worst dimension drives the rung (step count over its cap)" {
            val c =
                Constraints
                    .newBuilder()
                    .setMaxLlmCostUsd(10.0)
                    .setMaxStepCount(4)
                    .build()
            val tracker = BudgetTracker(c, BudgetPolicy.BUDGET_CONTINUE)
            tracker.record(usd = 0.1, steps = 3) shouldBe LadderAction.WARN // 3/4 = 75% steps
        }
    })
