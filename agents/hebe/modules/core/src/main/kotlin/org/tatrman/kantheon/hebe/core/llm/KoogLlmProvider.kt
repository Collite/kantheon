@file:Suppress("UNUSED_PARAMETER")

package org.tatrman.kantheon.hebe.core.llm

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import org.tatrman.kantheon.hebe.api.ChatMessage
import org.tatrman.kantheon.hebe.api.ChatRequest
import org.tatrman.kantheon.hebe.api.LlmProvider
import org.tatrman.kantheon.hebe.api.ParsedToolCall
import org.tatrman.kantheon.hebe.api.ProviderCapabilities
import org.tatrman.kantheon.hebe.api.StreamEvent
import org.tatrman.kantheon.hebe.providers.openai.OpenAiCompatProvider
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * KoogLlmProvider is the only file in the repo that imports ai.koog.* from a public type.
 *
 * Direct chat() calls bypass the koog AIAgent intentionally: our own LoopDriver handles the
 * agentic loop, so routing through koog's AIAgent would double-loop. KoogPromptExecutor is
 * retained so that any koog-native features (e.g. future subgraph integrations) can reuse
 * the same transport without importing koog elsewhere.
 */
class KoogLlmProvider(
    private val transport: LlmProvider,
) : LlmProvider {
    override suspend fun chat(req: ChatRequest): Flow<StreamEvent> = transport.chat(req)

    override fun capabilities(): ProviderCapabilities = transport.capabilities()
}

private val hebeClock = Clock.System

object KoogLlmProviderFactory {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    fun create(
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        maxContextTokens: Int = 128_000,
    ): KoogLlmProvider {
        val openAiProvider =
            OpenAiCompatProvider(
                baseUrl = baseUrl,
                defaultModel = defaultModel,
                httpClient =
                    org.tatrman.kantheon.hebe.providers.openai.HttpClientFactory
                        .create(apiKey),
                maxContextTokens = maxContextTokens,
                json = json,
            )

        return KoogLlmProvider(openAiProvider)
    }
}

private class KoogPromptExecutor(
    private val transport: LlmProvider,
    private val json: Json,
) : PromptExecutor() {
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Response> {
        val chatRequest = prompt.toChatRequest(model)
        val events = mutableListOf<StreamEvent>()
        transport.chat(chatRequest).collect { events.add(it) }

        val textParts = events.filterIsInstance<StreamEvent.TextDelta>()
        val toolCalls = events.filterIsInstance<StreamEvent.ToolCall>()
        val usage = events.filterIsInstance<StreamEvent.TokenUsage>().lastOrNull()

        val assistantContent = textParts.joinToString("") { it.text }

        val responses = mutableListOf<Message.Response>()

        responses.add(
            Message.Assistant(
                parts = listOf(ContentPart.Text(assistantContent)),
                metaInfo =
                    ResponseMetaInfo.create(
                        clock = hebeClock,
                        totalTokensCount = (usage?.input ?: 0) + (usage?.output ?: 0),
                        inputTokensCount = usage?.input,
                        outputTokensCount = usage?.output,
                    ),
            ),
        )

        for (tc in toolCalls) {
            responses.add(
                Message.Tool.Call(
                    id = tc.call.id,
                    tool = tc.call.name,
                    parts = listOf(ContentPart.Text(tc.call.args.toString())),
                    metaInfo = ResponseMetaInfo.create(hebeClock),
                ),
            )
        }

        return responses
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> {
        val chatRequest = prompt.toChatRequest(model)
        return flow {
            emitAll(
                transport.chat(chatRequest).map { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> StreamFrame.TextDelta(event.text)
                        is StreamEvent.ToolCall ->
                            StreamFrame.ToolCallComplete(
                                id = event.call.id,
                                name = event.call.name,
                                content = event.call.args.toString(),
                            )
                        is StreamEvent.TokenUsage ->
                            StreamFrame.End(
                                finishReason = "stop",
                                metaInfo =
                                    ResponseMetaInfo.create(
                                        clock = hebeClock,
                                        totalTokensCount = event.input + event.output,
                                        inputTokensCount = event.input,
                                        outputTokensCount = event.output,
                                    ),
                            )
                        is StreamEvent.Done -> StreamFrame.End(finishReason = "stop")
                        is StreamEvent.Error -> StreamFrame.End(finishReason = "error")
                    }
                },
            )
        }
    }

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult = ModerationResult(isHarmful = false, categories = emptyMap())

    private fun Prompt.toChatRequest(model: LLModel): ChatRequest {
        val systemText = messages.filterIsInstance<Message.System>().joinToString("\n") { it.content }

        val chatMessages =
            messages.mapNotNull { msg ->
                when (msg) {
                    is Message.User -> ChatMessage.User(content = msg.content)
                    is Message.Assistant -> ChatMessage.Assistant(content = msg.content, toolCalls = emptyList())
                    is Message.Tool.Call -> {
                        val argsJson =
                            try {
                                json.parseToJsonElement(msg.content).safeAsJsonObject()
                            } catch (_: Exception) {
                                JsonObject(emptyMap())
                            }
                        ChatMessage.Assistant(
                            content = "",
                            toolCalls = listOf(ParsedToolCall(msg.id ?: "", msg.tool, argsJson)),
                        )
                    }
                    is Message.Tool.Result ->
                        ChatMessage.ToolResult(
                            callId = msg.id ?: "",
                            content = msg.content,
                            isError = msg.isError,
                        )
                    is Message.System -> null
                    is Message.Reasoning -> null
                }
            }

        return ChatRequest(
            model = model.id,
            systemPrompt = systemText,
            messages = chatMessages,
            tools = emptyList(),
            temperature = 0.7,
            maxTokens = null,
        )
    }

    private fun Json.parseToJsonElement(text: String): kotlinx.serialization.json.JsonElement =
        kotlinx.serialization.json.Json
            .parseToJsonElement(text)

    private fun kotlinx.serialization.json.JsonElement.safeAsJsonObject(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

    override fun close() {}
}
