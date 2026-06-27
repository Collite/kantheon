package org.tatrman.kantheon.golem.integration

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.testkit.integration.ContextHandle
import java.util.Base64

// Helpers for the `golem-erp` integration spec: minting OBO bearers and driving the
// Golem `POST /v1/answer/sync` REST edge (GolemRequest JSON → ConversationalResponse
// JSON) through the ContextHandle-resolved golem service URL. Mirrors the
// theseus-runquery McpQueryDriver, but over plain HTTP/JSON rather than MCP.

/**
 * Mints an **unsigned** JWT (`header.payload.sig`). Golem's [ShemAdmission]
 * base64url-decodes only the payload in v1 (`preferred_username`, `tenant`,
 * `realm_access.roles`) — JWKS verification terminates at the ingress edge — so an
 * unsigned token carrying the right claims is sufficient to exercise admission +
 * role-visibility end-to-end. [roles] must intersect the deployed Shem's
 * `visibility_roles` for a turn to be admitted.
 */
fun unsignedJwt(
    username: String,
    roles: List<String>,
    tenant: String = "default",
): String {
    val enc = Base64.getUrlEncoder().withoutPadding()

    fun seg(s: String) = enc.encodeToString(s.toByteArray())
    val header = seg("""{"alg":"none","typ":"JWT"}""")
    val rolesJson = roles.joinToString(",") { "\"$it\"" }
    val payload =
        seg(
            """{"preferred_username":"$username","tenant":"$tenant","realm_access":{"roles":[$rolesJson]}}""",
        )
    return "$header.$payload.sig"
}

/** Outcome of a `/v1/answer/sync` call — the HTTP status code and the parsed JSON body. */
data class GolemAnswer(
    val status: Int,
    val body: JsonObject?,
) {
    /** `status` enum from a `ConversationalResponse` (e.g. `STATUS_DONE`), or null. */
    fun turnStatus(): String? = (body?.get("status") as? JsonPrimitive)?.content

    /** The `envelopes[]` array of the response (empty when absent). */
    fun envelopes(): JsonArray = (body?.get("envelopes") as? JsonArray) ?: JsonArray(emptyList())

    /** The error code of the first `messages[]` entry (Rule-6), if any. */
    fun firstMessageCode(): String? {
        val messages = body?.get("messages") as? JsonArray ?: return null
        val first = messages.firstOrNull() as? JsonObject ?: return null
        return (first["code"] as? JsonPrimitive)?.content
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Builds a minimal `GolemRequest` (proto3-JSON, camelCase) for a free-text domain
 * question. [golemId] must match the pod's loaded Shem.
 */
fun golemRequest(
    id: String,
    golemId: String,
    question: String,
    locale: String = "en",
    userId: String = "alice",
    tenantId: String = "default",
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("golemId", golemId)
        put("question", question)
        put(
            "caller",
            buildJsonObject {
                put("userId", userId)
                put("tenantId", tenantId)
                put("correlationId", id)
            },
        )
        put("context", buildJsonObject { put("locale", locale) })
    }

/**
 * POSTs [request] to the context's golem `/v1/answer/sync`. When [bearer] is set it
 * travels as `Authorization: Bearer …` (the OBO token). Returns the HTTP status +
 * parsed JSON body (null if the body isn't a JSON object).
 */
suspend fun ContextHandle.answerGolem(
    request: JsonObject,
    bearer: String? = null,
): GolemAnswer {
    val http = HttpClient(CIO)
    try {
        val response =
            http.post("${url("golem")}/v1/answer/sync") {
                contentType(ContentType.Application.Json)
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(request.toString())
            }
        val text = response.bodyAsText()
        val body = runCatching { lenientJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        return GolemAnswer(response.status.value, body)
    } finally {
        http.close()
    }
}
