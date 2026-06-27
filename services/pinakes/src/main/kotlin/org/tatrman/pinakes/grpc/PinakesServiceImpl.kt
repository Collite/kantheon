package org.tatrman.pinakes.grpc

import io.grpc.Status
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.tatrman.pinakes.catalog.AssetCatalog
import org.tatrman.pinakes.catalog.AssetRecord
import org.tatrman.pinakes.pipeline.PipelineService
import org.tatrman.pinakes.pipeline.StageRunRecord
import org.tatrman.pinakes.pipeline.StoredRun
import org.tatrman.pinakes.stage.SeaweedAssetStore
import org.tatrman.pinakes.v1.Asset
import org.tatrman.pinakes.v1.EmbedConfig
import org.tatrman.pinakes.v1.GetLineageRequest
import org.tatrman.pinakes.v1.GetPipelineRequest
import org.tatrman.pinakes.v1.GetRunRequest
import org.tatrman.pinakes.v1.Lineage
import org.tatrman.pinakes.v1.ListAssetsRequest
import org.tatrman.pinakes.v1.ListAssetsResponse
import org.tatrman.pinakes.v1.ListPipelinesRequest
import org.tatrman.pinakes.v1.ListPipelinesResponse
import org.tatrman.pinakes.v1.Pipeline
import org.tatrman.pinakes.v1.PipelineRun
import org.tatrman.pinakes.v1.PinakesServiceGrpc
import org.tatrman.pinakes.v1.RegisterAssetRequest
import org.tatrman.pinakes.v1.RunPipelineRequest
import org.tatrman.pinakes.v1.Stage
import org.tatrman.pinakes.v1.StageRecord
import java.util.UUID

/**
 * The PinakesService gRPC surface (P3 Stage 3.1). `RegisterAsset`/`ListAssets`
 * stage assets; `RunPipeline` runs a named pipeline through the stage library +
 * runner (per-stage records, resumable); `GetRun`/`GetLineage`/`ListPipelines`/
 * `GetPipeline` expose runs, the asset→source/page chain, and the registry.
 */
class PinakesServiceImpl(
    private val assetStore: SeaweedAssetStore,
    private val catalog: AssetCatalog,
    private val pipelines: PipelineService,
) : PinakesServiceGrpc.PinakesServiceImplBase() {
    private val log = LoggerFactory.getLogger(PinakesServiceImpl::class.java)

    override fun registerAsset(
        request: RegisterAssetRequest,
        responseObserver: StreamObserver<Asset>,
    ) {
        try {
            val assetId = UUID.randomUUID().toString()
            val assetRef =
                assetStore.put(
                    request.sourceFeed,
                    assetId,
                    request.originalName,
                    request.mimeType,
                    request.content.toByteArray(),
                )
            val record =
                catalog.record(
                    AssetRecord(assetId, assetRef, request.sourceFeed, request.mimeType, request.originalName),
                )
            responseObserver.onNext(record.toProto())
            responseObserver.onCompleted()
        } catch (e: Exception) {
            log.error("RegisterAsset failed", e)
            responseObserver.onError(Status.INTERNAL.withDescription(e.message).asException())
        }
    }

    override fun listAssets(
        request: ListAssetsRequest,
        responseObserver: StreamObserver<ListAssetsResponse>,
    ) {
        val assets = catalog.list(request.sourceFeed.takeIf { it.isNotBlank() }).map { it.toProto() }
        responseObserver.onNext(ListAssetsResponse.newBuilder().addAllAssets(assets).build())
        responseObserver.onCompleted()
    }

    override fun runPipeline(
        request: RunPipelineRequest,
        responseObserver: StreamObserver<PipelineRun>,
    ) {
        try {
            val pipelineId = pipelines.resolvePipelineId(request.pipelineId, request.assetIdsList.firstOrNull())
            val stored = runBlocking { pipelines.run(pipelineId, request.assetIdsList, UUID.randomUUID().toString()) }
            responseObserver.onNext(stored.toProto())
            responseObserver.onCompleted()
        } catch (e: Exception) {
            log.error("RunPipeline failed", e)
            responseObserver.onError(Status.INTERNAL.withDescription(e.message).asException())
        }
    }

    override fun getRun(
        request: GetRunRequest,
        responseObserver: StreamObserver<PipelineRun>,
    ) {
        val run = pipelines.getRun(request.runId)
        if (run == null) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("run ${request.runId}").asException())
            return
        }
        responseObserver.onNext(run.toProto())
        responseObserver.onCompleted()
    }

    override fun getLineage(
        request: GetLineageRequest,
        responseObserver: StreamObserver<Lineage>,
    ) {
        val lineage = pipelines.lineage(request.assetId)
        responseObserver.onNext(
            Lineage
                .newBuilder()
                .setAssetId(request.assetId)
                .addAllRunIds(lineage?.runIds ?: emptyList())
                .addAllSourceIds(lineage?.sourceIds ?: emptyList())
                .addAllPageIds(lineage?.pageIds ?: emptyList())
                .build(),
        )
        responseObserver.onCompleted()
    }

    override fun listPipelines(
        request: ListPipelinesRequest,
        responseObserver: StreamObserver<ListPipelinesResponse>,
    ) {
        val feed = request.sourceFeed.takeIf { it.isNotBlank() }
        val list = pipelines.pipelines().filter { feed == null || it.sourceFeed == feed }.map { it.toProto() }
        responseObserver.onNext(ListPipelinesResponse.newBuilder().addAllPipelines(list).build())
        responseObserver.onCompleted()
    }

    override fun getPipeline(
        request: GetPipelineRequest,
        responseObserver: StreamObserver<Pipeline>,
    ) {
        val p = pipelines.pipeline(request.pipelineId)
        if (p == null) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("pipeline ${request.pipelineId}").asException())
            return
        }
        responseObserver.onNext(p.toProto())
        responseObserver.onCompleted()
    }

    // ── proto mapping ────────────────────────────────────────────────────────
    private fun AssetRecord.toProto(): Asset =
        Asset
            .newBuilder()
            .setId(id)
            .setAssetRef(assetRef)
            .setSourceFeed(sourceFeed)
            .setMimeType(mimeType)
            .setOriginalName(originalName)
            .setStagedAt(stagedAt)
            .build()

    private fun StoredRun.toProto(): PipelineRun =
        PipelineRun
            .newBuilder()
            .setId(runId)
            .setPipelineId(pipelineId)
            .addAllAssetIds(assetIds)
            .setStatus(status)
            .addAllStageRecords(stages.map { it.toProto() })
            .build()

    private fun StageRunRecord.toProto(): StageRecord {
        val b =
            StageRecord
                .newBuilder()
                .setStageId(stageId)
                .setKind(kind)
                .setStatus(status)
                .setItemsIn(itemsIn)
                .setItemsOut(itemsOut)
                .setLatencyMs(latencyMs)
                .setCostUsd(costUsd)
        error?.let { b.error = it }
        return b.build()
    }

    private fun org.tatrman.pinakes.pipeline.Pipeline.toProto(): Pipeline =
        Pipeline
            .newBuilder()
            .setId(id)
            .setDisplayName(displayName)
            .setSourceFeed(sourceFeed)
            .addAllStages(
                stages.map {
                    Stage
                        .newBuilder()
                        .setId(it.name.lowercase())
                        .setKind(it)
                        .build()
                },
            ).setEmbed(
                EmbedConfig
                    .newBuilder()
                    .setModelId(embed.modelId)
                    .setDimensions(embed.dimensions)
                    .setModelVersion(embed.modelVersion)
                    .build(),
            ).build()
}
