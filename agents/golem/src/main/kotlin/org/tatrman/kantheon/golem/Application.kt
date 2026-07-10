package org.tatrman.kantheon.golem

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import org.tatrman.kantheon.golem.api.answerRoutes
import org.tatrman.kantheon.golem.api.healthRoutes
import org.tatrman.kantheon.golem.api.refreshRoutes
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorEngine
import shared.ktor.KtorServerBootstrap
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "golem", 7420, KtorEngine.NETTY)
    KtorServerBootstrap.createServer(serverConfig) { module(config, serverConfig) }.start(wait = true)
}

fun Application.module(
    config: Config,
    serverConfig: KtorServerConfig,
) {
    installKtorServerBase(serverConfig)

    if (config.getBoolean("telemetry.enabled")) {
        createOpenTelemetrySdk(
            OtelEndpointConfig(
                serviceName = "golem",
                protocol = System.getenv("GOLEM_OTEL_PROTOCOL") ?: "grpc",
            ),
        )
    }

    val components = buildComponents(config)
    installErrorPages()

    routing {
        healthRoutes(components.readiness)
        refreshRoutes(components.model)
        // The answer surface exists only when a Shem is configured (not in skeleton boot).
        val admission = components.admission
        val answer = components.answer
        if (admission != null && answer != null) {
            answerRoutes(admission, answer)
        }
    }

    // Load the model + prompts and register the Shem after the server is up, so a
    // slow/unreachable Veles never blocks the bind; /ready flips once loaded.
    monitor.subscribe(ApplicationStarted) {
        launch { components.bootLoad() }
    }
    monitor.subscribe(ApplicationStopping) {
        components.registration?.shutdown()
        components.onStop()
    }
}
