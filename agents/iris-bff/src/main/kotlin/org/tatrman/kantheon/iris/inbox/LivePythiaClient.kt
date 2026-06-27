package org.tatrman.kantheon.iris.inbox

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Ktor-client [PythiaClient] over Pythia's REST control surface (contracts §2):
 * `POST /v1/investigations` to submit, `GET /v1/investigations?user_id=…` for the
 * inbox view. The caller's OBO bearer is forwarded verbatim (identity discipline —
 * never service identity). The live SSE bridge (`GET …/events?from_seq`) is consumed
 * by the inbox lifecycle infra; the live-HTTP fidelity is an integration concern, the
 * unit gate drives this against a Ktor `MockEngine`.
 */
class LivePythiaClient(
    private val baseUrl: String,
    timeoutMs: Long = 15_000,
    private val httpClient: HttpClient =
        HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = timeoutMs }
        },
) : PythiaClient,
    AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = baseUrl.trimEnd('/')
    private val log = LoggerFactory.getLogger(LivePythiaClient::class.java)

    override suspend fun listInvestigations(
        userId: String,
        bearer: String,
    ): List<InvestigationSummary> {
        val resp =
            httpClient.get("$base/v1/investigations") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                parameter("user_id", userId)
            }
        // Never let a transport/auth failure masquerade silently as "empty inbox". We still
        // degrade to empty (the inbox/SSE path must not 500 or tear down the stream on a flaky
        // list call), but the failure is logged — a 401/403 is called out explicitly as a bearer
        // problem (OBO expiry) so it's distinguishable from a genuinely empty list in the logs.
        if (!resp.status.isSuccess()) {
            if (resp.status.value == 401 || resp.status.value == 403) {
                log.warn(
                    "pythia list rejected the bearer for user {} ({}) — OBO expired? empty inbox",
                    userId,
                    resp.status,
                )
            } else {
                log.warn("pythia list for user {} returned {} — degrading to empty inbox", userId, resp.status)
            }
            return emptyList()
        }
        val body = json.parseToJsonElement(resp.bodyAsText()) as? JsonObject ?: return emptyList()
        val rows = (body["investigations"] as? JsonArray) ?: return emptyList()
        return rows.filterIsInstance<JsonObject>().map { it.toSummary() }
    }

    override suspend fun submit(
        questionJson: String,
        bearer: String,
    ): String {
        val resp =
            httpClient.post("$base/v1/investigations") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                contentType(ContentType.Application.Json)
                setBody(questionJson)
            }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException("pythia submit failed: ${resp.status}")
        }
        val body = json.parseToJsonElement(resp.bodyAsText()) as? JsonObject
        return (body?.get("id") ?: body?.get("investigation_id"))?.jsonPrimitive?.content
            ?: throw IllegalStateException("pythia submit returned no id")
    }

    private fun JsonObject.toSummary(): InvestigationSummary =
        InvestigationSummary(
            id = str("id") ?: "",
            question = str("question") ?: "",
            status = str("status") ?: "STATUS_SUBMITTED",
            createdAt = str("created_at") ?: str("createdAt") ?: "",
            updatedAt = str("updated_at") ?: str("updatedAt") ?: "",
            callerKind = str("caller_kind") ?: "IRIS",
            costSoFar =
                ((this["resource_usage"] as? JsonObject)?.get("total_usd")?.jsonPrimitive?.doubleOrNull)
                    ?: 0.0,
        )

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    override fun close() {
        httpClient.close()
    }
}
