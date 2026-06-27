package org.tatrman.kantheon.golem.plan

import org.tatrman.kantheon.golem.v1.MiniPlan

/**
 * Confidence-gate thresholds (architecture §4 `pick_plan`, config-driven; defaults
 * carried from new-golem v2). `auto` ≥ → execute clean; `[warn, auto)` → execute
 * with a mild warning; `[clarify, warn)` → execute with a low-confidence warning;
 * `< clarify` → clarify instead of answering.
 */
data class GateThresholds(
    val auto: Double = 0.95,
    val warn: Double = 0.85,
    val clarify: Double = 0.60,
)

/** The gate's decision over a composed [MiniPlan]. */
sealed interface GateDecision {
    /** Proceed to execution. [warning] is a non-null Rule-6 hint below the auto threshold. */
    data class Execute(
        val warning: String?,
        val losingPlanSummary: String?,
    ) : GateDecision

    /** Confidence below the floor — ask the user instead of answering. */
    data class Clarify(
        val reason: String,
        val confidence: Double,
    ) : GateDecision
}

/**
 * Deterministic confidence gate over a composer-produced plan. Pure — the composer
 * sets `confidence` + `losing_plan_summary`; this maps it onto execute/clarify.
 */
fun gatePlan(
    plan: MiniPlan,
    thresholds: GateThresholds = GateThresholds(),
): GateDecision {
    val c = plan.confidence
    val losing =
        if (plan.hasLosingPlanSummary() &&
            plan.losingPlanSummary.isNotBlank()
        ) {
            plan.losingPlanSummary
        } else {
            null
        }
    val pct = "%.2f".format(c)
    return when {
        c >= thresholds.auto -> GateDecision.Execute(warning = null, losingPlanSummary = losing)
        c >= thresholds.warn ->
            GateDecision.Execute(
                warning = "plan confidence $pct is below the high-confidence threshold ${thresholds.auto}",
                losingPlanSummary = losing,
            )
        c >= thresholds.clarify ->
            GateDecision.Execute(
                warning = "low plan confidence $pct — the result should be verified",
                losingPlanSummary = losing,
            )
        else ->
            GateDecision.Clarify(
                reason = "plan confidence $pct is below the clarification floor ${thresholds.clarify}",
                confidence = c,
            )
    }
}
