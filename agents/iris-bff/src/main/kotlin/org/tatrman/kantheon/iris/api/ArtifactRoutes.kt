package org.tatrman.kantheon.iris.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.iris.artifact.ArtifactService
import org.tatrman.kantheon.iris.domain.ArtifactKind
import org.tatrman.kantheon.iris.domain.ArtifactPatch
import org.tatrman.kantheon.iris.domain.ArtifactRecord
import org.tatrman.kantheon.iris.domain.ArtifactStore
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.stream.respondSse
import java.util.UUID

/**
 * Artifact surface (PD-6, contracts §2.8): pins & dashboards. Pin capture reads
 * the producing turn's terminal envelope; refresh re-executes deterministically.
 * Ownership is enforced here (the trust boundary) — every id-keyed call loads the
 * artifact and rejects `artifact.userId != caller.userId` with a 404.
 */
fun Route.artifactRoutes(
    sessions: SessionStore,
    artifacts: ArtifactStore,
    service: ArtifactService,
    auth: BearerAuthenticator,
    heartbeatMs: Long = 15_000,
) {
    post("/v1/artifacts") {
        val caller = call.requireCaller(auth) ?: return@post
        val req = call.receive<ArtifactCreateDto>()
        if (req.name.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", "name is required"))
        }
        when (req.kind) {
            "pin" -> {
                val turnId = req.turnId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (turnId == null || req.bubbleId.isNullOrBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorBody("bad_request", "pin needs {turnId, bubbleId, name}"),
                    )
                }
                val turn = sessions.getTurn(turnId)
                val session = turn?.let { sessions.getSession(it.sessionId) }
                if (turn == null || session == null || session.userId != caller.userId) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorBody("not_found", "No such turn"))
                }
                val pin =
                    service.capturePin(caller, turn, session, req.bubbleId, req.name)
                        ?: return@post call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorBody("no_envelope", "Turn has no envelope to pin"),
                        )
                call.respond(HttpStatusCode.Created, pin.toDto())
            }
            "dashboard" -> {
                val memberIds = req.memberIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                val dash =
                    service.createDashboard(
                        caller,
                        req.name,
                        memberIds,
                        req.layoutJson,
                        req.templateId,
                        req.paramsJson,
                        req.refreshMode,
                    )
                call.respond(HttpStatusCode.Created, dash.toDto())
            }
            else ->
                call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", "unknown kind '${req.kind}'"))
        }
    }

    get("/v1/artifacts") {
        val caller = call.requireCaller(auth) ?: return@get
        val kind = call.request.queryParameters["kind"]?.let { runCatching { ArtifactKind.fromWire(it) }.getOrNull() }
        call.respond(ArtifactsListDto(artifacts.list(caller.userId, kind).map { it.toDto() }))
    }

    get("/v1/artifacts/{id}") {
        val (_, artifact) = call.ownedArtifact(auth, artifacts) ?: return@get
        call.respond(artifact.toDto())
    }

    patch("/v1/artifacts/{id}") {
        val (_, artifact) = call.ownedArtifact(auth, artifacts) ?: return@patch
        val body = call.receive<ArtifactPatchDto>()
        val updated =
            artifacts.patch(
                artifact.artifactId,
                ArtifactPatch(
                    name = body.name,
                    paramsJson = body.paramsJson,
                    layoutJson = body.layoutJson,
                    memberIds = body.memberIds?.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() },
                    refreshMode = body.refreshMode,
                ),
            )
        if (updated == null) {
            call.respond(HttpStatusCode.NotFound, ErrorBody("not_found", "No such artifact"))
        } else {
            call.respond(updated.toDto())
        }
    }

    delete("/v1/artifacts/{id}") {
        val (_, artifact) = call.ownedArtifact(auth, artifacts) ?: return@delete
        artifacts.delete(artifact.artifactId)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/v1/artifacts/{id}/refresh") {
        val (caller, artifact) = call.ownedArtifact(auth, artifacts) ?: return@post
        val updated =
            service.refresh(caller, artifact)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorBody("not_found", "No such artifact"))
        call.respond(updated.toDto())
    }

    // Dashboard open: refresh members concurrently (wall-clock = slowest member, not
    // the sum), then stream one frame per member in declared order. A missing or
    // non-owned member gets an explicit `pin-error` frame rather than silently
    // vanishing (PD-6 "never silently wrong").
    get("/v1/dashboards/{id}/open") {
        val (caller, dashboard) = call.ownedArtifact(auth, artifacts) ?: return@get
        if (dashboard.kind != ArtifactKind.DASHBOARD) {
            return@get call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", "Not a dashboard"))
        }
        call.respondSse(heartbeatMs) { emit ->
            val frames =
                coroutineScope {
                    dashboard.memberIds
                        .map { memberId ->
                            async {
                                val pin = artifacts.get(memberId)
                                when {
                                    pin == null -> memberErrorFrame(memberId, "not_found")
                                    pin.userId != caller.userId -> memberErrorFrame(memberId, "forbidden")
                                    else -> {
                                        // Refresh on open when either the dashboard or the pin opts in.
                                        val refreshOnOpen =
                                            dashboard.refreshMode == "on_open" || pin.refreshMode == "on_open"
                                        val result = if (refreshOnOpen) service.refresh(caller, pin) ?: pin else pin
                                        "event: pin\ndata: ${Json.encodeToString(result.toDto())}\n\n"
                                    }
                                }
                            }
                        }.awaitAll()
                }
            for (frame in frames) emit(frame)
            emit("event: done\ndata: {}\n\n")
        }
    }
}

/** An explicit dashboard-member failure frame (missing or not owned) — surfaced
 *  on the wire so the member doesn't silently disappear from the dashboard. */
private fun memberErrorFrame(
    memberId: UUID,
    reason: String,
): String {
    val payload =
        buildJsonObject {
            put("memberId", memberId.toString())
            put("reason", reason)
        }
    return "event: pin-error\ndata: $payload\n\n"
}

/** Parse `{id}`, load the artifact, enforce ownership; responds 400/404 and returns null on failure. */
private suspend fun ApplicationCall.ownedArtifact(
    auth: BearerAuthenticator,
    store: ArtifactStore,
): Pair<CallerIdentity, ArtifactRecord>? {
    val caller = requireCaller(auth) ?: return null
    val id = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", "Invalid artifact id"))
        return null
    }
    val artifact = store.get(id)
    if (artifact == null || artifact.userId != caller.userId) {
        respond(HttpStatusCode.NotFound, ErrorBody("not_found", "No such artifact"))
        return null
    }
    return caller to artifact
}
