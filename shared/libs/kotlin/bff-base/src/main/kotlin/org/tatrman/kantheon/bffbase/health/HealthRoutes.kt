package org.tatrman.kantheon.bffbase.health

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Readiness signal — true once the BFF can serve real traffic. */
fun interface Readiness {
    fun isReady(): Boolean
}

/**
 * `/health` (liveness — always UP once serving) + `/ready` (readiness, gated on
 * [readiness]). Emits a literal JSON string via `respondText` so the routes work
 * without the consumer having installed ContentNegotiation — a base lib must not
 * assume a serializer is wired.
 */
fun Route.healthRoutes(readiness: Readiness) {
    get("/health") {
        call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
    }
    get("/ready") {
        if (readiness.isReady()) {
            call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
        } else {
            call.respondText(
                """{"status":"NOT_READY"}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
        }
    }
}
