package org.tatrman.kantheon.iris.dispatch.golemv2

import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.dispatch.AgentClient
import org.tatrman.kantheon.iris.dispatch.AgentResume
import org.tatrman.kantheon.iris.dispatch.AgentTurn
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.stream.IrisStreamMux
import org.tatrman.kantheon.iris.stream.TurnOutcome
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import java.util.UUID

/**
 * Transitional [AgentClient] over new-golem /v2 (Stage 3.1 T4). Owns the v2-thread
 * lifecycle (the 1:1 session↔thread binding lives here, not in the dispatcher —
 * it is golem-specific) and runs the cold v2 SSE flow through [IrisStreamMux]
 * (v2 → envelope/v1, `done` synthesis). Deleted at the Golem-rewrite cutover; the
 * `handoff` is accepted but not forwarded (v2 has no handoff field — it is
 * consumed by Themis upstream).
 */
class GolemV2AgentClient(
    private val store: SessionStore,
    private val client: GolemV2Client,
    private val mux: IrisStreamMux,
) : AgentClient {
    override suspend fun runTurn(
        turn: AgentTurn,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome {
        val threadId = ensureThread(turn.sessionId, turn.caller, turn.correlationId)
        return mux.run(
            turn.turnId,
            client.chatStream(
                V2ChatRequest(threadId, turn.question, turn.desiredFormat),
                turn.caller.userId,
                turn.correlationId,
                turn.caller.bearer,
            ),
            emit,
        )
    }

    override suspend fun runResume(
        resume: AgentResume,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome {
        val threadId = ensureThread(resume.sessionId, resume.caller, resume.correlationId)
        return mux.run(
            resume.turnId,
            client.resume(
                V2ResumeRequest(threadId, resume.resumeToken, resume.selectedOptionId, resume.freeTextAnswer),
                resume.caller.userId,
                resume.correlationId,
                resume.caller.bearer,
            ),
            emit,
        )
    }

    /** The transitional 1:1 session↔thread binding (contracts §3 / §5). */
    private suspend fun ensureThread(
        sessionId: UUID,
        caller: CallerIdentity,
        correlationId: String,
    ): String {
        store.getV2Thread(sessionId)?.let { return it }
        val threadId = sessionId.toString()
        client.createSession(threadId, caller.userId, correlationId, caller.bearer)
        store.putV2Thread(sessionId, threadId)
        return threadId
    }
}
