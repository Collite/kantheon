@file:Suppress("detekt:MagicNumber", "detekt:UnusedPrivateProperty")

package org.tatrman.kantheon.hebe.memory.search

import org.tatrman.kantheon.hebe.api.MemoryCategory
import org.tatrman.kantheon.hebe.api.MemoryHit
import org.tatrman.kantheon.hebe.api.MemoryScope
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.memory.db.Db
import org.tatrman.kantheon.hebe.memory.embeddings.EmbeddingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Searcher(
    private val db: Db,
    private val embeddings: EmbeddingProvider,
    private val observer: Observer? = null,
) {
    companion object {
        private const val COL_DOC_PATH = 1
        private const val COL_CHUNK_IDX = 2
        private const val COL_SNIPPET = 3
        private const val COL_CONTENT = 3
        private const val DEFAULT_LIMIT = 4
        private const val K0 = 60
        private const val PARAM_QUERY = 1
        private const val PARAM_SCOPE = 2
        private const val PARAM_LIMIT = 3
        private const val PARAM_SCOPE_VEC = 1
        private const val PARAM_LIMIT_VEC = 2
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
                val ftsHits = ftsQuery(query, k * DEFAULT_LIMIT, scope, categories)
                val vecHits =
                    try {
                        val queryVec = embeddings.embed(listOf(query)).first()
                        vecQuery(queryVec, k * DEFAULT_LIMIT, scope, categories)
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
            conn
                .prepareStatement(
                    """
                    SELECT doc_path, chunk_idx,
                           snippet(memory_chunks_fts, 2, '<b>', '</b>', '...', 32) AS snippet
                    FROM memory_chunks_fts
                    WHERE content MATCH ?
                      AND doc_path IN (
                          SELECT path FROM memory_docs WHERE scope = ?
                            ${if (categories != null) "AND category IN (${categories.joinToString { "'${it.name}'" }})" else ""}
                      )
                    ORDER BY rank
                    LIMIT ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(PARAM_QUERY, query)
                    ps.setString(PARAM_SCOPE, scope.name)
                    ps.setInt(PARAM_LIMIT, limit)
                    val rs = ps.executeQuery()
                    while (rs.next()) {
                        hits.add(
                            Rrf.RankedHit(
                                rs.getString(COL_DOC_PATH),
                                rs.getInt(COL_CHUNK_IDX),
                                rs.getString(COL_SNIPPET) ?: "",
                            ),
                        )
                    }
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
            conn
                .prepareStatement(
                    """
                    SELECT mc.doc_path, mc.chunk_idx, mc.content
                    FROM memory_chunks_vec mcv
                    JOIN memory_chunks mc ON mc.doc_path = mcv.doc_path AND mc.chunk_idx = mcv.chunk_idx
                    JOIN memory_docs md ON md.path = mc.doc_path
                    WHERE md.scope = ?
                      ${if (categories != null) "AND md.category IN (${categories.joinToString { "'${it.name}'" }})" else ""}
                      AND vec_distance_cosine(mcv.embedding, ?) < 1.0
                    ORDER BY vec_distance_cosine(mcv.embedding, ?)
                    LIMIT ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, scope.name)
                    ps.setBytes(2, floatArrayToBytes(queryVec))
                    ps.setBytes(3, floatArrayToBytes(queryVec))
                    ps.setInt(4, limit)
                    val rs = ps.executeQuery()
                    while (rs.next()) {
                        hits.add(
                            Rrf.RankedHit(
                                rs.getString(COL_DOC_PATH),
                                rs.getInt(COL_CHUNK_IDX),
                                rs.getString(COL_CONTENT) ?: "",
                            ),
                        )
                    }
                }
        }
        return hits
    }

    private fun floatArrayToBytes(vec: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(vec.size * 4)
        for (v in vec) buffer.putFloat(v)
        return buffer.array()
    }
}
