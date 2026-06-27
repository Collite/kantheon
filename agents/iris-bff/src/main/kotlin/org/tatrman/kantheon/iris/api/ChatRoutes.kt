package org.tatrman.kantheon.iris.api

import com.google.protobuf.util.JsonFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.stream.IrisSse
import org.tatrman.kantheon.iris.stream.respondSse
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import java.util.UUID

/**
 * Chat surface (contracts §2.2): SSE stream + sync turn + clarification resume,
 * all dispatched (transitionally) to new-golem /v2 through [ChatDispatcher].
 */
fun Route.chatRoutes(
    store: SessionStore,
    auth: BearerAuthenticator,
    dispatcher: ChatDispatcher,
    heartbeatMs: Long = 15_000,
) {
    val printer = JsonFormat.printer().omittingInsignificantWhitespace()

    // POST /v1/chat/stream → text/event-stream of IrisStreamEvent.
    post("/v1/chat/stream") {
        val caller = call.requireCaller(auth) ?: return@post
        val req = call.receive<ChatTurnRequestDto>()
        val sessionId = call.ownedSession(store, caller, req.sessionId) ?: return@post
        val corr = call.correlationId()
        call.respondSse(heartbeatMs) { emit ->
            dispatcher.runTurn(caller, sessionId, req.question, req.desiredFormat, corr, req.routingHintAgentId) { ev ->
                emit(IrisSse.frame(ev))
            }
        }
    }

    // POST /v1/chat/turn → terminal IrisStreamEvent.envelope JSON (sync convenience).
    post("/v1/chat/turn") {
        val caller = call.requireCaller(auth) ?: return@post
        val req = call.receive<ChatTurnRequestDto>()
        val sessionId = call.ownedSession(store, caller, req.sessionId) ?: return@post
        val corr = call.correlationId()
        var terminal: IrisStreamEvent? = null
        dispatcher.runTurn(caller, sessionId, req.question, req.desiredFormat, corr, req.routingHintAgentId) { ev ->
            if (ev.hasEnvelope() || ev.hasError()) terminal = ev
        }
        if (terminal != null) {
            call.respondText(printer.print(terminal), ContentType.Application.Json)
        } else {
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // POST /v1/chat/resume → text/event-stream (routed to the clarification issuer).
    post("/v1/chat/resume") {
        val caller = call.requireCaller(auth) ?: return@post
        val req = call.receive<ChatResumeRequestDto>()
        val sessionId = call.ownedSession(store, caller, req.sessionId) ?: return@post
        val issuer = dispatcher.resumeIssuer(sessionId, req.resumeToken)
        if (issuer == null) {
            call.respond(HttpStatusCode.NotFound, ErrorBody("not_found", "No open clarification for that token"))
            return@post
        }
        val corr = call.correlationId()
        call.respondSse(heartbeatMs) { emit ->
            dispatcher.runResume(caller, sessionId, req, corr) { ev ->
                emit(IrisSse.frame(ev))
            }
        }
    }
}

private fun ApplicationCall.correlationId(): String =
    request.headers["X-Correlation-Id"] ?: UUID.randomUUID().toString()

/** Parse + ownership-check a session id; responds 400/404 and returns null on failure. */
private suspend fun ApplicationCall.ownedSession(
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
