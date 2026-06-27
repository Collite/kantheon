package org.tatrman.kantheon.iris.domain

import java.time.Instant
import java.util.UUID

/** Turn lifecycle status (persisted lowercase per contracts §3 `iris_turns.status`). */
enum class TurnStatus(
    val wire: String,
) {
    DONE("done"),
    FAILED("failed"),
    CLARIFICATION("clarification"),
    DISCARDED("discarded"),
    ;

    companion object {
        fun fromWire(s: String): TurnStatus =
            entries.firstOrNull { it.wire == s }
                ?: error(
                    "unknown TurnStatus wire value '$s' (DB column drift — expected one of ${entries.map { it.wire }})",
                )
    }
}

/** Turn origin (Hebe co-design; `iris/v1.TurnOrigin` ↔ `iris_turns.origin`). */
enum class TurnOriginKind(
    val wire: String,
) {
    USER("user"),
    SCHEDULED("scheduled"),
    ;

    companion object {
        fun fromWire(s: String): TurnOriginKind =
            entries.firstOrNull { it.wire == s }
                ?: error(
                    "unknown TurnOriginKind wire value '$s' (DB column drift — expected one of ${entries.map {
                        it.wire
                    }})",
                )
    }
}

/** `iris_sessions` row. JSON columns are carried as opaque JSON strings. */
data class SessionRecord(
    val sessionId: UUID,
    val userId: String,
    val tenantId: String,
    val entityContextJson: String = "[]",
    val currentDisplayJson: String = "{}",
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** `iris_turns` row. */
data class TurnRecord(
    val turnId: UUID,
    val sessionId: UUID,
    val seq: Int,
    val agentId: String,
    val question: String,
    val status: TurnStatus,
    val origin: TurnOriginKind = TurnOriginKind.USER,
    val originRef: String? = null,
    val artifactRef: String? = null,
    val envelopeJson: String? = null,
    val displayedBlockIds: List<String> = emptyList(),
    val alternatesOffered: List<String> = emptyList(),
    val pendingResumeToken: String? = null,
    val resumeIssuerAgentId: String? = null,
    val createdAt: Instant,
)

/** `iris_snapshots` row — captures the conversation state restorable by undo. */
data class SnapshotRecord(
    val snapshotId: UUID,
    val sessionId: UUID,
    val reason: String,
    val entityContextJson: String,
    val turnIds: List<UUID>,
    val createdAt: Instant,
)

/** A session plus its visible (non-discarded) turns, ordered by seq. */
data class SessionWithTurns(
    val session: SessionRecord,
    val turns: List<TurnRecord>,
)

/** Lightweight session row for the session list (`GET /v1/sessions`). */
data class SessionSummary(
    val sessionId: UUID,
    val title: String,
    val turnCount: Int,
    val updatedAt: Instant,
)

/**
 * Fields a caller supplies to append a turn; `seq`, `turnId`, and `createdAt`
 * are assigned by the store.
 */
data class NewTurn(
    val sessionId: UUID,
    val turnId: UUID = UUID.randomUUID(),
    val agentId: String,
    val question: String,
    val status: TurnStatus,
    val origin: TurnOriginKind = TurnOriginKind.USER,
    val originRef: String? = null,
    val artifactRef: String? = null,
    val envelopeJson: String? = null,
    val displayedBlockIds: List<String> = emptyList(),
    val alternatesOffered: List<String> = emptyList(),
    val pendingResumeToken: String? = null,
    val resumeIssuerAgentId: String? = null,
)
