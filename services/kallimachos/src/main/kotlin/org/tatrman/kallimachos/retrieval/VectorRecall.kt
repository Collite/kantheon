package org.tatrman.kallimachos.retrieval

import org.tatrman.kallimachos.adapters.vector.VectorHit
import org.tatrman.kallimachos.adapters.vector.VectorPort
import org.tatrman.kallimachos.embeddings.EmbeddingsPort

/**
 * The vector recall-booster primitive (architecture §8). `getContext` (Stage 2.3)
 * leads with the graph and uses this to add recall for chunks the graph didn't
 * reach. Embeds the query in the conformed space, then KNN over the mart's
 * member sources.
 */
class VectorRecall(
    private val vector: VectorPort,
    private val embeddings: EmbeddingsPort,
) {
    suspend fun recall(
        queryText: String,
        k: Int,
        allowedSourceIds: Set<Long>?,
    ): List<VectorHit> {
        if (queryText.isBlank()) return emptyList()
        val embedding = embeddings.embed(listOf(queryText)).vectors.firstOrNull() ?: return emptyList()
        return vector.knn(embedding, k, allowedSourceIds)
    }
}
