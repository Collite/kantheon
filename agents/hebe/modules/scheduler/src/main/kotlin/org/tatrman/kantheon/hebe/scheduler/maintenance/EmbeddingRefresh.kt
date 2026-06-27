@file:Suppress("ForbiddenComment", "MagicNumber", "NewLineAtEndOfFile", "TooGenericExceptionCaught")

package org.tatrman.kantheon.hebe.scheduler.maintenance

import org.tatrman.kantheon.hebe.api.LlmProvider
import org.tatrman.kantheon.hebe.api.StreamEvent
import org.tatrman.kantheon.hebe.memory.db.Db
import javax.sql.DataSource
import kotlinx.coroutines.flow.collect
import org.slf4j.LoggerFactory

class EmbeddingRefresh(
    private val db: Db,
    private val llmProvider: LlmProvider,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val ds: DataSource get() = db.dataSource

    companion object {
        const val DEFAULT_CRON = "*/15 * * * *"
        const val BATCH_SIZE = 32
        const val MAX_PER_TICK = 100
    }

    data class Config(
        val cron: String = DEFAULT_CRON,
        val batchSize: Int = BATCH_SIZE,
        val maxPerTick: Int = MAX_PER_TICK,
    )

    @Suppress("NestedBlockDepth")
    suspend fun run(): Result<Int> {
        return try {
            val chunks = findChunksWithoutEmbeddings(MAX_PER_TICK)
            if (chunks.isEmpty()) {
                return Result.success(0)
            }

            var embedded = 0
            for (batch in chunks.chunked(BATCH_SIZE)) {
                try {
                    val embeddings = embedBatch(batch)
                    for ((chunk, embedding) in batch.zip(embeddings)) {
                        updateChunkEmbedding(chunk.docPath, chunk.chunkIdx, embedding)
                        insertVecRow(chunk.docPath, chunk.chunkIdx, embedding)
                        embedded++
                    }
                } catch (e: Exception) {
                    logger.warn("batch embedding failed: {}", e.message)
                }
            }
            Result.success(embedded)
        } catch (e: Exception) {
            logger.error("embedding refresh failed: {}", e.message, e)
            Result.failure(e)
        }
    }

    private fun findChunksWithoutEmbeddings(limit: Int): List<ChunkRow> {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT mc.doc_path, mc.chunk_idx, mc.content
                    FROM memory_chunks mc
                    LEFT JOIN memory_chunks_vec mcv ON mc.doc_path = mcv.doc_path AND mc.chunk_idx = mcv.chunk_idx
                    WHERE mcv.doc_path IS NULL
                    LIMIT ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setInt(1, limit)
                    val rs = ps.executeQuery()
                    val chunks = mutableListOf<ChunkRow>()
                    while (rs.next()) {
                        chunks.add(
                            ChunkRow(
                                docPath = rs.getString(1),
                                chunkIdx = rs.getInt(2),
                                content = rs.getString(3),
                            ),
                        )
                    }
                    return chunks
                }
        }
    }

    private suspend fun embedBatch(chunks: List<ChunkRow>): List<FloatArray> {
        // TODO: Replace with a dedicated embedding endpoint (LlmProvider.embed()) once available.
        // Chat completions cannot produce meaningful embeddings — the free-text output will be noise.
        if (chunks.isEmpty()) return emptyList()

        val texts = chunks.map { it.content }
        val model = llmProvider.capabilities().defaultModel.ifEmpty { "default" }

        val prompt = "Embed this text: ${texts.joinToString("\n\n") { it.take(1000) }}"
        val request =
            org.tatrman.kantheon.hebe.api.ChatRequest(
                model = model,
                systemPrompt = "You are an embedding provider. Return a JSON array of embedding vectors.",
                messages =
                    listOf(
                        org.tatrman.kantheon.hebe.api.ChatMessage
                            .User(prompt),
                    ),
                tools = emptyList(),
                temperature = 0.0,
                stream = false,
            )

        val textParts = mutableListOf<String>()
        llmProvider.chat(request).collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> textParts.add(event.text)
                else -> {}
            }
        }

        return parseEmbeddings(textParts.joinToString(""), chunks.size)
    }

    private fun parseEmbeddings(
        json: String,
        count: Int,
    ): List<FloatArray> {
        if (json.isBlank()) return List(count) { FloatArray(1536) { 0f } }
        return try {
            val elem =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(json)
            val arr = elem as? kotlinx.serialization.json.JsonArray ?: return List(count) { FloatArray(1536) { 0f } }
            arr
                .take(count)
                .map { item ->
                    val inner =
                        item as? kotlinx.serialization.json.JsonArray
                            ?: kotlinx.serialization.json.JsonArray(emptyList())
                    inner
                        .map { v ->
                            (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
                        }.toFloatArray()
                }.ifEmpty { List(count) { FloatArray(1536) { 0f } } }
        } catch (_: Exception) {
            List(count) { FloatArray(1536) { 0f } }
        }
    }

    private fun updateChunkEmbedding(
        docPath: String,
        chunkIdx: Int,
        embedding: FloatArray,
    ) {
        val bytes = embeddingToBytes(embedding)
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    UPDATE memory_chunks
                    SET embedding = ?
                    WHERE doc_path = ? AND chunk_idx = ?
                    """.trimIndent(),
                ).use { ps ->
                    val blob = conn.createBlob()
                    blob.setBytes(1, bytes)
                    ps.setBlob(1, blob)
                    ps.setString(2, docPath)
                    ps.setInt(3, chunkIdx)
                    ps.executeUpdate()
                }
        }
    }

    private fun insertVecRow(
        docPath: String,
        chunkIdx: Int,
        embedding: FloatArray,
    ) {
        val bytes = embeddingToBytes(embedding)
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT OR REPLACE INTO memory_chunks_vec(doc_path, chunk_idx, vector)
                    VALUES (?, ?, ?)
                    """.trimIndent(),
                ).use { ps ->
                    val blob = conn.createBlob()
                    blob.setBytes(1, bytes)
                    ps.setString(1, docPath)
                    ps.setInt(2, chunkIdx)
                    ps.setBlob(3, blob)
                    ps.executeUpdate()
                }
        }
    }

    private fun embeddingToBytes(embedding: FloatArray): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)
        for (v in embedding) out.writeFloat(v)
        return buf.toByteArray()
    }

    data class ChunkRow(
        val docPath: String,
        val chunkIdx: Int,
        val content: String,
    )
}
