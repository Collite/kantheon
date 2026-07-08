package org.tatrman.kantheon.pythia.integration

import com.google.protobuf.util.JsonFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.kantheon.testkit.integration.ContextHandle
import org.tatrman.kantheon.pythia.v1.Caller
import org.tatrman.kantheon.pythia.v1.Constraints
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.HitlPolicy
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.InvestigationContext
import org.tatrman.kantheon.pythia.v1.PlanApprovalPolicy

// Helpers for the `pythia-rca` integration specs (WS-C2 T5). Pythia's edge is the async REST control
// surface (contracts §2): POST /v1/investigations → 202 {id, status}, then GET /v1/investigations/{id}
// → the InvestigationArtifact (with its live Status) — there is no synchronous run-to-terminal edge,
// so the gated tier submits then POLLS to a terminal Status. Every endpoint enforces PD-8 admission
// (auth/Admission.kt): a missing/blank bearer → 403 `unauthenticated`; reading another user's
// investigation → 403 `forbidden`. The bearer is the v1 structural form `Bearer <userId>` or
// `Bearer <userId>#role1,role2` (NOT a JWT).

/** Pythia's four terminal investigation statuses (proto Status enum). */
val PYTHIA_TERMINAL_STATUSES = setOf("STATUS_DONE", "STATUS_FAILED", "STATUS_HALTED", "STATUS_INCONCLUSIVE")

/** A raw Pythia bearer token value (the caller adds the `Bearer ` prefix). */
fun pythiaToken(
    userId: String,
    roles: List<String> = emptyList(),
): String = if (roles.isEmpty()) userId else "$userId#${roles.joinToString(",")}"

private val JSON = Json { ignoreUnknownKeys = true }
private val protoPrinter = JsonFormat.printer().omittingInsignificantWhitespace()

/** The outcome of a Pythia control-surface call: HTTP status + raw body, with typed accessors. */
data class PythiaHttpResult(
    val status: Int,
    val body: String,
) {
    private fun json(): JsonObject? = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull()

    /** `id` from a 202 submit response (`{ id, status }`). */
    fun investigationId(): String? = (json()?.get("id") as? JsonPrimitive)?.content

    /** The `status` string — the submit `{status}` or the GET artifact's `status` field. */
    fun statusField(): String? = (json()?.get("status") as? JsonPrimitive)?.content

    /** The first Rule-6 `messages[].code` of an error body (`unauthenticated` / `forbidden` / …). */
    fun firstMessageCode(): String? {
        val messages = json()?.get("messages") as? JsonArray ?: return null
        val first = messages.firstOrNull() as? JsonObject ?: return null
        return (first["code"] as? JsonPrimitive)?.content
    }
}

private fun httpClient(timeoutMs: Long): HttpClient =
    HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = timeoutMs }
    }

/** POST /v1/investigations with [investigation] as proto-canonical JSON; [token] → `Authorization: Bearer`. */
suspend fun ContextHandle.submitInvestigation(
    investigation: Investigation,
    token: String?,
    timeoutMs: Long = 30_000,
): PythiaHttpResult {
    val http = httpClient(timeoutMs)
    try {
        val response =
            http.post("${url("pythia")}/v1/investigations") {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(protoPrinter.print(investigation))
            }
        return PythiaHttpResult(response.status.value, response.bodyAsText())
    } finally {
        http.close()
    }
}

/** GET /v1/investigations/{id} — the current InvestigationArtifact (proto-JSON) or an error body. */
suspend fun ContextHandle.getInvestigation(
    id: String,
    token: String?,
    timeoutMs: Long = 30_000,
): PythiaHttpResult {
    val http = httpClient(timeoutMs)
    try {
        val response =
            http.get("${url("pythia")}/v1/investigations/$id") {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
        return PythiaHttpResult(response.status.value, response.bodyAsText())
    } finally {
        http.close()
    }
}

/**
 * Polls GET /v1/investigations/{id} until the artifact's Status is terminal (DONE/FAILED/HALTED/
 * INCONCLUSIVE) or [timeoutMs] elapses. Returns the last observed result (its `statusField()` names
 * the terminal, or the last non-terminal status on timeout).
 */
suspend fun ContextHandle.pollInvestigationUntilTerminal(
    id: String,
    token: String,
    timeoutMs: Long = 180_000,
    pollIntervalMs: Long = 2_000,
): PythiaHttpResult {
    var elapsed = 0L
    var last = getInvestigation(id, token)
    while (elapsed < timeoutMs) {
        if (last.status == 200 && last.statusField() in PYTHIA_TERMINAL_STATUSES) return last
        delay(pollIntervalMs)
        elapsed += pollIntervalMs
        last = getInvestigation(id, token)
    }
    return last
}

/**
 * The smallest RCA [Investigation] that reaches a terminal Status: a natural-language question with a
 * NORMAL depth budget and a **non-interactive** HitlPolicy (`PLAN_APPROVAL_AUTO`) so the run does not
 * park at `AWAITING_PLAN_APPROVAL` (Pythia's `hitl_default` is INTERACTIVE). Mirrors the RCA E2E
 * fixture (RcaE2ESpec). `caller.user_id` is overwritten server-side from the bearer.
 */
fun minimalRcaInvestigation(
    question: String,
    locale: String = "en",
): Investigation =
    Investigation
        .newBuilder()
        .setQuestion(question)
        .setCaller(Caller.newBuilder().setKind(Caller.Kind.IRIS))
        .setContext(InvestigationContext.newBuilder().setLocale(locale))
        .setConstraints(Constraints.newBuilder().setDepthBudget(DepthBudget.DEPTH_NORMAL))
        .setHitlPolicy(HitlPolicy.newBuilder().setPlanApproval(PlanApprovalPolicy.PLAN_APPROVAL_AUTO))
        .build()
