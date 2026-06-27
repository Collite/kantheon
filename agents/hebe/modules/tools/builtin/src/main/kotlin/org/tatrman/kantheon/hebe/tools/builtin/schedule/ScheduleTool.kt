package org.tatrman.kantheon.hebe.tools.builtin.schedule

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class ScheduleTool : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "schedule",
            description = "CRUD for scheduled routines. Risk: Medium.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("verb")) })
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "verb",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Verb: create | list | disable | enable | delete"))
                                },
                            )
                            put(
                                "name",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Routine name"))
                                },
                            )
                            put(
                                "cron",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Cron expression"))
                                },
                            )
                            put(
                                "body_kind",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("skill or tool"))
                                },
                            )
                            put(
                                "body_ref",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Skill name or tool name"))
                                },
                            )
                            put(
                                "id",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Routine ID for delete"))
                                },
                            )
                        },
                    )
                },
            pathScope = org.tatrman.kantheon.hebe.api.PathScope.WorkspaceOnly,
        )

    override val risk = RiskLevel.Medium
    override val readOnly = false

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val verb =
            args["verb"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: verb")

        logger.debug("schedule verb={}", verb)

        return when (verb.lowercase()) {
            "create" -> createRoutine(args)
            "list" -> listRoutines()
            "disable" -> updateRoutine(args, false)
            "enable" -> updateRoutine(args, true)
            "delete" -> deleteRoutine(args)
            else -> ToolResult.Err("unknown verb: $verb")
        }
    }

    private fun createRoutine(args: JsonObject): ToolResult {
        val name =
            args["name"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("name required for create")
        val cron =
            args["cron"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("cron required for create")
        val bodyKind =
            args["body_kind"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("body_kind required for create")
        val bodyRef =
            args["body_ref"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("body_ref required for create")

        if (!isValidCron(cron)) {
            return ToolResult.Err("invalid cron expression: $cron")
        }

        // stub: pending M8.T1 for DB persistence
        return ToolResult.Ok(
            buildJsonObject {
                put("id", JsonPrimitive("routine-$name"))
                put("name", JsonPrimitive(name))
                put("cron", JsonPrimitive(cron))
                put("body_kind", JsonPrimitive(bodyKind))
                put("body_ref", JsonPrimitive(bodyRef))
            },
        )
    }

    private fun listRoutines(): ToolResult {
        // stub: pending M8.T1 for DB persistence
        return ToolResult.Ok(JsonArray(emptyList()))
    }

    private fun updateRoutine(
        args: JsonObject,
        enabled: Boolean,
    ): ToolResult {
        val id =
            args["id"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("id required")
        return ToolResult.Ok(
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("enabled", JsonPrimitive(enabled))
            },
        )
    }

    private fun deleteRoutine(args: JsonObject): ToolResult {
        val id =
            args["id"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("id required")
        return ToolResult.Ok(
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("deleted", JsonPrimitive(true))
            },
        )
    }

    private fun isValidCron(cron: String): Boolean {
        val parts = cron.split(" ")
        if (parts.size !in 5..6) return false
        return parts.all { part ->
            part == "*" ||
                part.matches(Regex("^\\d+(-\\d+)?(,\\d+(-\\d+)?)*$")) ||
                part.matches(Regex("^\\*/\\d+$"))
        }
    }
}
