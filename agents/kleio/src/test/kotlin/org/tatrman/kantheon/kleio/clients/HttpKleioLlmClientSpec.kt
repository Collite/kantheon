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

        // Was "still honors the top-level content fallback on the /v1 path", and it had been RED
        // since the day it was written (review-078 R6). The fallback it asserted does not exist:
        // `5af7361` (LG-P6·S1) deliberately DELETED `?: root["content"]` from `extractContent` and
        // rewrote its KDoc to say `choices[].message.content` only — the 2.0 `/v1` surface is a
        // standard OpenAI chat.completion and has no top-level `content`. The later commit
        // `b814697` then added a case asserting the behaviour that had just been removed on
        // purpose. So the test was wrong, not the code; it is rewritten here to pin what the
        // client actually contracts to do, which is the coverage LG-P6·S1 wanted anyway.
        "a body with no choices[] is passed through verbatim rather than invented into an answer" {
            var url: String? = null
            val body = """{"content":"plain ungrounded text"}"""
            val client = mockClient(body, onRequest = { url = it })

            val answer = runBlocking { client.answer("q?", listOf(chunk)) }

            url shouldContain "/v1/chat/completions"
            // No `choices[0].message.content` and no `answer` key, so neither extraction step
            // finds anything: the raw body surfaces as the answer text. Ugly on purpose — a
            // malformed gateway reply should be visibly malformed, not silently unwrapped into
            // something that reads like a grounded answer.
            answer.text shouldBe body
            answer.citedPartIds shouldBe emptyList()
            answer.citedPageIds shouldBe emptyList()
        }
    })
