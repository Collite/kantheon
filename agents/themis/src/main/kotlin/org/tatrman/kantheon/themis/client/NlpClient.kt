// Vendored from ai-platform `tools/nlp-mcp/src/main/kotlin/tools/nlp/mcp/client/NlpClient.kt`
// during Stage 2.2 (resolver extraction). nlp-mcp does not publish a client jar; this
// vendor copy is the source of truth in kantheon until ai-platform publishes one.
package org.tatrman.kantheon.themis.client

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
data class NlpToken(
    val text: String,
    val charStart: Int,
    val charEnd: Int,
    val lemma: String,
    val upos: String,
    val xpos: String,
    val feats: Map<String, String>,
    val depHead: Int,
    val depRelation: String,
)

@Serializable
data class NlpSpan(
    val charStart: Int,
    val charEnd: Int,
)

@Serializable
data class NlpEntity(
    val text: String,
    val label: String,
    val charStart: Int,
    val charEnd: Int,
    val normalizedValue: String,
    val sourceEngine: String,
)

@Serializable
data class NlpMessage(
    val severity: String,
    val code: String,
    val message: String,
)

@Serializable
data class NlpAnalyzeResult(
    val language: String,
    val languageConfidence: Double,
    val engineUsed: String,
    val tokens: List<NlpToken>,
    val sentences: List<NlpSpan>,
    val paragraphs: List<NlpSpan>,
    val entities: List<NlpEntity>,
    val traceId: String,
    val elapsedMs: Long,
    val messages: List<NlpMessage>,
)

/**
 * HTTP client for infra/nlp. Caller owns [httpClient] and its lifecycle.
 * Timeouts and connection config belong on the injected client.
 * W3C trace context is injected into every request so traces stitch across the boundary.
 */
class NlpClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analyze(
        text: String,
        language: String = "",
        ops: Set<String>,
        mode: String = "NORMAL",
        engineHints: Map<String, String> = emptyMap(),
        authHeaders: Map<String, String> = emptyMap(),
    ): NlpAnalyzeResult {
        val requestBody =
            buildJsonObject {
                put("text", JsonPrimitive(text))
                put("language", JsonPrimitive(language))
                put("ops", JsonArray(ops.map { JsonPrimitive(it) }))
                put("mode", JsonPrimitive(mode))
                if (engineHints.isNotEmpty()) {
                    putJsonObject("engineHints") {
                        engineHints.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    }
                }
            }

        val traceCarrier = mutableMapOf<String, String>()
        W3CTraceContextPropagator.getInstance().inject(
            Context.current(),
            traceCarrier,
            TextMapSetter { c, k, v -> c?.set(k, v) },
        )

        val response =
            httpClient.post("$baseUrl/v1/analyze") {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
                traceCarrier.forEach { (k, v) -> header(k, v) }
                authHeaders.forEach { (k, v) -> header(k, v) }
            }

        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw NlpClientException("NLP service error: ${response.status.value} - $responseText")
        }
        return json.decodeFromString<NlpAnalyzeResult>(responseText)
    }
}

class NlpClientException(
    message: String,
) : Exception(message)
