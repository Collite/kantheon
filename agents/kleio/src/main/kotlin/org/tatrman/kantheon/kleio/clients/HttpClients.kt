package org.tatrman.kantheon.kleio.clients

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val json = Json { ignoreUnknownKeys = true }

/** A null JSON array reads as empty — shared by both clients' lenient parsers. */
private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

/**
 * Kleio → kallimachos-mcp `library.getContext` (MCP JSON-RPC tools/call), carrying
 * the caller OBO bearer. The store's `ContextResponse` rides in the MCP
 * TextContent; we map its cited chunks to [RetrievedChunk]. Integration-verified.
 */
class HttpKallimachosMcpClient(
    private val http: HttpClient,
    private val mcpBaseUrl: String,
) : KallimachosMcpClient {
    override suspend fun getContext(
        notebookId: String,
        question: String,
        k: Int,
        bearer: String?,
    ): List<RetrievedChunk> {
        val rpc =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "library.getContext")
                    putJsonObject("arguments") {
                        put("notebookId", notebookId)
                        put("query", question)
                        put("k", k)
                        bearer?.let { put("bearer", it) }
                    }
                }
            }
        val raw =
            http
                .post("$mcpBaseUrl/mcp") {
                    contentType(ContentType.Application.Json)
                    bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    setBody(rpc.toString())
                }.bodyAsText()
        return parseChunks(raw)
    }

    private fun parseChunks(raw: String): List<RetrievedChunk> {
        val text =
            try {
                json
                    .parseToJsonElement(raw)
                    .jsonObject["result"]
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content ?: return emptyList()
            } catch (e: Exception) {
                return emptyList()
            }
        return try {
            (json.parseToJsonElement(text).jsonObject["chunks"] as? JsonArray).orEmpty().map { it.jsonObject.toChunk() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun JsonObject.toChunk(): RetrievedChunk {
        val citation = this["citation"]?.jsonObject
        return RetrievedChunk(
            partId = this["partId"]?.jsonPrimitive?.content?.toLong() ?: 0,
            sourceId = this["sourceId"]?.jsonPrimitive?.content?.toLong() ?: 0,
            pageId = this["pageId"]?.jsonPrimitive?.content?.toLongOrNull(),
            text = this["text"]?.jsonPrimitive?.content ?: "",
            score = this["score"]?.jsonPrimitive?.content?.toDouble() ?: 0.0,
            title = citation?.get("title")?.jsonPrimitive?.content ?: "",
            locator = citation?.get("locator")?.jsonPrimitive?.content ?: "",
            sourceRef = citation?.get("sourceRef")?.jsonPrimitive?.content ?: "",
        )
    }
}

/**
 * Kleio → LLM-gateway grounded synthesis. Speaks the LLM gateway's OpenAI-shaped chat
 * surface — `POST /v1/chat/completions`, `{model, messages:[{role,content}]}`,
 * reply `{choices:[{message:{content}}]}`.
 * The prompt constrains the answer to the retrieved chunks and asks the model to
 * return `{"answer", "citedPartIds", "citedPageIds"}`; a parse failure yields the
 * raw text with no citations (the render then drops to NO-citations, never
 * fabricated). Integration-verified.
 */
class HttpKleioLlmClient(
    private val http: HttpClient,
    private val llmGatewayBaseUrl: String,
    private val systemPrompt: String,
    private val model: String,
    private val apiKey: String = "", // ttrk- gateway key (2.0 /v1 is key-gated); blank in tests
) : KleioLlmClient {
    override suspend fun answer(
        question: String,
        chunks: List<RetrievedChunk>,
    ): GroundedAnswer {
        val context =
            chunks.joinToString("\n\n") {
                "[part ${it.partId}${it.pageId?.let { p ->
                    ", page $p"
                } ?: ""}] ${it.text}"
            }
        val user = "Question: $question\n\nRetrieved chunks (cite by part/page id):\n$context"
        val body =
            buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", user)
                    }
                }
            }
        val raw =
            http
                .post("$llmGatewayBaseUrl/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                    setBody(body.toString())
                }.bodyAsText()
        return parse(raw)
    }

    private fun parse(raw: String): GroundedAnswer {
        val content = extractContent(raw) ?: raw
        return try {
            val o = json.parseToJsonElement(content.trim()).jsonObject
            GroundedAnswer(
                text = o["answer"]?.jsonPrimitive?.content ?: content,
                citedPartIds = (o["citedPartIds"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content.toLong() },
                citedPageIds = (o["citedPageIds"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content.toLong() },
            )
        } catch (e: Exception) {
            GroundedAnswer(text = content, citedPartIds = emptyList(), citedPageIds = emptyList())
        }
    }

    /** The assistant text — `choices[0].message.content` (standard OpenAI chat.completion). */
    private fun extractContent(raw: String): String? =
        try {
            val root = json.parseToJsonElement(raw).jsonObject
            root["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.content
        } catch (e: Exception) {
            null
        }
}
