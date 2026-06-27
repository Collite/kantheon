package org.tatrman.kantheon.hebe.tools.builtin.memory

import org.tatrman.kantheon.hebe.api.MemoryStore
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

class MemoryReadTool(
    private val memory: MemoryStore,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "memory_read",
            description = "Read a document from memory by path.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("path")) })
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "path",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Document path"))
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
        val path =
            args["path"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: path")

        logger.debug("memory_read path={}", path)

        return try {
            val content = memory.readDoc(path)
            if (content != null) {
                ToolResult.Ok(JsonPrimitive(content))
            } else {
                ToolResult.Err("document not found: $path")
            }
        } catch (e: Exception) {
            ToolResult.Err("read failed: ${e.message}")
        }
    }
}
