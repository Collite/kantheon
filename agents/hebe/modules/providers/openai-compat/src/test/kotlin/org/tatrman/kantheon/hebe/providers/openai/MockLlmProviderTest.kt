package org.tatrman.kantheon.hebe.providers.openai

import org.tatrman.kantheon.hebe.api.ChatRequest
import org.tatrman.kantheon.hebe.api.ProviderCapabilities
import org.tatrman.kantheon.hebe.api.StreamEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class MockLlmProviderTest {
    @Test
    fun `single turn replay`() {
        val provider =
            MockLlmProvider
                .builder()
                .turn {
                    textDelta("Hello, world")
                    done()
                }.build()

        val events = mutableListOf<StreamEvent>()
        runBlocking {
            provider
                .chat(
                    ChatRequest("test", "", emptyList(), emptyList()),
                ).collect { events.add(it) }
        }

        events.shouldContainExactly(
            StreamEvent.TextDelta("Hello, world"),
            StreamEvent.Done,
        )
    }

    @Test
    fun `multi-turn scripts work in order`() {
        val provider =
            MockLlmProvider
                .builder()
                .turn {
                    textDelta("Turn 1")
                    done()
                }.turn {
                    textDelta("Turn 2")
                    done()
                }.build()

        val events1 = mutableListOf<StreamEvent>()
        runBlocking {
            provider
                .chat(ChatRequest("m", "", emptyList(), emptyList()))
                .collect { events1.add(it) }
        }
        events1.shouldContainExactly(StreamEvent.TextDelta("Turn 1"), StreamEvent.Done)

        val events2 = mutableListOf<StreamEvent>()
        runBlocking {
            provider
                .chat(ChatRequest("m", "", emptyList(), emptyList()))
                .collect { events2.add(it) }
        }
        events2.shouldContainExactly(StreamEvent.TextDelta("Turn 2"), StreamEvent.Done)
    }

    @Test
    fun `out of script throws`() {
        val provider =
            MockLlmProvider
                .builder()
                .turn {
                    textDelta("Only turn")
                    done()
                }.build()

        runBlocking {
            provider
                .chat(ChatRequest("m", "", emptyList(), emptyList()))
                .collect { }
        }

        var thrown: Throwable? = null
        runBlocking {
            try {
                provider
                    .chat(ChatRequest("m", "", emptyList(), emptyList()))
                    .collect { }
            } catch (e: Throwable) {
                thrown = e
            }
        }

        thrown shouldBe IllegalStateException("MockLlmProvider: out of script for turn 1")
    }

    @Test
    fun `tool call with args`() {
        val provider =
            MockLlmProvider
                .builder()
                .turn {
                    toolCall("call_1", "file_read", mapOf("path" to "README.md"))
                    done()
                }.build()

        val events = mutableListOf<StreamEvent>()
        runBlocking {
            provider
                .chat(ChatRequest("m", "", emptyList(), emptyList()))
                .collect { events.add(it) }
        }

        val toolCall = events.filterIsInstance<StreamEvent.ToolCall>().single()
        toolCall.call.name shouldBe "file_read"
        toolCall.call.args.toString() shouldBe """{"path":"README.md"}"""
    }

    @Test
    fun `capabilities default`() {
        val provider = MockLlmProvider.builder().build()
        val caps = provider.capabilities()
        caps.streaming shouldBe true
        caps.toolUse shouldBe true
        caps.multimodal shouldBe false
        caps.maxContextTokens shouldBe 128_000
        caps.supportsPromptCaching shouldBe true
    }

    @Test
    fun `capabilities can be customized`() {
        val caps =
            ProviderCapabilities(
                streaming = false,
                toolUse = false,
                multimodal = true,
                maxContextTokens = 32_000,
                supportsPromptCaching = false,
            )
        val provider =
            MockLlmProvider
                .builder()
                .capabilities(caps)
                .build()

        provider.capabilities() shouldBe caps
    }

    @Test
    fun `tokenUsage and error events`() {
        val provider =
            MockLlmProvider
                .builder()
                .turn {
                    textDelta("hello")
                    tokenUsage(100, 50, 20)
                    error("something went wrong", retriable = true)
                    done()
                }.build()

        val events = mutableListOf<StreamEvent>()
        runBlocking {
            provider
                .chat(ChatRequest("m", "", emptyList(), emptyList()))
                .collect { events.add(it) }
        }

        events.shouldContainExactly(
            StreamEvent.TextDelta("hello"),
            StreamEvent.TokenUsage(100, 50, 20),
            StreamEvent.Error("something went wrong", retriable = true),
            StreamEvent.Done,
        )
    }
}
