package org.tatrman.kantheon.hebe.tools.builtin.kantheon

import com.google.protobuf.util.JsonFormat
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tatrman.kantheon.iris.v1.ChatTurnRequest
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import org.tatrman.kantheon.iris.v1.TurnOrigin

/**
 * Hebe's headless iris-bff client — the "kantheon" tool family (P4 S4.1). Drives the
 * same surface as the Vue FE: create a session, POST a chat turn, consume the SSE
 * `IrisStreamEvent` stream. Authenticated with the bound user's **OBO bearer**
 * (Stage 2.3 `OboTokenService.currentBearer`) on every call — never a service
 * identity (contracts §3.2). Built against the iris/v1 + hebe/v1 protos (wire
 * policy); JsonFormat carries the proto-canonical JSON.
 *
 * The HTTP client is injected so the SSE consumption is testable with a MockEngine /
 * Wiremock'd iris-bff (planning-conventions §4); the live run is gated on iris-bff ≥
 * Iris Phase 2.
 */
class IrisBffClient(
    private val baseUrl: String,
    private val bearer: suspend () -> String,
    private val httpClient: HttpClient,
) {
    private val printer: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()
    private val parser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()
    private val json = Json { ignoreUnknownKeys = true }

    /** Creates a per-routine Iris session titled [title] (contracts §3.3); returns its `session_ref`. */
    suspend fun createSession(title: String): String {
        val token = bearer()
        val response =
            httpClient.post("$base/v1/sessions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("title", title) }.toString())
            }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return (body["sessionRef"] ?: body["session_ref"] ?: body["sessionId"])!!.jsonPrimitive.content
    }

    /**
     * POSTs a scheduled turn and streams the `IrisStreamEvent`s. `origin = SCHEDULED`
     * + `origin_ref = <routine id>` mark it for the session log / PD-2 inbox (iris-bff
     * does not gate on it, contracts §3.1).
     */
    fun streamTurn(
        sessionRef: String,
        question: String,
        originRef: String,
        routingHint: String? = null,
    ): Flow<IrisStreamEvent> {
        val req =
            ChatTurnRequest
                .newBuilder()
                .setSessionId(sessionRef)
                .setQuestion(question)
                .setOrigin(TurnOrigin.SCHEDULED)
                .setOriginRef(originRef)
                .apply { if (!routingHint.isNullOrBlank()) routingHintAgentId = routingHint }
                .build()
        return channelFlow {
            val token = bearer()
            val data = StringBuilder()
            httpClient
                .preparePost("$base/v1/chat/stream") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(printer.print(req))
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        when {
                            line.isEmpty() -> flushFrame(data) { trySend(it) }
                            line.startsWith(":") -> Unit // SSE comment / keep-alive
                            line.startsWith("data:") -> data.appendLine(line.removePrefix("data:").trimStart())
                        }
                    }
                    flushFrame(data) { trySend(it) }
                }
        }
    }

    /** Streams [streamTurn] to completion and resolves the turn outcome (P4 S4.1 T5). */
    suspend fun runTurn(
        sessionRef: String,
        question: String,
        originRef: String,
        routingHint: String? = null,
    ): IrisTurnResult {
        val events = mutableListOf<IrisStreamEvent>()
        streamTurn(sessionRef, question, originRef, routingHint).collect { events.add(it) }
        return IrisStreamMapping.resolve(events)
    }

    private fun flushFrame(
        data: StringBuilder,
        emit: (IrisStreamEvent) -> Unit,
    ) {
        if (data.isEmpty()) return
        val payload = data.toString().trimEnd('\n')
        data.clear()
        if (payload.isBlank() || payload == "[DONE]") return
        val builder = IrisStreamEvent.newBuilder()
        parser.merge(payload, builder)
        emit(builder.build())
    }

    private val base: String get() = baseUrl.trimEnd('/')
}
