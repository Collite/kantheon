package org.tatrman.kantheon.hebe.providers.openai

import org.tatrman.kantheon.hebe.api.ChatMessage
import org.tatrman.kantheon.hebe.api.ChatRequest
import org.tatrman.kantheon.hebe.api.StreamEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The llm-gateway path (P2 Stage 2.2; contracts §5.2). The gateway is the same
 * [OpenAiCompatProvider] over a [GatewayClient]-built HTTP client — only the
 * base URL + auth/cost headers change. Asserted with a ktor `MockEngine` (no
 * live HTTP): base-URL/auth swap, streaming assembly, tool-use round-trip, the
 * cost-attribution headers, and graceful degrade when no usage metadata returns.
 */
class GatewayLlmProviderSpec :
    StringSpec({

        val captured = mutableListOf<HttpRequestData>()

        fun engineReturning(sse: String) =
            MockEngine { request ->
                captured.add(request)
                respond(
                    content = ByteReadChannel(sse),
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }

        fun provider(
            sse: String,
            apiKey: String = "gw-secret",
            costCenter: String = "hebe/bora",
            baseUrl: String = "https://llm-gateway.kantheon.example.com/v1",
        ): OpenAiCompatProvider {
            captured.clear()
            val client = GatewayClient.build(apiKey = apiKey, costCenter = costCenter, engine = engineReturning(sse))
            return OpenAiCompatProvider(baseUrl = baseUrl, defaultModel = "gw-model", httpClient = client)
        }

        val textSse =
            """
            data: {"delta":{"content":"Hello"}}

            data: {"delta":{"content":" world"}}

            data: {"usage":{"prompt_tokens":11,"completion_tokens":3},"object":"chunk"}

            data: [DONE]
            """.trimIndent()

        "base-URL + auth + cost-center headers ride the gateway request" {
            runTest {
                val p = provider(textSse)
                p.chat(ChatRequest("", "sys", listOf(ChatMessage.User("hi")), emptyList())).toList()
                val req = captured.single()
                req.url.toString() shouldBe "https://llm-gateway.kantheon.example.com/v1/chat/completions"
                req.headers[HttpHeaders.Authorization] shouldBe "Bearer gw-secret"
                req.headers[GatewayClient.HEADER_COST_CENTER] shouldBe "hebe/bora"
            }
        }

        "X-Turn-Ref is stamped from the coroutine context when present" {
            runTest {
                val p = provider(textSse)
                withTurnRef("job-42") {
                    p.chat(ChatRequest("", "sys", listOf(ChatMessage.User("hi")), emptyList())).toList()
                }
                captured.single().headers[GatewayClient.HEADER_TURN_REF] shouldBe "job-42"
            }
        }

        "X-Turn-Ref is omitted for ad-hoc console chat (no turn ref in scope)" {
            runTest {
                val p = provider(textSse)
                p.chat(ChatRequest("", "sys", listOf(ChatMessage.User("hi")), emptyList())).toList()
                captured.single().headers[GatewayClient.HEADER_TURN_REF] shouldBe null
            }
        }

        "streaming text deltas assemble into the full completion" {
            runTest {
                val p = provider(textSse)
                val events = p.chat(ChatRequest("", "sys", listOf(ChatMessage.User("hi")), emptyList())).toList()
                val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }
                text shouldBe "Hello world"
            }
        }

        "usage metadata becomes a TokenUsage event (cost capture)" {
            runTest {
                val p = provider(textSse)
                val events = p.chat(ChatRequest("", "sys", listOf(ChatMessage.User("hi")), emptyList())).toList()
                val usage = events.filterIsInstance<StreamEvent.TokenUsage>().single()
                usage.input shouldBe 11
                usage.output shouldBe 3
            }
        }

        "gateway usage.cost (USD) is parsed into costMicrosUsd (micro-USD)" {
            runTest {
                // The Kantheon llm-gateway returns a USD `usage.cost` float
                // (e.g. 0.0001 = $0.0001); the provider converts it to micro-USD
                // so CostGuard can enforce the daily cap on gateway turns.
                val costSse =
                    """
                    data: {"delta":{"content":"hi"}}

                    data: {"usage":{"prompt_tokens":11,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":7},"cost":0.0001},"object":"chunk"}

                    data: [DONE]
                    """.trimIndent()
                val p = provider(costSse)
                val usage =
                    p
                        .chat(ChatRequest("", "sys", listOf(ChatMessage.User("hi")), emptyList()))
                        .toList()
                        .filterIsInstance<StreamEvent.TokenUsage>()
                        .single()
                usage.costMicrosUsd shouldBe 100L
                usage.cached shouldBe 7
            }
        }

        "usage without a cost field leaves costMicrosUsd null (BYOK/local degrade)" {
            runTest {
                val p = provider(textSse)
                val usage =
                    p
                        .chat(ChatRequest("", "sys", listOf(ChatMessage.User("hi")), emptyList()))
                        .toList()
                        .filterIsInstance<StreamEvent.TokenUsage>()
                        .single()
                usage.costMicrosUsd shouldBe null
            }
        }

        "a response without usage metadata still completes (graceful degrade)" {
            runTest {
                val noUsage =
                    """
                    data: {"delta":{"content":"ok"}}

                    data: [DONE]
                    """.trimIndent()
                val p = provider(noUsage)
                val events = p.chat(ChatRequest("", "sys", listOf(ChatMessage.User("hi")), emptyList())).toList()
                events.filterIsInstance<StreamEvent.TokenUsage>() shouldHaveSize 0
                events.last() shouldBe StreamEvent.Done
            }
        }

        "a tool-call response round-trips through the gateway client" {
            runTest {
                val toolSse =
                    """
                    data: {"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"search","arguments":"{}"}}]}}

                    data: [DONE]
                    """.trimIndent()
                val p = provider(toolSse)
                val events = p.chat(ChatRequest("", "sys", listOf(ChatMessage.User("find")), emptyList())).toList()
                val call = events.filterIsInstance<StreamEvent.ToolCall>().single()
                call.call.name shouldBe "search"
            }
        }

        "the request body carries the gateway default model" {
            runTest {
                val p = provider(textSse)
                p.chat(ChatRequest("", "sys", listOf(ChatMessage.User("hi")), emptyList())).toList()
                val body = captured.single().body.toByteArrayString()
                body shouldContain "gw-model"
            }
        }
    })

private fun io.ktor.http.content.OutgoingContent.toByteArrayString(): String =
    when (this) {
        is io.ktor.http.content.TextContent -> text
        is io.ktor.http.content.ByteArrayContent -> String(bytes())
        else -> toString()
    }
