package org.tatrman.kantheon.themis.integration

import com.google.protobuf.util.JsonFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.tatrman.kantheon.testkit.integration.ContextHandle
import org.tatrman.kantheon.themis.v1.Themis.ResolveRequest
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse
import java.util.Base64

// Helpers for the `themis-routing` integration specs (WS-C2 T5). Two wires, matching Themis's
// two edges (Main.kt):
//  - the MCP `resolve` tool over StreamableHTTP — the ROBUST smoke. On this edge Themis sets
//    `routingEnabled=false`, so it exercises NLP (Kadmos) + the resolution graph and returns an
//    outcome, but no agent-routing. No bearer required.
//  - REST `POST /v1/resolve` (proto-canonical JSON via JsonFormat, mirroring iris-bff's
//    HttpThemisClient) — the GATED agent-routing tier: routing is only computed here.

// ── MCP `resolve` tool (robust smoke) ────────────────────────────────────────────────────────

/**
 * Opens an MCP StreamableHTTP client to the context's themis-mcp, calls the `resolve` tool with
 * [question] (+ optional [locale]/[conversationId]), and returns the [CallToolResult]. Themis's MCP
 * resolve encodes its outcome as a JSON object in the first text content block (isError=false on a
 * nominal run; isError=true only on a hard error).
 *
 * [callTimeout] bounds the whole MCP call — generous (3 min) because a cold Themis JVM in a
 * CPU-throttled test namespace runs NLP (Kadmos) + up to four LLM legs (via Prometheus) on the
 * first request. The CIO engine-level timeout is disabled so this MCP-level bound governs.
 */
suspend fun ContextHandle.callResolve(
    question: String,
    locale: String = "cs",
    conversationId: String? = null,
    callTimeout: Duration = 3.minutes,
): CallToolResult {
    val args =
        buildMap<String, Any?> {
            put("question", question)
            put("locale", locale)
            conversationId?.let { put("conversation_id", it) }
        }
    val http =
        HttpClient(CIO) {
            // 0 = no engine-level request timeout; the MCP RequestOptions timeout below governs.
            engine { requestTimeout = 0 }
        }
    try {
        val client = http.mcpStreamableHttp("${url("themis-mcp")}/mcp") {}
        try {
            return client.callTool("resolve", args, options = RequestOptions(timeout = callTimeout))
        } finally {
            client.close()
        }
    } finally {
        http.close()
    }
}

/** The text payload of the first content block — for MCP resolve, the outcome JSON string. */
fun CallToolResult.bodyText(): String = (content.firstOrNull() as? TextContent)?.text ?: ""

/** Parse the first text content block as a JSON object, or null if it isn't one. */
fun CallToolResult.bodyJson(): JsonObject? =
    bodyText()
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

/**
 * The resolve `outcome` — `resolved` | `awaiting_clarification` | `refused` on a nominal run (the
 * graph always terminates in one of these, degrading gracefully when the LLM legs fail). Null when
 * the body is a hard-error result (`{"error":...}`) or unparseable.
 */
fun CallToolResult.outcome(): String? = (bodyJson()?.get("outcome") as? JsonPrimitive)?.content

/** The `trace_id` from the outcome JSON — present iff Kadmos NLP ran (it stamps the trace). */
fun CallToolResult.traceId(): String? = (bodyJson()?.get("trace_id") as? JsonPrimitive)?.content

/** The `error` message from a hard-error result (`isError=true`), if any. */
fun CallToolResult.errorMessage(): String? = (bodyJson()?.get("error") as? JsonPrimitive)?.content

// ── REST `/v1/resolve` (gated agent-routing tier) ────────────────────────────────────────────

private val protoPrinter = JsonFormat.printer().omittingInsignificantWhitespace()
private val protoParser = JsonFormat.parser().ignoringUnknownFields()

/**
 * Calls Themis `POST /v1/resolve` with [request] as proto-canonical JSON (JsonFormat), forwarding
 * [bearer] as the OBO token — exactly the wire iris-bff's `HttpThemisClient` uses. Returns the
 * parsed [ResolveResponse]. This is the only edge that computes routing (`Resolution.routing`).
 */
suspend fun ContextHandle.resolveRest(
    request: ResolveRequest,
    bearer: String,
    timeoutMs: Long = 180_000,
): ResolveResponse {
    val http =
        HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = timeoutMs }
        }
    try {
        val response =
            http.post("${url("themis-mcp")}/v1/resolve") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                contentType(ContentType.Application.Json)
                setBody(protoPrinter.print(request))
            }
        val builder = ResolveResponse.newBuilder()
        protoParser.merge(response.bodyAsText(), builder)
        return builder.build()
    } finally {
        http.close()
    }
}

/** A fresh (non-resume) [ResolveRequest] for [text] in [locale], asking for routed resolution. */
fun freshResolveRequest(
    text: String,
    locale: String = "cs",
): ResolveRequest =
    ResolveRequest
        .newBuilder()
        .apply {
            freshBuilder
                .setText(text)
                .setLocale(locale)
        }.build()

/**
 * Mints an **unsigned** JWT (`header.payload.sig`) carrying [username] + [roles] in the Keycloak
 * shape (`realm_access.roles`). Themis forwards the caller's bearer verbatim as the OBO token;
 * signature verification is the ingress/sidecar's job, so an unsigned token with the right claims
 * suffices to exercise identity end-to-end. Mirrors the theseus-runquery driver.
 */
fun unsignedJwt(
    username: String,
    roles: List<String>,
): String {
    val enc = Base64.getUrlEncoder().withoutPadding()

    fun seg(s: String) = enc.encodeToString(s.toByteArray())
    val header = seg("""{"alg":"none","typ":"JWT"}""")
    val rolesJson = roles.joinToString(",") { "\"$it\"" }
    val payload = seg("""{"preferred_username":"$username","realm_access":{"roles":[$rolesJson]}}""")
    return "$header.$payload.sig"
}
