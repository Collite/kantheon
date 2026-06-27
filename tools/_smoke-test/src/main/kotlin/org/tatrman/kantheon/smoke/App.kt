// TODO: delete in Phase 1 close — exercise module only.
package org.tatrman.kantheon.smoke

import com.typesafe.config.ConfigFactory
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main() {
    val cfg = ConfigFactory.load()
    val port = cfg.getInt("ktor.deployment.port")
    embeddedServer(CIO, port = port, module = Application::smokeModule).start(wait = true)
}

fun Application.smokeModule() {
    install(ContentNegotiation) { json() }
    routing { healthRoutes() }
}

fun Route.healthRoutes() {
    get("/health") {
        call.respond(
            HttpStatusCode.OK,
            buildJsonObject { put("status", JsonPrimitive("ok")) },
        )
    }
}
