package org.tatrman.kallimachos.adapters.exposed

import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.tatrman.kallimachos.adapters.fulltext.FullTextHit
import org.tatrman.kallimachos.adapters.fulltext.FullTextPort
import org.tatrman.kallimachos.adapters.fulltext.FullTextQuery
import org.tatrman.kallimachos.adapters.fulltext.IndexedPart

/**
 * The live full-text plane on Postgres. Indexing is FREE here — the V2 trigger
 * maintains `parts.content_tsv` on every part insert — so [index] is a no-op in
 * the PG profile (the fan-out still calls it; the in-memory profile needs it).
 * [search] rides raw SQL: `content_tsv @@ plainto_tsquery('simple', ?)` ranked by
 * `ts_rank_cd`, restricted to the mart's member sources. Integration-verified.
 *
 * The mart-scope source-id filter is inlined (server-controlled Longs, never user
 * input); only the query text is bound. Metadata filtering is the in-memory
 * profile's at v1 (parity with doc-store's PG adapter, which also text-only).
 */
class PostgresFullTextAdapter : FullTextPort {
    override fun index(part: IndexedPart) {
        // No-op: `parts.content_tsv` is maintained by the V2 BEFORE-trigger when
        // the relational adapter inserts the part. Kept on the interface so the
        // in-memory profile (which has no trigger) can index explicitly.
    }

    override fun search(
        query: FullTextQuery,
        allowedSourceIds: Set<Long>?,
    ): List<FullTextHit> {
        if (allowedSourceIds != null && allowedSourceIds.isEmpty()) return emptyList()
        val queryString = (listOfNotNull(query.text) + query.keywords).joinToString(" ").trim()
        if (queryString.isBlank()) return emptyList()

        val sourceFilter =
            if (allowedSourceIds == null) "" else "AND p.source_id IN (${allowedSourceIds.joinToString(",")})"
        val limit = query.limit.coerceAtLeast(1)
        val sql =
            "SELECT p.id AS id, p.source_id AS source_id, " +
                "ts_rank_cd(p.content_tsv, q) AS score, left(p.content_text, 200) AS snippet " +
                "FROM parts p, plainto_tsquery('simple', ?) q " +
                "WHERE p.content_tsv @@ q $sourceFilter " +
                "ORDER BY score DESC, p.id ASC LIMIT $limit"

        return TransactionManager.current().exec(
            sql,
            args = listOf(TextColumnType() to queryString),
        ) { rs ->
            val hits = mutableListOf<FullTextHit>()
            while (rs.next()) {
                hits +=
                    FullTextHit(
                        partId = rs.getLong("id"),
                        sourceId = rs.getLong("source_id"),
                        score = rs.getDouble("score"),
                        snippet = rs.getString("snippet") ?: "",
                    )
            }
            hits.toList()
        } ?: emptyList()
    }
}
