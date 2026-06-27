package org.tatrman.kantheon.iris.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import org.tatrman.kantheon.iris.action.EscalationHandler
import org.tatrman.kantheon.iris.action.ReaskHandler
import org.tatrman.kantheon.iris.action.TypedActionDispatcher
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.stream.IrisSse
import org.tatrman.kantheon.iris.stream.respondSse
import org.tatrman.kantheon.iris.v1.DoneEvent
import org.tatrman.kantheon.iris.v1.ErrorEvent
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import java.util.UUID

private val actionJson = Json { ignoreUnknownKeys = true }

/**
 * Typed-action surface (contracts §2.4), streamed as `IrisStreamEvent` SSE on the
 * same wire as `/v1/chat/stream`. The data-shaping kinds (`sort`/`filter`/
 * `paginate`) reshape the cached bubble BFF-side; `select_row` drills down;
 * `reask_agent` (PD-14) and `investigate` (PD-1) re-issue pinned to a target
 * agent; `chip_invocation` and `edit_resend` re-enter the normal turn flow. Any
 * unrecognised kind streams a terminal `NOT_IMPLEMENTED` so the wire stays
 * well-formed.
 */
fun Route.actionRoutes(
    store: SessionStore,
    auth: BearerAuthenticator,
    dispatcher: ChatDispatcher,
    typedActions: TypedActionDispatcher,
    reask: ReaskHandler,
    escalation: EscalationHandler,
    heartbeatMs: Long = 15_000,
) {
    post("/v1/action") {
        val caller = call.requireCaller(auth) ?: return@post
        val req = call.receive<TypedActionRequestDto>()
        val sessionId = call.ownedActionSession(store, caller, req.sessionId) ?: return@post
        val corr = call.request.headers["X-Correlation-Id"] ?: UUID.randomUUID().toString()

        when {
            typedActions.handles(req.action.kind) -> {
                val bubbleId = req.bubbleId
                val directive = typedActions.parse(req.action.kind, req.action.payloadJson)
                if (bubbleId.isNullOrBlank() || directive == null) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorBody("bad_request", "${req.action.kind} needs a bubbleId + a valid payload"),
                    )
                }
                call.respondSse(heartbeatMs) { emit ->
                    typedActions.shape(
                        caller,
                        sessionId,
                        bubbleId,
                        req.action.kind,
                        req.action.payloadJson,
                        directive,
                        corr,
                    ) { ev ->
                        emit(IrisSse.frame(ev))
                    }
                }
            }
            typedActions.handlesSelect(req.action.kind) -> {
                val bubbleId = req.bubbleId
                val rowIndex = typedActions.parseRowIndex(req.action.payloadJson)
                if (bubbleId.isNullOrBlank() || rowIndex == null || rowIndex < 0) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorBody("bad_request", "select_row needs a bubbleId + {rowIndex>=0}"),
                    )
                }
                call.respondSse(heartbeatMs) { emit ->
                    typedActions.select(caller, sessionId, bubbleId, rowIndex, corr) { ev ->
                        emit(IrisSse.frame(ev))
                    }
                }
            }
            req.action.kind == "reask_agent" -> {
                val payload =
                    runCatching { actionJson.decodeFromString<ReaskPayload>(req.action.payloadJson) }.getOrNull()
                val turnId = payload?.turnId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (payload == null || turnId == null || payload.targetAgentId.isBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorBody("bad_request", "reask_agent needs {turnId, targetAgentId}"),
                    )
                }
                val turn = store.getTurn(turnId)
                if (turn == null || turn.sessionId != sessionId) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorBody("not_found", "No such turn"))
                }
                // Record the misroute label (corrected_agent_id) + audit, then re-issue
                // the turn pinned to the chosen agent (routing_hint, Layer 0 via Themis).
                reask.record(caller.userId, turnId, turn.agentId, payload.targetAgentId)
                call.respondSse(heartbeatMs) { emit ->
                    dispatcher.runTurn(caller, sessionId, turn.question, null, corr, payload.targetAgentId) { ev ->
                        emit(IrisSse.frame(ev))
                    }
                }
            }
            req.action.kind == "investigate" -> {
                val payload =
                    runCatching { actionJson.decodeFromString<InvestigatePayload>(req.action.payloadJson) }.getOrNull()
                val turnId = payload?.turnId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (payload == null || turnId == null) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorBody("bad_request", "investigate needs {turnId, proposedQuestion?}"),
                    )
                }
                val turn = store.getTurn(turnId)
                if (turn == null || turn.sessionId != sessionId) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorBody("not_found", "No such turn"))
                }
                val proposed =
                    payload.proposedQuestion?.takeIf { it.isNotBlank() } ?: "Investigate: ${turn.question}"
                // Audit the escalation, then re-issue pinned to Pythia (routing_hint).
                // pythia has no registered client at Phase 3 → NO_AGENT_CLIENT terminal.
                escalation.recordEscalation(caller.userId, turn, proposed)
                call.respondSse(heartbeatMs) { emit ->
                    dispatcher.runTurn(caller, sessionId, proposed, null, corr, escalation.targetAgentId) { ev ->
                        emit(IrisSse.frame(ev))
                    }
                }
            }
            req.action.kind == "chip_invocation" -> {
                val payload =
                    runCatching {
                        actionJson.decodeFromString<ChipInvocationPayload>(req.action.payloadJson)
                    }.getOrNull()
                if (payload == null || payload.prompt.isBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorBody("bad_request", "chip_invocation needs a non-empty {prompt}"),
                    )
                }
                // A chip click is just a normal turn — route the prompt through Themis.
                call.respondSse(heartbeatMs) { emit ->
                    dispatcher.runTurn(caller, sessionId, payload.prompt, null, corr, null) { ev ->
                        emit(IrisSse.frame(ev))
                    }
                }
            }
            req.action.kind == "edit_resend" -> {
                val payload =
                    runCatching { actionJson.decodeFromString<EditResendPayload>(req.action.payloadJson) }.getOrNull()
                val fromTurnId = payload?.fromTurnId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (payload == null || fromTurnId == null || payload.editedQuestion.isBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorBody("bad_request", "edit_resend needs a non-empty {editedQuestion, fromTurnId}"),
                    )
                }
                // Snapshot (reason=edit_resend) + discard the tail, then re-run the turn.
                store.discardTurnsAfter(sessionId, fromTurnId)
                call.respondSse(heartbeatMs) { emit ->
                    dispatcher.runTurn(caller, sessionId, payload.editedQuestion, null, corr) { ev ->
                        emit(IrisSse.frame(ev))
                    }
                }
            }
            else -> {
                call.respondSse(heartbeatMs) { emit ->
                    val turnId = UUID.randomUUID().toString()
                    emit(IrisSse.frame(notImplemented(turnId, req.action.kind)))
                    emit(IrisSse.frame(doneFailed(turnId)))
                }
            }
        }
    }
}

/** Parse + ownership-check the session id; responds 400/404 and returns null on failure. */
private suspend fun ApplicationCall.ownedActionSession(
    store: SessionStore,
    caller: CallerIdentity,
    raw: String,
): UUID? {
    val id = runCatching { UUID.fromString(raw) }.getOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", "Invalid session id"))
        return null
    }
    val session = store.getSession(id)
    if (session == null || session.userId != caller.userId) {
        respond(HttpStatusCode.NotFound, ErrorBody("not_found", "No such session"))
        return null
    }
    return id
}

private fun notImplemented(
    turnId: String,
    kind: String,
): IrisStreamEvent =
    IrisStreamEvent
        .newBuilder()
        .setTurnId(turnId)
        .setSequence(1)
        .setError(
            ErrorEvent
                .newBuilder()
                .setCode("NOT_IMPLEMENTED")
                .setMessage("Typed action '$kind' is not available yet (Phase 3 Stage 3.2)")
                .setRecoverable(false),
        ).build()

private fun doneFailed(turnId: String): IrisStreamEvent =
    IrisStreamEvent
        .newBuilder()
        .setTurnId(turnId)
        .setSequence(2)
        .setDone(DoneEvent.newBuilder().setOutcome("failed"))
        .build()
