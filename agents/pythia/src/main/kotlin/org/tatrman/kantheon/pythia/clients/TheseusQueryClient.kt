package org.tatrman.kantheon.pythia.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.pythia.executor.TokenExpiredException

/**
 * Real [QueryClient] over **theseus-mcp** (MCP streamable-HTTP) — mirrors golem's
 * `TheseusQueryClient`, adding `pipeline_warnings` forwarding (Rule-6) and bearer
 * fail-closed + token-expiry mapping (401/Unauthorized → [TokenExpiredException],
 * which parks AWAITING_USER_INPUT, kantheon-security §2.1). A fresh client+transport
 * is built per call so the per-user bearer never leaks. **Live-edge transport is
 * integration-deferred** (planning-conventions §4); the unit gate runs a fake.
 */
class TheseusQueryClient(
    private val url: String,
    private val clientName: String = "pythia",
    private val clientVersion: String = "v0.2.0",
) : QueryClient {
    private val log = LoggerFactory.getLogger(TheseusQueryClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun compile(
        source: String,
        sourceLanguage: String,
        targetDialect: String,
        bearer: String?,
    ): CompileResult {
        val result =
            callTool(
                "compile",
                mapOf("source" to source, "source_language" to sourceLanguage, "target_dialect" to targetDialect),
                bearer,
            )
        val sql = firstText(result) ?: throw QueryException("compile returned no SQL")
        val meta = result.structuredContent
        return CompileResult(
            compiledSql = sql,
            appliedSecurity = meta?.get("appliedSecurity")?.jsonPrimitive?.booleanOrNull ?: true,
            warnings = pipelineWarnings(meta),
        )
    }

    override suspend fun query(
        source: String,
        sourceLanguage: String,
        paramsJson: String,
        rowLimit: Int?,
        bearer: String?,
    ): QueryResult {
        val args =
            buildMap<String, Any?> {
                put("source", source)
                put("source_language", sourceLanguage)
                parseParams(paramsJson)?.let { put("parameters", it) }
                put("format", "json")
                rowLimit?.let { put("row_limit", it) }
            }
        val result = callTool("query", args, bearer)
        val rows =
            firstText(result)
                ?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
                ?: JsonArray(emptyList())
        val meta = result.structuredContent
        return QueryResult(
            rows = rows,
            columns = parseColumns(meta),
            rowCount = meta?.get("rowCount")?.jsonPrimitive?.longOrNull ?: rows.size.toLong(),
            truncated = meta?.get("truncated")?.jsonPrimitive?.booleanOrNull ?: false,
            warnings = pipelineWarnings(meta),
        )
    }

    private suspend fun callTool(
        tool: String,
        arguments: Map<String, Any?>,
        bearer: String?,
    ): CallToolResult {
        if (bearer.isNullOrBlank()) throw QueryException("theseus-mcp '$tool' requires an OBO bearer (none forwarded)")
        val httpClient = buildHttpClient(bearer)
        try {
            val client = Client(Implementation(name = clientName, version = clientVersion))
            client.connect(StreamableHttpClientTransport(client = httpClient, url = url))
            return try {
                (client.callTool(name = tool, arguments = arguments) as? CallToolResult)
                    ?: throw QueryException("theseus-mcp '$tool' returned no usable result")
            } finally {
                client.close()
            }
        } catch (e: QueryException) {
            throw e
        } catch (e: Exception) {
            // Map an auth rejection to a parkable token-expiry (fail-closed, kantheon-security §2.1).
            if (looksLikeAuthFailure(e)) throw TokenExpiredException("theseus-mcp rejected the bearer: ${e.message}")
            log.warn("theseus-mcp '{}' call failed: {}", tool, e.message)
            throw QueryException("theseus-mcp '$tool' call failed: ${e.message}", e)
        } finally {
            httpClient.close()
        }
    }

    private fun looksLikeAuthFailure(e: Throwable): Boolean {
        val msg = (e.message ?: "") + (e.cause?.message ?: "")
        return msg.contains("401") || msg.contains("Unauthorized", ignoreCase = true) || msg.contains("403")
    }

    private fun buildHttpClient(bearer: String): HttpClient =
        HttpClient(CIO) {
            install(SSE)
            install(
                createClientPlugin("PythiaObo") {
                    onRequest { request, _ -> request.header("Authorization", "Bearer $bearer") }
                },
            )
        }

    private fun firstText(result: CallToolResult): String? =
        result.content
            .filterIsInstance<TextContent>()
            .firstOrNull()
            ?.text

    private fun pipelineWarnings(meta: JsonObject?): List<String> =
        (meta?.get("pipeline_warnings") as? JsonArray)?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()

    private fun parseColumns(meta: JsonObject?): List<ColumnMeta> =
        (meta?.get("columns") as? JsonArray)
            ?.mapNotNull { el ->
                (el as? JsonObject)?.let { col ->
                    val name = col["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    ColumnMeta(
                        name,
                        col["type"]?.jsonPrimitive?.content ?: "",
                        col["nullable"]?.jsonPrimitive?.booleanOrNull ?: true,
                    )
                }
            } ?: emptyList()

    private fun parseParams(paramsJson: String): JsonObject? =
        if (paramsJson.isBlank() || paramsJson == "{}") {
            null
        } else {
            runCatching { json.parseToJsonElement(paramsJson).jsonObject }.getOrNull()
        }
}
