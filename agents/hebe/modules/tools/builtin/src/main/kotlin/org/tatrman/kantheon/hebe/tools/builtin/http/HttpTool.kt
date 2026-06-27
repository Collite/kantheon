package org.tatrman.kantheon.hebe.tools.builtin.http

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.SecretLookup
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import org.tatrman.kantheon.hebe.tools.builtin.builtinHttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class HttpTool(
    private val secretLookup: SecretLookup,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val client = builtinHttpClient

    override val spec =
        ToolSpec(
            name = "http",
            description = "Make an HTTP request. Risk Medium. Domain allowlist enforced by security policy.",
            schema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "required",
                        buildJsonArray {
                            add(JsonPrimitive("method"))
                            add(JsonPrimitive("url"))
                        },
                    )
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "method",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("HTTP method: GET, POST, PUT, DELETE, PATCH"))
                                },
                            )
                            put(
                                "url",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Target URL"))
                                },
                            )
                            put(
                                "headers",
                                buildJsonObject {
                                    put("type", JsonPrimitive("object"))
                                    put("description", JsonPrimitive("Optional request headers"))
                                },
                            )
                            put(
                                "body",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Optional request body"))
                                },
                            )
                            put(
                                "secret_header_name",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Header name for secret injection (e.g. Authorization)"))
                                },
                            )
                            put(
                                "secret_name",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Secret name to inject into secret_header_name"))
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
        val methodStr =
            args["method"]?.jsonPrimitive?.content?.uppercase()
                ?: return ToolResult.Err("missing required argument: method")
        val url =
            args["url"]?.jsonPrimitive?.content
                ?: return ToolResult.Err("missing required argument: url")

        val headersObj = (args["headers"] as? JsonObject)
        val requestBody = args["body"]?.jsonPrimitive?.content
        val secretHeaderName = args["secret_header_name"]?.jsonPrimitive?.content
        val secretName = args["secret_name"]?.jsonPrimitive?.content

        logger.debug("http method={} url={}", methodStr, url)

        val finalHeaders =
            buildJsonObject {
                headersObj?.forEach { (k, v) ->
                    if (v is JsonPrimitive) put(k, v)
                }
                if (secretHeaderName != null && secretName != null) {
                    val secretValue = secretLookup.secret(secretName)
                    if (secretValue != null) {
                        put(secretHeaderName, JsonPrimitive(secretValue))
                    }
                }
            }

        return try {
            val httpResponse: Triple<Int, String, Headers>
            when (methodStr) {
                "GET" -> {
                    val resp =
                        client.get(url) {
                            applyHeaders(finalHeaders)
                        }
                    httpResponse = Triple(resp.status.value, resp.bodyAsText(), resp.headers)
                }
                "POST" -> {
                    val resp =
                        client.post(url) {
                            applyHeaders(finalHeaders)
                            requestBody?.let { setBody(it) }
                        }
                    httpResponse = Triple(resp.status.value, resp.bodyAsText(), resp.headers)
                }
                "PUT" -> {
                    val resp =
                        client.put(url) {
                            applyHeaders(finalHeaders)
                            requestBody?.let { setBody(it) }
                        }
                    httpResponse = Triple(resp.status.value, resp.bodyAsText(), resp.headers)
                }
                "DELETE" -> {
                    val resp =
                        client.delete(url) {
                            applyHeaders(finalHeaders)
                        }
                    httpResponse = Triple(resp.status.value, resp.bodyAsText(), resp.headers)
                }
                "PATCH" -> {
                    val resp =
                        client.patch(url) {
                            applyHeaders(finalHeaders)
                            requestBody?.let { setBody(it) }
                        }
                    httpResponse = Triple(resp.status.value, resp.bodyAsText(), resp.headers)
                }
                else -> return ToolResult.Err("unsupported HTTP method: $methodStr")
            }

            val truncated = truncate(httpResponse.second, 1_000_000)
            val responseHeaders =
                buildJsonObject {
                    httpResponse.third.forEach { name, values ->
                        put(name, JsonPrimitive(values.joinToString(",")))
                    }
                }
            ToolResult.Ok(
                buildJsonObject {
                    put("status", JsonPrimitive(httpResponse.first))
                    put("body", JsonPrimitive(truncated))
                    put("headers", responseHeaders)
                },
            )
        } catch (e: Exception) {
            ToolResult.Err("http error: ${e.message}")
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyHeaders(jsonHeaders: JsonObject) {
        jsonHeaders.forEach { (name, value) ->
            if (value is JsonPrimitive) {
                headers.append(name, value.content)
            }
        }
    }

    private fun truncate(
        s: String,
        maxLen: Int,
    ): String =
        if (s.length > maxLen) {
            s.take(maxLen) + "\n[truncated]"
        } else {
            s
        }
}
