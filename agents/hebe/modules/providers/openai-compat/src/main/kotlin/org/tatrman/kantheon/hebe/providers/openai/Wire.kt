package org.tatrman.kantheon.hebe.providers.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<WireMessage>,
    val tools: List<WireTool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val temperature: Double = 0.7,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: JsonElement? = null,
)

@Serializable
data class WireMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class WireTool(
    val type: String = "function",
    val function: WireFunction,
)

@Serializable
data class WireFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class WireToolCall(
    val id: String,
    val type: String = "function",
    val function: WireToolCallFn,
)

@Serializable
data class WireToolCallFn(
    val name: String,
    val arguments: String,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val choices: List<ChunkChoice>,
    val usage: Usage? = null,
)

@Serializable
data class ChunkChoice(
    val index: Int,
    val delta: ChunkDelta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChunkToolCall>? = null,
)

@Serializable
data class ChunkToolCall(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: ChunkToolCallFn? = null,
)

@Serializable
data class ChunkToolCallFn(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val prompt: Int,
    @SerialName("completion_tokens") val completion: Int,
    @SerialName("prompt_tokens_details") val promptDetails: PromptDetails? = null,
)

@Serializable
data class PromptDetails(
    @SerialName("cached_tokens") val cached: Int = 0,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val choices: List<ResponseChoice>,
    val usage: Usage? = null,
)

@Serializable
data class ResponseChoice(
    val index: Int,
    val message: ResponseMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ResponseMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
)
