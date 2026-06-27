package org.tatrman.kantheon.capabilities.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.capabilities.agentCapability
import org.tatrman.kantheon.capabilities.asCapability
import org.tatrman.kantheon.capabilities.registry.InMemoryRegistry
import org.tatrman.kantheon.capabilities.registry.RegistryQueryService
import org.tatrman.kantheon.capabilities.toolCapability
import org.tatrman.kantheon.capabilities.v1.AgentKind

private fun seededMcpApp(registry: InMemoryRegistry): io.ktor.server.application.Application.() -> Unit =
    {
        installCapabilitiesMcp(RegistryQueryService(registry))
    }

/**
 * Smoke test the MCP transport at `POST /mcp`. The Kotlin MCP SDK wires
 * JSON-RPC framing; here we manually issue `initialize` + `tools/list` to
 * confirm the six capabilities tools are advertised. Per-tool semantics
 * are covered by [CapabilitiesRestSpec] via the shared
 * [RegistryQueryService].
 */
class CapabilitiesMcpSpec :
    StringSpec({

        "POST /mcp tools/list advertises six capabilities.* tools" {
            val reg =
                InMemoryRegistry().apply {
                    register(toolCapability("model.fit.arima:v1").asCapability(), fromFixture = true)
                    register(agentCapability("pythia", AgentKind.INVESTIGATOR).asCapability(), fromFixture = true)
                }
            testApplication {
                application(seededMcpApp(reg))

                val initResp =
                    client.post("/mcp") {
                        contentType(ContentType.Application.Json)
                        headers { append(HttpHeaders.Accept, "application/json, text/event-stream") }
                        setBody(
                            """
                            {
                              "jsonrpc": "2.0",
                              "id": 1,
                              "method": "initialize",
                              "params": {
                                "protocolVersion": "2025-06-18",
                                "capabilities": {},
                                "clientInfo": { "name": "test-client", "version": "0.0.1" }
                              }
                            }
                            """.trimIndent(),
                        )
                    }
                initResp.status shouldBe HttpStatusCode.OK
                val sessionId =
                    initResp.headers["Mcp-Session-Id"]
                        ?: error("MCP server did not return Mcp-Session-Id on initialize")

                val listResp =
                    client.post("/mcp") {
                        contentType(ContentType.Application.Json)
                        headers {
                            append(HttpHeaders.Accept, "application/json, text/event-stream")
                            append("Mcp-Session-Id", sessionId)
                        }
                        setBody(
                            """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
                        )
                    }
                listResp.status shouldBe HttpStatusCode.OK
                val text = listResp.bodyAsText()
                // SSE-framed JSON response — the body may start with "data: " framing or be raw JSON.
                text shouldContain "capabilities.search"
                text shouldContain "capabilities.list_agents"
                text shouldContain "capabilities.register"
                text shouldContain "capabilities.heartbeat"

                val payload = extractJsonRpcPayload(text)
                val tools = payload["result"]!!.jsonObject["tools"]!!.jsonArray
                tools shouldHaveAtLeastSize 6
                tools
                    .map { it.jsonObject["name"]!!.jsonPrimitive.content }
                    .sorted() shouldBe
                    listOf(
                        "capabilities.get",
                        "capabilities.heartbeat",
                        "capabilities.list",
                        "capabilities.list_agents",
                        "capabilities.register",
                        "capabilities.search",
                    )
            }
        }
    })

private fun extractJsonRpcPayload(body: String): JsonObject {
    // Streamable HTTP responses may arrive as raw JSON or as a single SSE `data:` event.
    val jsonText =
        body
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("{") || it.startsWith("data:") }
            ?.removePrefix("data:")
            ?.trim()
            ?: error("no JSON payload in MCP response: $body")
    return Json.parseToJsonElement(jsonText).jsonObject
}
