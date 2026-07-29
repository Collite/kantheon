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
