package org.tatrman.kantheon.kleio.clients

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * LG-P0·S2·T6 — pins Kleio onto the LLM gateway's `/v1/chat/completions` surface after the
 * `/api/v1` → `/v1` migration (design A-2; 1.x serves both, so the change is safe and Kleio is the
 * sole `/api/v1/chat/completions` caller per the SQ-2 sweep). The path assertion is the regression
 * guard; the parsing assertions confirm the grounded-answer contract is unaffected.
 */
class HttpKleioLlmClientSpec :
    StringSpec({

        // Build a `chat.completions` envelope whose choices[0].message.content is [messageContent].
        fun envelope(messageContent: String): String =
            buildJsonObject {
                putJsonArray("choices") {
                    addJsonObject {
                        putJsonObject("message") { put("content", messageContent) }
                    }
                }
            }.toString()

        fun mockClient(
            responseBody: String,
            onRequest: (String) -> Unit,
        ): HttpKleioLlmClient {
            val engine =
                MockEngine { request ->
                    onRequest(request.url.toString())
                    respond(
                        content = responseBody,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            return HttpKleioLlmClient(
                http = HttpClient(engine),
                llmGatewayBaseUrl = "http://llm-gateway.test",
                systemPrompt = "Answer only from the retrieved chunks.",
                model = "kleio-model",
            )
        }

        val chunk =
            RetrievedChunk(
                12L,
                1L,
                3L,
                "Bratislava is the capital of Slovakia.",
                0.9,
                "Geo",
                "¶1",
                "kallimachos://nb/1/12",
            )

        "posts to /v1/chat/completions (not /api/v1) and parses the grounded answer" {
            var url: String? = null
            // the model's answer is itself a JSON envelope carried in message.content
            val answerJson = """{"answer":"It is Bratislava.","citedPartIds":[12],"citedPageIds":[3]}"""
            val client = mockClient(envelope(answerJson), onRequest = { url = it })

            val answer = runBlocking { client.answer("What is the capital?", listOf(chunk)) }

            url shouldBe "http://llm-gateway.test/v1/chat/completions"
            url!! shouldNotContain "/api/v1"
            answer.text shouldBe "It is Bratislava."
            answer.citedPartIds shouldBe listOf(12L)
            answer.citedPageIds shouldBe listOf(3L)
        }

        "still honors the top-level content fallback on the /v1 path" {
            var url: String? = null
            val client = mockClient("""{"content":"plain ungrounded text"}""", onRequest = { url = it })

            val answer = runBlocking { client.answer("q?", listOf(chunk)) }

            url shouldContain "/v1/chat/completions"
            answer.text shouldBe "plain ungrounded text"
            answer.citedPartIds shouldBe emptyList()
        }
    })
