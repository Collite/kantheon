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
 * LLM-gateway-driven). A thin chat-completion client; the compile is batch/offline
 * (never on the query path). Speaks the LLM-gateway standard OpenAI chat surface —
 * `POST /v1/chat/completions`, `{model, messages:[{role,content}]}`, reply
 * `{choices:[{message:{content}}]}`. Wiremock-tested.
 */
interface LlmGatewayClient {
    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String
}

@Serializable
private data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ChatBody(
    val model: String,
    val messages: List<ChatMessage>,
)

@Serializable
private data class ChatReply(
    val choices: List<Choice> = emptyList(),
) {
    @Serializable
    data class Choice(
        val message: Message = Message(),
    )

    @Serializable
    data class Message(
        val content: String = "",
    )
}

class HttpLlmGatewayClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val model: String,
) : LlmGatewayClient {
    override suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val resp: HttpResponse =
            http.post("$baseUrl/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody(
                    ChatBody(
                        model = model,
                        messages =
                            listOf(
                                ChatMessage("system", systemPrompt),
                                ChatMessage("user", userPrompt),
                            ),
                    ),
                )
            }
        require(resp.status.isSuccess()) { "LLM-gateway chat failed: ${resp.status}" }
        return resp
            .body<ChatReply>()
            .choices
            .firstOrNull()
            ?.message
            ?.content
            ?: ""
    }
}
