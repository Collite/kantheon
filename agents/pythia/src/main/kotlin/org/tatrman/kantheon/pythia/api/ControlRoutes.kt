package org.tatrman.kantheon.pythia.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tatrman.kantheon.pythia.auth.Admission
import org.tatrman.kantheon.pythia.auth.Principal
import org.tatrman.kantheon.pythia.auth.canRead
import org.tatrman.kantheon.pythia.auth.isAdmin
import org.tatrman.kantheon.pythia.orchestrator.BudgetDecision
import org.tatrman.kantheon.pythia.orchestrator.InvestigationOrchestrator
import org.tatrman.kantheon.pythia.orchestrator.ResumeOutcome
import org.tatrman.kantheon.pythia.persistence.InvestigationRepository
import org.tatrman.kantheon.pythia.v1.Investigation
import java.util.UUID

private val JSON = Json { ignoreUnknownKeys = true }

/**
 * The REST control surface (contracts §2) over scripted stubs. Every endpoint
 * validates the bearer (PD-8) and re-checks visibility on reads; resume is
 * idempotent (second call → 409 with the current status). `replay`/`reproduce`
 * are 501 until Phase 3 Stage 3.3.
 */
fun Route.controlRoutes(
    orchestrator: InvestigationOrchestrator,
    investigations: InvestigationRepository,
    assembler: ArtifactAssembler,
    admission: Admission,
) {
    route("/v1/investigations") {
        // PD-2 inbox list (must precede /{id} so it isn't shadowed).
        get {
            val principal = call.principalOrReject(admission) ?: return@get
            val requestedUser = call.request.queryParameters["user_id"]
            if (requestedUser != null && requestedUser != principal.userId && !principal.isAdmin()) {
                return@get call.reject(
                    HttpStatusCode.Forbidden,
                    "forbidden",
                    "cannot list another user's investigations",
                )
            }
            // Accept both bare (DONE) and canonical (STATUS_DONE) status filters.
            val statuses =
                call.request.queryParameters["statuses"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.map { if (it.startsWith("STATUS_")) it else "STATUS_$it" }
                    ?.toSet()
                    ?: emptySet()
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val pageSize = (call.request.queryParameters["page_size"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
            val result = investigations.list(requestedUser ?: principal.userId, statuses, page, pageSize)
            val body =
                buildJsonObject {
                    put(
                        "investigations",
                        kotlinx.serialization.json.buildJsonArray {
                            result.rows.forEach { add(JSON.parseToJsonElement(ProtoJson.print(assembler.summary(it)))) }
                        },
                    )
                    result.nextPage?.let { put("next_page", JsonPrimitive(it)) }
                }
            call.respond(body)
        }

        post {
            val principal = call.principalOrReject(admission) ?: return@post
            val parsed =
                runCatching { ProtoJson.parseInto(call.receiveText(), Investigation.newBuilder()) }
                    .getOrElse {
                        return@post call.reject(
                            HttpStatusCode.BadRequest,
                            "bad_request",
                            "request body is not a valid Investigation",
                        )
                    }
            // Stamp the caller identity from the validated bearer (never trust the body).
            parsed.callerBuilder.setUserId(principal.userId)
            // Forward the OBO bearer so downstream Themis/query calls carry the user's identity (PD-8).
            val id = orchestrator.submit(parsed.build(), call.bearer())
            call.respond(
                HttpStatusCode.Accepted,
                buildJsonObject {
                    put("id", JsonPrimitive(id.toString()))
                    put("status", JsonPrimitive("STATUS_SUBMITTED"))
                },
            )
        }

        route("/{id}") {
            get {
                val principal = call.principalOrReject(admission) ?: return@get
                val rec = call.findOrNull(investigations) ?: return@get
                if (!principal.canRead(assembler.ownerUserId(rec))) {
                    return@get call.reject(HttpStatusCode.Forbidden, "forbidden", "not visible to this caller")
                }
                call.respondText(ProtoJson.print(assembler.assemble(rec)), io.ktor.http.ContentType.Application.Json)
            }

            post("/approve-plan") {
                val principal = call.principalOrReject(admission) ?: return@post
                val rec = call.findOrNull(investigations) ?: return@post
                if (!principal.canRead(assembler.ownerUserId(rec))) {
                    return@post call.reject(HttpStatusCode.Forbidden, "forbidden", "not visible to this caller")
                }
                val approve = call.verdict() ?: return@post
                call.respondResume(orchestrator.approvePlan(UUID.fromString(rec.id.toString()), approve))
            }

            post("/approve-revision") {
                val principal = call.principalOrReject(admission) ?: return@post
                val rec = call.findOrNull(investigations) ?: return@post
                if (!principal.canRead(assembler.ownerUserId(rec))) {
                    return@post call.reject(HttpStatusCode.Forbidden, "forbidden", "not visible to this caller")
                }
                val approve = call.verdict() ?: return@post
                call.respondResume(orchestrator.approveRevision(rec.id, approve))
            }

            post("/answer") {
                val principal = call.principalOrReject(admission) ?: return@post
                val rec = call.findOrNull(investigations) ?: return@post
                if (!principal.canRead(assembler.ownerUserId(rec))) {
                    return@post call.reject(HttpStatusCode.Forbidden, "forbidden", "not visible to this caller")
                }
                call.respondResume(orchestrator.answer(rec.id))
            }

            post("/budget-decision") {
                val principal = call.principalOrReject(admission) ?: return@post
                val rec = call.findOrNull(investigations) ?: return@post
                if (!principal.canRead(assembler.ownerUserId(rec))) {
                    return@post call.reject(HttpStatusCode.Forbidden, "forbidden", "not visible to this caller")
                }
                val decision =
                    runCatching { BudgetDecision.valueOf(call.body()["decision"]?.jsonPrimitive?.content ?: "") }
                        .getOrElse {
                            return@post call.reject(
                                HttpStatusCode.BadRequest,
                                "bad_decision",
                                "decision must be CONTINUE|HALT_GRACEFULLY|ABANDON",
                            )
                        }
                call.respondResume(orchestrator.budgetDecision(rec.id, decision))
            }

            post("/halt") {
                val principal = call.principalOrReject(admission) ?: return@post
                val rec = call.findOrNull(investigations) ?: return@post
                if (!principal.canRead(assembler.ownerUserId(rec))) {
                    return@post call.reject(HttpStatusCode.Forbidden, "forbidden", "not visible to this caller")
                }
                call.respondResume(orchestrator.halt(rec.id))
            }

            post("/replay") {
                val principal = call.principalOrReject(admission) ?: return@post
                val rec = call.findOrNull(investigations) ?: return@post
                if (!principal.canRead(assembler.ownerUserId(rec))) {
                    return@post call.reject(HttpStatusCode.Forbidden, "forbidden", "not visible to this caller")
                }
                val overrides = call.body()["overrides"]?.toString()
                val newId = orchestrator.replay(rec.id, overrides, call.bearer())
                call.respondNewId(newId)
            }
            post("/reproduce") {
                val principal = call.principalOrReject(admission) ?: return@post
                val rec = call.findOrNull(investigations) ?: return@post
                if (!principal.canRead(assembler.ownerUserId(rec))) {
                    return@post call.reject(HttpStatusCode.Forbidden, "forbidden", "not visible to this caller")
                }
                call.respondNewId(orchestrator.reproduce(rec.id, call.bearer()))
            }
        }
    }
}

// ---- helpers ----

private suspend fun ApplicationCall.principalOrReject(admission: Admission): Principal? {
    val principal = admission.authenticate(request.headers["Authorization"])
    // Echo a *sanitised, length-capped* correlation id (defang header-injection via CR/LF).
    request.headers["X-Correlation-Id"]
        ?.replace("\n", "")
        ?.replace("\r", "")
        ?.take(200)
        ?.let { response.headers.append("X-Correlation-Id", it) }
    if (principal == null) {
        reject(HttpStatusCode.Forbidden, "unauthenticated", "missing or invalid bearer token")
        return null
    }
    return principal
}

/** The OBO bearer token from the Authorization header (PD-8), or empty. */
private fun ApplicationCall.bearer(): String =
    request.headers["Authorization"]
        ?.removePrefix("Bearer ")
        ?.trim()
        .orEmpty()

/**
 * Parse a plan/revision approval `verdict` (APPROVE → true, REJECT → false). A
 * missing/unrecognised verdict is a 400 (rather than a silent reject that would
 * discard the plan on a client typo); returns null after responding in that case.
 */
private suspend fun ApplicationCall.verdict(): Boolean? =
    when (body()["verdict"]?.jsonPrimitive?.content?.uppercase()) {
        "APPROVE" -> true
        "REJECT" -> false
        else -> {
            reject(HttpStatusCode.BadRequest, "bad_verdict", "verdict must be APPROVE or REJECT")
            null
        }
    }

private suspend fun ApplicationCall.findOrNull(investigations: InvestigationRepository) =
    parameters["id"]
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?.let { investigations.findById(it) }
        ?: run {
            reject(HttpStatusCode.NotFound, "not_found", "no such investigation")
            null
        }

private suspend fun ApplicationCall.body(): JsonObject =
    runCatching { JSON.parseToJsonElement(receiveText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))

private suspend fun ApplicationCall.respondResume(outcome: ResumeOutcome) {
    when (outcome) {
        is ResumeOutcome.Ok ->
            respond(buildJsonObject { put("status", JsonPrimitive(outcome.newStatus.name)) })
        is ResumeOutcome.Conflict ->
            respond(
                HttpStatusCode.Conflict,
                ruleSix("already_resumed", "investigation already resumed", "status" to outcome.currentStatus.name),
            )
        ResumeOutcome.NotFound -> reject(HttpStatusCode.NotFound, "not_found", "no such investigation")
    }
}

private suspend fun ApplicationCall.respondNewId(newId: java.util.UUID?) {
    if (newId == null) {
        reject(HttpStatusCode.NotFound, "not_found", "no such investigation")
    } else {
        respond(
            HttpStatusCode.Accepted,
            buildJsonObject {
                put("id", JsonPrimitive(newId.toString()))
                put("status", JsonPrimitive("STATUS_SUBMITTED"))
            },
        )
    }
}

private suspend fun ApplicationCall.reject(
    status: HttpStatusCode,
    code: String,
    message: String,
) = respond(status, ruleSix(code, message))

/** A Rule-6 shaped error body: `{ messages: [{ severity, code, humanMessage }], ... }`. */
private fun ruleSix(
    code: String,
    message: String,
    vararg extra: Pair<String, String>,
): JsonObject =
    buildJsonObject {
        extra.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        put(
            "messages",
            kotlinx.serialization.json.buildJsonArray {
                add(
                    buildJsonObject {
                        put("severity", JsonPrimitive("ERROR"))
                        put("code", JsonPrimitive(code))
                        put("humanMessage", JsonPrimitive(message))
                    },
                )
            },
        )
    }
