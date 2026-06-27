package org.tatrman.kantheon.capabilities.api

import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.capabilities.v1.AgentCapability
import org.tatrman.kantheon.capabilities.v1.Capability
import org.tatrman.kantheon.capabilities.v1.ToolCapability
import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity

/**
 * JSON adapters for the capabilities-mcp wire surface (MCP + REST).
 *
 * Wire shape per `docs/architecture/themis/contracts.md` §2:
 *  - camelCase keys (proto3 default for protobuf-util JsonFormat).
 *  - `Capability` serialised with a `kind` discriminator (`"tool"` / `"agent"`).
 *  - Every response carries `messages: []` per Rule 6.
 */
object CapabilityJson {
    private val protoPrinter: JsonFormat.Printer =
        JsonFormat.printer().alwaysPrintFieldsWithNoPresence().omittingInsignificantWhitespace()

    private val protoParser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    fun toolToJson(tool: ToolCapability): JsonObject = protoToJson(tool)

    fun agentToJson(agent: AgentCapability): JsonObject = protoToJson(agent)

    fun capabilityToJson(cap: Capability): JsonObject =
        buildJsonObject {
            when {
                cap.hasTool() -> {
                    put("kind", JsonPrimitive("tool"))
                    put("tool", toolToJson(cap.tool))
                }
                cap.hasAgent() -> {
                    put("kind", JsonPrimitive("agent"))
                    put("agent", agentToJson(cap.agent))
                }
                else -> {
                    put("kind", JsonNull)
                }
            }
        }

    fun capabilityFromJson(node: JsonObject): Capability {
        val kind = node["kind"]?.let { (it as? JsonPrimitive)?.contentOrNull() }
        val builder = Capability.newBuilder()
        when (kind) {
            "tool" -> {
                val toolNode =
                    node["tool"]?.let { it as? JsonObject }
                        ?: error("Capability.kind=tool requires a 'tool' object")
                val toolBuilder = ToolCapability.newBuilder()
                protoParser.merge(toolNode.toString(), toolBuilder)
                builder.tool = toolBuilder.build()
            }
            "agent" -> {
                val agentNode =
                    node["agent"]?.let { it as? JsonObject }
                        ?: error("Capability.kind=agent requires an 'agent' object")
                val agentBuilder = AgentCapability.newBuilder()
                protoParser.merge(agentNode.toString(), agentBuilder)
                builder.agent = agentBuilder.build()
            }
            else -> error("Capability requires kind='tool' or kind='agent', got: $kind")
        }
        return builder.build()
    }

    fun messageJson(
        severity: Severity,
        code: String,
        humanMessage: String,
    ): JsonObject =
        protoToJson(
            ResponseMessage
                .newBuilder()
                .setSeverity(severity)
                .setCode(code)
                .setHumanMessage(humanMessage)
                .build(),
        )

    fun emptyMessages(): JsonArray = JsonArray(emptyList())

    fun messagesArray(vararg msgs: JsonObject): JsonArray = buildJsonArray { msgs.forEach { add(it) } }

    private fun protoToJson(msg: MessageOrBuilder): JsonObject =
        json.decodeFromString(JsonObject.serializer(), protoPrinter.print(msg))

    private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null
}
