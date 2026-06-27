package org.tatrman.kantheon.capabilities.api

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.ktor.server.application.Application
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.capabilities.registry.RegistryQueryService
import org.tatrman.kantheon.common.v1.Severity

/**
 * Mount the six `capabilities.*` MCP tools at `POST /mcp`.
 *
 * The SDK installs its own ContentNegotiation with `McpJson` — call this
 * BEFORE installing your own ContentNegotiation or use the shared
 * instance.
 */
fun Application.installCapabilitiesMcp(
    service: RegistryQueryService,
    path: String = "/mcp",
) {
    mcpStreamableHttp(
        path = path,
        // Test apps bind via `client.post("/mcp")` whose Host header is "localhost".
        enableDnsRebindingProtection = false,
    ) {
        Server(
            serverInfo = Implementation(name = "capabilities-mcp", version = "0.1.0"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = null)),
                ),
        ).apply {
            addTool(
                name = "capabilities.search",
                description = "Search for tool + agent capabilities matching intent kinds, entity types, or tags.",
                inputSchema = searchSchema(),
            ) { req ->
                guarded("capabilities.search") {
                    val params = JsonAdapters.searchParamsFromJson(req.params.arguments ?: JsonObject(emptyMap()))
                    val entries = service.search(params)
                    structured(
                        buildJsonObject {
                            put("entries", JsonArray(entries.map { CapabilityJson.capabilityToJson(it) }))
                            put("messages", CapabilityJson.emptyMessages())
                        },
                    )
                }
            }

            addTool(
                name = "capabilities.list",
                description = "List all registered capabilities, optionally filtered by category.",
                inputSchema = listSchema(),
            ) { req ->
                guarded("capabilities.list") {
                    val params = JsonAdapters.listParamsFromJson(req.params.arguments ?: JsonObject(emptyMap()))
                    val entries = service.list(params)
                    structured(
                        buildJsonObject {
                            put("entries", JsonArray(entries.map { CapabilityJson.capabilityToJson(it) }))
                            put("messages", CapabilityJson.emptyMessages())
                        },
                    )
                }
            }

            addTool(
                name = "capabilities.list_agents",
                description = "List all registered AgentCapability entries (Themis Layer 1 input).",
                inputSchema = listAgentsSchema(),
            ) { req ->
                guarded("capabilities.list_agents") {
                    val filter = JsonAdapters.listAgentsFilterFromJson(req.params.arguments ?: JsonObject(emptyMap()))
                    val agents = service.listAgents(filter)
                    structured(
                        buildJsonObject {
                            put("agents", JsonArray(agents.map { CapabilityJson.agentToJson(it) }))
                            put("messages", CapabilityJson.emptyMessages())
                        },
                    )
                }
            }

            addTool(
                name = "capabilities.get",
                description = "Fetch a capability by id (capability_id or agent_id).",
                inputSchema = getSchema(),
            ) { req ->
                guarded("capabilities.get") {
                    val id =
                        (req.params.arguments?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?: return@guarded errorResult("missing_id", "'id' is required")
                    val cap = service.get(id)
                    structured(
                        buildJsonObject {
                            if (cap == null) {
                                put("capability", JsonNull)
                            } else {
                                put("capability", CapabilityJson.capabilityToJson(cap))
                            }
                            put("messages", CapabilityJson.emptyMessages())
                        },
                    )
                }
            }

            addTool(
                name = "capabilities.register",
                description = "Register or update a Capability. Idempotent on capability_id / agent_id.",
                inputSchema = registerSchema(),
            ) { req ->
                guarded("capabilities.register") {
                    val capNode =
                        req.params.arguments?.get("capability") as? JsonObject
                            ?: return@guarded errorResult("missing_capability", "'capability' is required")
                    val capability = CapabilityJson.capabilityFromJson(capNode)
                    val rid = service.register(capability)
                    structured(
                        buildJsonObject {
                            put("registrationId", JsonPrimitive(rid))
                            put("messages", CapabilityJson.emptyMessages())
                        },
                    )
                }
            }

            addTool(
                name = "capabilities.heartbeat",
                description = "Refresh the heartbeat for a previously registered capability.",
                inputSchema = heartbeatSchema(),
            ) { req ->
                guarded("capabilities.heartbeat") {
                    val rid =
                        (req.params.arguments?.get("registrationId") as? JsonPrimitive)
                            ?.takeIf { it.isString }
                            ?.content
                            ?: return@guarded errorResult(
                                "missing_registration_id",
                                "'registrationId' is required",
                            )
                    when (val outcome = service.heartbeat(rid)) {
                        is RegistryQueryService.HeartbeatOutcome.Accepted ->
                            structured(
                                buildJsonObject {
                                    put("acceptedAt", JsonPrimitive(outcome.acceptedAt.toString()))
                                    put("messages", CapabilityJson.emptyMessages())
                                },
                            )

                        RegistryQueryService.HeartbeatOutcome.Unknown ->
                            errorResult(
                                code = "unknown_registration_id",
                                message = "registration_id '$rid' is not registered",
                            )
                    }
                }
            }
        }
    }
}

