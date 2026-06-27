package org.tatrman.kantheon.pythia.persistence

import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Legal checkpoint reasons (contracts §4 `pythia_checkpoints.reason`). */
enum class CheckpointReason(
    val wire: String,
) {
    AWAITING("awaiting"),
    PLAN_REVISED("plan_revised"),
    BATCH_COMPLETED("batch_completed"),
}

/**
 * Diff-based scheduler-state checkpointer (design §5, contracts §3a/§4).
 *
 * - [checkpoint] folds the latest stored state, diffs the new state against it,
 *   and appends a row carrying only the delta (`diff`). The full snapshot lives
 *   in `scheduler_state` at the baseline (seq 0) and `{}` thereafter, so storage
 *   stays small across long investigations.
 * - [restore] folds the default state through every diff in seq order.
 * - [tryResume] is the status-conditional resume guard (first-signal-wins): it
 *   delegates to [InvestigationRepository.compareAndSetStatus], so a second
 *   resume of an already-resumed AWAITING_* affects nothing and returns false.
 */
class Checkpointer(
    private val checkpoints: CheckpointRepository,
    private val investigations: InvestigationRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val metrics: org.tatrman.kantheon.pythia.obs.PythiaMetrics? = null,
) {
    private val emptyDiff = JsonObject(emptyMap())

    /**
     * The last state checkpointed in *this* process, per investigation — so the diff
     * baseline is read in O(1) instead of re-folding the whole checkpoint history on
     * every write (which was O(n²) over an investigation's life). Empty after a restart,
     * where it falls back to [restore]; correctness is identical, only the cost differs.
     */
    private val lastState = ConcurrentHashMap<UUID, SchedulerState>()

    /** Snapshot [state] under [reason]; returns the assigned checkpoint seq. */
    fun checkpoint(
        investigationId: UUID,
        reason: CheckpointReason,
        state: SchedulerState,
    ): Int {
        val prior = lastState[investigationId] ?: restore(investigationId)
        val nextSeq = (checkpoints.latestSeq(investigationId) ?: -1) + 1
        val diff = SchedulerStateCodec.diff(prior, state)
        // Full snapshot only at the baseline; '{}' afterwards (the diff carries the delta).
        val fullSnapshot =
            if (nextSeq == 0) {
                SchedulerStateCodec.encode(state).toString()
            } else {
                emptyDiff.toString()
            }
        val diffJson = diff.toString()
        checkpoints.append(
            CheckpointRow(
                investigationId = investigationId,
                seq = nextSeq,
                reason = reason.wire,
                schedulerStateJson = fullSnapshot,
                diffJson = diffJson,
                takenAt = clock.instant(),
            ),
        )
        metrics?.checkpointBytes((fullSnapshot.length + diffJson.length).toLong())
        lastState[investigationId] = state
        return nextSeq
    }

    /** Reconstruct the latest scheduler state by folding stored diffs (default if none). */
    fun restore(investigationId: UUID): SchedulerState {
        val rows = checkpoints.loadAll(investigationId)
        if (rows.isEmpty()) return SchedulerState()
        val diffs = rows.map { SchedulerStateCodec.json.parseToJsonElement(it.diffJson) as JsonObject }
        return SchedulerStateCodec.fold(diffs)
    }

    /**
     * Status-conditional resume (idempotent). Returns true iff this caller won the
     * race from [from] → [to]; a second concurrent/duplicate signal returns false.
     */
    fun tryResume(
        investigationId: UUID,
        from: String,
        to: String,
    ): Boolean = investigations.compareAndSetStatus(investigationId, from, to)
}
