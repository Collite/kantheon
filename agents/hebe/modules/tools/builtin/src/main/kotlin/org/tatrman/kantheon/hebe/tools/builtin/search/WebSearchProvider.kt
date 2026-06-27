package org.tatrman.kantheon.hebe.tools.builtin.search

data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val rank: Int,
    val source: String,
)

interface WebSearchProvider {
    val name: String

    suspend fun search(
        query: String,
        k: Int = 10,
    ): List<SearchHit>
}
