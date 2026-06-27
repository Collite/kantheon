package org.tatrman.kantheon.pythia.persistence

import java.util.UUID

/** Thrown when inserting an investigation whose id already exists. */
class DuplicateInvestigationException(
    id: UUID,
) : RuntimeException("duplicate pythia_investigations id $id")

/** A page of the PD-2 inbox list. */
data class InvestigationPage(
    val rows: List<InvestigationRecord>,
    val nextPage: Int?,
)

/**
 * Persistence for the investigation aggregate root (`pythia_investigations`).
 * The interface is the behavioural contract; [InMemoryInvestigationRepository]
 * is the unit/component fake and `ExposedInvestigationRepository` the Postgres
 * binding (real-PG fidelity = integration suite, planning-conventions §4).
 */
interface InvestigationRepository {
    fun insert(record: InvestigationRecord)

    fun findById(id: UUID): InvestigationRecord?

    /** Overwrite the mutable snapshot fields (status, plan, resolution, conclusion, usage, awaiting, …). */
    fun save(record: InvestigationRecord)

    /**
     * Status-conditional transition (resume idempotency, architecture §5): set
     * `status = to` **only if** the current status is [from]. Returns whether this
     * caller won the race (true = transitioned; false = someone already did). Also
     * clears the `awaiting*` columns when leaving an AWAITING_* state.
     */
    fun compareAndSetStatus(
        id: UUID,
        from: String,
        to: String,
    ): Boolean

    /** PD-2 inbox: per-user, optionally status-filtered, paged (newest first). */
    fun list(
        userId: String,
        statuses: Set<String>,
        page: Int,
        pageSize: Int,
    ): InvestigationPage

    /** Investigations parked past their TTL (for the Stage 1.3 sweeper). */
    fun findExpiredAwaiting(nowEpochMillis: Long): List<InvestigationRecord>
}

/** `pythia_hypotheses` — upsert keyed `(investigation_id, hyp_id)`. */
interface HypothesisRepository {
    fun upsert(record: HypothesisRecord)

    fun findByInvestigation(investigationId: UUID): List<HypothesisRecord>
}

/** `pythia_steps` — upsert keyed `(investigation_id, step_id)`. */
interface StepRepository {
    fun upsert(row: StepRow)

    fun findByInvestigation(investigationId: UUID): List<StepRow>
}

/** `pythia_handles` — insert keyed `(investigation_id, handle_id)`; inline BYTEA round-trips. */
interface HandleRepository {
    fun insert(row: HandleRow)

    fun findById(
        investigationId: UUID,
        handleId: String,
    ): HandleRow?

    fun findByInvestigation(investigationId: UUID): List<HandleRow>
}

/** `pythia_checkpoints` — append diff-based rows; read them back in seq order. */
interface CheckpointRepository {
    fun append(row: CheckpointRow)

    fun latestSeq(investigationId: UUID): Int?

    fun loadAll(investigationId: UUID): List<CheckpointRow>
}

/**
 * `pythia_events` — the authoritative event log. `append` assigns the next
 * per-investigation sequence gap-free. Concurrency: the in-memory fake synchronises
 * per investigation; the Exposed binding computes `max(sequence)+1` inside the
 * append transaction and, on a `(investigation_id, sequence)` PK 23505, recomputes
 * and **retries** (bounded) so concurrent emitters serialise rather than fail the
 * investigation. Events are appended from a single per-investigation orchestrator
 * coroutine in practice, so contention is rare regardless.
 */
interface EventRepository {
    /** Append [payloadJson] (kind [kind]); returns the assigned sequence. */
    fun append(
        investigationId: UUID,
        kind: String,
        payloadJson: String,
        emittedAtMillis: Long,
    ): Long

    /** Replay from [fromSeq] inclusive, ascending (the SSE bridge's PG-replay source). */
    fun replay(
        investigationId: UUID,
        fromSeq: Long,
    ): List<EventRow>
}
