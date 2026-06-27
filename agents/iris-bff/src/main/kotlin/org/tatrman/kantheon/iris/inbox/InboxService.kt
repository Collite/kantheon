package org.tatrman.kantheon.iris.inbox

import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.domain.SessionStore
import java.util.UUID

/**
 * Assembles the investigation inbox view for a caller (PD-2, contracts §2.7):
 * Pythia's per-user investigation list ⋈ iris's `iris_turns` (session/turn refs,
 * `TurnOrigin` badges). The caller's OBO bearer is forwarded to Pythia.
 */
class InboxService(
    private val pythia: PythiaClient,
    private val sessions: SessionStore,
) {
    suspend fun build(caller: CallerIdentity): InboxView {
        val investigations = pythia.listInvestigations(caller.userId, caller.bearer)
        return InboxAggregator.build(investigations) { turnId -> joinTurn(caller, turnId) }
    }

    private fun joinTurn(
        caller: CallerIdentity,
        turnId: String,
    ): TurnJoin? {
        val id = runCatching { UUID.fromString(turnId) }.getOrNull() ?: return null
        val turn = sessions.getTurn(id) ?: return null
        // Ownership guard: never join a turn the caller doesn't own.
        val session = sessions.getSession(turn.sessionId) ?: return null
        if (session.userId != caller.userId) return null
        return TurnJoin(
            sessionId = turn.sessionId.toString(),
            sessionTitle = turn.question,
            turnId = turn.turnId.toString(),
            origin = turn.origin.wire,
        )
    }
}
