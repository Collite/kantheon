package org.tatrman.kantheon.golem.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.golem.context.GolemModelSubsystem

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.api.RefreshRoutes")

/**
 * `POST /v1/refresh` (contracts §2) — re-pull PackageContext **and** prompts from
 * Veles. Ops/cluster-internal: deliberately **not** behind [ShemAdmission] (it's
 * an operational reload, not a domain turn). Responds 200 on success, 503 when the
 * reload fails (e.g. Veles unreachable) so callers can retry.
 */
fun Route.refreshRoutes(model: GolemModelSubsystem) {
    post("/v1/refresh") {
        try {
            model.refresh()
            call.respondText(
                buildJsonObject { put("status", JsonPrimitive("refreshed")) }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        } catch (e: Exception) {
            log.warn("/v1/refresh failed: {}", e.message)
            call.respondText(
                buildJsonObject {
                    put("status", JsonPrimitive("refresh_failed"))
                    put("message", JsonPrimitive(e.message ?: "reload failed"))
                }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
        }
    }
}
