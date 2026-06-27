package org.tatrman.kantheon.hebe.mcp

import org.tatrman.kantheon.hebe.config.McpServerConfig
import org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher
import org.tatrman.kantheon.hebe.tools.dispatch.ToolRegistry
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.tatrman.kantheon.hebe.mcp.KtorTransports")

fun Application.installMcpHttpTransport(
    server: Server,
    config: McpServerConfig,
    registry: ToolRegistry,
    dispatcher: ToolDispatcher,
    sessionId: String,
) {
    if (config.httpBind.isBlank() && config.httpPort == 0) {
        logger.info("MCP HTTP transport disabled (http_bind/http_port not set)")
        return
    }

    server.registerToolsFromRegistry(registry, config, dispatcher, sessionId)

    routing {
        mcpStreamableHttp(path = "/mcp") {
            server
        }
    }

    logger.info("MCP HTTP/SSE/WS routes mounted at /mcp")
}
