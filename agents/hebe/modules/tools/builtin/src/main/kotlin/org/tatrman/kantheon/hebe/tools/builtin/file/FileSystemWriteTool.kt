package org.tatrman.kantheon.hebe.tools.builtin.file

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneResult
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneScanner
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class FileSystemWriteTool(
    private val fs: WorkspaceFs,
    private val hygieneScanner: HygieneScanner = HygieneScanner(),
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "file_system_write",
            description = "Write a file to the workspace. Creates or overwrites.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "required",
                        buildJsonArray {
                            add(JsonPrimitive("path"))
                            add(JsonPrimitive("content"))
                        },
                    )
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "path",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Workspace-relative destination path"))
                                },
                            )
                            put(
                                "content",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("File content to write"))
                                },
                            )
                            put(
                                "encoding",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Encoding: utf-8 (default) or base64"))
                                    put("default", JsonPrimitive("utf-8"))
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
        val pathStr =
            args["path"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: path")
        val contentRaw =
            args["content"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: content")
        val encoding = args["encoding"]?.jsonPrimitive?.content ?: "utf-8"

        val path = WorkspacePath(pathStr)
        logger.debug("writing workspace path: {}", path.value)

        val content =
            if (encoding == "base64") {
                try {
                    String(
                        java.util.Base64
                            .getDecoder()
                            .decode(contentRaw),
                    )
                } catch (e: Exception) {
                    return ToolResult.Err("invalid base64 content: ${e.message}")
                }
            } else {
                contentRaw
            }

        val hygieneResult = hygieneScanner.scan(content)
        if (hygieneResult is HygieneResult.Reject) {
            val findings = hygieneResult.findings.joinToString("; ") { "${it.rule}(${it.severity})" }
            logger.warn("hygiene rejected write to {}: {}", path.value, findings)
            return ToolResult.Err("content blocked by hygiene: $findings")
        }

        return runCatching {
            fs.write(path, content)
            ToolResult.Ok(JsonPrimitive("written: ${path.value}"))
        }.getOrElse { e ->
            ToolResult.Err("write failed: ${e.message}")
        }
    }
}
