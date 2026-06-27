package org.tatrman.kantheon.hebe.tools.builtin.jobs

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class JobCreateTool : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "job_create",
            description = "Create an ad-hoc or maintenance job. Risk: Low.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "required",
                        buildJsonArray {
                            add(JsonPrimitive("kind"))
                            add(JsonPrimitive("payload"))
                        },
                    )
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "kind",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Job kind: adhoc | routine | maintenance | heartbeat"))
                                },
                            )
                            put(
                                "payload",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Job payload as JSON string"))
                                },
                            )
                        },
                    )
                },
            pathScope = org.tatrman.kantheon.hebe.api.PathScope.WorkspaceOnly,
        )

    override val risk = RiskLevel.Low
    override val readOnly = false

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val kind =
            args["kind"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: kind")
        val payload =
            args["payload"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: payload")

        val validKinds = setOf("adhoc", "routine", "maintenance", "heartbeat")
        if (kind !in validKinds) {
            return ToolResult.Err("invalid kind: $kind (must be one of: $validKinds)")
        }

        val id = "job-${System.currentTimeMillis()}"
        logger.debug("job_create kind={} id={}", kind, id)

        // stub: pending M8.T2 for DB persistence
        return ToolResult.Ok(
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("kind", JsonPrimitive(kind))
                put("status", JsonPrimitive("pending"))
            },
        )
    }
}

class JobStatusTool : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "job_status",
            description = "Get status of a job by ID. Risk: Low, read-only.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("id")) })
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "id",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Job ID"))
                                },
                            )
                        },
                    )
                },
            pathScope = org.tatrman.kantheon.hebe.api.PathScope.WorkspaceOnly,
        )

    override val risk = RiskLevel.Low
    override val readOnly = true

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val id =
            args["id"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: id")

        logger.debug("job_status id={}", id)

        // stub: pending M8.T2 for DB persistence
        return ToolResult.Ok(
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("status", JsonPrimitive("pending"))
            },
        )
    }
}

class JobCancelTool : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "job_cancel",
            description = "Cancel a pending or running job. Risk: Medium.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("id")) })
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "id",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Job ID to cancel"))
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
        val id =
            args["id"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: id")

        logger.debug("job_cancel id={}", id)

        return ToolResult.Ok(
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("status", JsonPrimitive("cancelled"))
            },
        )
    }
}
