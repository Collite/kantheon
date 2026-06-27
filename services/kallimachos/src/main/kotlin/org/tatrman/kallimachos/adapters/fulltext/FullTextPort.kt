package org.tatrman.kallimachos.adapters.fulltext

import org.tatrman.kallimachos.model.MetadataValue

/**
 * The full-text plane (contracts §3 — `parts.content_tsv`). Ported in spirit
 * from doc-store's `FullTextPort` / `PostgresFullTextAdapter`, reshaped to the
 * mart-scoped Kleio query. Search is ALWAYS mart-scoped at the service layer:
 * `allowedSourceIds` restricts hits to a notebook's member sources (null only
 * for the admin "*" scope).
 */
interface FullTextPort {
    fun index(part: IndexedPart)

    fun search(
        query: FullTextQuery,
        allowedSourceIds: Set<Long>?,
    ): List<FullTextHit>
}

data class IndexedPart(
    val partId: Long,
    val sourceId: Long,
    val contentText: String,
    val metadata: Map<String, MetadataValue> = emptyMap(),
)

data class FullTextQuery(
    val text: String? = null,
    val keywords: List<String> = emptyList(),
    val metadataFilter: Map<String, MetadataValue> = emptyMap(),
    val limit: Int = 10,
)

data class FullTextHit(
    val partId: Long,
    val sourceId: Long,
    val score: Double,
    val snippet: String,
    val metadata: Map<String, MetadataValue> = emptyMap(),
)
