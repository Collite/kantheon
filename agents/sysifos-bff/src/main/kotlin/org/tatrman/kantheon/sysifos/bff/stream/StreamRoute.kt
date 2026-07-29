package org.tatrman.kantheon.sysifos.bff.stream

import com.google.protobuf.util.Timestamps
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.tatrman.kantheon.bffbase.auth.BearerAuthenticator
import org.tatrman.kantheon.bffbase.auth.requireCaller
import org.tatrman.kantheon.sysifos.bff.session.SessionStore
import org.tatrman.kantheon.sysifos.v1.SessionHeartbeat
import org.tatrman.kantheon.sysifos.v1.SysifosStreamEvent

/**
 * SSE stream (contracts §3.6). Writes a `": ready"` preamble, then subscribes the
 * connected session to the [SessionStreamBus] — draft/loader/batch events flow here —
 * and runs a [heartbeatMs] ticker that publishes `SessionHeartbeat`s onto the same bus
 * so a quiet connection still emits frames (keeps Traefik from reaping it). The loop
 * ends when the client disconnects (the writer coroutine is cancelled).
 *
 * `X-Accel-Buffering: no` + `Cache-Control: no-cache` make the proxy deliver
 * frames incrementally instead of buffering the (never-ending) response.
 *
 * **The preamble is load-bearing, not decoration.** This service calls
 * `embeddedServer(Netty, ...)` directly rather than going through the shared
 * `KtorServerBootstrap`, so it does **not** get that library's 180s
 * `responseWriteTimeoutSeconds` (ST-P2) — it inherits Ktor's **10s** default, which caps
 * *time-to-first-byte* for a streaming response. Committing the response up front is what
 * keeps a quiet session alive; [heartbeatMs] is defence-in-depth against *proxy* idle-read
 * timeouts, a separate family of cut. Pinned by `SseNettyWriteTimeoutSpec`.
 *
 * See `project/server/features/stream-timeouts/` and review-078 R1.
 */
fun Route.streamRoute(
    auth: BearerAuthenticator,
    sessions: SessionStore,
    bus: SessionStreamBus,
    heartbeatMs: Long,
) {
    get("/stream") {
        val caller = call.requireCaller(auth) ?: return@get
        val session = sessions.current(caller.userId, caller.tenantId)
        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.response.headers.append("X-Accel-Buffering", "no")
        call.respondTextWriter(ContentType.parse("text/event-stream")) {
            // Commit the response BEFORE any slow work — the same fix ST-P1 made in iris-bff
            // and pythia, and the shape golem has always had. `respondTextWriter` issues the
            // chunked response head, which arms the engine's write timeout; nothing below
            // writes until a bus event or the heartbeat, so without this the socket was reaped
            // with no status line and the caller saw a 502. Byte-identical to golem's
            // `SseAnswer` and iris-bff's `SseStream`: one estate, one preamble.
            write(": ready\n\n")
            flush()

            coroutineScope {
                val ticker =
                    launch {
                        while (isActive) {
                            delay(heartbeatMs)
                            bus.publish(
                                session.sessionId,
                                SysifosStreamEvent
                                    .newBuilder()
                                    .setHeartbeat(
                                        SessionHeartbeat
                                            .newBuilder()
                                            .setSessionId(session.sessionId)
                                            .setAt(Timestamps.fromMillis(System.currentTimeMillis())),
                                    ).build(),
                            )
                        }
                    }
                try {
                    bus.events(session.sessionId).collect { event ->
                        write(SysifosSse.frame(event))
                        flush()
                    }
                } finally {
                    ticker.cancel()
                }
            }
        }
    }
}
