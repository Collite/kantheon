package org.tatrman.kallimachos.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The thin (zero-logic) forward from a `library.*` MCP tool to a Kallimachos HTTP
 * endpoint (contracts §4). Each tool maps its args to a [ForwardSpec]; the
 * forwarder issues the call carrying the caller OBO bearer (forwarded for RLS —
 * the predicate enforces in S4.2) and returns the store's response verbatim
 * (Rule-6 `messages` ride through; errors propagate as the store's status).
 */
data class ForwardSpec(
    val method: HttpMethod,
    val path: String,
    val body: JsonElement? = null,
)

data class ForwardResult(
    val status: Int,
    val body: String,
)

class LibraryForwarder(
    private val http: HttpClient,
    private val baseUrl: String,
) {
    suspend fun forward(
        spec: ForwardSpec,
        bearer: String?,
    ): ForwardResult {
        val resp =
            http.request("$baseUrl${spec.path}") {
                method = spec.method
                bearer?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                if (spec.body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(spec.body.toString())
                }
            }
        return ForwardResult(resp.status.value, resp.bodyAsText())
    }
}

/**
 * The `library.*` tool → store-endpoint map (contracts §4 table). Pure arg
 * shaping; the store remains the only place with logic.
 */
object LibrarySpecs {
    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: error("missing '$key' arg")

    private fun String.enc(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8)

    /**
     * The `bearer` arg is an MCP-edge transport detail (it carries the OBO token
     * for RLS); it is NOT part of any store DTO. The store deserializes strictly,
     * so it must be stripped from the forwarded body or every authenticated call
     * 400s on an unknown key.
     */
    private fun JsonObject.body(): JsonObject = JsonObject(this.filterKeys { it != "bearer" })

    fun getContext(args: JsonObject) = ForwardSpec(HttpMethod.Post, "/getContext", args.body())

    fun search(args: JsonObject) = ForwardSpec(HttpMethod.Post, "/query", args.body())

    fun findSimilar(args: JsonObject) = ForwardSpec(HttpMethod.Post, "/findSimilar", args.body())

    fun getPage(args: JsonObject) =
        ForwardSpec(HttpMethod.Get, "/pages/${args.str("id")}?notebookId=${args.str("notebookId").enc()}")

    fun traverse(args: JsonObject) = ForwardSpec(HttpMethod.Post, "/traverse", args.body())

    fun getSource(args: JsonObject) =
        ForwardSpec(HttpMethod.Get, "/sources/${args.str("id")}?notebookId=${args.str("notebookId").enc()}")

    fun listNotebooks() = ForwardSpec(HttpMethod.Get, "/notebooks")

    fun createNotebook(args: JsonObject) = ForwardSpec(HttpMethod.Post, "/notebooks", args.body())

    fun addToNotebook(args: JsonObject) =
        ForwardSpec(HttpMethod.Post, "/notebooks/${args.str("notebookId")}/members", args.body())
}
