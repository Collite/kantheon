package org.tatrman.kantheon.capabilities

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.tatrman.kantheon.capabilities.api.capabilitiesRestRoutes
import org.tatrman.kantheon.capabilities.api.healthRoutes
import org.tatrman.kantheon.capabilities.api.installCapabilitiesMcp
import org.tatrman.kantheon.capabilities.loader.ManifestYamlLoader
import org.tatrman.kantheon.capabilities.registry.InMemoryRegistry
import org.tatrman.kantheon.capabilities.registry.RegistryQueryService
import org.tatrman.kantheon.capabilities.registry.TtlPruner
import shared.ktor.KtorConfigFactory
import shared.ktor.KtorEngine
import shared.ktor.KtorServerBootstrap
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk
import java.nio.file.Path
import java.time.Duration

private val logger = KotlinLogging.logger {}

fun main() {
    val config = ConfigFactory.load()

    createOpenTelemetrySdk(
        OtelEndpointConfig(
            serviceName = "capabilities-mcp",
            protocol = System.getenv("CAPABILITIES_OTEL_PROTOCOL") ?: "grpc",
        ),
        enabled = config.getBoolean("telemetry.enabled"),
    )

    val serverConfig =
        KtorConfigFactory.fromConfig(
            config = config,
            defaultServiceName = "capabilities-mcp",
            defaultPort = 7501,
            engine = KtorEngine.CIO,
        )

    KtorServerBootstrap
        .createServer(serverConfig) { module(serverConfig, config) }
        .start(wait = true)
}

fun Application.module(
    serverConfig: KtorServerConfig,
    config: Config = ConfigFactory.load(),
) {
    installKtorServerBase(serverConfig)

    val registry = InMemoryRegistry()
    val readiness = ReadinessGate()
    val service = RegistryQueryService(registry)
    val loader = resolveLoader(config)
    val ttl = Duration.ofSeconds(config.getLong("capabilities.ttl-seconds"))

    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    monitor.subscribe(ApplicationStarted) {
        backgroundScope.launch {
            val report = loader.loadAll(registry)
            logger.info {
                "fixtures loaded: ${report.loaded} ok; ${report.skipped.size} skipped"
            }
            report.skipped.forEach { logger.warn { "  skipped ${it.path}: ${it.reason}" } }
            readiness.ready = true
        }
    }

    val pruner = TtlPruner(registry, ttl = ttl)
    pruner.start(backgroundScope)

    monitor.subscribe(ApplicationStopped) {
        pruner.stop()
        backgroundScope.cancel()
    }

    installCapabilitiesMcp(service)
    routing {
        healthRoutes(readiness)
        capabilitiesRestRoutes(service)
    }
}

private fun resolveLoader(config: Config): ManifestYamlLoader {
    val raw = config.getString("capabilities.manifests-dir")
    return when {
        raw.startsWith(
            "classpath:",
        ) -> ManifestYamlLoader(classpathBase = "/" + raw.removePrefix("classpath:").trimStart('/'))
        else -> ManifestYamlLoader(filesystemBase = Path.of(raw))
    }
}
