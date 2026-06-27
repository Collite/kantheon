package org.tatrman.kantheon.iris.action

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.kantheon.common.v1.HandoffContext
import org.tatrman.kantheon.envelope.v1.InvestigateChip
import org.tatrman.kantheon.iris.api.RoutingMetrics
import org.tatrman.kantheon.iris.audit.AuditStore
import org.tatrman.kantheon.iris.domain.SessionRecord
import org.tatrman.kantheon.iris.domain.TurnRecord
import org.tatrman.kantheon.iris.routing.HandoffAssembler
import java.time.Instant

/**
 * The always-on "Investigate this" escalation (PD-1, contracts §1.1 InvestigateChip).
 * The BFF owns the turn, so it builds the [InvestigateChip] (handoff assembled +
 * size-capped by [HandoffAssembler]) and, on click, re-issues the turn pinned to
 * Pythia (`routing_hint = pythia`) after writing an `escalation` audit row.
 *
 * At Phase 3 the `pythia` agent client is NOT registered in the AgentDispatcher
 * (that client is the Pythia arc), so the re-issue resolves through Themis to a
 * `chosen_agent_id` with no client and returns the `NO_AGENT_CLIENT` error
 * envelope — the escalation is fully built + audited; only the terminal dispatch
 * is a clean no-client error until the Pythia client lands.
 */
class EscalationHandler(
    private val audit: AuditStore,
    private val pythiaAgentId: String = "pythia",
    private val now: () -> Instant = Instant::now,
    private val metrics: RoutingMetrics = RoutingMetrics.NOOP,
) {
    /** Build the InvestigateChip the FE renders on a table/chart block. */
    fun buildChip(
        session: SessionRecord,
        turn: TurnRecord,
        proposedQuestion: String,
    ): InvestigateChip {
        val handoff =
            HandoffAssembler.fromPreviousTurn(session, turn, suggestedFocus = proposedQuestion)
                ?: HandoffContext.getDefaultInstance()
        return InvestigateChip
            .newBuilder()
            .setHandoff(handoff)
            .setProposedQuestion(proposedQuestion)
            .setLabel("Investigate this")
            .build()
    }

    /** Audit the escalation (the click), before the Pythia re-issue. */
    fun recordEscalation(
        userId: String,
        turn: TurnRecord,
        proposedQuestion: String,
    ) {
        metrics.recordEscalation()
        audit.append(
            userId = userId,
            eventKind = "escalation",
            payloadJson =
                buildJsonObject {
                    put("turnId", JsonPrimitive(turn.turnId.toString()))
                    put("fromAgentId", JsonPrimitive(turn.agentId))
                    put("routingHint", JsonPrimitive(pythiaAgentId))
                    put("proposedQuestion", JsonPrimitive(proposedQuestion))
                }.toString(),
            ts = now(),
        )
    }

    val targetAgentId: String get() = pythiaAgentId
}
