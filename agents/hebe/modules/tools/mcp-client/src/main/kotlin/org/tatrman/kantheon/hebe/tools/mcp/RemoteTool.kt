package org.tatrman.kantheon.hebe.tools.mcp

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

private typealias McpCallToolResult = io.modelcontextprotocol.kotlin.sdk.types.CallToolResult

@Suppress("TooGenericExceptionCaught")
class RemoteTool(
    private val name: String,
    private val description: String,
    private val inputSchema: ToolSchema?,
    private val mcpClient: Client,
    private val originalName: String,
    override val risk: RiskLevel = RiskLevel.Medium,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec: ToolSpec
        get() =
            ToolSpec(
                name = name,
                description = description,
                schema = inputSchema?.properties ?: kotlinx.serialization.json.buildJsonObject { },
            )

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult =
        try {
            val result =
                mcpClient.callTool(
                    name = originalName,
                    arguments = args.toMap(),
                )
            callToolResultToToolResult(result)
        } catch (e: Exception) {
            logger.error("Remote tool '{}' invocation failed: {}", name, e.message)
            ToolResult.Err(message = "Remote tool failed: ${e.message}", retriable = true)
        }

    @Suppress("MaxLineLength")
    private fun callToolResultToToolResult(result: McpCallToolResult): ToolResult =
        if (result.isError == true) {
            val message =
                result.content
                    .filterIsInstance<TextContent>()
                    .firstOrNull()
                    ?.text ?: "Unknown error"
            ToolResult.Err(message = message, retriable = true)
        } else {
            val content =
                result.content
                    .filterIsInstance<TextContent>()
                    .firstOrNull()
                    ?.text ?: ""
            ToolResult.Ok(content = JsonPrimitive(content))
        }

    private fun JsonObject.toMap(): Map<String, Any?> =
        this.keys.associateWith { key ->
            this[key]?.let { element ->
                if (element is JsonPrimitive) element.content else element.toString()
            }
        }
}
