package org.tatrman.kantheon.sysifos.bff.write

import com.google.protobuf.util.Timestamps
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.bffbase.auth.CallerIdentity
import org.tatrman.kantheon.sysifos.bff.session.DraftScratch
import org.tatrman.kantheon.sysifos.v1.Draft
import org.tatrman.kantheon.sysifos.v1.DraftAck
import org.tatrman.kantheon.sysifos.v1.DraftCommitted
import org.tatrman.kantheon.sysifos.v1.DraftKind
import org.tatrman.kantheon.sysifos.v1.DraftRejected
import org.tatrman.kantheon.sysifos.v1.DraftStatus
import org.tatrman.kantheon.sysifos.v1.SysifosStreamEvent

/**
 * Drives one draft through PENDING → COMMITTING → COMMITTED | REJECTED
 * (contracts §3.2). Emits `DraftAck` on entry, then `DraftCommitted` or
 * `DraftRejected` on the outcome, onto the caller's [DraftEventSink] (the SSE
 * bus). Status transitions are mirrored into [DraftScratch] so `GET /drafts/{id}`
 * stays answerable while the commit runs.
 *
 * Stage 1.3 ships the `DRAFT_CLIENT` committer; bulk/import committers
 * (`DRAFT_TRANSACTION_BATCH`, `DRAFT_LOADER_RUN_COMMIT`) join the map in 2.3/2.5.
 */
class DraftStateMachine(
    private val committers: Map<DraftKind, DraftCommitter>,
    private val scratch: DraftScratch,
) {
    private val log = LoggerFactory.getLogger(DraftStateMachine::class.java)

    suspend fun run(
        draft: Draft,
        caller: CallerIdentity,
        sink: DraftEventSink,
    ) {
        sink.emit(
            SysifosStreamEvent
                .newBuilder()
                .setDraftAck(DraftAck.newBuilder().setDraftId(draft.draftId).setAt(nowTs()))
                .build(),
        )
        scratch.updateStatus(draft.draftId, DraftStatus.DRAFT_COMMITTING)

        val committer = committers[draft.kind]
        val outcome =
            if (committer == null) {
                CommitOutcome.Rejected("UNSUPPORTED_DRAFT_KIND", emptyList())
            } else {
                runCatching { committer.commit(draft, caller, sink) }.getOrElse { e ->
                    log.warn("sysifos-bff: draft {} commit failed: {}", draft.draftId, e.message)
                    CommitOutcome.Rejected("MIDAS_UNAVAILABLE", emptyList())
                }
            }

        when (outcome) {
            is CommitOutcome.Committed -> {
                scratch.updateStatus(draft.draftId, DraftStatus.DRAFT_COMMITTED, outcome.artifactRef)
                sink.emit(
                    SysifosStreamEvent
                        .newBuilder()
                        .setDraftCommitted(
                            DraftCommitted
                                .newBuilder()
                                .setDraftId(draft.draftId)
                                .setArtifactRef(outcome.artifactRef)
                                .setCommittedCount(outcome.committedCount)
                                .setSkippedCount(outcome.skippedCount),
                        ).build(),
                )
            }
            is CommitOutcome.Rejected -> {
                scratch.updateStatus(draft.draftId, DraftStatus.DRAFT_REJECTED)
                sink.emit(
                    SysifosStreamEvent
                        .newBuilder()
                        .setDraftRejected(
                            DraftRejected
                                .newBuilder()
                                .setDraftId(draft.draftId)
                                .setReason(outcome.reason)
                                .addAllErrors(outcome.errors),
                        ).build(),
                )
            }
        }
    }

    private fun nowTs() = Timestamps.fromMillis(System.currentTimeMillis())
}
