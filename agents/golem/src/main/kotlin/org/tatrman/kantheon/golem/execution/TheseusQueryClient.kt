package org.tatrman.kantheon.golem.execution

import io.ktor.client.HttpClient
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorClientTelemetry
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

private val log = LoggerFactory.getLogger(QueryQueryClient::class.java)

/**
 * Real [QueryClient] over **query-mcp** (MCP streamable-HTTP). Calls the `query`
 * and `compile` tools, forwarding the caller's OBO bearer as `Authorization:
 * Bearer` (kantheon-security §2). Rows come back in a `TextContent` (the `query`
 * tool emits JSON rows; `compile` emits the SQL text).
 *
 * Row metadata (rowCount / columns / truncated) is read from the result's
 * `structuredContent`, falling back to the returned rows when absent. The OBO
 * bearer is mandatory — a null bearer fails closed (never an anonymous call).
 *
 * **Integration-pending (Stage 2.4 T3).** Wiremock / mock-MCP-server tests are
 * tracked in GH issue #32 — this impl ships untested against a live edge. A fresh
 * client+transport is built per call so the per-user bearer never leaks across
 * turns; connection pooling is a follow-up.
 */
class QueryQueryClient(
    private val url: String,
    private val clientName: String = "golem",
    private val clientVersion: String = "v0.1.0",
    private val otel: OpenTelemetry? = null,
) : QueryClient {
    private val json = Json { ignoreUnknownKeys = true }

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
        val text = firstText(result)
        val rows =
            text
                ?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
                ?: JsonArray(emptyList())
        val meta = result.structuredContent
        return QueryResult(
            rows = rows,
            columns = parseColumns(meta),
            rowCount = meta?.get("rowCount")?.jsonPrimitive?.longOrNull ?: rows.size.toLong(),
            truncated = meta?.get("truncated")?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    override suspend fun compile(
        source: String,
        sourceLanguage: String,
        targetDialect: String,
        bearer: String?,
    ): CompileResult {
        val args =
            mapOf<String, Any?>(
                "source" to source,
                "source_language" to sourceLanguage,
                "target_dialect" to targetDialect,
            )
        val result = callTool("compile", args, bearer)
        val sql = firstText(result) ?: throw QueryException("compile returned no SQL")
        val appliedSecurity =
            result.structuredContent
                ?.get("appliedSecurity")
                ?.jsonPrimitive
                ?.booleanOrNull ?: true
        return CompileResult(compiledSql = sql, appliedSecurity = appliedSecurity)
    }

    private suspend fun callTool(
        tool: String,
        arguments: Map<String, Any?>,
        bearer: String?,
    ): CallToolResult {
        // Fail closed: the caller's OBO bearer is mandatory (kantheon-security §2) — a
        // null bearer is a wiring bug, not an anonymous call.
        if (bearer == null) throw QueryException("query-mcp '$tool' requires an OBO bearer (none forwarded)")
        val httpClient = buildHttpClient(bearer)
        try {
            val client = Client(Implementation(name = clientName, version = clientVersion))
            client.connect(StreamableHttpClientTransport(client = httpClient, url = url))
            return try {
                val result = client.callTool(name = tool, arguments = arguments)
                (result as? CallToolResult) ?: throw QueryException("query-mcp '$tool' returned no usable result")
            } finally {
                client.close()
            }
        } catch (e: QueryException) {
            throw e
        } catch (e: Exception) {
            log.warn("query-mcp '{}' call failed: {}", tool, e.message)
            throw QueryException("query-mcp '$tool' call failed: ${e.message}", e)
        } finally {
            httpClient.close()
        }
    }

    internal fun buildHttpClient(bearer: String): HttpClient =
        HttpClient(CIO) {
            install(SSE)
            // PT P0·S0.1 T5: the tool hop joins the turn's trace.
            //
            // This REPLACES a hand-rolled `traceparent` injection that was a silent
            // no-op: it propagated through `GlobalOpenTelemetry`, and nothing in this
            // estate ever registers an SDK globally, so the propagator it fetched was
            // always `NoopTextMapPropagator` and no header was ever written. The plugin
            // takes its propagators from the SDK it is handed instead.
            otel?.let { sdk -> install(KtorClientTelemetry) { setOpenTelemetry(sdk) } }
            install(
                createClientPlugin("GolemObo") {
                    onRequest { request, _ ->
                        request.header("Authorization", "Bearer $bearer")
                    }
                },
            )
        }

    private fun firstText(result: CallToolResult): String? =
        result.content
            .filterIsInstance<TextContent>()
            .firstOrNull()
            ?.text

    /** Column metadata from `structuredContent.columns` (`[{name,type,nullable}]`). */
    private fun parseColumns(meta: JsonObject?): List<ColumnMeta> =
        (meta?.get("columns") as? JsonArray)
            ?.mapNotNull { el ->
                (el as? JsonObject)?.let { col ->
                    val name = col["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    ColumnMeta(
                        name = name,
                        type = col["type"]?.jsonPrimitive?.content ?: "",
                        nullable = col["nullable"]?.jsonPrimitive?.booleanOrNull ?: true,
                    )
                }
            }
            ?: emptyList()

    private fun parseParams(paramsJson: String): JsonObject? =
        if (paramsJson.isBlank() || paramsJson == "{}") {
            null
        } else {
            runCatching { json.parseToJsonElement(paramsJson).jsonObject }.getOrNull()
        }
}