private fun searchSchema(): ToolSchema =
    ToolSchema(
        properties =
            buildJsonObject {
                put("intentKinds", arraySchema("string"))
                put("entityTypes", arraySchema("string"))
                put("capabilityTags", arraySchema("string"))
                put("filter", capabilityFilterSchema())
            },
    )

private fun listSchema(): ToolSchema =
    ToolSchema(
        properties =
            buildJsonObject {
                put("category", stringSchema())
                put("filter", capabilityFilterSchema())
            },
    )

private fun listAgentsSchema(): ToolSchema =
    ToolSchema(
        properties =
            buildJsonObject {
                put("filter", capabilityFilterSchema())
            },
    )

private fun getSchema(): ToolSchema =
    ToolSchema(
        properties = buildJsonObject { put("id", stringSchema()) },
        required = listOf("id"),
    )

private fun registerSchema(): ToolSchema =
    ToolSchema(
        properties =
            buildJsonObject {
                put(
                    "capability",
                    buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("description", JsonPrimitive("Capability sealed-union (kind=tool|agent, plus payload)."))
                    },
                )
            },
        required = listOf("capability"),
    )

private fun heartbeatSchema(): ToolSchema =
    ToolSchema(
        properties = buildJsonObject { put("registrationId", stringSchema()) },
        required = listOf("registrationId"),
    )

private fun stringSchema(): JsonObject = buildJsonObject { put("type", JsonPrimitive("string")) }

private fun arraySchema(itemType: String): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("items", buildJsonObject { put("type", JsonPrimitive(itemType)) })
    }

private fun capabilityFilterSchema(): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put("includeTools", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                put("includeAgents", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                put("includePruned", buildJsonObject { put("type", JsonPrimitive("boolean")) })
            },
        )
    }

/**
 * Tool-callback safety wrapper. Inspired by ai-platform's `safeMcpTool`:
 * surfaces timeouts and exceptions as `isError = true` CallToolResults
 * instead of bubbling out of the MCP transport layer.
 */
private suspend fun guarded(
    toolName: String,
    timeoutMs: Long = 5_000,
    block: suspend () -> CallToolResult,
): CallToolResult =
    try {
        withTimeout(timeoutMs) { block() }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        errorResult(code = "tool_timeout", message = "tool '$toolName' exceeded ${timeoutMs}ms")
    } catch (e: Throwable) {
        errorResult(code = "tool_internal_error", message = "${e::class.simpleName}: ${e.message ?: "(no message)"}")
    }

private fun structured(content: JsonObject): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(text = content.toString())),
        structuredContent = content,
        isError = false,
    )

private fun errorResult(
    code: String,
    message: String,
): CallToolResult {
    val body =
        buildJsonObject {
            put(
                "messages",
                CapabilityJson.messagesArray(CapabilityJson.messageJson(Severity.ERROR, code, message)),
            )
        }
    return CallToolResult(
        content = listOf(TextContent(text = body.toString())),
        structuredContent = body,
        isError = true,
    )
}
