package org.tatrman.kantheon.hebe.tools.builtin.memory

import org.tatrman.kantheon.hebe.api.MemoryCategory
import org.tatrman.kantheon.hebe.api.MemoryScope
import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneResult
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneScanner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class MemoryWriteTool(
    private val memory: MemoryStore,
    private val hygieneScanner: HygieneScanner = HygieneScanner(),
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec =
        ToolSpec(
            name = "memory_write",
            description = "Write a document to memory.",
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
                                    put("description", JsonPrimitive("Document path"))
                                },
                            )
                            put(
                                "content",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Document content"))
                                },
                            )
                            put(
                                "category",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Category: Document, Fact, Preference, Skill (default: Document)"))
                                    put("default", JsonPrimitive("Document"))
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
        val path =
            args["path"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: path")
        val content =
            args["content"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: content")
        val categoryStr = args["category"]?.jsonPrimitive?.content ?: "Document"

        val category =
            try {
                MemoryCategory.valueOf(categoryStr)
            } catch (_: Exception) {
                MemoryCategory.Document
            }

        logger.debug("memory_write path={} category={}", path, category)

        val hygieneResult = hygieneScanner.scan(content)
        if (hygieneResult is HygieneResult.Reject) {
            val findings = hygieneResult.findings.joinToString("; ") { "${it.rule}(${it.severity})" }
            logger.warn("hygiene rejected write to {}: {}", path, findings)
            return ToolResult.Err("content blocked by hygiene: $findings")
        }

        return try {
            memory.appendDoc(path, content, MemoryScope.Default, category)
            ToolResult.Ok(JsonPrimitive("written: $path"))
        } catch (e: Exception) {
            ToolResult.Err("write failed: ${e.message}")
        }
    }
}
