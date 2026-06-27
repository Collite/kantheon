package org.tatrman.kantheon.hebe.providers.openai

import org.tatrman.kantheon.hebe.api.ChatMessage
import org.tatrman.kantheon.hebe.api.ChatRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * W3C trace-context propagation on gateway calls (P2 Stage 2.4 T4). With an
 * active span context, the gateway request carries a `traceparent` whose trace
 * id matches; with no active span, no `traceparent` is sent.
 */
class GatewayTraceContextSpec : StringSpec({

    val captured = mutableListOf<HttpRequestData>()
    val sse = "data: {\"delta\":{\"content\":\"ok\"}}\n\ndata: [DONE]"

    fun provider(): OpenAiCompatProvider {
        captured.clear()
        val engine =
            MockEngine { request ->
                captured.add(request)
                respond(ByteReadChannel(sse), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        val client = GatewayClient.build(apiKey = "k", costCenter = "hebe/x", engine = engine)
        return OpenAiCompatProvider(baseUrl = "https://gw/v1", defaultModel = "m", httpClient = client)
    }

    val traceId = "0af7651916cd43dd8448eb211c80319c"
    val spanId = "b7ad6b7169203331"

    "an active span context produces a matching traceparent header" {
        runTest {
            val ctx =
                Context.root().with(
                    Span.wrap(
                        SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()),
                    ),
                )
            val scope = ctx.makeCurrent()
            try {
                provider().chat(ChatRequest("", "s", listOf(ChatMessage.User("hi")), emptyList())).toList()
            } finally {
                scope.close()
            }
            val tp = captured.single().headers["traceparent"]!!
            tp shouldStartWith "00-$traceId-"
        }
    }

    "no active span ⇒ no traceparent header" {
        runTest {
            provider().chat(ChatRequest("", "s", listOf(ChatMessage.User("hi")), emptyList())).toList()
            captured.single().headers["traceparent"] shouldBe null
        }
    }
})
