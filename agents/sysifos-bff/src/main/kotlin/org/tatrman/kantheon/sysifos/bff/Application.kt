package org.tatrman.kantheon.sysifos.bff

import com.typesafe.config.ConfigFactory
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.bffbase.auth.ErrorBody
import org.tatrman.kantheon.bffbase.health.healthRoutes
import org.tatrman.kantheon.sysifos.bff.api.crudProxyRoutes
import org.tatrman.kantheon.sysifos.bff.api.dictionaryRoutes
import org.tatrman.kantheon.sysifos.bff.api.draftRoutes
import org.tatrman.kantheon.sysifos.bff.api.loaderProxyRoutes
import org.tatrman.kantheon.sysifos.bff.api.screenRoutes
import org.tatrman.kantheon.sysifos.bff.api.sessionRoutes
import org.tatrman.kantheon.sysifos.bff.stream.streamRoute
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.sysifos.bff.Application")

/** Midas-core reachability poll cadence for the `/ready` gate. */
private val REACHABILITY_POLL = 3.seconds

fun main() {
    val config = ConfigFactory.load()
    val deps = buildDeps(config)
    val httpPort = config.getInt("sysifos-bff.http.port")
    log.info("sysifos-bff starting on :{}", httpPort)
    embeddedServer(Netty, port = httpPort) { module(deps) }.start(wait = true)
}

/**
 * Sysifos-BFF wiring (Stage 1.2). Auth + tenant forwarding live in `bff-base`;
 * this module installs JSON + dev CORS + a uniform error envelope and mounts the
 * session, dictionary, stream, and health routes. A reachability poller refreshes
 * `/ready` without blocking the probe on a downstream call.
 */
fun Application.module(deps: SysifosDeps) {
    install(ContentNegotiation) { json() }
    install(CORS) {
        anyHost() // dev/local; tightened per-environment at the Ingress
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Patch, HttpMethod.Delete).forEach { allowMethod(it) }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("sysifos-bff: unhandled error on {}", call.request.local.uri, cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("INTERNAL", cause.message ?: "Internal error"))
        }
    }

    routing {
        healthRoutes(deps.readiness)
        sessionRoutes(deps.sessions, deps.auth)
        dictionaryRoutes(deps.dictionaries)
        screenRoutes(deps.midas, deps.loader, deps.auth)
        crudProxyRoutes(deps.midas, deps.auth)
        loaderProxyRoutes(deps.loader, deps.auth)
        draftRoutes(deps.auth, deps.sessions, deps.draftScratch, deps.stateMachine, deps.bus)
        streamRoute(deps.auth, deps.sessions, deps.bus, deps.heartbeatMs)
    }

    deps.reachabilityPoll?.let { poll ->
        launch {
            while (isActive) {
                runCatching { poll() }
                kotlinx.coroutines.delay(REACHABILITY_POLL)
            }
        }
    }
    monitor.subscribe(ApplicationStopping) { deps.onStop() }
}
