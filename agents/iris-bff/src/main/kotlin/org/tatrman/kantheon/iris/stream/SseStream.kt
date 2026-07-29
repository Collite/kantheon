package org.tatrman.kantheon.iris.stream

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondTextWriter
import io.ktor.util.cio.ChannelWriteException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.Writer

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.iris.stream.SseStream")

/**
 * Respond with a Server-Sent-Events stream (contracts §2.3). Sets the standard
 * event-stream headers — including `Cache-Control: no-cache` and the nginx
 * `X-Accel-Buffering: no` hint so the FE proxy delivers frames incrementally
 * instead of buffering the whole response — and runs a `:heartbeat` comment-frame
 * ticker every [heartbeatMs] on idle so proxies don't reap a long-running but
 * quiet dispatch. Writes from [body] and the heartbeat ticker are serialised on a
 * mutex. [body] receives an `emit(frame)` callback.
 *
 * A `": ready"` preamble is written **before** [body] runs, so the response is committed
 * immediately rather than whenever the dispatch first produces something. That is not
 * cosmetic — see below.
 *
 * ## A stream died. Which timeout was it?
 *
 * Three different cuts sit on this path and they are routinely mistaken for each other.
 * Read the *shape* of the failure before changing anything:
 *
 * | Symptom | Cause | Owner |
 * |---|---|---|
 * | Dies at exactly **10s**; 502; `time_starttransfer=0`; nginx logs *"upstream prematurely closed connection while reading response header"* | Ktor Netty `responseWriteTimeoutSeconds` (default 10s, caps **time-to-first-byte**). The preamble above is what prevents this. | this file / ST-P2 in the shared `KtorServerBootstrap` |
 * | HTTP **200** but the stream stops mid-answer at exactly **15s**; `response_flags: UT` in the Envoy access log | Envoy's default per-route timeout. **Permanent estate config** — a `BackendTrafficPolicy` with `requestTimeout: 0s` in Olymp. No code change here retires it. | Olymp |
 * | A typed `THEMIS_UNAVAILABLE` error envelope at **60s** | `iris.themis.timeout-ms` — the routing budget. | working as designed |
 *
 * The engine timeout caps time-to-first-byte only: once the response is committed, an idle
 * gap does not trigger it (measured 2026-07-29). So the preamble is the load-bearing fix
 * and [heartbeatMs] is defence-in-depth — it still earns its keep against *proxy* idle read
 * timeouts, which are a fourth, separate family.
 *
 * Pinned by `SsePreambleSpec` (ordering + the heartbeat invariant) and
 * `SseNettyWriteTimeoutSpec` (a real Netty socket). Full write-up:
 * `project/server/features/stream-timeouts/`.
 */
suspend fun ApplicationCall.respondSse(
    heartbeatMs: Long,
    body: suspend (emit: suspend (String) -> Unit) -> Unit,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-cache")
    response.headers.append("X-Accel-Buffering", "no")
    try {
        respondTextWriter(ContentType.parse("text/event-stream")) {
            sseLoop(this, heartbeatMs, body)
        }
    } catch (e: ChannelWriteException) {
        // A client that goes away mid-stream is ordinary, not a server fault. Left to
        // propagate it reached StatusPages as "Unhandled error on /v1/chat/stream" and
        // tried to write a 500 onto a response that was already committed and dead.
        // The final flush when the writer closes can raise this even after the body ran
        // cleanly, so it is caught here rather than only at the emit site.
        log.info("SSE client disconnected before the stream completed: {}", e.message)
    } catch (e: IOException) {
        log.info("SSE stream to the client failed: {}", e.message)
    }
}

private suspend fun sseLoop(
    writer: Writer,
    heartbeatMs: Long,
    body: suspend (emit: suspend (String) -> Unit) -> Unit,
) = coroutineScope {
    val mutex = Mutex()

    suspend fun write(frame: String) =
        mutex.withLock {
            writer.write(frame)
            writer.flush()
        }

    // Commit the response BEFORE any slow work: this flushes the status line and headers,
    // so proxies and the engine see an open, healthy stream instead of a silent socket.
    //
    // Ktor's Netty engine reaps a response that has produced no bytes within
    // `responseWriteTimeoutSeconds` (its default is 10s, and `KtorServerBootstrap` does not
    // override it). A Themis cold resolve is ~19-28s, and the heartbeat below used to be
    // 15s — so the first frame arrived 5s after the engine had already closed the socket.
    // The user saw HTTP 502 ("upstream prematurely closed connection while reading response
    // header"); the server logged nothing but a late "lost its client stream after 0
    // event(s)". Byte-identical to golem's preamble in `SseAnswer.kt`, deliberately: one
    // estate, one spelling. See `project/server/features/stream-timeouts/`.
    write(": ready\n\n")

    val heartbeat =
        launch {
            while (isActive) {
                delay(heartbeatMs)
                runCatching { write(":heartbeat\n\n") }
            }
        }
    try {
        body { frame -> write(frame) }
    } finally {
        heartbeat.cancel()
    }
}
