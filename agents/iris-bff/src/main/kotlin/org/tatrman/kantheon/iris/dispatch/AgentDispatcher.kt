package org.tatrman.kantheon.iris.dispatch

import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.stream.TurnOutcome
import org.tatrman.kantheon.iris.v1.DoneEvent
import org.tatrman.kantheon.iris.v1.ErrorEvent
import org.tatrman.kantheon.iris.v1.IrisStreamEvent

/**
 * Routes a resolved turn to the [AgentClient] registered for its `agent_id`
 * (Stage 3.1 T4). The map is the dispatch seam: `golem-v2` (transitional) is the
 * only entry at Phase 3; native Golem/Pythia clients register here later. An
 * unknown `chosen_agent_id` is a well-formed terminal error (`NO_AGENT_CLIENT`),
 * never an exception — the wire stays clean and the turn is persisted FAILED.
 */
class AgentDispatcher(
    private val clients: Map<String, AgentClient>,
) {
    fun supports(agentId: String): Boolean = clients.containsKey(agentId)

    suspend fun dispatch(
        chosenAgentId: String,
        turn: AgentTurn,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome {
        val client = clients[chosenAgentId] ?: return noClient(turn.turnId, chosenAgentId, emit)
        return client.runTurn(turn, emit)
    }

    suspend fun resume(
        issuerAgentId: String,
        resume: AgentResume,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome {
        val client = clients[issuerAgentId] ?: return noClient(resume.turnId, issuerAgentId, emit)
        return client.runResume(resume, emit)
    }

    private suspend fun noClient(
        turnId: String,
        agentId: String,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome {
        emit(
            IrisStreamEvent
                .newBuilder()
                .setTurnId(turnId)
                .setSequence(1)
                .setError(
                    ErrorEvent
                        .newBuilder()
                        .setCode("NO_AGENT_CLIENT")
                        .setMessage("No dispatch client registered for agent '$agentId'")
                        .setRecoverable(false),
                ).build(),
        )
        emit(
            IrisStreamEvent
                .newBuilder()
                .setTurnId(turnId)
                .setSequence(2)
                .setDone(DoneEvent.newBuilder().setOutcome("failed"))
                .build(),
        )
        return TurnOutcome(
            envelope = null,
            status = TurnStatus.FAILED,
            pendingResumeToken = null,
            errorCode = "NO_AGENT_CLIENT",
            doneOutcome = "failed",
        )
    }
}
