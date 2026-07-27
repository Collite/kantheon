package org.tatrman.kantheon.iris.dispatch.golem

import com.google.protobuf.util.JsonFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.kantheon.common.v1.ViewProvenance
import org.tatrman.kantheon.envelope.v1.ClarificationOption
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.envelope.v1.PendingClarification
import org.tatrman.kantheon.golem.v1.ConversationalResponse
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.Status

/**
 * Golem-shaped test material. The responses are built as protos and printed with the same
 * proto3-JSON printer golem uses, so a fixture cannot drift from the wire it claims to be.
 */
object GolemFixtures {
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()

    fun textEnvelope(
        bubbleId: String,
        text: String,
    ): FormatEnvelope =
        FormatEnvelope
            .newBuilder()
            .setBubbleId(bubbleId)
            .setTurnId("golem-turn")
            .setText(text)
            .setFormat(FormatSpec.newBuilder().setKind(FormatKind.PLAINTEXT))
            .setCreatedAt("2026-07-27T22:00:00Z")
            .setAgentVersion("golem@test")
            .build()

    fun tableEnvelope(bubbleId: String): FormatEnvelope =
        FormatEnvelope
            .newBuilder()
            .setBubbleId(bubbleId)
            .setTurnId("golem-turn")
            .setContentJson("""[{"m":"2026-01","r":120}]""")
            .setFormat(FormatSpec.newBuilder().setKind(FormatKind.TABLE))
            .setCreatedAt("2026-07-27T22:00:00Z")
            .setAgentVersion("golem@test")
            .build()

    /** The param-fill clarification golem emits — note it also carries an `error_code`. */
    fun clarificationEnvelope(resumeToken: String): FormatEnvelope =
        FormatEnvelope
            .newBuilder()
            .setBubbleId("b-clar")
            .setTurnId("golem-turn")
            .setText("Za jaké období?")
            .setFormat(FormatSpec.newBuilder().setKind(FormatKind.PLAINTEXT))
            .setErrorCode("param_fill")
            .setPendingClarification(
                PendingClarification
                    .newBuilder()
                    .setKind("missing_arg")
                    .setResumeToken(resumeToken)
                    .addOptions(ClarificationOption.newBuilder().setId("period").setDisplay("Za jaké období?")),
            ).setCreatedAt("2026-07-27T22:00:00Z")
            .setAgentVersion("golem@test")
            .build()

    fun response(
        status: Status = Status.STATUS_DONE,
        envelopes: List<FormatEnvelope> = listOf(tableEnvelope("b-1")),
        currentViewBubble: String? = null,
        messages: List<ResponseMessage> = emptyList(),
    ): ConversationalResponse {
        val b =
            ConversationalResponse
                .newBuilder()
                .setId("resp-1")
                .setRequestId("golem-turn")
                .setGolemId("golem-hartland")
                .setFinalisedAt("2026-07-27T22:00:01Z")
                .addAllEnvelopes(envelopes)
                .addAllMessages(messages)
                .setStatus(status)
        currentViewBubble?.let {
            b.currentView =
                ViewProvenance
                    .newBuilder()
                    .setBubbleId(it)
                    .setPatternId("sales_by_month")
                    .setArgsJson("""{"year":2026}""")
                    .setSql("SELECT 1")
                    .setTotalRows(72)
                    .build()
        }
        return b.build()
    }

    fun error(
        code: String,
        message: String,
    ): ResponseMessage =
        ResponseMessage
            .newBuilder()
            .setSeverity(Severity.ERROR)
            .setCode(code)
            .setHumanMessage(message)
            .build()

    /** Golem's SSE body for a completed turn — the same frame sequence `SseAnswer` writes. */
    fun sseBody(response: ConversationalResponse): String =
        buildString {
            append(": ready\n\n")
            append("event: node_start\ndata: {\"node\":\"compose\"}\n\n")
            append("event: node_done\ndata: {\"node\":\"compose\"}\n\n")
            append(": ping\n\n")
            append("event: plan_pick\ndata: {\"source\":\"PATTERN\",\"score\":0.91}\n\n")
            append("event: exec_done\ndata: {\"row_count\":72,\"duration_ms\":1840}\n\n")
            append("event: envelope\ndata: ${printer.print(response)}\n\n")
        }

    fun json(response: ConversationalResponse): String = printer.print(response)
}

/**
 * Scripted [GolemV1Client] — replays a canned event list and records what the BFF sent, so
 * OBO forwarding and request assembly are assertable without an HTTP harness.
 */
class FakeGolemV1Client(
    private val events: List<GolemV1Event> = listOf(GolemV1Event.Turn(GolemFixtures.response())),
    private val resumeEvents: List<GolemV1Event> =
        listOf(GolemV1Event.Turn(GolemFixtures.response(envelopes = listOf(GolemFixtures.textEnvelope("b-r", "OK"))))),
) : GolemV1Client {
    val requests = mutableListOf<GolemRequest>()
    val resumes = mutableListOf<GolemResume>()
    val bearers = mutableListOf<String>()
    val correlationIds = mutableListOf<String>()

    override fun answer(
        request: GolemRequest,
        correlationId: String,
        bearer: String,
    ): Flow<GolemV1Event> {
        requests += request
        bearers += bearer
        correlationIds += correlationId
        return events.asFlow()
    }

    override fun resume(
        request: GolemResume,
        correlationId: String,
        bearer: String,
    ): Flow<GolemV1Event> {
        resumes += request
        bearers += bearer
        correlationIds += correlationId
        return resumeEvents.asFlow()
    }
}
