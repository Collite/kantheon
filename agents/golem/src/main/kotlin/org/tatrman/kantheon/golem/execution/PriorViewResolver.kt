package org.tatrman.kantheon.golem.execution

import org.tatrman.kantheon.golem.persistence.TurnsRepository
import org.tatrman.kantheon.golem.v1.GolemContext
import java.util.UUID

/**
 * The prior view an AMEND/DRILL turn builds on — the bindings the producing turn
 * used, plus (when the producing turn is found in `golem_turns`) its persisted
 * plan for rehydration.
 */
data class ResolvedPriorView(
    val patternId: String?,
    val argsJson: String,
    val sql: String?,
    val bubbleId: String?,
    val sourceTurnId: UUID?,
    val priorPlanJson: String?,
)

/**
 * Resolves `GolemContext.prior_view` (envelope/v1 `CurrentView`) for AMEND/DRILL
 * (contracts §4). The typed view arrives on the request (BFF-populated from the
 * `TurnPointer`); when it carries a `bubble_id`, the producing `golem_turns` row is
 * looked up so the prior `plan` can be read back. Returns null when the turn is not
 * an amend/drill (no prior view).
 */
class PriorViewResolver(
    private val turns: TurnsRepository,
) {
    fun resolve(
        context: GolemContext,
        userId: String,
        tenantId: String,
    ): ResolvedPriorView? {
        if (!context.hasPriorView()) return null
        val pv = context.priorView
        val bubbleId = pv.bubbleId.ifBlank { null }
        // Scoped to the caller (H2): the prior turn must belong to the same user + tenant.
        val priorTurn = bubbleId?.let { turns.findByBubbleId(it, userId, tenantId) }
        return ResolvedPriorView(
            patternId = pv.patternId.ifBlank { null },
            argsJson = pv.argsJson.ifBlank { "{}" },
            sql = pv.sql.ifBlank { null },
            bubbleId = bubbleId,
            sourceTurnId = priorTurn?.id,
            priorPlanJson = priorTurn?.planJson,
        )
    }
}
