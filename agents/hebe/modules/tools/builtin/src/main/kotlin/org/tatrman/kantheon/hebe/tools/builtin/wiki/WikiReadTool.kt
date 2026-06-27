package org.tatrman.kantheon.hebe.tools.builtin.wiki

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

class WikiReadTool(
    private val fs: WorkspaceFs,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val WIKI_PREFIX = "wiki/"
    }

    override val spec =
        ToolSpec(
            name = "wiki_read",
            description = "Read a wiki page by slug. Maps slug to workspace/wiki/<slug>.md.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("slug")) })
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "slug",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Wiki page slug (e.g. architecture)"))
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
        val slug =
            args["slug"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: slug")

        val path = WorkspacePath("$WIKI_PREFIX$slug.md")
        logger.debug("wiki_read slug={} path={}", slug, path.value)

        return when (val content = fs.read(path)) {
            null -> ToolResult.Err("wiki page not found: $slug")
            else -> {
                val links = extractWikilinks(content)
                ToolResult.Ok(
                    buildJsonObject {
                        put("content", JsonPrimitive(content))
                        put("slug", JsonPrimitive(slug))
                        put("links", buildJsonArray { links.forEach { add(JsonPrimitive(it)) } })
                    },
                )
            }
        }
    }

    private fun extractWikilinks(content: String): List<String> {
        val pattern = Regex("""\[\[([^\]]+)]]""")
        return pattern.findAll(content).map { it.groupValues[1] }.toList()
    }
}
