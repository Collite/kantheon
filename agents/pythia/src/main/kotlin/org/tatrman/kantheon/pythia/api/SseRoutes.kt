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
 *
 * **This endpoint has no keepalive ticker** — unlike golem (`PING_INTERVAL_MS`) and
 * iris-bff (`iris.stream.heartbeat-s`), both of which ping every 5s. That is safe only
 * because the handler never idles today: it authenticates, replays a finite log, and
 * returns. The `: ready` preamble below covers *time-to-first-byte*, which is the engine's
 * cap; it does nothing for an idle gap once the stream is open, and a proxy idle-read
 * timeout is a separate family of cut (`project/server/features/stream-timeouts/`
 * architecture §4).
 *
 * So: **the NATS live-tail must add a keepalive before it can hold a session open between
 * events.** Interval < 10s, matching golem. Until then there is nothing to keep alive and
 * an unused ticker would be worse than this note.
 */
fun Route.sseRoutes(
    investigations: InvestigationRepository,
    events: EventRepository,
    assembler: ArtifactAssembler,
    admission: Admission,
) {
    sse("/v1/investigations/{id}/events") {
        // Commit the response before any work — ST-P1·S2. Ktor's Netty engine reaps a
        // response that has produced no bytes within `responseWriteTimeoutSeconds` (Ktor's
        // unconfigured default is 10s; since ST-P2 the shared `KtorServerBootstrap` sets it
        // explicitly to 180s, which this service gets), and MEASURED 2026-07-29: the
        // `sse { }` plugin does NOT commit on
        // session start, so it offers no protection of its own (SsePluginWriteTimeoutSpec
        // pins both halves). Today this handler is fast — authenticate, find, replay,
        // return — so it does not trip the cap; but that is a property of the current
        // body, not of the endpoint. A slow `authenticate` (cold JWKS fetch) or the NATS
        // live-tail landing later would both idle here, and the failure mode is silent:
        // the socket closes with no status line and the user sees a 502.
        //
        // Rendered as `: ready` — the same spelling golem and iris-bff write by hand, one
        // estate, one preamble. Not byte-identical: Ktor's SSE serialiser terminates lines
        // with CRLF (`io.ktor.sse.END_OF_LINE = "\r\n"`), so this goes on the wire as
        // `": ready\r\n\r\n"` against their `": ready\n\n"`. Both are valid SSE and every
        // parser in the estate handles both.
        // See `project/server/features/stream-timeouts/`.
        send(ServerSentEvent(comments = "ready"))

        // An auth/visibility failure can't return a 403 once the response is committed —
        // instead we emit a single terminal `error` frame and close, so a client can tell
        // "denied" from "idle" rather than seeing a silent empty 200. (That was already
        // this endpoint's contract; the preamble above only makes the commit explicit
        // rather than incidental.)
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
