package org.tatrman.kantheon.iris.inbox

/** The iris-side join for an investigation's originating turn (contracts §2.7). */
data class TurnJoin(
    val sessionId: String,
    val sessionTitle: String?,
    val turnId: String,
    /** `user` | `scheduled` (TurnOrigin → inbox origin badge). */
    val origin: String,
)

/**
 * Builds the investigation inbox (PD-2, contracts §2.7): a **view over Pythia's
 * persisted state** joined with iris's own `iris_turns`. Pure transform — the
 * route resolves the join (`joinOf`) and ownership; this maps the 12→5 status,
 * computes the Running / Needs-input counts, and tolerates investigations with no
 * matching turn (orphaned scheduled runs render with the Pythia origin only).
 */
object InboxAggregator {
    fun build(
        investigations: List<InvestigationSummary>,
        joinOf: (String) -> TurnJoin?,
    ): InboxView {
        val items =
            investigations.map { inv ->
                val join = inv.originTurnId?.let { joinOf(it) }
                val status = UserFacingStatus.of(inv.status)
                InboxItem(
                    investigationId = inv.id,
                    question = inv.question,
                    status = status,
                    rawStatus = inv.status,
                    // The turn's TurnOrigin wins; else infer from the Pythia caller kind.
                    origin = join?.origin ?: if (inv.callerKind.equals("SCHEDULED", true)) "scheduled" else "user",
                    costSoFar = inv.costSoFar,
                    updatedAt = inv.updatedAt,
                    sessionId = join?.sessionId,
                    sessionTitle = join?.sessionTitle,
                    turnId = join?.turnId,
                    partial = status == UserFacingStatus.CANCELLED,
                )
            }
        return InboxView(
            items = items,
            counts =
                InboxCounts(
                    running = items.count { it.status == UserFacingStatus.RUNNING },
                    needsInput = items.count { it.status == UserFacingStatus.NEEDS_INPUT },
                ),
        )
    }
}
