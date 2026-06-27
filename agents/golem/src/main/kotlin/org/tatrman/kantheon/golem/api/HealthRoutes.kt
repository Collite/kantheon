package org.tatrman.kantheon.golem.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Readiness signal — gated on boot prerequisites (migrations now; Shem +
 *  PackageContext + PromptStore added in Stage 2.2). */
fun interface Readiness {
    fun isReady(): Boolean
}

/**
 * `/health` (liveness) + `/ready` (readiness). Responses are built with
 * `buildJsonObject` + JsonPrimitive (not `mapOf`, which trips kotlinx type
 * erasure in `respond`).
 */
fun Route.healthRoutes(readiness: Readiness) {
    get("/health") {
        call.respond(buildJsonObject { put("status", JsonPrimitive("UP")) })
    }
    get("/ready") {
        if (readiness.isReady()) {
            call.respond(buildJsonObject { put("status", JsonPrimitive("UP")) })
        } else {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                buildJsonObject { put("status", JsonPrimitive("NOT_READY")) },
            )
        }
    }
}
