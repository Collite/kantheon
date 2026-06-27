package org.tatrman.kantheon.hebe.tools.builtin.file

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class FileSystemGlobTool(
    private val fs: WorkspaceFs,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "file_system_glob",
            description = "Find files in the workspace matching a glob pattern.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("pattern")) })
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "pattern",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Glob pattern, e.g. **/*.md"))
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
        val pattern =
            args["pattern"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: pattern")

        logger.debug("glob pattern: {}", pattern)

        return try {
            val allFiles = fs.list(WorkspacePath(""))
            val matcher: PathMatcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            val matched =
                allFiles.filter { wp ->
                    matcher.matches(Path.of(wp.value))
                }
            ToolResult.Ok(buildJsonArray { matched.forEach { add(JsonPrimitive(it.value)) } })
        } catch (e: Exception) {
            ToolResult.Err("glob error: ${e.message}")
        }
    }
}
