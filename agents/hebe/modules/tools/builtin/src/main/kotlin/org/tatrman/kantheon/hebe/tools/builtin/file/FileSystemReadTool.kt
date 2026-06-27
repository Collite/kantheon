package org.tatrman.kantheon.hebe.tools.builtin.file

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import org.tatrman.kantheon.hebe.memory.workspace.MarkdownInferrer
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class FileSystemReadTool(
    private val fs: WorkspaceFs,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "file_system_read",
            description = "Read a file from the workspace.",
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
                                    put("description", JsonPrimitive("Workspace-relative path to the file"))
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

    override val risk = RiskLevel.Low
    override val readOnly = true

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val pathStr =
            args["path"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: path")
        val encoding = args["encoding"]?.jsonPrimitive?.content ?: "utf-8"

        val path =
            try {
                WorkspacePath(pathStr)
            } catch (e: IllegalArgumentException) {
                return ToolResult.Err("invalid path: ${e.message}")
            }
        logger.debug("reading workspace path: {}", path.value)

        // Read raw bytes so we can detect binary content reliably before any charset decoding
        val bytes =
            fs.readBytes(path)
                ?: return ToolResult.Err("file not found: ${path.value}")

        if (encoding == "base64") {
            return ToolResult.Ok(JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
        }

        // Null bytes are the standard heuristic for binary content
        val isBinary = bytes.any { it == 0.toByte() }
        if (isBinary) {
            return ToolResult.Err("binary file: use encoding=base64")
        }

        val content = String(bytes, Charsets.UTF_8)
        val meta = MarkdownInferrer.metadata(path.value, content)
        val isMarkdown = meta.extension == "md" || meta.extension == "markdown"
        return buildJsonObject {
            put("content", JsonPrimitive(content))
            if (isMarkdown) {
                put("title", JsonPrimitive(meta.title))
                put("headings", buildJsonArray { meta.headings.forEach { add(JsonPrimitive(it)) } })
                meta.frontmatter?.let { fm ->
                    put(
                        "frontmatter",
                        buildJsonObject {
                            fm.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                        },
                    )
                }
            }
            put("extension", JsonPrimitive(meta.extension))
            put("size", JsonPrimitive(bytes.size))
        }.let { ToolResult.Ok(it) }
    }
}
