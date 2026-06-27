package org.tatrman.pinakes.catalog

/**
 * Lineage (contracts §2 `Lineage`): the provenance chain `asset → run_ids →
 * source_ids → page_ids` (architecture §7 — Pinakes owns definitions, runs, and
 * lineage). In-memory at P1/P3 (the Pinakes schema is the deploy path, plan §8);
 * a run merges its produced ids into the asset's record.
 */
data class LineageRecord(
    val assetId: String,
    val runIds: List<String> = emptyList(),
    val sourceIds: List<Long> = emptyList(),
    val pageIds: List<Long> = emptyList(),
)

class LineageStore {
    private val byAsset = linkedMapOf<String, LineageRecord>()

    fun record(
        assetId: String,
        runId: String,
        sourceIds: List<Long>,
        pageIds: List<Long> = emptyList(),
    ): LineageRecord {
        val existing = byAsset[assetId] ?: LineageRecord(assetId)
        val merged =
            existing.copy(
                runIds = (existing.runIds + runId).distinct(),
                sourceIds = (existing.sourceIds + sourceIds).distinct(),
                pageIds = (existing.pageIds + pageIds).distinct(),
            )
        byAsset[assetId] = merged
        return merged
    }

    fun get(assetId: String): LineageRecord? = byAsset[assetId]
}
