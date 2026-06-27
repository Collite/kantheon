package org.tatrman.kantheon.hebe.tools.builtin.file

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class FileSystemListTool(
    private val fs: WorkspaceFs,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "file_system_list",
            description = "List files in the workspace.",
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
                                    put("description", JsonPrimitive("Workspace-relative prefix (default: empty = root)"))
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
        val prefixStr = args["prefix"]?.jsonPrimitive?.content ?: ""
        val prefix = WorkspacePath(prefixStr)
        logger.debug("listing workspace prefix: {}", prefix.value)

        val files = fs.list(prefix)
        val items =
            files.map { wp ->
                val metadata = fs.stat(wp) ?: WorkspaceFs.FileMetadata(0L, 0L)
                buildJsonObject {
                    put("name", JsonPrimitive(wp.value))
                    put("size", JsonPrimitive(metadata.size))
                    put("modified", JsonPrimitive(metadata.modifiedMs))
                }
            }
        return ToolResult.Ok(buildJsonArray { items.forEach { add(it) } })
    }
}
