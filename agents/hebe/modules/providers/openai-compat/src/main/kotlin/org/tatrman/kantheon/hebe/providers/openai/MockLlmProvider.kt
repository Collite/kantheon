package org.tatrman.kantheon.hebe.providers.openai

import org.tatrman.kantheon.hebe.api.ChatRequest
import org.tatrman.kantheon.hebe.api.LlmProvider
import org.tatrman.kantheon.hebe.api.ProviderCapabilities
import org.tatrman.kantheon.hebe.api.StreamEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class MockLlmProvider private constructor(
    private val script: List<List<StreamEvent>>,
    private val caps: ProviderCapabilities,
) : LlmProvider {
    private val turn = AtomicInteger()

    override suspend fun chat(req: ChatRequest): Flow<StreamEvent> =
        flow {
            val t = turn.getAndIncrement()
            check(t < script.size) { "MockLlmProvider: out of script for turn $t" }
            script[t].forEach { emit(it) }
        }

    override fun capabilities(): ProviderCapabilities = caps

    class Builder(
        private var caps: ProviderCapabilities =
            ProviderCapabilities(
                streaming = true,
                toolUse = true,
                multimodal = false,
                maxContextTokens = 128_000,
                supportsPromptCaching = true,
            ),
    ) {
        private val turns = mutableListOf<List<StreamEvent>>()

        fun capabilities(caps: ProviderCapabilities): Builder {
            this.caps = caps
            return this
        }

        fun turn(block: TurnBuilder.() -> Unit): Builder {
            val tb = TurnBuilder()
            block(tb)
            turns.add(tb.events)
            return this
        }

        fun build(): MockLlmProvider = MockLlmProvider(turns.toList(), caps)
    }

    class TurnBuilder {
        internal val events = mutableListOf<StreamEvent>()

        fun textDelta(text: String) {
            events.add(StreamEvent.TextDelta(text))
        }

        fun toolCall(
            id: String,
            name: String,
            args: Map<String, String> = emptyMap(),
        ) {
            val argsJson =
                buildJsonObject {
                    args.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                }
            events.add(
                StreamEvent.ToolCall(
                    org.tatrman.kantheon.hebe.api
                        .ParsedToolCall(id, name, argsJson),
                ),
            )
        }

        fun done() {
            events.add(StreamEvent.Done)
        }

        fun tokenUsage(
            input: Int,
            output: Int,
            cached: Int = 0,
            costMicrosUsd: Long? = null,
        ) {
            events.add(StreamEvent.TokenUsage(input, output, cached, costMicrosUsd))
        }

        fun error(
            cause: String,
            retriable: Boolean = false,
        ) {
            events.add(StreamEvent.Error(cause, retriable))
        }
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
