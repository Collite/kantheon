package org.tatrman.kantheon.golem.persistence

import java.time.Instant
import java.util.UUID

/**
 * Turn terminal status. Persisted as a lowercase string in `golem_turns.status`
 * (contracts §4 — a denormalised column guarded by a CHECK, distinct from the
 * proto3-JSON in the JSONB columns); [wire] mirrors golem/v1 `Status` minus the
 * proto's `STATUS_` prefix.
 */
enum class GolemTurnStatus(
    val wire: String,
) {
    DONE("done"),
    FAILED("failed"),
    CLARIFICATION("clarification"),
    ;

    companion object {
        fun fromWire(s: String): GolemTurnStatus =
            entries.firstOrNull { it.wire == s }
                ?: error(
                    "unknown GolemTurnStatus wire value '$s' (DB column drift — expected one of ${entries.map {
                        it.wire
                    }})",
                )
    }
}

/**
 * One `golem_turns` row (contracts §4) — the persisted record of a finished turn.
 * One turn = one row; conversation memory is Iris's job. AMEND/DRILL read
 * [planJson] + [currentViewJson] of a prior turn back (resolved in Stage 2.4).
 *
 * JSONB columns are carried as opaque JSON strings (proto3-JSON / Rule-7 shapes);
 * the repository serialises/deserialises them by identity, treating the payload
 * as opaque exactly as iris-bff does for its envelope snapshots.
 */
data class GolemTurnRecord(
    val id: UUID,
    val requestId: UUID,
    val golemId: String,
    val userId: String,
    val tenantId: String,
    val question: String,
    val resolvedIntentJson: String,
    val planJson: String,
    val envelopesJson: String,
    val currentViewJson: String? = null,
    val stepRecordsJson: String = "[]",
    val resourceUsageJson: String = "{}",
    val pendingResumeToken: String? = null,
    val status: GolemTurnStatus,
    val createdAt: Instant,
    val finalisedAt: Instant? = null,
)
