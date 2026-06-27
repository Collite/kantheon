package org.tatrman.pinakes.clients

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/**
 * The LLM egress for the compile stage (architecture §7 — the COMPILE stage is
 * Prometheus-driven). A thin chat-completion client; the compile is batch/offline
 * (never on the query path). REST mirror of the Prometheus `Chat` surface,
 * Wiremock-tested.
 */
interface PrometheusClient {
    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String
}

@Serializable
private data class ChatBody(
    val system: String,
    val user: String,
)

@Serializable
private data class ChatReply(
    val content: String = "",
)

class HttpPrometheusClient(
    private val http: HttpClient,
    private val baseUrl: String,
) : PrometheusClient {
    override suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val resp: HttpResponse =
            http.post("$baseUrl/v1/chat") {
                contentType(ContentType.Application.Json)
                setBody(ChatBody(systemPrompt, userPrompt))
            }
        require(resp.status.isSuccess()) { "Prometheus chat failed: ${resp.status}" }
        return resp.body<ChatReply>().content
    }
}
