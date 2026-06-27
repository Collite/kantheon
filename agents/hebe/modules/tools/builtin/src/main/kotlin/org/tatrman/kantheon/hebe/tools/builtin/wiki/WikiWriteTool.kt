package org.tatrman.kantheon.hebe.tools.builtin.wiki

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

class WikiWriteTool(
    private val memory: MemoryStore,
    private val hygieneScanner: HygieneScanner = HygieneScanner(),
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val WIKI_PREFIX = "wiki/"
    }

    override val spec =
        ToolSpec(
            name = "wiki_write",
            description = "Write a wiki page by slug. Writes to memory as wiki/<slug>.md.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "required",
                        buildJsonArray {
                            add(JsonPrimitive("slug"))
                            add(JsonPrimitive("content"))
                        },
                    )
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "slug",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Wiki page slug"))
                                },
                            )
                            put(
                                "content",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Page content (supports [[wikilinks]]))"))
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
        val slug =
            args["slug"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: slug")
        val content =
            args["content"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: content")

        val path = "$WIKI_PREFIX$slug.md"
        logger.debug("wiki_write slug={} path={}", slug, path)

        val hygieneResult = hygieneScanner.scan(content)
        if (hygieneResult is HygieneResult.Reject) {
            val findings = hygieneResult.findings.joinToString("; ") { "${it.rule}(${it.severity})" }
            logger.warn("hygiene rejected wiki write {}: {}", slug, findings)
            return ToolResult.Err("content blocked by hygiene: $findings")
        }

        return try {
            memory.appendDoc(path, content, MemoryScope.Default, MemoryCategory.Document)
            ToolResult.Ok(JsonPrimitive("written: $path"))
        } catch (e: Exception) {
            ToolResult.Err("write failed: ${e.message}")
        }
    }
}
