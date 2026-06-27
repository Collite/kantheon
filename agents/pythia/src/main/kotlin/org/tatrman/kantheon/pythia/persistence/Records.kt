package org.tatrman.kantheon.pythia.persistence

import java.time.Instant
import java.util.UUID

/**
 * Persisted investigation row (`pythia_investigations`, contracts §4). JSONB
 * columns are carried as opaque proto3-JSON strings (the repository serialises /
 * deserialises by identity, treating the payload as opaque — golem idiom). The
 * denormalised `status` column drives the lifecycle; `awaiting*` columns drive
 * the Stage 1.3 TTL sweeper.
 */
data class InvestigationRecord(
    val id: UUID,
    val parentId: UUID? = null,
    val callerJson: String,
    val question: String,
    val requestJson: String,
    val status: String,
    val resolutionJson: String? = null,
    val planJson: String? = null,
    val conclusionJson: String? = null,
    val resourceUsageJson: String = "{}",
    val warningsJson: String = "[]",
    val awaitingSince: Instant? = null,
    val awaitingTtlUntil: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val finalisedAt: Instant? = null,
)

/** One `pythia_hypotheses` row — keyed `(investigation_id, hyp_id)`; upserted. */
data class HypothesisRecord(
    val investigationId: UUID,
    val hypId: String,
    val parentHypId: String? = null,
    val bodyJson: String,
    val status: String,
    val confidence: Double? = null,
)

/** One `pythia_steps` row — keyed `(investigation_id, step_id)`; upserted. */
data class StepRow(
    val investigationId: UUID,
    val stepId: String,
    val nodeId: String,
    val bodyJson: String,
    val status: String,
    val outputHandleJson: String? = null,
)

/**
 * One `pythia_handles` row. `inlineData` carries the capped Arrow IPC payload for
 * a `PgResultSnapshot` (divergence 1); null for reference handles.
 */
data class HandleRow(
    val investigationId: UUID,
    val handleId: String,
    val kind: String,
    val bodyJson: String,
    val inlineData: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is HandleRow &&
                    investigationId == other.investigationId &&
                    handleId == other.handleId &&
                    kind == other.kind &&
                    bodyJson == other.bodyJson &&
                    inlineData.contentEqualsOrNull(other.inlineData)
            )

    override fun hashCode(): Int {
        var result = investigationId.hashCode()
        result = 31 * result + handleId.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + bodyJson.hashCode()
        result = 31 * result + (inlineData?.contentHashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> contentEquals(other)
    }

/** One `pythia_checkpoints` row (diff-based; contracts §4 + §3a). */
data class CheckpointRow(
    val investigationId: UUID,
    val seq: Int,
    val reason: String,
    val schedulerStateJson: String,
    val diffJson: String,
    val takenAt: Instant,
)

/** One `pythia_events` row — the authoritative, gap-free, sequence-numbered log. */
data class EventRow(
    val investigationId: UUID,
    val sequence: Long,
    val emittedAt: Instant,
    val kind: String,
    val payloadJson: String,
)
