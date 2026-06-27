@file:Suppress("detekt:MagicNumber", "detekt:UnusedPrivateProperty")

package org.tatrman.kantheon.hebe.memory.indexer

import org.tatrman.kantheon.hebe.api.MemoryCategory
import org.tatrman.kantheon.hebe.api.MemoryScope
import org.tatrman.kantheon.hebe.memory.chunker.Chunk
import org.tatrman.kantheon.hebe.memory.chunker.Chunker
import org.tatrman.kantheon.hebe.memory.db.Db
import org.tatrman.kantheon.hebe.memory.embeddings.EmbeddingProvider
import org.tatrman.kantheon.hebe.memory.workspace.WorkspacePath
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Indexer(
    private val db: Db,
    private val embeddings: EmbeddingProvider,
) {
    companion object {
        private const val PARAM_DOC_PATH = 1
        private const val PARAM_DOC_CONTENT = 2
        private const val PARAM_SCOPE = 3
        private const val PARAM_CATEGORY = 4
        private const val PARAM_TS = 5
        private const val PARAM_BYTE_SIZE = 6
        private const val PARAM_HASH = 7
        private const val PARAM_CHUNK_IDX = 2
        private const val PARAM_CHUNK_CONTENT = 3
        private const val PARAM_TOKEN_COUNT = 4
    }

    suspend fun indexDoc(
        path: WorkspacePath,
        content: String,
        scope: MemoryScope = MemoryScope.Default,
        category: MemoryCategory = MemoryCategory.Document,
    ) = withContext(Dispatchers.IO) {
        val hash = sha256(content)
        val now = System.currentTimeMillis()

        if (unchanged(path.value, hash)) return@withContext

        db.dataSource.connection.use { conn ->
            conn.setAutoCommit(false)
            try {
                deleteChunks(path.value, conn)
                deleteVecRows(path.value, conn)
                upsertDoc(path.value, content, scope, category, hash, now, conn)
                val chunks = Chunker.chunk(content)
                insertChunks(path.value, chunks, now, conn)
                insertVecRows(path.value, chunks, conn)
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
                ps.setString(PARAM_DOC_PATH, path)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    return rs.getString(1) == hash
                }
            }
        }
        return false
    }

    private fun deleteChunks(
        path: String,
        conn: java.sql.Connection,
    ) {
        conn.prepareStatement("DELETE FROM memory_chunks WHERE doc_path = ?").use { ps ->
            ps.setString(PARAM_DOC_PATH, path)
            ps.executeUpdate()
        }
    }

    private fun deleteVecRows(
        path: String,
        conn: java.sql.Connection,
    ) {
        conn.prepareStatement("DELETE FROM memory_chunks_vec WHERE doc_path = ?").use { ps ->
            ps.setString(PARAM_DOC_PATH, path)
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
        ts: Long,
        conn: java.sql.Connection,
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
                ps.setString(PARAM_DOC_PATH, path)
                ps.setString(PARAM_DOC_CONTENT, content)
                ps.setString(PARAM_SCOPE, scope.name)
                ps.setString(PARAM_CATEGORY, category.name)
                ps.setLong(PARAM_TS, ts)
                ps.setLong(PARAM_BYTE_SIZE, content.length.toLong())
                ps.setString(PARAM_HASH, hash)
                ps.executeUpdate()
            }
    }

    private fun insertChunks(
        path: String,
        chunks: List<Chunk>,
        ts: Long,
        conn: java.sql.Connection,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO memory_chunks (doc_path, chunk_idx, content, token_count, ts)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                for (chunk in chunks) {
                    ps.setString(PARAM_DOC_PATH, path)
                    ps.setInt(PARAM_CHUNK_IDX, chunk.index)
                    ps.setString(PARAM_CHUNK_CONTENT, chunk.content)
                    ps.setInt(PARAM_TOKEN_COUNT, chunk.tokenCount)
                    ps.setLong(PARAM_TS, ts)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
    }

    private suspend fun insertVecRows(
        path: String,
        chunks: List<Chunk>,
        conn: java.sql.Connection,
    ) {
        val texts = chunks.map { it.content }
        val vectors = embeddings.embed(texts)
        conn
            .prepareStatement(
                """
                INSERT INTO memory_chunks_vec (doc_path, chunk_idx, embedding)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                for ((i, chunk) in chunks.withIndex()) {
                    ps.setString(PARAM_DOC_PATH, path)
                    ps.setInt(PARAM_CHUNK_IDX, chunk.index)
                    ps.setBytes(3, floatArrayToBytes(vectors[i]))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
    }

    private fun floatArrayToBytes(vec: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(vec.size * 4)
        for (v in vec) buffer.putFloat(v)
        return buffer.array()
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray())
        return hash.toHexString()
    }

    private fun ByteArray.toHexString(): String {
        val sb = StringBuilder()
        for (b in this) sb.append("%02x".format(b))
        return sb.toString()
    }
}
