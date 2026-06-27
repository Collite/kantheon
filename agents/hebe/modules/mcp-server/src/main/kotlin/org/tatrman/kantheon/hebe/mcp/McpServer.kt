package org.tatrman.kantheon.hebe.mcp

import org.tatrman.kantheon.hebe.config.HebeConfig
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import org.tatrman.kantheon.hebe.tools.builtin.file.FileSystemAppendTool
import org.tatrman.kantheon.hebe.tools.builtin.file.FileSystemGlobTool
import org.tatrman.kantheon.hebe.tools.builtin.file.FileSystemListTool
import org.tatrman.kantheon.hebe.tools.builtin.file.FileSystemReadTool
import org.tatrman.kantheon.hebe.tools.builtin.file.FileSystemWriteTool
import org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher
import org.tatrman.kantheon.hebe.tools.dispatch.ToolRegistry
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.nio.file.Path
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.tatrman.kantheon.hebe.mcp.McpServer")

fun registerMcpBuiltinTools(
    registry: ToolRegistry,
    workspaceRoot: Path,
) {
    val fs = WorkspaceFs(workspaceRoot)
    registry.register(FileSystemReadTool(fs))
    registry.register(FileSystemWriteTool(fs))
    registry.register(FileSystemListTool(fs))
    registry.register(FileSystemGlobTool(fs))
    registry.register(FileSystemAppendTool(fs))
    logger.info("Registered builtin filesystem tools")
}

suspend fun runMcpStdioServer(
    config: HebeConfig,
    registry: ToolRegistry,
    dispatcher: ToolDispatcher,
) {
    logger.info("Starting Hebe MCP Server (stdio mode)")

    val server = createHebeMcpServer()
    val sessionId = "stdio-${System.currentTimeMillis()}"
    server.registerToolsFromRegistry(registry, config.mcp.server, dispatcher, sessionId)

    val transport =
        StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered(),
        )

    val session = server.createSession(transport)
    session.onClose {
        logger.info("MCP session closed")
    }
    kotlinx.coroutines.Job().let { job ->
        session.onClose { job.complete() }
        job.join()
    }
}

fun createHebeMcpServer(): Server =
    Server(
        serverInfo = Implementation(name = "hebe", version = "1.0.0"),
        options =
            ServerOptions(
                capabilities =
                    ServerCapabilities(
                        tools = ServerCapabilities.Tools(listChanged = false),
                    ),
            ),
    )

fun Server.addHelloWorldTool() {
    addTool(
        name = "hello_world",
        description = "A simple hello world tool for testing MCP integration",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "name",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Name to greet")
                                    },
                                )
                            },
                        )
                    },
                required = emptyList(),
            ),
    ) { request ->
        val name =
            request.arguments
                ?.get("name")
                ?.jsonPrimitive
                ?.content
                ?: "World"

        CallToolResult(
            content = listOf(TextContent(text = "Hello, $name!")),
            isError = false,
        )
    }
}
