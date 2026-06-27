@file:Suppress("detekt:MagicNumber")

package org.tatrman.kantheon.hebe.memory.search

import org.tatrman.kantheon.hebe.api.MemoryCategory
import org.tatrman.kantheon.hebe.api.MemoryHit
import org.tatrman.kantheon.hebe.api.MemoryScope
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.memory.db.PgDb
import org.tatrman.kantheon.hebe.memory.embeddings.EmbeddingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Postgres counterpart of [Searcher]. Fetches `k * 4` full-text candidates
 * (`ts_rank_cd`) and `k * 4` vector candidates (pgvector cosine `<=>`) via the
 * [PgMemorySql] templates, then fuses them through the **same** [Rrf] (k₀ = 60) as
 * the SQLite backend — only the two candidate queries differ (architecture §5.2),
 * which is the entire basis of the RRF parity contract. Vector failures degrade to
 * full-text-only, identical to [Searcher].
 */
class PgSearcher(
    private val db: PgDb,
    private val embeddings: EmbeddingProvider,
    private val observer: Observer? = null,
) {
    companion object {
        private const val CANDIDATE_FACTOR = 4
        private const val K0 = 60
    }

    suspend fun search(
        query: String,
        k: Int = 10,
        scope: MemoryScope = MemoryScope.Default,
        categories: Set<MemoryCategory>? = null,
    ): List<MemoryHit> =
        withContext(Dispatchers.IO) {
            val span = observer?.span("memory.search", mapOf("query.length" to query.length, "k" to k))
            try {
                val limit = k * CANDIDATE_FACTOR
                val ftsHits = ftsQuery(query, limit, scope, categories)
                val vecHits =
                    try {
                        val queryVec = embeddings.embed(listOf(query)).first()
                        vecQuery(queryVec, limit, scope, categories)
                    } catch (
                        @Suppress("TooGenericExceptionCaught") ex: Exception,
                    ) {
                        System.err.println("Warning: vec search failed: ${ex.message}")
                        emptyList()
                    }
                val result = Rrf.fuse(ftsHits, vecHits, k0 = K0, k = k)
                span?.setAttribute("results", result.size)
                result
            } finally {
                span?.close()
            }
        }

    private fun ftsQuery(
        query: String,
        limit: Int,
        scope: MemoryScope,
        categories: Set<MemoryCategory>?,
    ): List<Rrf.RankedHit> {
        val hits = mutableListOf<Rrf.RankedHit>()
        db.dataSource.connection.use { conn ->
            conn.prepareStatement(PgMemorySql.ftsCandidateSql(categories)).use { ps ->
                ps.setString(1, query)
                ps.setString(2, scope.name)
                ps.setString(3, query)
                ps.setInt(4, limit)
                readHits(ps, hits)
            }
        }
        return hits
    }

    private fun vecQuery(
        queryVec: FloatArray,
        limit: Int,
        scope: MemoryScope,
        categories: Set<MemoryCategory>?,
    ): List<Rrf.RankedHit> {
        val hits = mutableListOf<Rrf.RankedHit>()
        db.dataSource.connection.use { conn ->
            conn.prepareStatement(PgMemorySql.vecCandidateSql(categories)).use { ps ->
                ps.setString(1, scope.name)
                ps.setString(2, PgMemorySql.vectorLiteral(queryVec))
                ps.setInt(3, limit)
                readHits(ps, hits)
            }
        }
        return hits
    }

    private fun readHits(
        ps: java.sql.PreparedStatement,
        out: MutableList<Rrf.RankedHit>,
    ) {
        val rs = ps.executeQuery()
        while (rs.next()) {
            out.add(Rrf.RankedHit(rs.getString(1), rs.getInt(2), rs.getString(3) ?: ""))
        }
    }
}
