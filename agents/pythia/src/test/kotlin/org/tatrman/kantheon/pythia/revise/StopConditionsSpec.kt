package org.tatrman.kantheon.pythia.revise

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.kantheon.pythia.v1.IntentKind
import org.tatrman.kantheon.pythia.v1.StopReason

/**
 * Stage 3.2 T6 — the stop-condition spine: each of the five stop reasons is
 * reachable, the four RCA brakes each trip on their condition, and a healthy
 * investigation continues until goal-reached.
 */
class StopConditionsSpec :
    StringSpec({

        fun stop(
            intent: IntentKind,
            state: StopState,
        ) = StopConditions.decide(intent, state)

        fun reason(decision: StopDecision) = (decision as StopDecision.Stop).reason

        "STOP_USER takes precedence" {
            reason(stop(IntentKind.INTENT_RCA, StopState(userHalt = true, budgetExhausted = true))) shouldBe
                StopReason.STOP_USER
        }

        "STOP_BUDGET when exhausted" {
            reason(stop(IntentKind.INTENT_RCA, StopState(budgetExhausted = true))) shouldBe StopReason.STOP_BUDGET
        }

        "STOP_HARD_CAP trips on each of the three hard-cap RCA brakes" {
            reason(stop(IntentKind.INTENT_RCA, StopState(decompositionDepth = 3, maxDecompositionDepth = 3))) shouldBe
                StopReason.STOP_HARD_CAP
            reason(stop(IntentKind.INTENT_RCA, StopState(perHypTestCount = 4, maxPerHypTests = 4))) shouldBe
                StopReason.STOP_HARD_CAP
            reason(stop(IntentKind.INTENT_RCA, StopState(revisionCount = 2, maxRevisions = 2))) shouldBe
                StopReason.STOP_HARD_CAP
        }

        "a zero revision cap disables the hard-cap brake (SHALLOW exhausts its plan, not the cap)" {
            // SHALLOW = zero revisions allowed. With nothing revised yet and the frontier
            // drained, the honest reason is STOP_PLAN_EXHAUSTED — not a spurious STOP_HARD_CAP.
            reason(
                stop(
                    IntentKind.INTENT_RCA,
                    StopState(revisionCount = 0, maxRevisions = 0, frontierEmpty = true, explainedVariance = 0.3),
                ),
            ) shouldBe StopReason.STOP_PLAN_EXHAUSTED
        }

        "goal-reached wins over the hard cap (a final-revision success is GOAL_REACHED, not HARD_CAP)" {
            reason(
                stop(
                    IntentKind.INTENT_RCA,
                    StopState(explainedVariance = 0.80, revisionCount = 2, maxRevisions = 2),
                ),
            ) shouldBe StopReason.STOP_GOAL_REACHED
        }

        "the marginal-value soft brake is reported separately" {
            StopConditions.marginalBrakeTripped(StopState(marginalValue = 0.01, minMarginalValue = 0.05)) shouldBe true
            StopConditions.marginalBrakeTripped(StopState(marginalValue = 0.5, minMarginalValue = 0.05)) shouldBe false
        }

        "RCA reaches the goal when explained variance clears 0.75" {
            reason(stop(IntentKind.INTENT_RCA, StopState(explainedVariance = 0.80))) shouldBe
                StopReason.STOP_GOAL_REACHED
        }

        "PROCEDURAL reaches the goal on its completion signal" {
            reason(stop(IntentKind.INTENT_PROCEDURAL, StopState(goalReached = true))) shouldBe
                StopReason.STOP_GOAL_REACHED
        }

        "STOP_PLAN_EXHAUSTED when the frontier empties without a goal" {
            reason(stop(IntentKind.INTENT_RCA, StopState(frontierEmpty = true, explainedVariance = 0.3))) shouldBe
                StopReason.STOP_PLAN_EXHAUSTED
        }

        "a healthy mid-investigation continues" {
            stop(
                IntentKind.INTENT_RCA,
                StopState(explainedVariance = 0.4, revisionCount = 1),
            ).shouldBeInstanceOf<StopDecision.Continue>()
        }
    })
