package org.tatrman.kantheon.hebe.tools.builtin.search

import org.tatrman.kantheon.hebe.tools.builtin.builtinHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class BraveSearchProvider(
    private val apiKey: String,
) : WebSearchProvider {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val client = builtinHttpClient

    override val name = "brave"

    override suspend fun search(
        query: String,
        k: Int,
    ): List<SearchHit> {
        logger.debug("brave search query={} k={}", query, k)
        return try {
            val resp =
                client.get("https://api.search.brave.com/res/v1/web/search") {
                    header("X-Subscription-Token", apiKey)
                    url {
                        parameters.append("q", query)
                        parameters.append("count", k.toString())
                    }
                }
            val body = resp.bodyAsText()
            parseBraveResponse(body)
        } catch (e: Exception) {
            logger.warn("brave search failed: {}", e.message)
            emptyList()
        }
    }

    private fun parseBraveResponse(body: String): List<SearchHit> {
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            val results = json["web"]?.jsonObject?.get("results")?.jsonArray ?: return emptyList()
            results.mapIndexed { idx, element ->
                val obj = element.jsonObject
                SearchHit(
                    title = obj["title"]?.jsonPrimitive?.content ?: "",
                    url = obj["url"]?.jsonPrimitive?.content ?: "",
                    snippet = obj["description"]?.jsonPrimitive?.content ?: "",
                    rank = idx + 1,
                    source = "brave",
                )
            }
        } catch (e: Exception) {
            logger.warn("failed to parse brave response: {}", e.message)
            emptyList()
        }
    }
}
