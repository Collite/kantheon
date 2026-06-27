package org.tatrman.kallimachos.mcp

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.capabilities.client.CapabilitiesClient
import shared.ktor.mcp.McpKtorConfig
import shared.ktor.mcp.McpTelemetry
import shared.ktor.mcp.installMcpKtorBase
import shared.ktor.mcp.safeMcpTool

private val log = LoggerFactory.getLogger("kallimachos-mcp")

/**
 * Thin MCP wrapper over the Kallimachos read/RAG/browse surface (architecture §4).
 * Streamable-HTTP at `POST /mcp`; every `library.*` tool forwards to a Kallimachos
 * HTTP endpoint carrying the caller bearer. Zero business logic. Registers the
 * `library.*:v1` `ToolCapability` manifests with capabilities-mcp (heartbeat).
 */
fun main(): Unit =
    runBlocking {
        val config = ConfigFactory.load()
        val port = config.getInt("kallimachos-mcp.port")
        val telemetry = McpTelemetry("kallimachos-mcp", "http")

        val kallimachosBase =
            "http://${config.getString("kallimachos-mcp.kallimachos-http.host")}:" +
                config.getInt("kallimachos-mcp.kallimachos-http.port")
        val http = HttpClient(CIO)
        val forwarder = LibraryForwarder(http, kallimachosBase)
        val meters =
            io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT,
            )
        val tools =
            McpTools(
                forwarder,
                org.tatrman.kallimachos.mcp.rls
                    .MartRlsGuard(forwarder, meters),
            )

        registerWithCapabilities(config)

        val mcpConfig = McpKtorConfig(serviceName = "kallimachos-mcp", serverPort = port)
        val appConfig =
            serverConfig {
                module {
                    installMcpKtorBase(mcpConfig, telemetry.openTelemetrySdk)
                    mcpStreamableHttp {
                        val server =
                            Server(
                                serverInfo = Implementation(name = "kallimachos-mcp", version = "0.1.0"),
                                options =
                                    ServerOptions(
                                        capabilities =
                                            ServerCapabilities(
                                                tools = ServerCapabilities.Tools(listChanged = false),
                                            ),
                                    ),
                            )
                        tools.all.forEach { tool ->
                            server.addTool(
                                name = tool.name,
                                description = tool.description ?: "",
                                inputSchema = tool.inputSchema,
                            ) { request ->
                                safeMcpTool(tool.name, 60_000) { tools.dispatch(tool.name, it) }(request)
                            }
                        }
                        server
                    }
                }
            }

        log.info("kallimachos-mcp listening on :{} (library.* → {})", port, kallimachosBase)
        embeddedServer(
            factory = ServerCIO,
            rootConfig = appConfig,
            configure = {
                connectors.add(
                    EngineConnectorBuilder().apply {
                        this.port = port
                        this.host = "0.0.0.0"
                    },
                )
            },
        ).start(wait = true)
    }

/**
 * Register the `library.*:v1` manifests with capabilities-mcp (warn-and-continue;
 * background heartbeat). Set `CAPABILITIES_MCP_URL` to enable.
 */
internal fun registerWithCapabilities(config: Config) {
    val endpoint =
        System.getenv("CAPABILITIES_MCP_URL")
            ?: if (config.hasPath("capabilities-mcp.url")) config.getString("capabilities-mcp.url") else ""
    val capabilities = ManifestLoader().loadAll()
    if (endpoint.isBlank() || capabilities.isEmpty()) {
        log.info(
            "capabilities-mcp registration skipped (endpoint blank or no manifests); {} library.* manifests loaded",
            capabilities.size,
        )
        return
    }
    capabilities.forEach { cap ->
        CapabilitiesClient.startupRegister(capability = cap, endpoint = endpoint, heartbeatIntervalMs = 30_000)
        log.info("kallimachos-mcp registered '{}'", cap.tool.capabilityId)
    }
}
