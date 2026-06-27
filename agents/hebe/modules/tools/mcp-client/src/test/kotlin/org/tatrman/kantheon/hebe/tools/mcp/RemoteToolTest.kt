package org.tatrman.kantheon.hebe.mcp

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.tools.mcp.RemoteTool
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

class RemoteToolTest :
    StringSpec(
        {
            "invoke returns Ok with text content on success" {
                val mockClient = mockk<Client>(relaxed = false)
                coEvery { mockClient.callTool(name = any(), arguments = any()) } returns
                    CallToolResult(
                        content = listOf(TextContent(text = "hello world")),
                        isError = null,
                    )

                val tool =
                    RemoteTool(
                        name = "test_tool",
                        description = "A test tool",
                        inputSchema = null,
                        mcpClient = mockClient,
                        originalName = "original_name",
                        risk = RiskLevel.Medium,
                    )

                val ctx = mockk<ToolContext>(relaxed = true)
                val result = tool.invoke(buildJsonObject { }, ctx)
                when (result) {
                    is ToolResult.Ok -> result.content.jsonPrimitive.content shouldBe "hello world"
                    else -> throw AssertionError("Expected ToolResult.Ok")
                }

                coVerify { mockClient.callTool(name = "original_name", arguments = emptyMap()) }
            }

            "invoke returns Err with retriable=true when isError=true" {
                val mockClient = mockk<Client>(relaxed = false)
                coEvery { mockClient.callTool(name = any(), arguments = any()) } returns
                    CallToolResult(
                        content = listOf(TextContent(text = "Something went wrong")),
                        isError = true,
                    )

                val tool =
                    RemoteTool(
                        name = "test_tool",
                        description = "A test tool",
                        inputSchema = null,
                        mcpClient = mockClient,
                        originalName = "original_name",
                        risk = RiskLevel.Medium,
                    )

                val ctx = mockk<ToolContext>(relaxed = true)
                val result = tool.invoke(buildJsonObject { }, ctx)
                when (result) {
                    is ToolResult.Err -> {
                        result.message shouldBe "Something went wrong"
                        result.retriable shouldBe true
                    }
                    else -> throw AssertionError("Expected ToolResult.Err")
                }
            }

            "invoke returns Err with retriable=true when client throws" {
                val mockClient = mockk<Client>(relaxed = false)
                coEvery { mockClient.callTool(name = any(), arguments = any()) } throws
                    RuntimeException("Connection lost")

                val tool =
                    RemoteTool(
                        name = "test_tool",
                        description = "A test tool",
                        inputSchema = null,
                        mcpClient = mockClient,
                        originalName = "original_name",
                        risk = RiskLevel.Medium,
                    )

                val ctx = mockk<ToolContext>(relaxed = true)
                val result = tool.invoke(buildJsonObject { }, ctx)
                when (result) {
                    is ToolResult.Err -> {
                        result.retriable shouldBe true
                        result.message shouldNotBe null
                    }
                    else -> throw AssertionError("Expected ToolResult.Err")
                }
            }

            "RemoteTool maps tool name and description correctly" {
                val mockClient = mockk<Client>(relaxed = true)

                val tool =
                    RemoteTool(
                        name = "mcp_server_tool",
                        description = "Description of the tool",
                        inputSchema = null,
                        mcpClient = mockClient,
                        originalName = "original_name",
                        risk = RiskLevel.Medium,
                    )

                tool.spec.name shouldBe "mcp_server_tool"
                tool.spec.description shouldBe "Description of the tool"
            }
        },
    )
