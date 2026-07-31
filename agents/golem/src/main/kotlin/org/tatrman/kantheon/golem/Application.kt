package org.tatrman.kantheon.golem

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.golem.api.PING_INTERVAL_MS
import org.tatrman.kantheon.golem.api.answerRoutes
import org.tatrman.kantheon.golem.api.healthRoutes
import org.tatrman.kantheon.golem.api.refreshRoutes
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorEngine
import shared.ktor.KtorServerBootstrap
import shared.ktor.KtorServerConfig
import io.opentelemetry.api.OpenTelemetry
import org.tatrman.kantheon.golem.otel.createGolemOtel
import org.tatrman.kantheon.golem.otel.installGolemServerTelemetry
import shared.ktor.installKtorServerBase

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.Application")

fun main() {
    val config = ConfigFactory.load()
    val serverConfig = KtorConfigFactory.fromConfig(config, "golem", 7420, KtorEngine.NETTY)
    // One SDK per process, threaded into the server plugin and the outbound tool
    // clients, so the agent hop joins the turn's trace (GI-7 / PT Phase 0).
    val otel = createGolemOtel(config)
    KtorServerBootstrap.createServer(serverConfig) { module(config, serverConfig, otel) }.start(wait = true)
}

fun Application.module(
    config: Config,
    serverConfig: KtorServerConfig,
    otel: OpenTelemetry? = null,
) {
    // Before the base install, so the SERVER span wraps the rest of the pipeline —
    // and so an incoming `traceparent` from the BFF is continued, not dropped.
    installGolemServerTelemetry(otel)
    installKtorServerBase(serverConfig)

    // Both halves of the SSE invariant on one line (contracts §6). The 2026-07-29 outage was
    // invisible precisely because neither number was ever logged: the engine timeout was an
    // inherited library default no config file mentioned. Golem was never exposed — `SseAnswer`
    // has always written its preamble first — but "we were fine" is not something you should
    // have to take on faith from a pod that prints nothing.
    log.info(
        "golem stream: ping={}ms, engine response-write-timeout={}s",
        PING_INTERVAL_MS,
        serverConfig.responseWriteTimeoutSeconds,
    )

    val components = buildComponents(config, otel)
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
