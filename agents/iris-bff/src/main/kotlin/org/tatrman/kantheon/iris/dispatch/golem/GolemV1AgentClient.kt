package org.tatrman.kantheon.iris.dispatch.golem

import org.tatrman.kantheon.iris.dispatch.AgentClient
import org.tatrman.kantheon.iris.dispatch.AgentResume
import org.tatrman.kantheon.iris.dispatch.AgentTurn
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.stream.TurnOutcome
import org.tatrman.kantheon.iris.v1.IrisStreamEvent

/**
 * [AgentClient] for a **native kantheon Golem** — the seam the `AgentClient` KDoc reserved
 * for "native Golem/Pythia clients … in their own arcs".
 *
 * One instance per Shem: [agentId] is both the dispatch-map key Themis routes to and the
 * `golem_id` the pod validates against its loaded Shem, so a client is bound to exactly one
 * golem endpoint. There is no session or thread to establish (golem holds no conversation
 * state — that is Iris's job), so a turn is a single call.
 *
 * Conversation memory travels per-turn instead: the excerpt is read from the session store
 * here rather than threaded through [AgentTurn], keeping the dispatch seam agent-agnostic.
 */
class GolemV1AgentClient(
    private val agentId: String,
    private val store: SessionStore,
    private val client: GolemV1Client,
    private val mux: GolemV1Mux = GolemV1Mux(),
) : AgentClient {
    override suspend fun runTurn(
        turn: AgentTurn,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome {
        val request = GolemRequestFactory.forTurn(agentId, turn, store.getTurns(turn.sessionId))
        return mux.run(
            turn.turnId,
            turn.sessionId,
            agentId,
            client.answer(request, turn.correlationId, turn.caller.bearer),
            emit,
        )
    }

    /**
     * Resume a clarification golem itself issued. The token is opaque to Iris — golem minted
     * it, signed it, and binds it to its issuing caller, so a replay by another user is
     * rejected there (a 400, surfaced as a terminal `GOLEM_BAD_REQUEST`).
     */
    override suspend fun runResume(
        resume: AgentResume,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome =
        mux.run(
            resume.turnId,
            resume.sessionId,
            agentId,
            client.resume(
                GolemResume(resume.resumeToken, resume.selectedOptionId, resume.freeTextAnswer),
                resume.correlationId,
                resume.caller.bearer,
            ),
            emit,
        )
}
