package org.tatrman.kantheon.hebe.mcp

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import org.tatrman.kantheon.hebe.config.McpServerConfig
import org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher
import org.tatrman.kantheon.hebe.tools.dispatch.ToolRegistry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.buildJsonObject

private fun createTestServer(): Server =
    Server(
        serverInfo = Implementation(name = "test", version = "1.0.0"),
        options =
            ServerOptions(
                capabilities =
                    ServerCapabilities(
                        tools = ServerCapabilities.Tools(listChanged = false),
                    ),
            ),
    )

private class TestTool(
    override val spec: ToolSpec,
    override val risk: RiskLevel,
) : Tool {
    override suspend fun invoke(
        args: kotlinx.serialization.json.JsonObject,
        ctx: ToolContext,
    ): ToolResult =
        org.tatrman.kantheon.hebe.api.ToolResult
            .Ok(content = kotlinx.serialization.json.JsonPrimitive("ok"))
}

class ToolBridgeTest :
    StringSpec({

        "registerToolsFromRegistry registers Low and Medium risk tools" {
            val registry = ToolRegistry()
            val dispatcher = mockk<ToolDispatcher>(relaxed = true)

            val lowRiskTool =
                TestTool(
                    spec = ToolSpec(name = "low_tool", description = "low risk", schema = buildJsonObject { }),
                    risk = RiskLevel.Low,
                )
            val mediumRiskTool =
                TestTool(
                    spec = ToolSpec(name = "medium_tool", description = "medium risk", schema = buildJsonObject { }),
                    risk = RiskLevel.Medium,
                )

            registry.register(lowRiskTool)
            registry.register(mediumRiskTool)

            val server = createTestServer()
            val config = McpServerConfig(exposeHighRisk = false)
            val sessionId = "test-session"

            val count = server.registerToolsFromRegistry(registry, config, dispatcher, sessionId)
            count shouldBe 2
        }

        "registerToolsFromRegistry skips High risk tools when exposeHighRisk=false" {
            val registry = ToolRegistry()
            val dispatcher = mockk<ToolDispatcher>(relaxed = true)

            val lowRiskTool =
                TestTool(
                    spec = ToolSpec(name = "low_tool", description = "low risk", schema = buildJsonObject { }),
                    risk = RiskLevel.Low,
                )
            val highRiskTool =
                TestTool(
                    spec = ToolSpec(name = "high_tool", description = "high risk", schema = buildJsonObject { }),
                    risk = RiskLevel.High,
                )

            registry.register(lowRiskTool)
            registry.register(highRiskTool)

            val server = createTestServer()
            val config = McpServerConfig(exposeHighRisk = false)
            val sessionId = "test-session"

            val count = server.registerToolsFromRegistry(registry, config, dispatcher, sessionId)
            count shouldBe 1
        }

        "registerToolsFromRegistry includes High risk tools when exposeHighRisk=true" {
            val registry = ToolRegistry()
            val dispatcher = mockk<ToolDispatcher>(relaxed = true)

            val lowRiskTool =
                TestTool(
                    spec = ToolSpec(name = "low_tool", description = "low risk", schema = buildJsonObject { }),
                    risk = RiskLevel.Low,
                )
            val highRiskTool =
                TestTool(
                    spec = ToolSpec(name = "high_tool", description = "high risk", schema = buildJsonObject { }),
                    risk = RiskLevel.High,
                )

            registry.register(lowRiskTool)
            registry.register(highRiskTool)

            val server = createTestServer()
            val config = McpServerConfig(exposeHighRisk = true)
            val sessionId = "test-session"

            val count = server.registerToolsFromRegistry(registry, config, dispatcher, sessionId)
            count shouldBe 2
        }
    })
