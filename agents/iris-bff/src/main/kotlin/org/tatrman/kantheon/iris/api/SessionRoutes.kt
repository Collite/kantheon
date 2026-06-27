package org.tatrman.kantheon.iris.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2Client
import org.tatrman.kantheon.iris.dispatch.golemv2.V2RefreshRequest
import org.tatrman.kantheon.iris.domain.SessionRecord
import org.tatrman.kantheon.iris.domain.SessionStore
import java.util.UUID

private val sessionLog = LoggerFactory.getLogger("org.tatrman.kantheon.iris.api.SessionRoutes")

/**
 * Session surface (contracts §2.1). All endpoints bearer-authenticated; a
 * session is visible only to its owning user (404 — never leak existence — when
 * the caller is not the owner).
 *
 * `golemClient` (Stage 2.2 BFF-grow): when present, session-create eagerly opens
 * the golem v2 thread and mirrors its discovery (chips / example questions /
 * version) onto the response, and `/v1/refresh` proxies golem's metadata
 * refresh — so the FE never calls golem directly. Null (tests / no backend) →
 * thin session, best-effort: creation never fails on golem being down.
 */
fun Route.sessionRoutes(
    store: SessionStore,
    auth: BearerAuthenticator,
    golemClient: GolemV2Client? = null,
    staticChips: StaticChipSource? = null,
) {
    route("/v1") {
        // POST /v1/session → fresh Session (+ curated static chips + golem discovery)
        post("/session") {
            val caller = call.requireCaller(auth) ?: return@post
            val session = store.createSession(caller.userId, caller.tenantId)
            val discovery = golemClient?.let { fetchDiscovery(it, store, session, caller) } ?: SessionDiscovery()
            // Curated chips (Stage 3.2 T3) lead, then the agent's heuristic chips.
            val merged = discovery.copy(staticChips = (staticChips?.chips() ?: emptyList()) + discovery.staticChips)
            call.respond(HttpStatusCode.Created, session.toDto(discovery = merged))
        }

        // POST /v1/refresh → proxy golem metadata refresh (`/refresh` slash command)
        post("/refresh") {
            val caller = call.requireCaller(auth) ?: return@post
            if (golemClient == null) {
                return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorBody("refresh_unavailable", "No refresh backend configured"),
                )
            }
            val service = call.request.queryParameters["service"]
            val resp =
                golemClient.refresh(
                    V2RefreshRequest(service),
                    caller.userId,
                    UUID.randomUUID().toString(),
                    caller.bearer,
                )
            call.respond(
                RefreshResponseDto(
                    resp.results.map { RefreshResultDto(it.service, it.status, it.detail, it.version) },
                ),
            )
        }

        // GET /v1/sessions → [SessionSummary] for the caller
        get("/sessions") {
            val caller = call.requireCaller(auth) ?: return@get
            call.respond(store.listSessions(caller.userId).map { it.toDto() })
        }

        // GET /v1/session/{id} → Session with turns
        get("/session/{id}") {
            val caller = call.requireCaller(auth) ?: return@get
            val id = call.pathUuid("id") ?: return@get
            val session = store.getSession(id)
            if (!session.ownedBy(caller)) return@get call.notFound("session")
            val withTurns = store.getSessionWithTurns(id)!!
            call.respond(withTurns.toDto())
        }

        // POST /v1/session/{id}/reset → snapshot taken, turns cleared
        post("/session/{id}/reset") {
            val caller = call.requireCaller(auth) ?: return@post
            val id = call.pathUuid("id") ?: return@post
            if (!store.getSession(id).ownedBy(caller)) return@post call.notFound("session")
            call.respond(store.reset(id).toDto())
        }

        // POST /v1/session/{id}/undo → restore the latest snapshot (reset / edit_resend)
        post("/session/{id}/undo") {
            val caller = call.requireCaller(auth) ?: return@post
            val id = call.pathUuid("id") ?: return@post
            if (!store.getSession(id).ownedBy(caller)) return@post call.notFound("session")
            val restored = store.restoreLatestSnapshot(id)
            if (restored == null) {
                call.respond(HttpStatusCode.Conflict, ErrorBody("nothing_to_undo", "No snapshot to restore"))
            } else {
                call.respond(store.getSessionWithTurns(id)!!.toDto())
            }
        }

        // GET /v1/session/{id}/turn/{turnId} → stored envelope(s) for one turn
        get("/session/{id}/turn/{turnId}") {
            val caller = call.requireCaller(auth) ?: return@get
            val id = call.pathUuid("id") ?: return@get
            val turnId = call.pathUuid("turnId") ?: return@get
            if (!store.getSession(id).ownedBy(caller)) return@get call.notFound("session")
            val turn = store.getTurn(turnId)
            if (turn == null || turn.sessionId != id) return@get call.notFound("turn")
            call.respond(turn.toEnvelopeDto())
        }
    }
}

/** Open the golem v2 thread for a fresh session and mirror its discovery onto
 *  the BFF session. Best-effort: on any golem failure, returns empty discovery
 *  (the session is already created) so session-create never hard-fails. */
private suspend fun fetchDiscovery(
    client: GolemV2Client,
    store: SessionStore,
    session: SessionRecord,
    caller: CallerIdentity,
): SessionDiscovery =
    runCatching {
        val v2 =
            client.createSession(
                threadId = session.sessionId.toString(),
                userId = caller.userId,
                correlationId = UUID.randomUUID().toString(),
                bearer = caller.bearer,
            )
        store.putV2Thread(session.sessionId, v2.thread_id)
        SessionDiscovery(
            staticChips = v2.static_chips.map { SessionChipDto(it.display, it.prompt) },
            exampleQuestions = v2.example_questions,
            packages = v2.packages,
            agentVersion = v2.agent_version,
        )
    }.getOrElse { e ->
        sessionLog.warn("session discovery from golem failed (best-effort; thin session): {}", e.message)
        SessionDiscovery()
    }

private fun SessionRecord?.ownedBy(caller: CallerIdentity): Boolean = this != null && userId == caller.userId

private suspend fun io.ktor.server.application.ApplicationCall.pathUuid(name: String): UUID? {
    val raw = parameters[name]
    val parsed = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (parsed == null) {
        respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", "Invalid UUID for '$name'"))
    }
    return parsed
}

private suspend fun io.ktor.server.application.ApplicationCall.notFound(what: String) =
    respond(HttpStatusCode.NotFound, ErrorBody("not_found", "No such $what"))
