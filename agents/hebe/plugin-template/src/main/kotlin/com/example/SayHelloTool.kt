package com.example

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SayHelloTool(private val host: org.tatrman.kantheon.hebe.plugin.api.PluginHost) : Tool {
    override val spec = org.tatrman.kantheon.hebe.api.ToolSpec(
        name = "say_hello",
        description = "Prints a greeting.",
        schema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
    )

    override val risk = RiskLevel.Low

    override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
        return ToolResult.Ok(JsonPrimitive("hello from plugin (pluginId=${host.pluginId})"))
    }
}