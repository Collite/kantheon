package org.tatrman.kantheon.pythia.dataplane

import org.tatrman.transfer.v1.WorkerKind
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.v1.Handle

/**
 * Materialises a large id-list (>500) into a worker DF so a downstream QueryNode can
 * bind against it as a staged DataFrame (a semi-join) instead of inlining the list
 * (Stage 2.3 flagged the path; Stage 4.1 T7 activates it). The returned handle id is
 * the staged worker DF.
 */
fun interface InListMaterialiser {
    /** Stage [sourceHandleId]'s data into [sessionId]; return the staged worker-DF handle id. */
    suspend fun materialise(
        sourceHandleId: String,
        handles: HandleTable,
        sessionId: String,
    ): String
}

/**
 * Default [InListMaterialiser]. A **Pythia-internal** source (a `PgResultSnapshot` /
 * `LiveQueryRef`, which Pythia's PG can't expose as a Charon connection) is pushed to
 * the worker via [WorkerClient.importDataFrame]; a **Charon-backed** source (Seaweed /
 * Redis / DbTable) is Charon-`Stage`d. Either way the result is a `WorkerSessionDF`
 * handle the query binds against.
 */
class DefaultInListMaterialiser(
    private val worker: WorkerClient,
    private val materialiser: Materialiser,
) : InListMaterialiser {
    override suspend fun materialise(
        sourceHandleId: String,
        handles: HandleTable,
        sessionId: String,
    ): String {
        val source =
            handles.get(sourceHandleId)
                ?: throw IllegalStateException("IN-list materialise: source handle '$sourceHandleId' missing")
        val stagedId = "$sourceHandleId-staged"
        return when (source.kindCase) {
            Handle.KindCase.PG_SNAPSHOT, Handle.KindCase.LIVE_QUERY -> {
                val rows = handles.rows(sourceHandleId) ?: kotlinx.serialization.json.JsonArray(emptyList())
                val output = worker.importDataFrame(sessionId, stagedId, rows)
                handles
                    .putWorkerDf(
                        handleId = stagedId,
                        workerPod = "",
                        sessionId = output.sessionId,
                        dfName = output.dfName,
                        rowCountEst = output.rowCount,
                        rows = output.rows,
                    ).handleId
            }
            else -> {
                val move =
                    MaterialisationMove.Stage(
                        source = source,
                        sessionId = sessionId,
                        dfName = stagedId,
                        workerKind = WorkerKind.POLARS,
                    )
                materialiser.apply(move, handles).handleId
            }
        }
    }
}
