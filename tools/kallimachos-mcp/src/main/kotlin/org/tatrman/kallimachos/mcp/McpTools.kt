package org.tatrman.kallimachos.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The `library.*` tool surface (contracts §4) — thin forwards to Kallimachos.
 * Each tool maps its args to a [ForwardSpec] (via [LibrarySpecs]) and the
 * [LibraryForwarder] issues the call carrying the caller bearer. Zero logic.
 *
 * The OBO bearer is read from the `bearer` arg at S4.1 (the caller forwards it);
 * extraction from the MCP HTTP edge + the Validate RLS predicate land in S4.2.
 */
class McpTools(
    private val forwarder: LibraryForwarder,
    private val rlsGuard: org.tatrman.kallimachos.mcp.rls.MartRlsGuard? = null,
) {
    private fun schema(
        vararg required: String,
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = ToolSchema(properties = kotlinx.serialization.json.buildJsonObject(block), required = required.toList())

    val getContext =
        Tool(
            name = "library.getContext",
            description = "Graph-primary, citation-bearing RAG context for a notebook (mart). Returns cited chunks.",
            inputSchema =
                schema("notebookId", "query") {
                    putJsonObject("notebookId") { put("type", "string") }
                    putJsonObject("query") { put("type", "string") }
                    putJsonObject("k") { put("type", "integer") }
                    putJsonObject("graphHops") { put("type", "integer") }
                    putJsonObject("vectorBoost") { put("type", "boolean") }
                    putJsonObject("bearer") { put("type", "string") }
                },
        )

    val search =
        Tool(
            name = "library.search",
            description = "Keyword / metadata search within a notebook (mart).",
            inputSchema =
                schema("notebookId") {
                    putJsonObject("notebookId") { put("type", "string") }
                    putJsonObject("text") { put("type", "string") }
                    putJsonObject("bearer") { put("type", "string") }
                },
        )

    val findSimilar =
        Tool(
            name = "library.findSimilar",
            description = "Vector recall — chunks similar to the query within a mart.",
            inputSchema =
                schema("notebookId", "query") {
                    putJsonObject("notebookId") { put("type", "string") }
                    putJsonObject("query") { put("type", "string") }
                    putJsonObject("bearer") { put("type", "string") }
                },
        )

    val getPage =
        Tool(
            name = "library.getPage",
            description = "A compiled wiki page (markdown + concept_ref) by id, scoped to a mart.",
            inputSchema =
                schema("id", "notebookId") {
                    putJsonObject("id") { put("type", "string") }
                    putJsonObject("notebookId") { put("type", "string") }
                    putJsonObject("bearer") { put("type", "string") }
                },
        )

    val traverse =
        Tool(
            name = "library.traverse",
            description = "Walk wiki links from a node within a mart (browse).",
            inputSchema =
                schema("fromNodeId", "notebookId") {
                    putJsonObject("fromNodeId") { put("type", "integer") }
                    putJsonObject("notebookId") { put("type", "string") }
                    putJsonObject("bearer") { put("type", "string") }
                },
        )

    val getSource =
        Tool(
            name = "library.getSource",
            description = "A source + its parts within a mart (Drilldown target).",
            inputSchema =
                schema("id", "notebookId") {
                    putJsonObject("id") { put("type", "string") }
                    putJsonObject("notebookId") { put("type", "string") }
                    putJsonObject("bearer") { put("type", "string") }
                },
        )

    val listNotebooks =
        Tool(
            name = "library.listNotebooks",
            description = "Marts (notebooks) visible to the caller.",
            inputSchema = schema { putJsonObject("bearer") { put("type", "string") } },
        )

    val createNotebook =
        Tool(
            name = "library.createNotebook",
            description = "Create a mart (ops/admin at v1).",
            inputSchema =
                schema("displayName") {
                    putJsonObject("displayName") { put("type", "string") }
                    putJsonObject("bearer") { put("type", "string") }
                },
        )

    val all = listOf(getContext, search, findSimilar, getPage, traverse, getSource, listNotebooks, createNotebook)

    /** Route a tool call to its forward spec + issue it. */
    suspend fun dispatch(
        toolName: String,
        request: CallToolRequest,
    ): CallToolResult {
        val args = request.arguments ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val bearer = (args["bearer"] as? kotlinx.serialization.json.JsonPrimitive)?.content

        // Mart RLS at the edge — deny BEFORE the store is touched (architecture §9).
        rlsGuard?.check(toolName, args, bearer)?.let { decision ->
            if (decision is org.tatrman.kallimachos.mcp.rls.RlsDecision.Deny) {
                val structured =
                    buildJsonObject {
                        put("error", "PERMISSION_DENIED")
                        put("detail", decision.reason)
                    }
                return CallToolResult(
                    content = listOf(TextContent(structured.toString())),
                    structuredContent = structured,
                    isError = true,
                )
            }
        }

        val spec =
            when (toolName) {
                "library.getContext" -> LibrarySpecs.getContext(args)
                "library.search" -> LibrarySpecs.search(args)
                "library.findSimilar" -> LibrarySpecs.findSimilar(args)
                "library.getPage" -> LibrarySpecs.getPage(args)
                "library.traverse" -> LibrarySpecs.traverse(args)
                "library.getSource" -> LibrarySpecs.getSource(args)
                "library.listNotebooks" -> LibrarySpecs.listNotebooks()
                "library.createNotebook" -> LibrarySpecs.createNotebook(args)
                else -> error("unknown tool: $toolName")
            }
        val result = forwarder.forward(spec, bearer)
        return CallToolResult(
            content = listOf(TextContent(result.body)),
            structuredContent = structured(result.body),
            isError = result.status >= 400,
        )
    }

    /**
     * MCP outputs ride `structuredContent` per the MCP spec (AGENTS.md §6.3), not a
     * hand-rolled JSON in `content[0].text`. The store returns a JSON object or
     * array; a top-level array (or a non-JSON body) is wrapped so the field is
     * always a JSON object as the spec requires.
     */
    private fun structured(body: String): JsonObject =
        runCatching { Json.parseToJsonElement(body) }
            .getOrNull()
            .let { el ->
                (el as? JsonObject) ?: buildJsonObject { if (el != null) put("result", el) else put("raw", body) }
            }
}
