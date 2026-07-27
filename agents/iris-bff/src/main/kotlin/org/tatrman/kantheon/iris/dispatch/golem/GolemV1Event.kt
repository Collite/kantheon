package org.tatrman.kantheon.iris.dispatch.golem

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.tatrman.kantheon.golem.v1.ConversationalResponse
import org.tatrman.kantheon.iris.stream.SseFrameAccumulator

/**
 * The native Golem SSE event family (`agents/golem` `SseEvents`; golem contracts §3,
 * §10 Δ2(d)). Golem runs the Koog graph atomically and then emits the fixed sequence:
 * a `node_start`/`node_done` pair per executed step, `plan_pick`, `exec_done`, and the
 * terminal `envelope` (or `error`). `: ready` / `: ping` comment frames are keepalives,
 * not events, and there is no `done` — the BFF synthesises one at stream close.
 *
 * **The `envelope` frame is not an envelope.** Golem sends the whole
 * [ConversationalResponse] (proto3-JSON), which carries *n* `FormatEnvelope`s plus the
 * authoritative `status`, `current_view`, `applied_context` and Rule-6 `messages`. This
 * is the one place the native surface genuinely differs in shape from the transitional
 * /v2 one, where the frame was a single bare envelope — hence [Turn] rather than
 * `Envelope`.
 */
sealed interface GolemV1Event {
    data class NodeStart(
        val node: String,
    ) : GolemV1Event

    data class NodeDone(
        val node: String,
    ) : GolemV1Event

    data class PlanPick(
        val source: String?,
        val score: Double?,
    ) : GolemV1Event

    data class ExecDone(
        val rowCount: Long?,
        val durationMs: Long?,
    ) : GolemV1Event

    /** Terminal happy path — the completed turn. */
    data class Turn(
        val response: ConversationalResponse,
    ) : GolemV1Event

    /** Terminal error path (golem's own `error` frame, or a transport failure). */
    data class Error(
        val code: String,
        val message: String,
    ) : GolemV1Event
}

/** Parses golem's SSE wire into [GolemV1Event]s. Unknown event names are skipped. */
object GolemSseParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val protoParser = JsonFormat.parser().ignoringUnknownFields()

    /** Parse a complete SSE response body into the ordered list of events. */
    fun parse(body: String): List<GolemV1Event> {
        val events = mutableListOf<GolemV1Event>()
        SseFrameAccumulator.consume(body) { event, data -> toEvent(event, data)?.let(events::add) }
        return events
    }

    @Suppress("ReturnCount")
    fun toEvent(
        event: String,
        data: String,
    ): GolemV1Event? {
        // The terminal frame is a proto message, not a loose JSON object — parse it first
        // so a ConversationalResponse is never coerced through the primitive helpers below.
        if (event == "envelope") return parseTurn(data)

        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: JsonObject(emptyMap())

        fun str(k: String) = obj[k]?.jsonPrimitive?.contentOrNull

        fun num(k: String) = obj[k]?.jsonPrimitive?.longOrNull
        return when (event) {
            "node_start" -> str("node")?.let { GolemV1Event.NodeStart(it) }
            "node_done" -> str("node")?.let { GolemV1Event.NodeDone(it) }
            "plan_pick" ->
                GolemV1Event.PlanPick(
                    source = str("source"),
                    score = obj["score"]?.jsonPrimitive?.doubleOrNull,
                )
            "exec_done" -> GolemV1Event.ExecDone(rowCount = num("row_count"), durationMs = num("duration_ms"))
            "error" ->
                GolemV1Event.Error(
                    code = str("code") ?: "GOLEM_STREAM_ERROR",
                    message = str("message") ?: "",
                )
            else -> null
        }
    }

    /**
     * Parse the terminal `envelope` frame's [ConversationalResponse]. A frame we cannot
     * decode is surfaced as a terminal error rather than dropped: the turn must not end
     * with no terminal event at all, which would present the user a silent empty answer
     * (the exact failure mode of dispatching to a surface that doesn't exist).
     */
    fun parseTurn(data: String): GolemV1Event =
        runCatching {
            val builder = ConversationalResponse.newBuilder()
            protoParser.merge(data, builder)
            GolemV1Event.Turn(builder.build())
        }.getOrElse { e ->
            GolemV1Event.Error(
                "GOLEM_BAD_ENVELOPE",
                "golem's terminal frame is not a ConversationalResponse: ${e.message}",
            )
        }
}
