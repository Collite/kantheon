package org.tatrman.kantheon.iris.action

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.kantheon.iris.audit.AuditStore
import org.tatrman.kantheon.iris.domain.FeedbackStore
import java.time.Instant
import java.util.UUID

/**
 * Records a `reask_agent` (PD-14, contracts §2.4): re-asking a turn with a
 * different agent is the strongest misroute label, so it upserts
 * `iris_feedback.corrected_agent_id` (a `down` / `wrong_agent` verdict) and writes
 * a `typed_action` audit row. The actual re-issue (with `routing_hint =
 * targetAgentId`, Layer 0 through Themis) is driven by the route via the
 * ChatDispatcher — this only records the feedback + audit side-effect.
 */
class ReaskHandler(
    private val feedback: FeedbackStore,
    private val audit: AuditStore,
    private val now: () -> Instant = Instant::now,
) {
    fun record(
        userId: String,
        turnId: UUID,
        originalAgentId: String,
        targetAgentId: String,
    ) {
        feedback.upsertCorrectedAgent(turnId, userId, originalAgentId, targetAgentId)
        audit.append(
            userId = userId,
            eventKind = "typed_action",
            payloadJson =
                buildJsonObject {
                    put("kind", JsonPrimitive("reask_agent"))
                    put("turnId", JsonPrimitive(turnId.toString()))
                    put("fromAgentId", JsonPrimitive(originalAgentId))
                    put("correctedAgentId", JsonPrimitive(targetAgentId))
                }.toString(),
            ts = now(),
        )
    }
}
