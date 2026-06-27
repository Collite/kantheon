package org.tatrman.kantheon.iris.stream

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Writer

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
    respondTextWriter(ContentType.parse("text/event-stream")) {
        sseLoop(this, heartbeatMs, body)
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
