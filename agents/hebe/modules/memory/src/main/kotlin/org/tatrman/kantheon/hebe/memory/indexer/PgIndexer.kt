@file:Suppress("detekt:MagicNumber")

package org.tatrman.kantheon.hebe.memory.indexer

import org.tatrman.kantheon.hebe.api.MemoryCategory
import org.tatrman.kantheon.hebe.api.MemoryScope
import org.tatrman.kantheon.hebe.memory.chunker.Chunk
import org.tatrman.kantheon.hebe.memory.chunker.Chunker
import org.tatrman.kantheon.hebe.memory.db.PgDb
import org.tatrman.kantheon.hebe.memory.embeddings.EmbeddingProvider
import org.tatrman.kantheon.hebe.memory.search.PgMemorySql
import org.tatrman.kantheon.hebe.memory.workspace.WorkspacePath
import java.security.MessageDigest
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Postgres counterpart of [Indexer]. Same contract: skip-if-unchanged (sha256),
 * then atomically replace the doc's chunks. The `tsv` full-text column is a
 * generated column (maintained by Postgres on insert — no trigger plumbing like the
 * SQLite FTS5 mirror), and the `embedding` is written as a pgvector `::vector`
 * literal. All writes run in one raw-JDBC transaction (the doc/chunk/embedding write
 * is the search projection — Exposed cannot express the `vector` cast, exactly as
 * the SQLite indexer keeps its vec0 writes in raw SQL).
 */
class PgIndexer(
    private val db: PgDb,
    private val embeddings: EmbeddingProvider,
) {
    suspend fun indexDoc(
        path: WorkspacePath,
        content: String,
        scope: MemoryScope = MemoryScope.Default,
        category: MemoryCategory = MemoryCategory.Document,
    ) = withContext(Dispatchers.IO) {
        val hash = sha256(content)
        if (unchanged(path.value, hash)) return@withContext

        val chunks = Chunker.chunk(content)
        val vectors = embeddings.embed(chunks.map { it.content })
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        db.dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                deleteChunks(path.value, conn)
                upsertDoc(path.value, content, scope, category, hash, now, conn)
                insertChunks(path.value, chunks, vectors, now, conn)
                conn.commit()
            } catch (
                @Suppress("TooGenericExceptionCaught") ex: Exception,
            ) {
                conn.rollback()
                throw ex
            }
        }
    }

    private fun unchanged(
        path: String,
        hash: String,
    ): Boolean {
        db.dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT hash_sha256 FROM memory_docs WHERE path = ?").use { ps ->
                ps.setString(1, path)
                val rs = ps.executeQuery()
                if (rs.next()) return rs.getString(1) == hash
            }
        }
        return false
    }

    private fun deleteChunks(
        path: String,
        conn: Connection,
    ) {
        conn.prepareStatement("DELETE FROM memory_chunks WHERE doc_path = ?").use { ps ->
            ps.setString(1, path)
            ps.executeUpdate()
        }
    }

    @Suppress("LongParameterList")
    private fun upsertDoc(
        path: String,
        content: String,
        scope: MemoryScope,
        category: MemoryCategory,
        hash: String,
        ts: OffsetDateTime,
        conn: Connection,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO memory_docs (path, content, scope, category, ts, byte_size, hash_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(path) DO UPDATE SET
                    content = excluded.content,
                    scope = excluded.scope,
                    category = excluded.category,
                    ts = excluded.ts,
                    byte_size = excluded.byte_size,
                    hash_sha256 = excluded.hash_sha256
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, path)
                ps.setString(2, content)
                ps.setString(3, scope.name)
                ps.setString(4, category.name)
                ps.setObject(5, ts)
                ps.setInt(6, content.length)
                ps.setString(7, hash)
                ps.executeUpdate()
            }
    }

    private fun insertChunks(
        path: String,
        chunks: List<Chunk>,
        vectors: List<FloatArray>,
        ts: OffsetDateTime,
        conn: Connection,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO memory_chunks (doc_path, chunk_idx, content, token_count, ts, embedding)
                VALUES (?, ?, ?, ?, ?, ?::vector)
                """.trimIndent(),
            ).use { ps ->
                for ((i, chunk) in chunks.withIndex()) {
                    ps.setString(1, path)
                    ps.setInt(2, chunk.index)
                    ps.setString(3, chunk.content)
                    ps.setInt(4, chunk.tokenCount)
                    ps.setObject(5, ts)
                    ps.setString(6, PgMemorySql.vectorLiteral(vectors[i]))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
    }

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
