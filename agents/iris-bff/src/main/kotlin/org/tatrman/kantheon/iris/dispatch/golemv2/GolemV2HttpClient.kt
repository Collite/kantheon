package org.tatrman.kantheon.iris.dispatch.golemv2

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Ktor-client [GolemV2Client] over new-golem /v2 (contracts §5). SSE bodies are
 * consumed line-by-line through the [SseAccumulator]. Resume uses the sync
 * `/v2/chat/resume` and is surfaced as a single terminal Envelope so the mux
 * path is uniform with streaming.
 *
 * NOTE: live-HTTP fidelity is exercised in the integration-test suite; the
 * unit/component gate runs the mux against recorded SSE fixtures and the routes
 * against a fake client.
 */
class GolemV2HttpClient(
    private val baseUrl: String,
    // A long-lived SSE stream must not use a request timeout (it would kill the
    // whole stream); a socket/idle timeout bounds a stalled upstream (no bytes)
    // so a hung golem can't pin the BFF coroutine + the FE's SSE connection.
    socketIdleMs: Long = 60_000,
    private val httpClient: HttpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) { socketTimeoutMillis = socketIdleMs }
        },
) : GolemV2Client,
    AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }

    private fun io.ktor.client.request.HttpRequestBuilder.identity(
        userId: String,
        correlationId: String,
        bearer: String,
    ) {
        // OBO: forward the caller's bearer, never a service token (identity discipline).
        header(HttpHeaders.Authorization, "Bearer $bearer")
        header("X-User-ID", userId)
        header("X-Correlation-Id", correlationId)
        contentType(ContentType.Application.Json)
    }

    override suspend fun createSession(
        threadId: String,
        userId: String,
        correlationId: String,
        bearer: String,
        locale: String,
    ): V2SessionStartResponse =
        httpClient
            .post("$baseUrl/v2/session") {
                identity(userId, correlationId, bearer)
                setBody(V2SessionStartRequest(thread_id = threadId, locale = locale, user_id = userId))
            }.body()

    override fun chatStream(
        req: V2ChatRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ): Flow<V2StreamEvent> =
        channelFlow {
            val acc = SseAccumulator { trySend(it) }
            httpClient
                .preparePost("$baseUrl/v2/chat/stream") {
                    identity(userId, correlationId, bearer)
                    setBody(req)
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    while (true) {
                        val line = channel.readUTF8Line() ?: break
                        acc.onLine(line)
                    }
                    acc.flush()
                }
        }

    override fun reissueAction(
        req: V2ActionRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ): Flow<V2StreamEvent> =
        channelFlow {
            val acc = SseAccumulator { trySend(it) }
            httpClient
                .preparePost("$baseUrl/v2/action") {
                    identity(userId, correlationId, bearer)
                    setBody(req)
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    while (true) {
                        val line = channel.readUTF8Line() ?: break
                        acc.onLine(line)
                    }
                    acc.flush()
                }
        }

    override fun resume(
        req: V2ResumeRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ): Flow<V2StreamEvent> =
        channelFlow {
            val body =
                httpClient
                    .post("$baseUrl/v2/chat/resume") {
                        identity(userId, correlationId, bearer)
                        setBody(req)
                    }.body<String>()
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            val envelope = obj?.get("envelope")
            if (envelope != null && envelope is kotlinx.serialization.json.JsonObject) {
                trySend(V2StreamEvent.Envelope(envelope))
            } else {
                trySend(V2StreamEvent.Error("RESUME_NO_ENVELOPE", "resume produced no envelope"))
            }
        }

    override suspend fun refresh(
        req: V2RefreshRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ): V2RefreshResponse =
        httpClient
            .post("$baseUrl/v2/refresh") {
                identity(userId, correlationId, bearer)
                setBody(req)
            }.body()

    override fun close() = httpClient.close()
}
