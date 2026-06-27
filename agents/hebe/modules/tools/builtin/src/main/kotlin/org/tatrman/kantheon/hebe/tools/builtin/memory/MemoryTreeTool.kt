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

class MemoryTreeTool(
    private val memory: MemoryStore,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "memory_tree",
            description = "List documents in memory under a prefix.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "prefix",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Prefix filter (default: empty)"))
                                    put("default", JsonPrimitive(""))
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
        val prefix = args["prefix"]?.jsonPrimitive?.content ?: ""

        logger.debug("memory_tree prefix={}", prefix)

        return try {
            val docs = memory.listDocs(prefix)
            val results =
                buildJsonArray {
                    docs.forEach { doc ->
                        add(JsonPrimitive(doc))
                    }
                }
            ToolResult.Ok(results)
        } catch (e: Exception) {
            ToolResult.Err("tree failed: ${e.message}")
        }
    }
}
