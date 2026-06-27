package org.tatrman.kantheon.hebe.tools.builtin.search

import org.tatrman.kantheon.hebe.tools.builtin.builtinHttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.slf4j.LoggerFactory

class DuckDuckGoSearchProvider : WebSearchProvider {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val client = builtinHttpClient

    override val name = "duckduckgo"

    override suspend fun search(
        query: String,
        k: Int,
    ): List<SearchHit> {
        logger.debug("ddg search query={} k={}", query, k)
        return try {
            val resp =
                client.get("https://html.duckduckgo.com/html") {
                    url {
                        parameters.append("q", query)
                    }
                }
            val body = resp.bodyAsText()
            parseDdgoResponse(body, k)
        } catch (e: Exception) {
            logger.warn("ddg search failed: {}", e.message)
            emptyList()
        }
    }

    private fun parseDdgoResponse(
        html: String,
        k: Int,
    ): List<SearchHit> {
        val results = mutableListOf<SearchHit>()
        val resultRegex = Regex("""<a class="result__a" href="([^"]+)">([^<]+)</a>""")
        val snippetRegex = Regex("""<a class="result__snippet"[^>]*>([^<]+)</a>""")

        val resultMatches = resultRegex.findAll(html).take(k).toList()
        val snippetMatches = snippetRegex.findAll(html).take(k).toList()

        resultMatches.forEachIndexed { idx, match ->
            val url = match.groupValues[1]
            val title = match.groupValues[2]
            val snippet = snippetMatches.getOrNull(idx)?.groupValues?.getOrNull(1) ?: ""
            results.add(SearchHit(title, url, snippet, idx + 1, "duckduckgo"))
        }
        return results
    }
}
