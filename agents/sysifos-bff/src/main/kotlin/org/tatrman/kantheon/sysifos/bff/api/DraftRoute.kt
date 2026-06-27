package org.tatrman.kantheon.sysifos.bff.api

import com.google.protobuf.util.JsonFormat
import com.google.protobuf.util.Timestamps
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.kantheon.bffbase.auth.BearerAuthenticator
import org.tatrman.kantheon.bffbase.auth.ErrorBody
import org.tatrman.kantheon.bffbase.auth.requireCaller
import org.tatrman.kantheon.sysifos.bff.session.DraftScratch
import org.tatrman.kantheon.sysifos.bff.session.SessionStore
import org.tatrman.kantheon.sysifos.bff.stream.SessionStreamBus
import org.tatrman.kantheon.sysifos.bff.write.DraftStateMachine
import org.tatrman.kantheon.sysifos.v1.Draft
import org.tatrman.kantheon.sysifos.v1.DraftStatus
import java.util.UUID

private val jsonParser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()

/**
 * Async write surface (contracts §3.2). `POST /drafts` accepts a `Draft` (kind +
 * `payload_json`), assigns server-side ids/status, returns `202 {draft_id,
 * PENDING}`, and drives the commit off-thread — clients watch `/stream` for
 * `DraftAck`/`DraftCommitted`/`DraftRejected`. `GET /drafts/{id}` returns the
 * current scratch state. Stage 1.3 exercises this with `DRAFT_CLIENT`.
 */
fun Route.draftRoutes(
    auth: BearerAuthenticator,
    sessions: SessionStore,
    scratch: DraftScratch,
    stateMachine: DraftStateMachine,
    bus: SessionStreamBus,
) {
    post("/drafts") {
        val caller = call.requireCaller(auth) ?: return@post
        val incoming =
            runCatching { Draft.newBuilder().also { jsonParser.merge(call.receiveText(), it) }.build() }
                .getOrElse {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorBody("VALIDATION_FAILED", "Malformed draft"),
                    )
                }

        val session = sessions.current(caller.userId, caller.tenantId)
        val draft =
            incoming
                .toBuilder()
                .setDraftId(UUID.randomUUID().toString())
                .setSessionId(session.sessionId)
                .setStatus(DraftStatus.DRAFT_PENDING)
                .setCreatedAt(Timestamps.fromMillis(System.currentTimeMillis()))
                .build()
        scratch.put(draft)

        call.respond(
            HttpStatusCode.Accepted,
            buildJsonObject {
                put("draft_id", JsonPrimitive(draft.draftId))
                put("status", JsonPrimitive(DraftStatus.DRAFT_PENDING.name))
            },
        )

        // Commit off-thread; events land on the session's SSE stream.
        call.application.launch { stateMachine.run(draft, caller, bus.sinkFor(session.sessionId)) }
    }

    get("/drafts/{id}") {
        val caller = call.requireCaller(auth) ?: return@get
        val draft = scratch.get(call.parameters["id"].orEmpty())
        // Only the owning session's user may read the draft (never leak existence).
        if (draft == null || sessions.get(draft.sessionId, caller.userId) == null) {
            return@get call.respond(HttpStatusCode.NotFound, ErrorBody("NOT_FOUND", "No such draft"))
        }
        call.respondProto(draft)
    }
}
