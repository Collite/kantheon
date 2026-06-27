package org.tatrman.kantheon.charon.mcp

import com.typesafe.config.ConfigFactory
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.tatrman.kantheon.capabilities.client.CapabilitiesClient
import org.tatrman.kantheon.capabilities.v1.Capability
import org.tatrman.kantheon.charon.mcp.client.CharonGrpcClient
import org.tatrman.kantheon.charon.mcp.client.GrpcCharonGrpcClient
import shared.ktor.mcp.McpKtorConfig
import shared.ktor.mcp.McpTelemetry
import shared.ktor.mcp.installMcpKtorBase
import shared.ktor.mcp.safeMcpTool

val logger = LoggerFactory.getLogger("charon-mcp")

/** The move-tool MCP timeout. Kept strictly larger than the gRPC client's 120s
 *  deadline ([GrpcCharonGrpcClient.deadlineSeconds]) so a slow move trips the
 *  gRPC DEADLINE_EXCEEDED — with its typed status + Rule-6 trailer mapping —
 *  rather than the coarser MCP TIMEOUT. */
const val MOVE_TOOL_TIMEOUT_MS: Long = 125_000

fun main(args: Array<String>): Unit =
    runBlocking {
        logger.info("Starting charon-mcp")

        val config = ConfigFactory.load()
        val telemetry = McpTelemetry("charon-mcp", "grpc")
        val serverPort = config.getString("server.port").toInt()

        val grpcClient: CharonGrpcClient? = buildGrpcClient(config)
        grpcClient?.let { client ->
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    runCatching { client.close() }
                        .onFailure { logger.warn("charon gRPC client shutdown failed: {}", it.message) }
                },
            )
        }
        val tools = Tools(grpcClient)

        registerWithCapabilities(config)

        val mcpConfig =
            McpKtorConfig(
                serviceName = "charon-mcp",
                serverPort = serverPort,
                shutdownUrlPath = "/shutdown",
                connectionIdleTimeoutSeconds = 120,
            )

        val appConfig =
            serverConfig {
                module {
                    installMcpKtorBase(mcpConfig, telemetry.openTelemetrySdk)

                    install(CallLogging) {
                        level = Level.INFO
                        format { call ->
                            val status = call.response.status()?.value ?: "Unhandled"
                            val method = call.request.httpMethod.value
                            val path = call.request.path()
                            val origin = call.request.headers[HttpHeaders.Origin] ?: "No-Origin"
                            "-> [$status] $method $path | Origin: $origin"
                        }
                    }

                    mcpStreamableHttp {
                        val server =
                            Server(
                                serverInfo = Implementation(name = "charon-mcp", version = "0.1.0"),
                                options =
                                    ServerOptions(
                                        capabilities =
                                            ServerCapabilities(
                                                tools = ServerCapabilities.Tools(listChanged = false),
                                            ),
                                    ),
                            )

                        server.addTool(
                            name = tools.materializeTool.name,
                            description = tools.materializeTool.description ?: "",
                            inputSchema = tools.materializeTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.materializeTool.name, MOVE_TOOL_TIMEOUT_MS) {
                                tools.materializeCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.stageTool.name,
                            description = tools.stageTool.description ?: "",
                            inputSchema = tools.stageTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.stageTool.name, MOVE_TOOL_TIMEOUT_MS) { tools.stageCallback(it) }(request)
                        }

                        server.addTool(
                            name = tools.copyTool.name,
                            description = tools.copyTool.description ?: "",
                            inputSchema = tools.copyTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.copyTool.name, MOVE_TOOL_TIMEOUT_MS) { tools.copyCallback(it) }(request)
                        }

                        server.addTool(
                            name = tools.evictTool.name,
                            description = tools.evictTool.description ?: "",
                            inputSchema = tools.evictTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.evictTool.name, 10_000) { tools.evictCallback(it) }(request)
                        }

                        server.addTool(
                            name = tools.describeTool.name,
                            description = tools.describeTool.description ?: "",
                            inputSchema = tools.describeTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.describeTool.name, 30_000) { tools.describeCallback(it) }(request)
                        }

                        server
                    }
                }
            }

        println("charon-mcp server running and listening on port $serverPort")

        embeddedServer(
            factory = CIO,
            rootConfig = appConfig,
            configure = {
                connectionIdleTimeoutSeconds = 120
                connectors.add(
                    EngineConnectorBuilder().apply {
                        this.port = serverPort
                        this.host = "0.0.0.0"
                    },
                )
            },
        ).start(wait = true)
    }

internal fun buildGrpcClient(config: com.typesafe.config.Config): CharonGrpcClient? {
    val host = if (config.hasPath("charon.host")) config.getString("charon.host") else ""
    val port = if (config.hasPath("charon.port")) config.getString("charon.port").toInt() else 7251
    return if (host.isNotBlank()) {
        logger.info("Charon gRPC client at {}:{}", host, port)
        GrpcCharonGrpcClient(host = host, port = port)
    } else {
        logger.warn("charon.host is blank — all move tools will report not-wired errors.")
        null
    }
}

private fun charonMcpCapabilities(): List<Capability> = ManifestLoader().loadAll()

internal fun registerWithCapabilities(config: com.typesafe.config.Config) {
    val endpoint =
        System.getenv("CAPABILITIES_MCP_URL")
            ?: if (config.hasPath("capabilities-mcp.url")) config.getString("capabilities-mcp.url") else ""
    val capabilities = charonMcpCapabilities()
    if (capabilities.isEmpty()) {
        logger.info("No charon-mcp capabilities to register (manifests dir empty or missing).")
        return
    }
    if (endpoint.isBlank()) {
        logger.info(
            "CAPABILITIES_MCP_URL not set — {} charon-mcp capabilities are not registered with capabilities-mcp.",
            capabilities.size,
        )
        return
    }
    var registered = 0
    for (cap in capabilities) {
        val id = cap.tool.capabilityId
        val handle =
            CapabilitiesClient.startupRegister(
                capability = cap,
                endpoint = endpoint,
                heartbeatIntervalMs = 30_000,
            )
        if (handle.registrationId != null) {
            registered++
            logger.info("charon-mcp registered '{}' with capabilities-mcp at {}", id, endpoint)
        } else {
            logger.warn(
                "charon-mcp startup register for '{}' at {} not yet complete; background retry continues.",
                id,
                endpoint,
            )
        }
    }
    logger.info("charon-mcp: {}/{} capabilities registered", registered, capabilities.size)
}
