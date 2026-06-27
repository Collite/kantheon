package org.tatrman.kallimachos.adapters.vector

/**
 * The vector plane (contracts §1 `PartVector` / §3 `doc_vectors`). Ported in
 * spirit from doc-store's `VectorPort`, reshaped to the conformed single-model
 * corpus. Vectors are keyed by `(partId, modelId, modelVersion)` so a model
 * upgrade is a clean dual-write re-embed (architecture §11).
 *
 * The vector write is the ONLY non-atomic edge of ingestion (the embedding call
 * is out-of-band — `embedding_status = PENDING` + backfill, architecture §13),
 * so the plane lives outside the one-tx fan-out.
 */
interface VectorPort {
    /** Idempotent upsert keyed by `(partId, modelId, modelVersion)`. */
    fun upsert(vectors: List<PartVectorRecord>)

    /** KNN recall (cosine), restricted to a mart's member sources when scoped. */
    fun knn(
        query: FloatArray,
        k: Int,
        allowedSourceIds: Set<Long>?,
    ): List<VectorHit>
}

data class PartVectorRecord(
    val partId: Long,
    val sourceId: Long,
    val vector: FloatArray,
    val modelId: String,
    val modelVersion: String,
)

data class VectorHit(
    val partId: Long,
    val sourceId: Long,
    val score: Double,
)
