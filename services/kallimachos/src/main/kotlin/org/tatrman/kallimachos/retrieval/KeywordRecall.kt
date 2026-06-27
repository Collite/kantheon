package org.tatrman.kallimachos.retrieval

import org.tatrman.kallimachos.adapters.fulltext.FullTextHit
import org.tatrman.kallimachos.adapters.fulltext.FullTextPort
import org.tatrman.kallimachos.adapters.fulltext.FullTextQuery
import org.tatrman.kallimachos.model.MetadataValue

/**
 * The keyword recall-booster (tsvector). One of the two boosters `HybridFusion`
 * fuses on top of the graph lead (architecture §8 step 3). Always mart-scoped.
 */
class KeywordRecall(
    private val fullText: FullTextPort,
) {
    fun recall(
        text: String?,
        keywords: List<String>,
        k: Int,
        allowedSourceIds: Set<Long>?,
        metadataFilter: Map<String, MetadataValue> = emptyMap(),
    ): List<FullTextHit> =
        fullText.search(
            FullTextQuery(text = text, keywords = keywords, metadataFilter = metadataFilter, limit = k),
            allowedSourceIds,
        )
}
