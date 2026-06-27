package org.tatrman.kantheon.iris.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.tatrman.kantheon.iris.domain.FeedbackStore
import org.tatrman.kantheon.iris.domain.SessionStore
import java.util.UUID

/** `POST /v1/turns/{turn_id}/feedback` body (PD-3, contracts §2.9). */
@Serializable
data class FeedbackRequestDto(
    val verdict: String,
    val reason: String? = null,
    val comment: String? = null,
)

@Serializable
data class FeedbackResponseDto(
    val turnId: String,
    val verdict: String,
    val reason: String? = null,
    val correctedAgentId: String? = null,
)

private val VALID_VERDICTS = setOf("up", "down")
private val VALID_REASONS = setOf("wrong_data", "wrong_agent", "wrong_format", "too_slow", "other")

/**
 * Turn feedback (PD-3, contracts §2.9): a 👍/👎 verdict + optional reason, upserted
 * per `(turn_id, user_id)`. Telemetry, not audit — no agent sees it at runtime; it
 * exports offline to per-agent eval corpora (`just feedback-export`). The turn's
 * `agent_id` is the join key for the misroute label; a re-ask's
 * `corrected_agent_id` (PD-14) is preserved.
 */
fun Route.feedbackRoutes(
    sessions: SessionStore,
    feedback: FeedbackStore,
    auth: BearerAuthenticator,
    metrics: RoutingMetrics = RoutingMetrics.NOOP,
) {
    post("/v1/turns/{turnId}/feedback") {
        val caller = call.requireCaller(auth) ?: return@post
        val turnId = call.parameters["turnId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (turnId == null) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", "Invalid turn id"))
        }
        val req = call.receive<FeedbackRequestDto>()
        if (req.verdict !in VALID_VERDICTS || (req.reason != null && req.reason !in VALID_REASONS)) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorBody("bad_request", "verdict ∈ $VALID_VERDICTS; reason ∈ $VALID_REASONS"),
            )
        }
        // Owner-check via the turn's session, and resolve the answering agent.
        val turn = sessions.getTurn(turnId)
        val session = turn?.let { sessions.getSession(it.sessionId) }
        if (turn == null || session == null || session.userId != caller.userId) {
            return@post call.respond(HttpStatusCode.NotFound, ErrorBody("not_found", "No such turn"))
        }
        val rec = feedback.upsertVerdict(turnId, caller.userId, turn.agentId, req.verdict, req.reason, req.comment)
        metrics.recordFeedback(turn.agentId, req.verdict, req.reason ?: "none")
        call.respond(FeedbackResponseDto(turnId.toString(), rec.verdict, rec.reason, rec.correctedAgentId))
    }
}
