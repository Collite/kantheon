package org.tatrman.pinakes.pipeline

import org.tatrman.pinakes.catalog.AssetCatalog
import org.tatrman.pinakes.catalog.LineageRecord
import org.tatrman.pinakes.catalog.LineageStore
import org.tatrman.pinakes.stage.SeaweedAssetStore
import org.tatrman.pinakes.v1.RunStatus

data class StoredRun(
    val runId: String,
    val pipelineId: String,
    val assetIds: List<String>,
    val status: RunStatus,
    val stages: List<StageRunRecord>,
)

/**
 * Orchestrates a pipeline run (architecture §7): for each asset, fetch the staged
 * bytes, run the named pipeline through the [Runner], record per-stage status,
 * and write the `asset → run → source/page` lineage. Stores runs for `GetRun`.
 * The loaded source lands in the feed's mart (`feed-{feed}`).
 */
class PipelineService(
    private val assetStore: SeaweedAssetStore,
    private val catalog: AssetCatalog,
    private val registry: PipelineRegistry,
    private val runner: Runner,
    private val lineageStore: LineageStore,
) {
    private val runs = linkedMapOf<String, StoredRun>()

    suspend fun run(
        pipelineId: String,
        assetIds: List<String>,
        runId: String,
    ): StoredRun {
        val pipeline = registry.get(pipelineId) ?: error("unknown pipeline: $pipelineId")
        val allRecords = mutableListOf<StageRunRecord>()
        var overall = RunStatus.SUCCEEDED

        for (assetId in assetIds) {
            val asset = catalog.get(assetId) ?: error("unknown asset: $assetId")
            val bytes = assetStore.get(asset.assetRef)
            val ctx =
                StageContext(
                    assetId = asset.id,
                    assetRef = asset.assetRef,
                    sourceFeed = asset.sourceFeed,
                    mimeType = asset.mimeType,
                    originalName = asset.originalName,
                    notebookId = "feed-${asset.sourceFeed}",
                    bytes = bytes,
                )
            val result = runner.run(pipeline, ctx, runId)
            allRecords += result.stages
            if (result.status != RunStatus.SUCCEEDED) {
                overall = result.status
            } else if (result.finalCtx.compileDegraded && overall == RunStatus.SUCCEEDED) {
                // Compile degraded to mechanical — the corpus is queryable, but the
                // wiki is thin for this source, so the run is PARTIAL (architecture §14).
                overall = RunStatus.PARTIAL
            }
            result.finalCtx.sourceId?.let { lineageStore.record(assetId, runId, listOf(it), result.finalCtx.pageIds) }
        }

        val stored = StoredRun(runId, pipelineId, assetIds, overall, allRecords)
        runs[runId] = stored
        return stored
    }

    /** Resolve the pipeline for a run request: explicit id, else the feed binding. */
    fun resolvePipelineId(
        requestedId: String,
        firstAssetId: String?,
    ): String {
        if (requestedId.isNotBlank()) return requestedId
        val feed = firstAssetId?.let { catalog.get(it)?.sourceFeed }
        return feed?.let { registry.forFeed(it)?.id } ?: error("no pipeline bound to the asset feed")
    }

    fun getRun(runId: String): StoredRun? = runs[runId]

    fun lineage(assetId: String): LineageRecord? = lineageStore.get(assetId)

    fun pipelines(): List<Pipeline> = registry.all()

    fun pipeline(id: String): Pipeline? = registry.get(id)
}
