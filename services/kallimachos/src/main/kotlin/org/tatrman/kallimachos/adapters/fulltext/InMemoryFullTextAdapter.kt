package org.tatrman.kallimachos.adapters.fulltext

import org.tatrman.kallimachos.model.MetadataList
import org.tatrman.kallimachos.model.MetadataSingle
import org.tatrman.kallimachos.model.MetadataValue
import org.tatrman.kallimachos.tx.SnapshotStore

/**
 * In-memory full-text plane — the wired adapter for the single-PG-not-yet-live
 * profile and the service-spec fake. Approximates `ts_rank_cd` with a token-
 * overlap score (count of distinct query tokens present in the part), which is
 * monotone enough to prove ranking + mart-scoping; the real Postgres adapter
 * uses `to_tsvector('simple', …) @@ plainto_tsquery` + `ts_rank_cd`.
 */
class InMemoryFullTextAdapter :
    FullTextPort,
    SnapshotStore {
    private val index = linkedMapOf<Long, IndexedPart>()

    override fun index(part: IndexedPart) {
        index[part.partId] = part
    }

    override fun search(
        query: FullTextQuery,
        allowedSourceIds: Set<Long>?,
    ): List<FullTextHit> {
        val queryTokens = (tokenize(query.text ?: "") + query.keywords.flatMap { tokenize(it) }).toSet()
        return index.values
            .asSequence()
            .filter { allowedSourceIds == null || it.sourceId in allowedSourceIds }
            .filter { matchesMetadata(it.metadata, query.metadataFilter) }
            .map { part ->
                val partTokens = tokenize(part.contentText).toSet()
                val overlap = if (queryTokens.isEmpty()) 0 else queryTokens.count { it in partTokens }
                part to overlap
            }
            // An empty query (no text, no keywords) lists the mart; a non-empty
            // query keeps only parts that match at least one token.
            .filter { (_, overlap) -> queryTokens.isEmpty() || overlap > 0 }
            .sortedWith(compareByDescending<Pair<IndexedPart, Int>> { it.second }.thenBy { it.first.partId })
            .take(query.limit.coerceAtLeast(1))
            .map { (part, overlap) ->
                val denom = queryTokens.size.coerceAtLeast(1)
                FullTextHit(
                    partId = part.partId,
                    sourceId = part.sourceId,
                    score = overlap.toDouble() / denom,
                    snippet = part.contentText.take(200),
                    metadata = part.metadata,
                )
            }.toList()
    }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(index)
        return {
            index.clear()
            index.putAll(copy)
        }
    }

    private fun tokenize(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    private fun matchesMetadata(
        meta: Map<String, MetadataValue>,
        filter: Map<String, MetadataValue>,
    ): Boolean =
        filter.all { (k, want) ->
            val have = meta[k] ?: return@all false
            valuesOf(have).any { it in valuesOf(want) }
        }

    private fun valuesOf(v: MetadataValue): Set<String> =
        when (v) {
            is MetadataSingle -> setOf(v.value)
            is MetadataList -> v.values.toSet()
        }
}
