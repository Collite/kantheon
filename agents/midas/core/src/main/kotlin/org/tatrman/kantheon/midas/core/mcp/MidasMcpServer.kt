package org.tatrman.kantheon.midas.core.mcp

import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.slf4j.LoggerFactory
import shared.ktor.mcp.McpKtorConfig
import shared.ktor.mcp.McpTelemetry
import shared.ktor.mcp.installMcpKtorBase
import shared.ktor.mcp.safeMcpTool

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.midas.core.mcp.MidasMcpServer")

private const val TOOL_TIMEOUT_MS = 30_000L

/**
 * Builds the Midas-core MCP [Server] and registers the five tools (Stage 1.4). The
 * per-tool handler is wrapped by [safeMcpTool] (timeout + structured error envelope).
 * Mirrors the thin `-mcp` wrappers' wiring (ariadne-mcp), but embedded in midas-core: the
 * server runs as a second CIO listener so the REST (Netty) error-envelope stack and
 * the MCP base stack stay independent.
 */
fun buildMidasMcpServer(tools: MidasTools): Server {
    val server =
        Server(
            serverInfo = Implementation(name = "midas-core", version = "0.1.0"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
                ),
        )

    server.addTool(
        name = tools.positionValuationTool.name,
        description = tools.positionValuationTool.description ?: "",
        inputSchema = tools.positionValuationTool.inputSchema,
    ) { request ->
        safeMcpTool(tools.positionValuationTool.name, TOOL_TIMEOUT_MS) {
            tools.positionValuationCallback(it)
        }(request)
    }

    server.addTool(
        name = tools.portfolioPerformanceTool.name,
        description = tools.portfolioPerformanceTool.description ?: "",
        inputSchema = tools.portfolioPerformanceTool.inputSchema,
    ) { request ->
        safeMcpTool(tools.portfolioPerformanceTool.name, TOOL_TIMEOUT_MS) {
            tools.portfolioPerformanceCallback(it)
        }(request)
    }

    server.addTool(
        name = tools.costBasisTool.name,
        description = tools.costBasisTool.description ?: "",
        inputSchema = tools.costBasisTool.inputSchema,
    ) { request ->
        safeMcpTool(tools.costBasisTool.name, TOOL_TIMEOUT_MS) {
            tools.costBasisCallback(it)
        }(request)
    }

    server.addTool(
        name = tools.feeAllocationTool.name,
        description = tools.feeAllocationTool.description ?: "",
        inputSchema = tools.feeAllocationTool.inputSchema,
    ) { request ->
        safeMcpTool(tools.feeAllocationTool.name, TOOL_TIMEOUT_MS) {
            tools.feeAllocationCallback(it)
        }(request)
    }

    server.addTool(
        name = tools.reconcileStatementTool.name,
        description = tools.reconcileStatementTool.description ?: "",
        inputSchema = tools.reconcileStatementTool.inputSchema,
    ) { request ->
        safeMcpTool(tools.reconcileStatementTool.name, TOOL_TIMEOUT_MS) {
            tools.reconcileStatementCallback(it)
        }(request)
    }

    return server
}

/**
 * Starts the MCP listener (CIO) on [port], serving `/mcp` + the base probes, and
 * returns the (already-started, non-blocking) engine so the caller can keep it for
 * shutdown. The REST server remains the process's blocking foreground listener.
 */
fun startMidasMcpServer(
    port: Int,
    tools: MidasTools,
    telemetry: McpTelemetry,
): EmbeddedServer<*, *> {
    val mcpConfig =
        McpKtorConfig(
            serviceName = "midas-core",
            serverPort = port,
            shutdownUrlPath = "/shutdown",
            connectionIdleTimeoutSeconds = 120,
        )

    val appConfig =
        serverConfig {
            module {
                installMcpKtorBase(mcpConfig, telemetry.openTelemetrySdk)
                mcpStreamableHttp { buildMidasMcpServer(tools) }
            }
        }

    log.info("midas-core MCP surface starting on :{} (/mcp)", port)
    return embeddedServer(
        factory = CIO,
        rootConfig = appConfig,
        configure = {
            connectionIdleTimeoutSeconds = 120
            connectors.add(
                EngineConnectorBuilder().apply {
                    this.port = port
                    this.host = "0.0.0.0"
                },
            )
        },
    ).start(wait = false)
}
