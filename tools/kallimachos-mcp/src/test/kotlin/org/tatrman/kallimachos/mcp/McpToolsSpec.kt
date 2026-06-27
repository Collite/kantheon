package org.tatrman.kallimachos.mcp

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get as wmGet
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post as wmPost
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * P4 Stage 4.1 T1 — JSON↔HTTP fidelity for the `library.*` tools (contracts §4)
 * against a Wiremock'd Kallimachos. Each tool forwards to the right endpoint,
 * carries the OBO bearer through, passes the store's response (incl. Rule-6
 * `messages`) verbatim, and propagates errors as the store's status.
 */
class McpToolsSpec :
    StringSpec({
        fun wiremock() = WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }

        fun forwarder(wm: WireMockServer) = LibraryForwarder(HttpClient(CIO), "http://localhost:${wm.port()}")

        fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

        "library.getContext forwards to POST /getContext with the args body + bearer" {
            val wm = wiremock()
            try {
                wm.stubFor(
                    wmPost(urlPathEqualTo("/getContext"))
                        .withHeader("Authorization", equalTo("Bearer tok"))
                        .withRequestBody(matchingJsonPath("$.notebookId", equalTo("nb")))
                        .willReturn(
                            aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                                """{"chunks":[{"partId":1,"text":"x"}],"grounded":true,"messages":[]}""",
                            ),
                        ),
                )
                val result =
                    runBlocking {
                        forwarder(
                            wm,
                        ).forward(LibrarySpecs.getContext(obj("""{"notebookId":"nb","query":"q"}""")), "tok")
                    }
                result.status shouldBe 200
                result.body shouldContain "\"grounded\":true"
                result.body shouldContain "\"messages\"" // Rule-6 channel passes through
            } finally {
                wm.stop()
            }
        }

        "library.getPage forwards to GET /pages/{id}" {
            val wm = wiremock()
            try {
                wm.stubFor(
                    wmGet(
                        urlPathEqualTo("/pages/42"),
                    ).willReturn(aResponse().withStatus(200).withBody("""{"id":42}""")),
                )
                val result =
                    runBlocking {
                        forwarder(wm).forward(LibrarySpecs.getPage(obj("""{"id":"42","notebookId":"nb"}""")), null)
                    }
                result.status shouldBe 200
                result.body shouldContain "42"
            } finally {
                wm.stop()
            }
        }

        "library.search + findSimilar + traverse + getSource + notebooks map to the right endpoints" {
            val wm = wiremock()
            try {
                listOf("/query", "/findSimilar", "/traverse", "/notebooks").forEach {
                    wm.stubFor(wmPost(urlPathEqualTo(it)).willReturn(aResponse().withStatus(200).withBody("[]")))
                }
                wm.stubFor(wmGet(urlPathEqualTo("/notebooks")).willReturn(aResponse().withStatus(200).withBody("[]")))
                wm.stubFor(wmGet(urlPathEqualTo("/sources/7")).willReturn(aResponse().withStatus(200).withBody("{}")))

                runBlocking {
                    val f = forwarder(wm)
                    f.forward(LibrarySpecs.search(obj("""{"notebookId":"nb"}""")), "t").status shouldBe 200
                    f.forward(LibrarySpecs.findSimilar(obj("""{"notebookId":"nb","query":"q"}""")), "t").status shouldBe
                        200
                    f.forward(LibrarySpecs.traverse(obj("""{"fromNodeId":1,"notebookId":"nb"}""")), "t").status shouldBe
                        200
                    f.forward(LibrarySpecs.getSource(obj("""{"id":"7","notebookId":"nb"}""")), "t").status shouldBe 200
                    f.forward(LibrarySpecs.listNotebooks(), "t").status shouldBe 200
                    f.forward(LibrarySpecs.createNotebook(obj("""{"displayName":"M"}""")), "t").status shouldBe 200
                }
            } finally {
                wm.stop()
            }
        }

        "an RLS denial propagates as the store's status (403)" {
            val wm = wiremock()
            try {
                wm.stubFor(
                    wmPost(urlPathEqualTo("/getContext")).willReturn(
                        aResponse().withStatus(403).withBody("""{"error":"PERMISSION_DENIED"}"""),
                    ),
                )
                val result =
                    runBlocking {
                        forwarder(
                            wm,
                        ).forward(LibrarySpecs.getContext(obj("""{"notebookId":"secret","query":"q"}""")), "tok")
                    }
                result.status shouldBe 403
                result.body shouldContain "PERMISSION_DENIED"
            } finally {
                wm.stop()
            }
        }
    })
