package org.tatrman.kantheon.iris.domain

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One `iris_feedback` row (PD-3 / §3.2 — telemetry, not audit). */
data class FeedbackRecord(
    val turnId: UUID,
    val userId: String,
    val agentId: String,
    val verdict: String,
    val reason: String? = null,
    val comment: String? = null,
    val correctedAgentId: String? = null,
)

/**
 * Turn feedback persistence (PD-3, contracts §3.2). At Stage 3.2 the only writer
 * is `reask_agent` (PD-14): re-asking with a different agent is the strongest
 * misroute label — a `down` / `wrong_agent` verdict carrying `corrected_agent_id`.
 * The full 👍/👎 surface lands in Phase 4 Stage 4.3, which reads these rows.
 * Upsert key is `(turn_id, user_id)`.
 */
interface FeedbackStore {
    /** Upsert the `corrected_agent_id` for a turn (PD-14); records a wrong_agent down-vote. */
    fun upsertCorrectedAgent(
        turnId: UUID,
        userId: String,
        agentId: String,
        correctedAgentId: String,
    )

    /** Upsert a 👍/👎 verdict (PD-3, Stage 4.3). Preserves any `corrected_agent_id`. */
    fun upsertVerdict(
        turnId: UUID,
        userId: String,
        agentId: String,
        verdict: String,
        reason: String?,
        comment: String?,
    ): FeedbackRecord

    fun get(
        turnId: UUID,
        userId: String,
    ): FeedbackRecord?

    /** All feedback rows (offline `feedback-export`; small table, full scan is fine). */
    fun all(): List<FeedbackRecord>
}

/** In-memory [FeedbackStore] — the unit/component-test fake. */
class InMemoryFeedbackStore : FeedbackStore {
    private val rows = ConcurrentHashMap<Pair<UUID, String>, FeedbackRecord>()

    override fun upsertCorrectedAgent(
        turnId: UUID,
        userId: String,
        agentId: String,
        correctedAgentId: String,
    ) {
        val key = turnId to userId
        val existing = rows[key]
        rows[key] =
            (existing ?: FeedbackRecord(turnId, userId, agentId, verdict = "down"))
                .copy(verdict = "down", reason = "wrong_agent", correctedAgentId = correctedAgentId)
    }

    override fun upsertVerdict(
        turnId: UUID,
        userId: String,
        agentId: String,
        verdict: String,
        reason: String?,
        comment: String?,
    ): FeedbackRecord {
        val key = turnId to userId
        val existing = rows[key]
        val updated =
            (existing ?: FeedbackRecord(turnId, userId, agentId, verdict = verdict))
                .copy(agentId = agentId, verdict = verdict, reason = reason, comment = comment)
        rows[key] = updated
        return updated
    }

    override fun get(
        turnId: UUID,
        userId: String,
    ): FeedbackRecord? = rows[turnId to userId]

    override fun all(): List<FeedbackRecord> = rows.values.toList()
}
