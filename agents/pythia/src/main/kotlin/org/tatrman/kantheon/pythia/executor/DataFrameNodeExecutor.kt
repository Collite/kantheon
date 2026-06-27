package org.tatrman.kantheon.pythia.executor

import org.tatrman.kantheon.pythia.dataplane.DataFrameOp
import org.tatrman.kantheon.pythia.dataplane.WorkerClient
import org.tatrman.kantheon.pythia.v1.Handle
import org.tatrman.kantheon.pythia.v1.PlanNode

/**
 * Executes a `DataFrameNode` (Phase 4) on the Polars worker (Steropes) via
 * [WorkerClient]. The node's `source_handle_id` must resolve to a `WorkerSessionDF`
 * handle — staged there by the materialisation policy engine when the upstream was
 * SQL/blob (cross-engine staging). **Sticky affinity** (architecture §5): the output
 * DF is assigned on the **same session** as the source, so a DataFrame chain stays
 * on one worker pod. The output is a fresh `WorkerSessionDF` handle keyed
 * `(session_id, df_name = node_id)`.
 *
 * A missing/wrong-kind source handle is a PERMANENT failure (the staging step that
 * should have produced it failed, so the node's hypotheses go INCONCLUSIVE).
 */
class DataFrameNodeExecutor(
    private val worker: WorkerClient,
) : NodeExecutor {
    override fun providerOf(node: PlanNode): String = "worker"

    override suspend fun execute(
        node: PlanNode,
        ctx: NodeContext,
    ): NodeResult {
        val df = node.dataframe
        val source =
            ctx.handles.get(df.sourceHandleId)
                ?: throw NodeExecutionException(
                    FailureKind.PERMANENT,
                    "DataFrameNode ${node.nodeId}: source handle '${df.sourceHandleId}' not found (staging failed?)",
                )
        if (source.kindCase != Handle.KindCase.WORKER_DF) {
            throw NodeExecutionException(
                FailureKind.PERMANENT,
                "DataFrameNode ${node.nodeId}: source '${df.sourceHandleId}' is ${source.kindCase}, " +
                    "expected WORKER_DF (cross-engine staging must precede a DataFrame op)",
            )
        }
        val sessionId = source.workerDf.sessionId
        val workerPod = source.workerDf.workerPod
        val output =
            worker.runDataFrame(
                DataFrameOp(
                    sessionId = sessionId,
                    outputDfName = node.nodeId,
                    dfdsl = df.dfdsl,
                    sourceDfName = source.workerDf.dfName,
                ),
            )
        val handle =
            ctx.handles.putWorkerDf(
                handleId = "h-${node.nodeId}",
                workerPod = workerPod,
                sessionId = output.sessionId,
                dfName = output.dfName,
                rowCountEst = output.rowCount,
                schemaJson = output.schemaJson,
                rows = output.rows,
            )
        return NodeResult(outputHandle = handle, rowCount = output.rowCount, costUsd = 0.0)
    }
}
