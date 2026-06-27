package org.tatrman.kantheon.hebe.tools.builtin.ask

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

class AskUserTool : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "ask_user",
            description = "Ask the user a question. Returns NeedsApproval which is delivered via the originating channel.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("question")) })
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "question",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Question to ask the user"))
                                },
                            )
                            put(
                                "purpose",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Purpose: general or credential"))
                                    put("default", JsonPrimitive("general"))
                                },
                            )
                            put(
                                "secretName",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Required when purpose=credential"))
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
        val question =
            args["question"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: question")
        val purpose = args["purpose"]?.jsonPrimitive?.content ?: "general"
        val secretName = args["secretName"]?.jsonPrimitive?.content

        logger.debug("ask_user question={} purpose={}", question, purpose)

        if (purpose == "credential" && secretName == null) {
            return ToolResult.Err("secretName required when purpose=credential")
        }

        val payload =
            buildJsonObject {
                put("purpose", JsonPrimitive(purpose))
                secretName?.let { put("secretName", JsonPrimitive(it)) }
            }

        return ToolResult.NeedsApproval(question, payload)
    }
}
