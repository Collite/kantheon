package org.tatrman.kantheon.pythia.dataplane

import org.tatrman.transfer.v1.Location
import org.tatrman.transfer.v1.SeaweedBlob
import org.tatrman.transfer.v1.WorkerKind
import org.tatrman.transfer.v1.WorkerSessionDf
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.Handle
import org.tatrman.kantheon.pythia.v1.PlanNode

/** Retention tags Charon maps to S3 lifecycle rules (contracts §6: 90 d / 7 d). */
object RetentionTag {
    const val PRODUCTION = "production"
    const val SHALLOW = "shallow"

    fun forDepth(depth: DepthBudget): String = if (depth == DepthBudget.DEPTH_SHALLOW) SHALLOW else PRODUCTION
}

/** A move the policy engine decided Pythia should ask Charon to perform. */
sealed interface MaterialisationMove {
    /** Cross-engine staging: a SQL/blob handle → a worker session DF (the make-it-hot verb). */
    data class Stage(
        val source: Handle,
        val sessionId: String,
        val dfName: String,
        val workerKind: WorkerKind = WorkerKind.POLARS,
    ) : MaterialisationMove

    /** Evidence persistence / TTL-approach: a handle → a durable Seaweed blob. */
    data class Persist(
        val source: Handle,
        val bucket: String,
        val key: String,
        val retentionTag: String,
    ) : MaterialisationMove
}

/**
 * The materialisation policy engine (design §6.2): decides **when** Pythia calls
 * Charon. Pure decisions (Charon never decides on its own, contracts §6) over three
 * triggers — **cross-engine staging** (a handle a different engine needs, e.g. an
 * SQL result a DataFrame op will consume), **evidence-persist** (a load-bearing
 * result → Seaweed at finalisation), and **TTL-approach** (a live handle nearing its
 * source TTL → persist before it expires). [Materialiser] executes the moves.
 */
class MaterialisationPolicy(
    private val evidenceBucket: String = "pythia-evidence",
) {
    /**
     * If [node] consumes a handle on a different engine than it runs on (a DataFrame/
     * Model node whose source is NOT already a worker DF), return the Stage move that
     * makes it hot. SQL-only / already-staged nodes need no move (null).
     */
    fun stageForCrossEngine(
        node: PlanNode,
        handles: HandleTable,
        sessionId: String,
        workerKind: WorkerKind = WorkerKind.POLARS,
    ): MaterialisationMove.Stage? {
        val sourceId =
            when (node.kindCase) {
                PlanNode.KindCase.DATAFRAME -> node.dataframe.sourceHandleId
                PlanNode.KindCase.MODEL -> node.model.inputHandleIdsList.firstOrNull()
                else -> null
            } ?: return null
        val source = handles.get(sourceId) ?: return null
        // Already a worker DF on the right engine → no staging.
        if (source.kindCase == Handle.KindCase.WORKER_DF) return null
        if (source.kindCase == Handle.KindCase.LIVE_QUERY || source.kindCase == Handle.KindCase.PG_SNAPSHOT) {
            return MaterialisationMove.Stage(source, sessionId, dfName = sourceId, workerKind = workerKind)
        }
        // Seaweed/Redis/DbTable also stage into a worker session for compute.
        return MaterialisationMove.Stage(source, sessionId, dfName = sourceId, workerKind = workerKind)
    }

    /**
     * Evidence persistence at finalisation: a **load-bearing** handle (referenced by
     * a supported hypothesis / the conclusion) → Seaweed, keyed
     * `{investigationId}/{handleId}.arrow`, with the depth-derived retention tag.
     * Pythia-internal kinds are never persisted to Charon (no mapping).
     */
    fun persistEvidence(
        handle: Handle,
        investigationId: String,
        depth: DepthBudget,
        loadBearing: Boolean,
    ): MaterialisationMove.Persist? {
        if (!loadBearing) return null
        if (handle.kindCase == Handle.KindCase.SEAWEED) return null // already durable
        return MaterialisationMove.Persist(
            source = handle,
            bucket = evidenceBucket,
            key = "$investigationId/${handle.handleId}.arrow",
            retentionTag = RetentionTag.forDepth(depth),
        )
    }

    /**
     * TTL-approach: a worker/redis handle whose remaining lifetime is below the
     * threshold → persist to Seaweed before it expires (always production retention —
     * we're saving it precisely because it's still needed).
     */
    fun ttlPersist(
        handle: Handle,
        investigationId: String,
        ttlApproaching: Boolean,
    ): MaterialisationMove.Persist? {
        if (!ttlApproaching) return null
        if (handle.kindCase != Handle.KindCase.WORKER_DF && handle.kindCase != Handle.KindCase.REDIS) return null
        return MaterialisationMove.Persist(
            source = handle,
            bucket = evidenceBucket,
            key = "$investigationId/${handle.handleId}.arrow",
            retentionTag = RetentionTag.PRODUCTION,
        )
    }
}

