package org.tatrman.kantheon.iris

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.tatrman.kantheon.iris.api.actionRoutes
import org.tatrman.kantheon.iris.api.artifactRoutes
import org.tatrman.kantheon.iris.api.auditRoutes
import org.tatrman.kantheon.iris.api.chatRoutes
import org.tatrman.kantheon.iris.api.discoverRoutes
import org.tatrman.kantheon.iris.api.feedbackRoutes
import org.tatrman.kantheon.iris.api.inboxRoutes
import org.tatrman.kantheon.iris.api.healthRoutes
import org.tatrman.kantheon.iris.api.sessionRoutes
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorEngine
import shared.ktor.KtorServerBootstrap
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "iris-bff", 7410, KtorEngine.NETTY)
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
                serviceName = "iris-bff",
                protocol = System.getenv("IRIS_BFF_OTEL_PROTOCOL") ?: "grpc",
            ),
        )
    }

    val components = buildComponents(config)
    installErrorPages()

    routing {
        // Prometheus scrape (Stage 3.3 observability).
        get("/metrics") {
            call.respondText(
                components.meterScrape(),
                ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
            )
        }
        healthRoutes(components.readiness)
        sessionRoutes(components.store, components.auth, components.golemClient, components.staticChips)
        chatRoutes(components.store, components.auth, components.dispatcher, components.heartbeatMs)
        actionRoutes(
            components.store,
            components.auth,
            components.dispatcher,
            components.typedActions,
            components.reask,
            components.escalation,
            components.heartbeatMs,
        )
        artifactRoutes(
            components.store,
            components.artifacts,
            components.artifactService,
            components.auth,
            components.heartbeatMs,
        )
        discoverRoutes(components.capabilities, components.auth)
        feedbackRoutes(components.store, components.feedback, components.auth, components.metrics)
        auditRoutes(components.audit, components.signer, components.auth)
        inboxRoutes(components.inboxService, components.lifecycleHub, components.auth, components.heartbeatMs)
    }

    // Inbox lifecycle polling fallback (PD-2) — the active producer until the
    // Pythia-arc NATS subscriber connects. Cancelled on shutdown.
    val pollerJob = components.lifecyclePoller.start(this)

    monitor.subscribe(ApplicationStopping) {
        pollerJob.cancel()
        components.onStop()
    }
}
