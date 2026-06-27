package org.tatrman.kantheon.hebe.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

interface LlmProvider {
    suspend fun chat(req: ChatRequest): Flow<StreamEvent>

    fun capabilities(): ProviderCapabilities
}

@Serializable
data class ProviderCapabilities(
    val streaming: Boolean,
    val toolUse: Boolean,
    val multimodal: Boolean,
    val maxContextTokens: Int,
    val supportsPromptCaching: Boolean = false,
    val defaultModel: String = "",
)

@Serializable
data class ChatRequest(
    val model: String,
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val stream: Boolean = true,
)

@Serializable
sealed interface ChatMessage {
    @Serializable
    @SerialName("user")
    data class User(
        val content: String,
        val attachments: List<Attachment> = emptyList(),
    ) : ChatMessage

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        val content: String,
        val toolCalls: List<ParsedToolCall> = emptyList(),
    ) : ChatMessage

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val callId: String,
        val content: String,
        val isError: Boolean = false,
    ) : ChatMessage

    @Serializable
    @SerialName("system")
    data class System(
        val content: String,
    ) : ChatMessage
}

@Serializable
sealed interface ToolChoice {
    @Serializable
    @SerialName("auto")
    data object Auto : ToolChoice

    @Serializable
    @SerialName("none")
    data object None : ToolChoice

    @Serializable
    @SerialName("required")
    data object Required : ToolChoice

    @Serializable
    @SerialName("specific")
    data class Specific(
        val name: String,
    ) : ToolChoice
}

@Serializable
sealed class StreamEvent {
    @Serializable
    @SerialName("text_delta")
    data class TextDelta(
        val text: String,
    ) : StreamEvent()

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val call: ParsedToolCall,
    ) : StreamEvent()

    @Serializable
    @SerialName("token_usage")
    data class TokenUsage(
        val input: Int,
        val output: Int,
        val cached: Int = 0,
        /**
         * Provider-returned call cost in **micro-USD** (1e-6 USD), or `null` when the
         * provider returns no money figure (BYOK/local). The llm-gateway returns a USD
         * `usage.cost` float; [org.tatrman.kantheon.hebe.providers.openai.OpenAiCompatProvider]
         * converts it to micros. Flows through to `CostGuard.recordCall` so the daily
         * budget cap is enforceable for gateway turns (PD-11).
         */
        val costMicrosUsd: Long? = null,
    ) : StreamEvent()

    @Serializable
    @SerialName("done")
    data object Done : StreamEvent()

    @Serializable
    @SerialName("error")
    data class Error(
        val cause: String,
        val retriable: Boolean,
    ) : StreamEvent()
}

@Serializable
data class ParsedToolCall(
    val id: String,
    val name: String,
    val args: JsonObject,
)
