package org.tatrman.kantheon.capabilities.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.capabilities.ReadinessGate

fun Route.healthRoutes(readiness: ReadinessGate) {
    get("/health") {
        call.respond(
            HttpStatusCode.OK,
            buildJsonObject { put("status", JsonPrimitive("ok")) },
        )
    }
    get("/ready") {
        if (readiness.ready) {
            call.respond(
                HttpStatusCode.OK,
                buildJsonObject { put("status", JsonPrimitive("ready")) },
            )
        } else {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                buildJsonObject { put("status", JsonPrimitive("not-ready")) },
            )
        }
    }
}
