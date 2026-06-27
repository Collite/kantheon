package org.tatrman.kantheon.hebe.tools.builtin.search

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.SecretLookup
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class WebSearchTool(
    private val secretLookup: SecretLookup,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val ddgProvider = DuckDuckGoSearchProvider()

    override val spec =
        ToolSpec(
            name = "web_search",
            description = "Search the web using Brave (preferred) or DuckDuckGo (fallback).",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("required", buildJsonArray { add(JsonPrimitive("query")) })
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "query",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Search query"))
                                },
                            )
                            put(
                                "k",
                                buildJsonObject {
                                    put("type", JsonPrimitive("integer"))
                                    put("description", JsonPrimitive("Number of results (default: 10)"))
                                    put("default", JsonPrimitive(10))
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
        val query =
            args["query"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: query")
        val k = args["k"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10

        logger.debug("web_search query={} k={}", query, k)

        val braveApiKey = ctx.secretLookup.secret("brave_api_key")
        val provider =
            if (braveApiKey != null) {
                BraveSearchProvider(braveApiKey)
            } else {
                logger.debug("no brave API key, using duckduckgo fallback")
                ddgProvider
            }

        val hits = provider.search(query, k)
        if (hits.isEmpty()) {
            return ToolResult.Ok(JsonArray(emptyList()))
        }

        val results =
            buildJsonArray {
                hits.forEach { hit ->
                    add(
                        buildJsonObject {
                            put("title", JsonPrimitive(hit.title))
                            put("url", JsonPrimitive(hit.url))
                            put("snippet", JsonPrimitive(hit.snippet))
                            put("rank", JsonPrimitive(hit.rank))
                            put("source", JsonPrimitive(hit.source))
                        },
                    )
                }
            }

        return ToolResult.Ok(results)
    }
}
