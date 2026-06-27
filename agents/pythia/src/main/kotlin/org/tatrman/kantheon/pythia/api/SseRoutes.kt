package org.tatrman.kantheon.pythia.api

import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import org.tatrman.kantheon.pythia.auth.Admission
import org.tatrman.kantheon.pythia.auth.canRead
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.persistence.EventRepository
import org.tatrman.kantheon.pythia.persistence.InvestigationRepository
import java.util.UUID

/**
 * The SSE event bridge (contracts §2, divergence 4): iris-bff consumes this rather
 * than NATS directly. `GET /v1/investigations/{id}/events?from_seq=N` replays the
 * PG log from `from_seq` (Phase 1 = PG replay; the NATS live-tail attaches when a
 * real publisher is wired — integration-deferred). Each frame is one
 * `InvestigationEvent` as proto-JSON.
 */
fun Route.sseRoutes(
    investigations: InvestigationRepository,
    events: EventRepository,
    assembler: ArtifactAssembler,
    admission: Admission,
) {
    sse("/v1/investigations/{id}/events") {
        // The SSE response commits (200, event-stream) once this handler runs, so an
        // auth/visibility failure can't return a 403 here — instead we emit a single
        // terminal `error` frame and close, so a client can tell "denied" from "idle"
        // rather than seeing a silent empty 200.
        val principal = admission.authenticate(call.request.headers["Authorization"])
        val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val rec = id?.let { investigations.findById(it) }
        if (principal == null || rec == null || !principal.canRead(assembler.ownerUserId(rec))) {
            send(ServerSentEvent(data = """{"error":"forbidden"}""", event = "error"))
            return@sse
        }
        val fromSeq = call.request.queryParameters["from_seq"]?.toLongOrNull() ?: 0L
        replayFromLog(events, id, fromSeq)
    }
}

private suspend fun ServerSSESession.replayFromLog(
    events: EventRepository,
    id: UUID,
    fromSeq: Long,
) {
    // `from_seq` is inclusive (a first connect with from_seq=0 yields sequence 0). Each
    // frame advertises id = sequence+1 ("next expected"), so a client reconnecting with
    // Last-Event-ID maps it straight back to from_seq and never re-receives the boundary
    // event — the replay/live-tail seam is duplicate-free (H6).
    for (row in events.replay(id, fromSeq)) {
        val event = EventEmitter.eventFromRow(row)
        send(
            ServerSentEvent(
                data = ProtoJson.print(event),
                event = event.eventCase.name,
                id = (row.sequence + 1).toString(),
            ),
        )
    }
}
