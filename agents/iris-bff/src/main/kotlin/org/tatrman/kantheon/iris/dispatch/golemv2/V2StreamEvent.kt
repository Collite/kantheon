package org.tatrman.kantheon.iris.dispatch.golemv2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The new-golem /v2 SSE event family (contracts §5; source-of-truth
 * `ai-platform/agents/golem/src/api/v2_routes.py::_stream_turn`). Terminal is
 * `envelope` (or `error`); there is no `done` event — the BFF synthesises one
 * on stream close. `: ready` / `: ping` comment frames are not events.
 */
sealed interface V2StreamEvent {
    data class NodeStart(
        val node: String,
    ) : V2StreamEvent

    data class NodeDone(
        val node: String,
    ) : V2StreamEvent

    data class PlanPick(
        val source: String?,
        val patternId: String?,
        val score: Double?,
    ) : V2StreamEvent

    data class ExecDone(
        val rowCount: Long?,
        val durationMs: Long?,
    ) : V2StreamEvent

    /** Terminal happy path — the raw FormatEnvelope v2 JSON. */
    data class Envelope(
        val raw: JsonObject,
    ) : V2StreamEvent

    /** Terminal error path. */
    data class Error(
        val code: String,
        val message: String,
    ) : V2StreamEvent
}

/**
 * Parse the SSE wire into [V2StreamEvent]s. Comment frames (`: ready`, `: ping`)
 * and unknown event names are skipped. Tolerant of `\r\n` and multi-`data:`
 * frames (joined by `\n` per the SSE spec).
 */
object V2SseParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parse a complete SSE response body into the ordered list of events. */
    fun parse(body: String): List<V2StreamEvent> {
        val events = mutableListOf<V2StreamEvent>()
        val acc = SseAccumulator { events.add(it) }
        body.split("\n").forEach { acc.onLine(it.removeSuffix("\r")) }
        acc.flush()
        return events
    }

    internal fun toEvent(
        event: String,
        data: String,
    ): V2StreamEvent? {
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: JsonObject(emptyMap())

        fun str(k: String) = obj[k]?.jsonPrimitive?.contentOrNull

        fun num(k: String) = obj[k]?.jsonPrimitive?.longOrNull
        return when (event) {
            "node_start" -> str("node")?.let { V2StreamEvent.NodeStart(it) }
            "node_done" -> str("node")?.let { V2StreamEvent.NodeDone(it) }
            "plan_pick" ->
                V2StreamEvent.PlanPick(
                    source = str("source"),
                    patternId = str("pattern_id"),
                    score = obj["score"]?.jsonPrimitive?.doubleOrNull,
                )
            "exec_done" -> V2StreamEvent.ExecDone(rowCount = num("row_count"), durationMs = num("duration_ms"))
            "envelope" -> V2StreamEvent.Envelope(obj)
            "error" ->
                V2StreamEvent.Error(
                    code = str("code") ?: "STREAM_ERROR",
                    message = str("message") ?: "",
                )
            else -> null
        }
    }
}

/**
 * Incremental SSE frame accumulator. Feed it raw lines (CRLF stripped); it emits
 * an event on each blank-line frame terminator. Comment lines (leading `:`) are
 * ignored. Used by both the whole-body parser and the streaming HTTP client.
 */
class SseAccumulator(
    private val onEvent: (V2StreamEvent) -> Unit,
) {
    private var event: String? = null
    private val data = StringBuilder()

    fun onLine(line: String) {
        when {
            line.isEmpty() -> flush()
            line.startsWith(":") -> Unit // comment frame (`: ready`, `: ping`)
            // SSE strips exactly one optional leading space after the colon — not a
            // full trim (which would corrupt whitespace-significant data).
            line.startsWith("event:") -> event = line.removePrefix("event:").removePrefix(" ")
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
        }
    }

    fun flush() {
        val e = event
        if (e != null) {
            V2SseParser.toEvent(e, data.toString())?.let(onEvent)
        }
        event = null
        data.clear()
    }
}
