package org.tatrman.kantheon.golem.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.golem.resume.ResumeTokenException
import org.tatrman.kantheon.golem.v1.GolemRequest

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.api.AnswerRoutes")

/**
 * `POST /v1/answer/sync` (contracts §2) — `GolemRequest` JSON → `ConversationalResponse`
 * JSON; and `POST /v1/answer` (contracts §3) — the SSE surface streaming the fixed event
 * set (`node_start`/`node_done`/`plan_pick`/`exec_done`/`envelope`/`error`). Both admit
 * the caller (PD-8) then run the turn through [AnswerService]. HTTP-level tests deferred to
 * GH #32.
 */
fun Route.answerRoutes(
    admission: ShemAdmission,
    service: AnswerService,
) {
    post("/v1/answer/sync") {
        val caller = call.admitOrRespond(admission) ?: return@post
        val request = call.parseGolemRequest() ?: return@post
        val response =
            try {
                service.answer(request, caller)
            } catch (e: Exception) {
                // The graph degrades compose/execute failures into clarification /
                // STATUS_FAILED turns; anything thrown here is unexpected (e.g. a
                // persistence fault). Fail with a Rule-6 envelope, not a raw 500.
                log.error("answer failed for turn {}: {}", request.id, e.message, e)
                call.respondText(
                    ruleSixDenial("internal_error", "turn could not be answered: ${e.message}").toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
                return@post
            }
        call.respondText(ProtoJson.print(response), ContentType.Application.Json, HttpStatusCode.OK)
    }

    post("/v1/answer") {
        val caller = call.admitOrRespond(admission) ?: return@post
        val request = call.parseGolemRequest() ?: return@post
        // The stream carries even failures as terminal `error`/`envelope` frames (the graph
        // degrades internally), so the SSE body owns error reporting from here on.
        call.streamAnswer { service.answer(request, caller) }
    }

    // POST /v1/resume — resume a clarification (contracts §3). Body:
    // {resume_token, free_text_answer?, selected_option_id?}.
    post("/v1/resume") {
        val caller = call.admitOrRespond(admission) ?: return@post
        val body =
            runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                ?: run {
                    call.respondText(
                        ruleSixDenial("bad_request", "resume body must be a JSON object").toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }
        val token = body["resume_token"]?.jsonPrimitive?.contentOrNull
        if (token.isNullOrBlank()) {
            call.respondText(
                ruleSixDenial("bad_request", "resume_token is required").toString(),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@post
        }
        val response =
            try {
                service.resume(
                    token = token,
                    caller = caller,
                    freeTextAnswer = body["free_text_answer"]?.jsonPrimitive?.contentOrNull,
                    selectedOptionId = body["selected_option_id"]?.jsonPrimitive?.contentOrNull,
                )
            } catch (e: ResumeTokenException) {
                call.respondText(
                    ruleSixDenial("invalid_resume_token", e.message ?: "resume token rejected").toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            } catch (e: Exception) {
                log.error("resume failed: {}", e.message, e)
                call.respondText(
                    ruleSixDenial("internal_error", "resume could not be processed: ${e.message}").toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
                return@post
            }
        call.respondText(ProtoJson.print(response), ContentType.Application.Json, HttpStatusCode.OK)
    }
}

// Resume inputs are strings on the wire (resume_token / free_text_answer / selected_option_id);
// a non-string JSON value is a malformed body, not a coercible value — reject it as absent.
private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else null

private suspend fun io.ktor.server.application.ApplicationCall.parseGolemRequest(): GolemRequest? =
    try {
        ProtoJson.parseInto(receiveText(), GolemRequest.newBuilder()).build()
    } catch (e: Exception) {
        log.info("malformed GolemRequest: {}", e.message)
        respondText(
            ruleSixDenial("bad_request", "GolemRequest could not be parsed: ${e.message}").toString(),
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        null
    }
