package org.tatrman.kantheon.iris.dispatch.golem

import com.google.protobuf.util.JsonFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.iris.stream.SseFrameAccumulator

/** Proto ↔ proto3-JSON for the golem REST wire (mirrors golem's own `ProtoJson`). */
internal object GolemProtoJson {
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()

    fun print(message: com.google.protobuf.Message): String = printer.print(message)
}

/**
 * Ktor-client [GolemV1Client] over the native Golem REST surface.
 *
 * Two things this must get right, because both have already bitten this estate:
 *
 * 1. **Non-2xx is not a stream.** Golem answers admission failures (401/403) and route
 *    misses (404) with a Rule-6 JSON body, not SSE. Deserialising that as the success
 *    shape is what produced `NoTransformationFoundException` with an empty bubble and no
 *    diagnosis. Every non-2xx is mapped to a terminal [GolemV1Event.Error] carrying
 *    golem's own `humanMessage` where present.
 * 2. **No request timeout on the stream.** Golem runs the whole graph (LLM compose +
 *    query execution) *before* emitting its terminal frame, so a wall-clock request
 *    timeout would sever turns that were about to succeed. A socket **idle** timeout
 *    bounds a genuinely hung upstream instead — safe because golem writes `: ping` every
 *    5s for the duration of the turn.
 */
class GolemV1HttpClient(
    private val baseUrl: String,
    socketIdleMs: Long = DEFAULT_SOCKET_IDLE_MS,
    private val httpClient: HttpClient =
        HttpClient(CIO) {
            install(HttpTimeout) {
                socketTimeoutMillis = socketIdleMs
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        },
) : GolemV1Client,
    AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }

    private fun io.ktor.client.request.HttpRequestBuilder.identity(
        correlationId: String,
        bearer: String,
    ) {
        // OBO: forward the caller's bearer, never a service token (identity discipline).
        header(HttpHeaders.Authorization, "Bearer $bearer")
        header("X-Correlation-Id", correlationId)
        contentType(ContentType.Application.Json)
    }

    override fun answer(
        request: GolemRequest,
        correlationId: String,
        bearer: String,
    ): Flow<GolemV1Event> =
        channelFlow {
            val acc = SseFrameAccumulator { event, data -> GolemSseParser.toEvent(event, data)?.let { trySend(it) } }
            httpClient
                .preparePost("$baseUrl/v1/answer") {
                    identity(correlationId, bearer)
                    setBody(GolemProtoJson.print(request))
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        trySend(httpError(response, "$baseUrl/v1/answer"))
                        return@execute
                    }
                    val channel = response.bodyAsChannel()
                    while (true) {
                        val line = channel.readUTF8Line() ?: break
                        acc.onLine(line)
                    }
                    acc.flush()
                }
        }

    override fun resume(
        request: GolemResume,
        correlationId: String,
        bearer: String,
    ): Flow<GolemV1Event> =
        channelFlow {
            val response =
                httpClient.post("$baseUrl/v1/resume") {
                    identity(correlationId, bearer)
                    setBody(
                        buildJsonObject {
                            put("resume_token", JsonPrimitive(request.resumeToken))
                            request.selectedOptionId?.let { put("selected_option_id", JsonPrimitive(it)) }
                            request.freeTextAnswer?.let { put("free_text_answer", JsonPrimitive(it)) }
                        }.toString(),
                    )
                }
            if (!response.status.isSuccess()) {
                trySend(httpError(response, "$baseUrl/v1/resume"))
                return@channelFlow
            }
            // Sync surface: the body IS the terminal frame's payload.
            trySend(GolemSseParser.parseTurn(response.bodyAsText()))
        }

    /**
     * Map a non-2xx into a terminal error, preferring golem's Rule-6 `humanMessage` over
     * the raw body so the user-visible text is the agent's own words when it has any.
     */
    private suspend fun httpError(
        response: HttpResponse,
        url: String,
    ): GolemV1Event.Error {
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        val detail = ruleSixMessage(body) ?: body.take(MAX_RAW_BODY_CHARS).ifBlank { "no response body" }
        return GolemV1Event.Error(codeFor(response.status), "$url → ${response.status.value}: $detail")
    }

    /** First Rule-6 `humanMessage` in a `{"messages":[…]}` body, if the body is one. */
    private fun ruleSixMessage(body: String): String? =
        runCatching {
            json
                .parseToJsonElement(body)
                .jsonObject["messages"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("humanMessage")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun codeFor(status: HttpStatusCode): String =
        when (status) {
            HttpStatusCode.Unauthorized -> "GOLEM_UNAUTHORIZED"
            HttpStatusCode.Forbidden -> "GOLEM_FORBIDDEN"
            // A 404 here means the configured endpoint does not serve golem's /v1 surface
            // at all — a deployment/config fault, not a turn failure. Name it as such.
            HttpStatusCode.NotFound -> "GOLEM_ENDPOINT_NOT_FOUND"
            HttpStatusCode.BadRequest -> "GOLEM_BAD_REQUEST"
            else -> "GOLEM_HTTP_${status.value}"
        }

    override fun close() = httpClient.close()

    companion object {
        /** Idle-byte budget. Golem pings every 5s, so this only fires on a truly dead peer. */
        const val DEFAULT_SOCKET_IDLE_MS = 120_000L
        private const val MAX_RAW_BODY_CHARS = 500
    }
}