/** Issues [MaterialisationMove]s against Charon and registers the resulting handle. */
class Materialiser(
    private val charon: CharonClient,
    private val metrics: org.tatrman.kantheon.pythia.obs.PythiaMetrics? = null,
) {
    /** Execute [move]; returns the new handle (a worker DF for Stage, a Seaweed blob for Persist). */
    suspend fun apply(
        move: MaterialisationMove,
        handles: HandleTable,
        workerPod: String = "",
    ): Handle =
        when (move) {
            is MaterialisationMove.Stage -> {
                val target =
                    WorkerSessionDf
                        .newBuilder()
                        .setWorkerKind(move.workerKind)
                        .setSessionId(move.sessionId)
                        .setDfName(move.dfName)
                        .build()
                val result =
                    charon.stage(
                        HandleLocationMapping.toLocation(move.source),
                        target,
                        fingerprint(move.source),
                    )
                metrics?.handleMaterialisation(
                    move.source.kindCase.name
                        .lowercase(),
                    "worker_df",
                )
                // The staged worker DF is keyed by the target df_name: cross-engine
                // staging sets df_name = sourceId (overwriting the source handle in place),
                // the IN-list path sets a distinct df_name (a fresh staged handle).
                handles.putWorkerDf(
                    handleId = move.dfName,
                    workerPod = workerPod,
                    sessionId = move.sessionId,
                    dfName = move.dfName,
                    rowCountEst = result.rowCount,
                    schemaJson = result.schemaJson,
                    rows = handles.rows(move.source.handleId),
                )
            }
            is MaterialisationMove.Persist -> {
                val target =
                    Location
                        .newBuilder()
                        .setSeaweed(
                            SeaweedBlob
                                .newBuilder()
                                .setBucket(move.bucket)
                                .setKey(move.key)
                                .setRetentionTag(move.retentionTag),
                        ).build()
                val result =
                    charon.materialize(
                        HandleLocationMapping.toLocation(move.source),
                        target,
                        fingerprint(move.source),
                    )
                metrics?.handleMaterialisation(
                    move.source.kindCase.name
                        .lowercase(),
                    "seaweed",
                )
                handles.putSeaweed(
                    handleId = move.source.handleId,
                    url = "${move.bucket}/${move.key}",
                    rowCount = result.rowCount,
                    // Stored as the blob's opaque identifier. NB it is a *schema* fingerprint, not a
                    // content hash — MoveResult exposes no content digest, so this is the closest stable
                    // id Charon returns. Treat it as opaque; don't read content-equality into it.
                    contentHash = result.schemaFingerprint,
                )
            }
        }

    private fun fingerprint(handle: Handle): String? =
        when (handle.kindCase) {
            Handle.KindCase.SEAWEED -> handle.seaweed.contentHash.ifBlank { null }
            else -> null
        }
}
