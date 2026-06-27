package org.tatrman.kantheon.pythia

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tatrman.kantheon.pythia.api.controlRoutes
import org.tatrman.kantheon.pythia.api.healthRoutes
import org.tatrman.kantheon.pythia.api.sseRoutes
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorEngine
import shared.ktor.KtorServerBootstrap
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "pythia", 7090, KtorEngine.NETTY)
    KtorServerBootstrap.createServer(serverConfig) { module(config, serverConfig) }.start(wait = true)
}

fun Application.module(
    config: Config,
    serverConfig: KtorServerConfig,
) {
    installKtorServerBase(serverConfig)
    install(SSE)

    if (config.getBoolean("telemetry.enabled")) {
        createOpenTelemetrySdk(
            OtelEndpointConfig(
                serviceName = "pythia",
                protocol = System.getenv("PYTHIA_OTEL_PROTOCOL") ?: "grpc",
            ),
        )
    }

    val components = buildComponents(config, scope = this)
    installErrorPages()

    routing {
        healthRoutes(components.readiness)
        controlRoutes(components.orchestrator, components.investigations, components.assembler, components.admission)
        sseRoutes(components.investigations, components.events, components.assembler, components.admission)
        // Prometheus scrape (Stage 5.3 T4) — the architecture §8 metric set.
        get("/metrics") { call.respondText(components.meterRegistry.scrape()) }
    }

    // Periodic AWAITING_* TTL sweep (clock-driven impl is unit-tested; this is the live ticker).
    monitor.subscribe(ApplicationStarted) {
        launch {
            val intervalMs = 60 * 60 * 1000L // hourly
            while (isActive) {
                runCatching { components.sweeper.sweep() }
                delay(intervalMs)
            }
        }
    }

    monitor.subscribe(ApplicationStopping) {
        components.onStop()
    }
}
