package org.tatrman.kantheon.metis.mcp

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
import org.tatrman.kantheon.metis.mcp.client.GrpcMetisGrpcClient
import org.tatrman.kantheon.metis.mcp.client.MetisGrpcClient
import shared.ktor.mcp.McpKtorConfig
import shared.ktor.mcp.McpTelemetry
import shared.ktor.mcp.installMcpKtorBase
import shared.ktor.mcp.safeMcpTool

val logger = LoggerFactory.getLogger("metis-mcp")

fun main(args: Array<String>): Unit =
    runBlocking {
        logger.info("Starting metis-mcp")

        val config = ConfigFactory.load()
        val telemetry = McpTelemetry("metis-mcp", "grpc")
        val serverPort = config.getString("server.port").toInt()

        val grpcClient: MetisGrpcClient? = buildGrpcClient(config)
        val tools = Tools(grpcClient)

        registerWithCapabilities(config)

        val mcpConfig =
            McpKtorConfig(
                serviceName = "metis-mcp",
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
                                serverInfo = Implementation(name = "metis-mcp", version = "0.1.0"),
                                options =
                                    ServerOptions(
                                        capabilities =
                                            ServerCapabilities(
                                                tools = ServerCapabilities.Tools(listChanged = false),
                                            ),
                                    ),
                            )

                        server.addTool(
                            name = tools.modelFitTool.name,
                            description = tools.modelFitTool.description ?: "",
                            inputSchema = tools.modelFitTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.modelFitTool.name, 60_000) {
                                tools.modelFitCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.modelDiagnoseTool.name,
                            description = tools.modelDiagnoseTool.description ?: "",
                            inputSchema = tools.modelDiagnoseTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.modelDiagnoseTool.name, 30_000) {
                                tools.modelDiagnoseCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.modelProjectTool.name,
                            description = tools.modelProjectTool.description ?: "",
                            inputSchema = tools.modelProjectTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.modelProjectTool.name, 60_000) {
                                tools.modelProjectCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.modelSimulateTool.name,
                            description = tools.modelSimulateTool.description ?: "",
                            inputSchema = tools.modelSimulateTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.modelSimulateTool.name, 30_000) {
                                tools.modelSimulateCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.dataImportTool.name,
                            description = tools.dataImportTool.description ?: "",
                            inputSchema = tools.dataImportTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.dataImportTool.name, 60_000) {
                                tools.dataImportCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.dataExportTool.name,
                            description = tools.dataExportTool.description ?: "",
                            inputSchema = tools.dataExportTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.dataExportTool.name, 60_000) {
                                tools.dataExportCallback(it)
                            }(request)
                        }

                        server.addTool(
                            name = tools.dataDropTool.name,
                            description = tools.dataDropTool.description ?: "",
                            inputSchema = tools.dataDropTool.inputSchema,
                        ) { request ->
                            safeMcpTool(tools.dataDropTool.name, 10_000) {
                                tools.dataDropCallback(it)
                            }(request)
                        }

                        server
                    }
                }
            }

        println("metis-mcp server running and listening on port $serverPort")

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

internal fun buildGrpcClient(config: com.typesafe.config.Config): MetisGrpcClient? {
    val host = if (config.hasPath("metis.host")) config.getString("metis.host") else ""
    val port = if (config.hasPath("metis.port")) config.getString("metis.port").toInt() else 7261
    return if (host.isNotBlank()) {
        logger.info("Metis gRPC client at {}:{}", host, port)
        GrpcMetisGrpcClient(host = host, port = port)
    } else {
        logger.warn("metis.host is blank — all metis tools will report not-wired errors.")
        null
    }
}

private fun metisMcpCapabilities(): List<Capability> {
    val loader = ManifestLoader()
    return loader.loadAll()
}

internal fun registerWithCapabilities(config: com.typesafe.config.Config) {
    val endpoint =
        System.getenv("CAPABILITIES_MCP_URL")
            ?: if (config.hasPath("capabilities-mcp.url")) config.getString("capabilities-mcp.url") else ""
    val capabilities = metisMcpCapabilities()
    if (capabilities.isEmpty()) {
        logger.info(
            "No metis-mcp capabilities to register (manifests dir empty or missing).",
        )
        return
    }
    if (endpoint.isBlank()) {
        logger.info(
            "CAPABILITIES_MCP_URL not set — {} metis-mcp capabilities are not registered " +
                "with capabilities-mcp.",
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
            logger.info("metis-mcp registered '{}' with capabilities-mcp at {}", id, endpoint)
        } else {
            logger.warn(
                "metis-mcp startup register for '{}' at {} not yet complete; background retry will continue.",
                id,
                endpoint,
            )
        }
    }
    logger.info("metis-mcp: {}/{} capabilities registered", registered, capabilities.size)
}
