package org.tatrman.kantheon.pythia.budget

import org.tatrman.kantheon.pythia.clients.GatewayClient
import org.tatrman.kantheon.pythia.v1.BudgetPolicy
import org.tatrman.kantheon.pythia.v1.Constraints

/** The four budget dimensions (design §3.1 Constraints). */
data class BudgetSpend(
    val usd: Double = 0.0,
    val tokens: Long = 0,
    val latencyMs: Long = 0,
    val stepCount: Int = 0,
)

/** The ladder rung an update lands on (architecture §5: 75 / 90 / 100 / 110). */
enum class LadderAction { OK, WARN, ASK, HALT_GRACEFULLY, HARD_STOP }

/**
 * Budget tracker (Stage 2.3 T5): four dimensions, project-and-reserve from gateway
 * pricing, and the 75 / 90 / 100 / 110 ladder. `on_budget_threshold = ASK` parks
 * AWAITING_BUDGET_DECISION at the 90% rung (PD-11); `/budget-decision CONTINUE`
 * raises the ceiling via [raiseCeiling]. The worst-case dimension drives the rung.
 */
class BudgetTracker(
    private val constraints: Constraints,
    private val policy: BudgetPolicy,
    private val gateway: GatewayClient = GatewayClient(),
) {
    private var spend = BudgetSpend()
    private var ceilingMultiplier = 1.0

    val currentSpend: BudgetSpend get() = spend

    /** Project the next batch's cost (project-and-reserve) before launching it. */
    fun projectBatch(
        tier: String,
        batchSize: Int,
    ): Double = gateway.projectCost(tier, batchSize)

    /** Record actual spend; returns the ladder rung the new total lands on. */
    fun record(
        usd: Double = 0.0,
        tokens: Long = 0,
        latencyMs: Long = 0,
        steps: Int = 0,
    ): LadderAction {
        spend =
            spend.copy(
                usd = spend.usd + usd,
                tokens = spend.tokens + tokens,
                latencyMs = spend.latencyMs + latencyMs,
                stepCount = spend.stepCount + steps,
            )
        return ladder()
    }

    /** The rung the *current* spend lands on (worst dimension), honouring the policy at 90%. */
    fun ladder(): LadderAction = ladderFor(worstPct())

    /** The rung the spend would land on if [projectedUsd] more were spent (project-and-reserve). */
    fun ladderIfReserved(projectedUsd: Double): LadderAction {
        val cap = costCap() ?: return ladderFor(worstPct())
        val projectedPct = if (cap > 0) (spend.usd + projectedUsd) / cap else 0.0
        return ladderFor(maxOf(worstPct(), projectedPct))
    }

    /** A `/budget-decision CONTINUE` raises the ceiling so the investigation can proceed past 100%. */
    fun raiseCeiling(by: Double = 0.5) {
        ceilingMultiplier += by
    }

    private fun ladderFor(pct: Double): LadderAction =
        when {
            pct >= 1.10 -> LadderAction.HARD_STOP
            pct >= 1.00 -> LadderAction.HALT_GRACEFULLY
            pct >= 0.90 -> if (policy == BudgetPolicy.BUDGET_ASK) LadderAction.ASK else LadderAction.WARN
            pct >= 0.75 -> LadderAction.WARN
            else -> LadderAction.OK
        }

    /** Worst (highest) utilisation across the four dimensions, scaled by the raised ceiling. */
    private fun worstPct(): Double {
        val pcts = mutableListOf<Double>()
        costCap()?.let { if (it > 0) pcts += spend.usd / it }
        if (constraints.maxLlmTokens > 0) pcts += spend.tokens.toDouble() / constraints.maxLlmTokens
        if (constraints.latencyBudgetMs > 0) pcts += spend.latencyMs.toDouble() / constraints.latencyBudgetMs
        if (constraints.maxStepCount > 0) pcts += spend.stepCount.toDouble() / constraints.maxStepCount
        return (pcts.maxOrNull() ?: 0.0)
    }

    private fun costCap(): Double? =
        if (constraints.hasMaxLlmCostUsd()) constraints.maxLlmCostUsd * ceilingMultiplier else null
}
