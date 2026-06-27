package org.tatrman.kantheon.iris.domain

import java.util.UUID

/**
 * Conversation-state persistence for iris-bff (contracts §3). The interface is
 * the behavioural contract; [InMemorySessionStore] is the unit/component-test
 * fake and `ExposedSessionStore` the Postgres binding. Real-PG fidelity is
 * exercised in the separate integration-test suite (planning-conventions §4).
 *
 * Discard discipline (contracts §3): turns are never deleted — `reset` and
 * `discardTurnsAfter` snapshot first, then status-flip turns to DISCARDED so an
 * undo can restore them.
 *
 * Ownership: these methods are owner-agnostic by design. The owner check is the
 * **route layer's** responsibility (the trust boundary) — `SessionRoutes` /
 * `ChatRoutes` load the session and reject `session.userId != caller.userId`
 * with a 404 before any id-keyed call here. Keeping the store owner-blind keeps
 * the snapshot/undo internals simple; do not call these with an unverified id.
 */
interface SessionStore {
    fun createSession(
        userId: String,
        tenantId: String,
    ): SessionRecord

    fun getSession(sessionId: UUID): SessionRecord?

    /** Session + its visible (non-discarded) turns, ordered by seq; null if absent. */
    fun getSessionWithTurns(sessionId: UUID): SessionWithTurns?

    fun listSessions(userId: String): List<SessionSummary>

    /** Visible (non-discarded by default) turns of a session, ordered by seq. */
    fun getTurns(
        sessionId: UUID,
        includeDiscarded: Boolean = false,
    ): List<TurnRecord>

    fun getTurn(turnId: UUID): TurnRecord?

    /** Append a turn; assigns the next monotone `seq` for the session. */
    fun appendTurn(turn: NewTurn): TurnRecord

    /**
     * Take a snapshot (reason = "reset"), discard every visible turn, and clear
     * the session's entity context + display state. Returns the cleared session.
     */
    fun reset(sessionId: UUID): SessionRecord

    /**
     * Snapshot (reason = "edit_resend") then discard every turn with `seq`
     * greater than `fromTurnId`'s. Returns the discarded turns. No-op (empty) if
     * the turn is unknown or already last.
     */
    fun discardTurnsAfter(
        sessionId: UUID,
        fromTurnId: UUID,
    ): List<TurnRecord>

    /**
     * Clear the `pending_resume_token` on whichever turn in the session carries
     * it (the open clarification is now consumed). No-op if no turn matches.
     * Called after a resume resolves so the token cannot be replayed.
     */
    fun clearPendingResumeToken(
        sessionId: UUID,
        resumeToken: String,
    )

    /**
     * Undo the most recent snapshot (reason `reset` or `edit_resend`): restore the
     * turns it captured to visible, discard every turn created after it, and
     * restore the snapshot's entity context. The snapshot is consumed (popped), so
     * repeated calls walk further back in history. Returns the rebuilt session, or
     * null when there is no snapshot to undo.
     *
     * Restored turns become [TurnStatus.DONE]: the pre-discard terminal status is
     * not retained (`iris_snapshots.turn_ids` is id-only), a v1-acceptable loss —
     * a discarded clarification cannot be meaningfully re-opened by an undo anyway.
     */
    fun restoreLatestSnapshot(sessionId: UUID): SessionRecord?

    /**
     * Replace the BFF-owned `current_display` JSON (per-bubble shaping directives —
     * sort/filter/paginate; Stage 3.2) so reload/hydration re-applies them. Returns
     * the updated session, or null if absent.
     */
    fun setCurrentDisplay(
        sessionId: UUID,
        currentDisplayJson: String,
    ): SessionRecord?

    /**
     * Replace the session `entity_context` JSON (PD-1/PD-4; Stage 3.2 T4) with the
     * bindings the agent echoed on its latest response, so the next turn's handoff
     * + excerpt carry the in-scope entities (coreference). Returns the updated
     * session, or null if absent.
     */
    fun setEntityContext(
        sessionId: UUID,
        entityContextJson: String,
    ): SessionRecord?

    fun snapshots(sessionId: UUID): List<SnapshotRecord>

    /** Transitional 1:1 session ↔ new-golem-thread binding (contracts §3). */
    fun getV2Thread(sessionId: UUID): String?

    fun putV2Thread(
        sessionId: UUID,
        v2ThreadId: String,
    )
}
