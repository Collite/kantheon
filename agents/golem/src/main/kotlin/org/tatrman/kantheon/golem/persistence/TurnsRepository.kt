package org.tatrman.kantheon.golem.persistence

import java.util.UUID

/**
 * Thrown by [TurnsRepository.insert] when a turn with the same id already exists.
 * Both the in-memory and Exposed implementations raise this (carry-in parity,
 * Stage 2.4 T0) so callers get one exception type regardless of backend.
 */
class DuplicateTurnException(
    id: UUID,
) : RuntimeException("duplicate golem_turns id $id")

/**
 * Persistence for finished Golem turns (`golem_turns`, contracts §4). The
 * interface is the behavioural contract; [InMemoryTurnsRepository] is the
 * unit/component-test fake and `ExposedTurnsRepository` the Postgres binding.
 * Real-PG fidelity is exercised in the separate integration-test suite
 * (planning-conventions §4).
 *
 * `artifact_ref` handed to Iris = `golem_turns.id`. No checkpoint table and no
 * event log — Golems don't pause; a clarification is a terminal-and-resume, not
 * a mid-turn checkpoint.
 */
interface TurnsRepository {
    /** Persist a finished turn. Ids are caller-assigned (== response id). */
    fun insert(turn: GolemTurnRecord)

    /** The turn with this id (== `ConversationalResponse.id`), or null. */
    fun findById(id: UUID): GolemTurnRecord?

    /**
     * The most recent turn for an Iris request id (== `GolemRequest.id`). A
     * clarification + its resume share a request id; the latest row wins.
     */
    fun findByRequestId(requestId: UUID): GolemTurnRecord?

    /**
     * The turn that produced a given envelope bubble (its persisted `current_view`
     * carries the `bubbleId`). Powers AMEND/DRILL prior-view resolution and row-detail
     * selection (contracts §4) — the producing turn's `plan` + `current_view` + rows are
     * read back.
     *
     * **Scoped to the caller** ([userId], [tenantId]) — a client supplies the `bubbleId`,
     * so the lookup must not return another user's or tenant's turn (H2 — no cross-tenant
     * read via a leaked/guessed bubble id). A bubble owned by a different caller resolves
     * to null (treated as stale).
     */
    fun findByBubbleId(
        bubbleId: String,
        userId: String,
        tenantId: String,
    ): GolemTurnRecord?
}
