package org.tatrman.kantheon.hebe.memory.search

import org.tatrman.kantheon.hebe.api.MemoryCategory

/**
 * Pure builders for the two Postgres candidate queries (full-text + vector) that
 * feed the **shared** [Rrf] fusion. Factored out so the query construction is
 * unit-testable as strings (planning-conventions §4: assert generated SQL against a
 * fake; real-Postgres execution is the integration suite). Only these two candidate
 * queries differ from the SQLite backend — the fusion (k₀ = 60) is identical, which
 * is what makes RRF parity provable (architecture §5.2).
 *
 * Text-search config is fixed at `'simple'` (the migration's `tsvector` config,
 * contracts §4.2) and never per-instance. Category names are a closed enum set (not
 * user input) so they are inlined exactly as the SQLite [Searcher] does.
 */
object PgMemorySql {
    /** `to_tsvector('simple', …)` config — must match V2__memory.sql's generated column. */
    const val TS_CONFIG = "simple"

    private fun categoryClause(categories: Set<MemoryCategory>?): String =
        if (categories != null) {
            "AND md.category IN (${categories.joinToString { "'${it.name}'" }})"
        } else {
            ""
        }

    /**
     * Full-text candidates ranked by `ts_rank_cd`. Params (in order): query (rank),
     * scope, query (match), limit.
     */
    fun ftsCandidateSql(categories: Set<MemoryCategory>?): String =
        """
        SELECT mc.doc_path, mc.chunk_idx, mc.content,
               ts_rank_cd(mc.tsv, plainto_tsquery('$TS_CONFIG', ?)) AS rank
        FROM memory_chunks mc
        JOIN memory_docs md ON md.path = mc.doc_path
        WHERE md.scope = ?
          ${categoryClause(categories)}
          AND mc.tsv @@ plainto_tsquery('$TS_CONFIG', ?)
        ORDER BY rank DESC
        LIMIT ?
        """.trimIndent()

    /**
     * Vector candidates ordered by pgvector cosine distance (`<=>`). Params (in
     * order): scope, embedding literal (`[f1,f2,…]::vector`), limit.
     */
    fun vecCandidateSql(categories: Set<MemoryCategory>?): String =
        """
        SELECT mc.doc_path, mc.chunk_idx, mc.content
        FROM memory_chunks mc
        JOIN memory_docs md ON md.path = mc.doc_path
        WHERE md.scope = ?
          ${categoryClause(categories)}
          AND mc.embedding IS NOT NULL
        ORDER BY mc.embedding <=> ?::vector
        LIMIT ?
        """.trimIndent()

    /** pgvector text input format: `[f1,f2,…]` (cast to `vector` in the query). */
    fun vectorLiteral(vec: FloatArray): String = vec.joinToString(",", "[", "]")
}
