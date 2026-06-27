package org.tatrman.kallimachos.service

import org.slf4j.LoggerFactory
import org.tatrman.kallimachos.adapters.relational.RelationalPort
import org.tatrman.kallimachos.adapters.vector.PartVectorRecord
import org.tatrman.kallimachos.adapters.vector.VectorPort
import org.tatrman.kallimachos.corpus.EmbeddingStatus
import org.tatrman.kallimachos.embeddings.EmbeddingsPort
import org.tatrman.kallimachos.tx.Transactor

/**
 * The EMBED operation + the backfill of the non-atomic embedding edge
 * (architecture §13). Ingestion commits parts with `embedding_status = PENDING`;
 * this service embeds them via Prometheus (out-of-band) and flips them to `OK`.
 * The embed call is OUTSIDE the transaction (no HTTP under a held tx); only the
 * vector upsert + status flip are transactional. A failed embed leaves the
 * source `PENDING` for a later backfill — never a hard error.
 */
class EmbeddingService(
    private val relational: RelationalPort,
    private val vector: VectorPort,
    private val embeddings: EmbeddingsPort,
    private val transactor: Transactor,
) {
    private val log = LoggerFactory.getLogger(EmbeddingService::class.java)

    suspend fun embedSource(sourceId: Long): Boolean {
        val parts = relational.partsOfSource(sourceId)
        if (parts.isEmpty()) {
            transactor.inTransaction { relational.setEmbeddingStatus(sourceId, EmbeddingStatus.OK) }
            return true
        }
        return try {
            val result = embeddings.embed(parts.map { it.contentText })
            // Guard the positional pairing — a provider that returns the wrong
            // count must not mis-align vectors to parts (or throw IOOBE silently).
            require(result.vectors.size == parts.size) {
                "embeddings returned ${result.vectors.size} vectors for ${parts.size} parts (source $sourceId)"
            }
            val records =
                parts.mapIndexed { i, p ->
                    PartVectorRecord(
                        partId = p.id,
                        sourceId = p.sourceId,
                        vector = result.vectors[i],
                        modelId = result.modelId,
                        modelVersion = result.modelVersion,
                    )
                }
            transactor.inTransaction {
                vector.upsert(records)
                relational.setEmbeddingStatus(sourceId, EmbeddingStatus.OK)
            }
            true
        } catch (e: Exception) {
            // Non-atomic edge: the source stays PENDING for the backfill — but the
            // failure is logged, not swallowed, so a persistent fault is visible.
            log.warn("embed failed for source {} — staying PENDING for backfill: {}", sourceId, e.message)
            false
        }
    }

    /** Embed all PENDING sources (idempotent — re-running re-embeds by key). */
    suspend fun backfillEmbeddings(limit: Int = DEFAULT_BACKFILL): Int {
        var embedded = 0
        for (id in relational.pendingEmbeddingSourceIds(limit)) {
            if (embedSource(id)) embedded++
        }
        return embedded
    }

    companion object {
        const val DEFAULT_BACKFILL = 100
    }
}
