package org.tatrman.kantheon.golem.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tatrman.kantheon.golem.v1.ConversationalResponse
import java.io.Writer

private const val PING_INTERVAL_MS = 5_000L

/** The fixed Golem SSE event set (contracts §3, §10 Δ2(d)). `envelope`/`error` are terminal. */
object SseEvents {
    const val NODE_START = "node_start"
    const val NODE_DONE = "node_done"
    const val PLAN_PICK = "plan_pick"
    const val EXEC_DONE = "exec_done"
    const val ENVELOPE = "envelope"
    const val ERROR = "error"

    fun frame(
        event: String,
        dataJson: String,
    ): String = "event: $event\ndata: $dataJson\n\n"
}

/**
 * Stream one turn as Server-Sent Events. Emits an initial `: ready` comment, a `: ping`
 * keepalive every 5s while the turn runs, then — once the turn completes — the fixed event
 * sequence: a `node_start`/`node_done` pair per executed step, a `plan_pick`, an `exec_done`,
 * and the terminal `envelope` (or `error`). There is **no live-log `/log` stream** (removed
 * upstream Phase 8). The turn is awaited rather than truly node-streamed (Koog runs the graph
 * atomically); the event names + ordering match the contract so the FE consumes one surface.
 */
suspend fun ApplicationCall.streamAnswer(run: suspend () -> ConversationalResponse) {
    response.headers.append(HttpHeaders.CacheControl, "no-cache")
    response.headers.append("X-Accel-Buffering", "no")
    respondTextWriter(ContentType.parse("text/event-stream")) {
        sseLoop(this, run)
    }
}

private suspend fun sseLoop(
    writer: Writer,
    run: suspend () -> ConversationalResponse,
) = coroutineScope {
    val mutex = Mutex()

    suspend fun write(frame: String) =
        mutex.withLock {
            writer.write(frame)
            writer.flush()
        }

    write(": ready\n\n")
    val done = CompletableDeferred<Unit>()
    val ping =
        launch {
            while (isActive && !done.isCompleted) {
                delay(PING_INTERVAL_MS)
                if (!done.isCompleted) runCatching { write(": ping\n\n") }
            }
        }
    try {
        val response =
            try {
                run()
            } catch (e: Exception) {
                write(SseEvents.frame(SseEvents.ERROR, errorJson("STREAM_ERROR", e.message ?: "turn failed")))
                return@coroutineScope
            }
        // A write failure mid-emit (client gone, broken pipe) still owes the contract a terminal
        // frame — attempt a best-effort `error` (it too may fail, hence runCatching) so the stream
        // never ends silently between non-terminal frames.
        try {
            emitTurnEvents(response) { write(it) }
        } catch (e: Exception) {
            runCatching {
                write(SseEvents.frame(SseEvents.ERROR, errorJson("STREAM_ERROR", e.message ?: "stream write failed")))
            }
        }
    } finally {
        done.complete(Unit)
        ping.cancel()
    }
}

private suspend fun emitTurnEvents(
    response: ConversationalResponse,
    write: suspend (String) -> Unit,
) {
    turnEventFrames(response).forEach { write(it) }
}

/**
 * The ordered SSE frames for a completed turn: a `node_start`/`node_done` pair per executed
 * step, then `plan_pick`, `exec_done`, and the terminal `envelope`. Pure, so the event
 * names + ordering are unit-testable without an HTTP harness.
 */
fun turnEventFrames(response: ConversationalResponse): List<String> {
    val frames = mutableListOf<String>()
    for (step in response.stepRecordsList) {
        frames += SseEvents.frame(SseEvents.NODE_START, """{"node":${quote(step.nodeId)}}""")
        frames += SseEvents.frame(SseEvents.NODE_DONE, """{"node":${quote(step.nodeId)}}""")
    }
    if (response.hasPlan()) {
        val p = response.plan
        frames += SseEvents.frame(SseEvents.PLAN_PICK, """{"source":${quote(p.source.name)},"score":${p.confidence}}""")
    }
    // `exec_done` always fires so the FE state machine sees the fixed sequence even on a
    // clarification / cached / no-usage turn (defaults to zero). `row_count` reads current_view
    // only when present (absent ⇒ 0, not a misleading stale value).
    run {
        val durationMs = if (response.hasResourceUsage()) response.resourceUsage.totalLatencyMs else 0L
        val rowCount = if (response.hasCurrentView()) response.currentView.totalRows else 0L
        frames += SseEvents.frame(SseEvents.EXEC_DONE, """{"row_count":$rowCount,"duration_ms":$durationMs}""")
    }
    // Terminal: the full ConversationalResponse as the envelope payload.
    frames += SseEvents.frame(SseEvents.ENVELOPE, ProtoJson.print(response))
    return frames
}

private fun errorJson(
    code: String,
    message: String,
): String = """{"code":${quote(code)},"message":${quote(message)}}"""

private fun quote(s: String): String = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
