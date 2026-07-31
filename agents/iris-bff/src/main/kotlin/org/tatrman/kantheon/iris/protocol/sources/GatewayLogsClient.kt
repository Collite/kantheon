package org.tatrman.kantheon.iris.protocol.sources

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class GatewayPage(
    val items: List<GatewayCall> = emptyList(),
)

/**
 * Reads `prompt_logs` rows from ttr-llm-gateway's inspect surface
 * (contracts §5, endpoint added in this arc's tatrman-server branch).
 *
 * **Correlation-keyed, never time-windowed.** Two users' turns overlap
 * constantly, so a time window would mix them; `turn_ref`/`trace_id` is what
 * makes attribution exact.
 *
 * The caller's OBO bearer is forwarded (architecture §7) — this client never
 * holds an identity of its own.
 */
class GatewayLogsClient(
    private val baseUrl: String,
    private val http: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun fetch(
        turnRef: String,
        traceId: String,
        limit: Int,
        bearer: String,
    ): SourceOutcome<GatewaySource> {
        if (baseUrl.isBlank()) return SourceOutcome.SkippedByConfig
        if (turnRef.isBlank() && traceId.isBlank()) {
            return SourceOutcome.Degraded("llm-gateway: turn has no turn_ref or trace_id to correlate on")
        }
        return guardSource("llm-gateway") {
            val res: HttpResponse =
                http.get("${baseUrl.trimEnd('/')}/v1/prompt-logs") {
                    if (turnRef.isNotBlank()) parameter("turn_ref", turnRef)
                    if (traceId.isNotBlank()) parameter("trace_id", traceId)
                    parameter("limit", limit)
                    bearerAuth(bearer)
                }
            if (!res.status.isSuccess()) error("HTTP ${res.status.value}")
            val page = json.decodeFromString<GatewayPage>(res.bodyAsText())
            GatewaySource(
                status = SourceStatus.OK,
                detail = "${page.items.size} call row(s)",
                items = page.items,
            )
        }
    }
}
