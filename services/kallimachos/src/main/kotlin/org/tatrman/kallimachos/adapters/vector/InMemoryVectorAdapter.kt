package org.tatrman.kallimachos.adapters.vector

import kotlin.math.sqrt

/**
 * In-memory vector plane — the wired adapter for the single-PG-not-yet-live
 * profile and the spec double. Cosine KNN approximates pgvector's
 * `vector_cosine_ops`; the real `PgVectorAdapter` runs the ANN index. Keyed by
 * `(partId, modelId, modelVersion)` — a re-embed with the same key overwrites
 * (idempotent), a new model version coexists.
 */
class InMemoryVectorAdapter : VectorPort {
    private data class Key(
        val partId: Long,
        val modelId: String,
        val modelVersion: String,
    )

    private val store = linkedMapOf<Key, PartVectorRecord>()

    override fun upsert(vectors: List<PartVectorRecord>) {
        vectors.forEach { store[Key(it.partId, it.modelId, it.modelVersion)] = it }
    }

    override fun knn(
        query: FloatArray,
        k: Int,
        allowedSourceIds: Set<Long>?,
    ): List<VectorHit> =
        store.values
            .asSequence()
            .filter { allowedSourceIds == null || it.sourceId in allowedSourceIds }
            .map { VectorHit(it.partId, it.sourceId, cosine(query, it.vector)) }
            .sortedWith(compareByDescending<VectorHit> { it.score }.thenBy { it.partId })
            .take(k.coerceAtLeast(1))
            .toList()

    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            na += a[i].toDouble() * a[i].toDouble()
            nb += b[i].toDouble() * b[i].toDouble()
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0.0 else dot / denom
    }
}
