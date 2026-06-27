package org.tatrman.kantheon.hebe.mcp

import org.tatrman.kantheon.hebe.api.ApprovalGate
import org.tatrman.kantheon.hebe.api.ApprovalStatus
import org.tatrman.kantheon.hebe.api.Channel
import org.tatrman.kantheon.hebe.api.ChannelHealth
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ObserverEvent
import org.tatrman.kantheon.hebe.api.OutboundMessage
import org.tatrman.kantheon.hebe.api.ParsedToolCall
import org.tatrman.kantheon.hebe.api.ReplyContext
import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.SecretLookup
import org.tatrman.kantheon.hebe.api.Span
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import org.tatrman.kantheon.hebe.config.McpServerConfig
import org.tatrman.kantheon.hebe.tools.dispatch.DispatchOutcome
import org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher
import org.tatrman.kantheon.hebe.tools.dispatch.ToolRegistry
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.tatrman.kantheon.hebe.mcp.ToolBridge")

fun Server.registerToolsFromRegistry(
    registry: ToolRegistry,
    config: McpServerConfig,
    dispatcher: ToolDispatcher,
    sessionId: String,
): Int {
    var count = 0
    val toolList = registry.list()
    for (tool in toolList) {
        if (tool.risk == RiskLevel.High && !config.exposeHighRisk) {
            logger.debug("Skipping high-risk tool {} (exposeHighRisk=false)", tool.spec.name)
            continue
        }

        addTool(
            name = tool.spec.name,
            description = tool.spec.description,
            inputSchema = toolSpecToMcpSchema(tool.spec.schema),
        ) { request ->
            bridgeHandler(request, dispatcher, sessionId)
        }
        count++
    }
    logger.info("Registered {}/{} tools from registry (exposeHighRisk={})", count, toolList.size, config.exposeHighRisk)
    return count
}

private fun toolSpecToMcpSchema(schema: JsonObject): ToolSchema {
    val requiredList =
        schema["required"]?.let { element ->
            if (element is JsonArray) {
                element.mapNotNull { it.jsonPrimitive?.content }
            } else {
                emptyList()
            }
        } ?: emptyList()
    return ToolSchema(
        properties = schema,
        required = requiredList,
    )
}

private suspend fun bridgeHandler(
    request: CallToolRequest,
    dispatcher: ToolDispatcher,
    sessionId: String,
): CallToolResult {
    val args = request.arguments ?: JsonObject(buildJsonObject { })

    val call =
        ParsedToolCall(
            id = "mcp-${System.currentTimeMillis()}",
            name = request.name,
            args = args,
        )

    val ctx = syntheticToolContext(sessionId)

    val outcome = dispatcher.dispatch(call, ctx)

    return when (outcome) {
        is DispatchOutcome.Result -> toolResultToMcpResult(outcome.result)
    }
}

private fun toolResultToMcpResult(result: ToolResult): CallToolResult =
    when (result) {
        is ToolResult.Ok -> {
            val content = TextContent(text = result.content.toString())
            CallToolResult(
                content = listOf(content),
                isError = false,
            )
        }
        is ToolResult.Err ->
            CallToolResult(
                content = listOf(TextContent(text = result.message)),
                isError = true,
            )
        is ToolResult.NeedsApproval ->
            CallToolResult(
                content = listOf(TextContent(text = "Approval required: ${result.prompt}")),
                isError = true,
            )
    }

private fun syntheticToolContext(sessionId: String): ToolContext =
    object : ToolContext {
        override val sessionId: String = sessionId
        override val turnId: String = "mcp:turn-${System.currentTimeMillis()}"
        override val userId: String = "mcp:user"
        override val requestor: Channel = McpChannel
        override val workspace: WorkspacePath = WorkspacePath(System.getProperty("user.home") + "/.hebe")
        override val approvalGate: ApprovalGate = McpApprovalGate
        override val observer: Observer = McpObserver
        override val secretLookup: SecretLookup =
            object : SecretLookup {
                override fun secret(name: String): String? = null
            }
    }

@Suppress("EmptyFunctionBlock")
private object McpChannel : Channel {
    override val name: String = "mcp"

    override suspend fun start(scope: CoroutineScope): Flow<org.tatrman.kantheon.hebe.api.IncomingMessage> = flow { }

    override suspend fun reply(
        ctx: ReplyContext,
        msg: OutboundMessage,
    ) {}

    override suspend fun healthCheck(): ChannelHealth = ChannelHealth.Up

    override suspend fun shutdown() {}
}

private object McpApprovalGate : ApprovalGate {
    private val logger = LoggerFactory.getLogger("org.tatrman.kantheon.hebe.mcp.McpApprovalGate")

    override fun requestIfNeeded(
        tool: Tool,
        args: JsonObject,
        turnId: String,
        channel: String,
        threadExtId: String?,
    ): Flow<ApprovalStatus> = flow {}

    override suspend fun awaitApproval(
        tool: Tool,
        args: JsonObject,
        turnId: String,
        channel: String,
        threadExtId: String?,
    ): Boolean {
        logger.warn(
            "MCPApprovalGate.awaitApproval called — returning false " +
                "(approval-required tools are blocked via MCP)",
        )
        return false
    }

    override fun resolve(
        approvalId: String,
        approved: Boolean,
    ): Boolean = false
}

@Suppress("EmptyFunctionBlock")
private object McpObserver : Observer {
    override fun event(e: ObserverEvent) {}

    override fun span(
        name: String,
        attrs: Map<String, Any>,
    ): Span = McpSpan
}

@Suppress("EmptyFunctionBlock")
private object McpSpan : Span {
    override fun setAttribute(
        key: String,
        value: Any,
    ) {}

    override fun recordError(t: Throwable) {}

    override fun close() {}
}
