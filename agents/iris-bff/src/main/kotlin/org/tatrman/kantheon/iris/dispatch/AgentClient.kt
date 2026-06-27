package org.tatrman.kantheon.iris.dispatch

import org.tatrman.kantheon.common.v1.HandoffContext
import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.stream.TurnOutcome
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import java.util.UUID

/**
 * One dispatchable agent (contracts §6.2; Stage 3.1 T4). The
 * [AgentDispatcher] holds a `Map<agent_id, AgentClient>`; at Phase 3 the only
 * registered client is `golem-v2` (transitional), and the map is the seam native
 * Golem/Pythia clients plug into in their own arcs. Implementations own their own
 * stream→`IrisStreamEvent` mapping (e.g. the golem mux) and return the
 * [TurnOutcome] the dispatcher hands back to the ChatDispatcher for persistence.
 */
interface AgentClient {
    suspend fun runTurn(
        turn: AgentTurn,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome

    suspend fun runResume(
        resume: AgentResume,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome
}

/**
 * A resolved turn ready for dispatch. [handoff] is the PD-1 context assembled by
 * the BFF; transitional agents (golem-v2) accept and ignore it (the handoff is
 * consumed by Themis), native agents seed from it.
 */
data class AgentTurn(
    val turnId: String,
    val sessionId: UUID,
    val caller: CallerIdentity,
    val correlationId: String,
    val question: String,
    val desiredFormat: String? = null,
    val handoff: HandoffContext? = null,
)

/** A clarification resume routed to its issuing agent. */
data class AgentResume(
    val turnId: String,
    val sessionId: UUID,
    val caller: CallerIdentity,
    val correlationId: String,
    val resumeToken: String,
    val selectedOptionId: String? = null,
    val freeTextAnswer: String? = null,
)
