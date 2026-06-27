package org.tatrman.kantheon.pythia.revise

import org.tatrman.kantheon.pythia.v1.IntentKind
import org.tatrman.kantheon.pythia.v1.StopReason

/** The state the stop-condition spine consults after each batch/evaluation. */
data class StopState(
    val userHalt: Boolean = false,
    val budgetExhausted: Boolean = false,
    val frontierEmpty: Boolean = false,
    // per-intent completion signals
    val goalReached: Boolean = false,
    val explainedVariance: Double = 0.0,
    // the four RCA brakes
    val decompositionDepth: Int = 0,
    val maxDecompositionDepth: Int = 3,
    val perHypTestCount: Int = 0,
    val maxPerHypTests: Int = 4,
    val revisionCount: Int = 0,
    val maxRevisions: Int = 2,
    val marginalValue: Double = 1.0,
    val minMarginalValue: Double = 0.05,
)

/** CONTINUE vs STOP (with a reason). */
sealed interface StopDecision {
    data object Continue : StopDecision

    data class Stop(
        val reason: StopReason,
    ) : StopDecision
}

/**
 * The stop-condition spine (Stage 3.2 T6, design §3.5): the five shared stop
 * reasons + per-intent completion criteria + the four RCA brakes (decomposition
 * depth, per-hypothesis test count, plan-revision count — hard caps; marginal
 * value — soft brake). Consulted after each batch to decide CONTINUE vs SYNTHESIZE.
 */
object StopConditions {
    private const val RCA_VARIANCE_GOAL = 0.75

    fun decide(
        intent: IntentKind,
        state: StopState,
    ): StopDecision {
        if (state.userHalt) return StopDecision.Stop(StopReason.STOP_USER)
        if (state.budgetExhausted) return StopDecision.Stop(StopReason.STOP_BUDGET)
        // Goal before hard-cap: a run that reaches its goal on the final allowed
        // revision reports STOP_GOAL_REACHED, not a truncation.
        if (completed(intent, state)) return StopDecision.Stop(StopReason.STOP_GOAL_REACHED)
        if (hitHardCap(state)) return StopDecision.Stop(StopReason.STOP_HARD_CAP)
        if (state.frontierEmpty) return StopDecision.Stop(StopReason.STOP_PLAN_EXHAUSTED)
        return StopDecision.Continue
    }

    /**
     * The three hard-cap RCA brakes (decomposition depth / per-hyp tests / revisions).
     * A cap of 0 *disables* its brake — it means "this dimension is not allowed at all"
     * (e.g. SHALLOW permits zero revisions), which surfaces as a clean STOP_PLAN_EXHAUSTED
     * when the frontier empties, not a spurious STOP_HARD_CAP before any work is capped.
     */
    private fun hitHardCap(s: StopState): Boolean =
        (s.maxDecompositionDepth > 0 && s.decompositionDepth >= s.maxDecompositionDepth) ||
            (s.maxPerHypTests > 0 && s.perHypTestCount >= s.maxPerHypTests) ||
            (s.maxRevisions > 0 && s.revisionCount >= s.maxRevisions)

    /** The soft marginal-value brake (true = diminishing returns, deepening not worth it). */
    fun marginalBrakeTripped(s: StopState): Boolean = s.marginalValue < s.minMarginalValue

    private fun completed(
        intent: IntentKind,
        s: StopState,
    ): Boolean =
        when (intent) {
            IntentKind.INTENT_RCA -> s.explainedVariance >= RCA_VARIANCE_GOAL || s.goalReached
            else -> s.goalReached // PROCEDURAL / FORECAST / SIMULATION: their completion signal
        }
}
