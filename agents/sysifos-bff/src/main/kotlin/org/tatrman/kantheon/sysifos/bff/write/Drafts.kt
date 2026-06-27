package org.tatrman.kantheon.sysifos.bff.write

import org.tatrman.kantheon.bffbase.auth.CallerIdentity
import org.tatrman.kantheon.sysifos.v1.Draft
import org.tatrman.kantheon.sysifos.v1.DraftKind
import org.tatrman.kantheon.sysifos.v1.FieldValidationError
import org.tatrman.kantheon.sysifos.v1.SysifosStreamEvent

/** Where a write should travel in the hybrid model (architecture §6). */
enum class WritePath { SYNC, ASYNC }

/**
 * Routes a `DraftKind` to the sync proxy or the async draft path. Single records
 * go SYNC (the CRUD proxy — feels instant); bulk + import go ASYNC (the draft +
 * SSE path). The `/drafts` endpoint still *accepts* any kind — Stage 1.3 drives a
 * single `DRAFT_CLIENT` through the async machinery to prove it before bulk/import
 * need it (Stage 2.3/2.5).
 */
object WriteDispatcher {
    fun route(kind: DraftKind): WritePath =
        when (kind) {
            DraftKind.DRAFT_TRANSACTION_BATCH, DraftKind.DRAFT_LOADER_RUN_COMMIT -> WritePath.ASYNC
            else -> WritePath.SYNC
        }
}

/** Sink for the draft state machine's stream events (the SSE bus implements it). */
fun interface DraftEventSink {
    suspend fun emit(event: SysifosStreamEvent)
}

/** The outcome of committing one draft to Midas-core. */
sealed interface CommitOutcome {
    data class Committed(
        val artifactRef: String,
        val committedCount: Int = 1,
        val skippedCount: Int = 0,
    ) : CommitOutcome

    data class Rejected(
        val reason: String,
        val errors: List<FieldValidationError> = emptyList(),
    ) : CommitOutcome
}

/**
 * Commits one draft to Midas-core (one impl per supported `DraftKind`). The
 * [sink] lets a committer stream intermediate events before the terminal outcome
 * — the bulk committer emits a `BatchRowResult` per row (S3). Single-record
 * committers ignore it.
 */
fun interface DraftCommitter {
    suspend fun commit(
        draft: Draft,
        caller: CallerIdentity,
        sink: DraftEventSink,
    ): CommitOutcome
}
