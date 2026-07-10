package org.tatrman.kantheon.pythia.dataplane

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.flow.fold
import kotlinx.serialization.json.JsonArray
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.WorkerServiceGrpcKt
import java.util.concurrent.TimeUnit

/** A DataFrame op dispatched to the Polars worker (Polars) over a session workspace. */
data class DataFrameOp(
    val sessionId: String,
    val outputDfName: String,
    val dfdsl: String,
    val sourceDfName: String,
)

/** The output of a worker DataFrame op — a session-resident DF (divergence 5 keying). */
data class DataFrameOutput(
    val sessionId: String,
    val dfName: String,
    val rowCount: Long,
    val schemaJson: String = "",
    /** Decoded rows (mocked path); null on the live path where rows stay in the worker. */
    val rows: JsonArray? = null,
)

/**
 * The seam to the Polars worker (Polars) `org.tatrman.worker.v1.WorkerService`
 * for DataFrame composition (architecture §5, contracts §6). Pythia stages inputs
 * into a worker session via Charon, then chains dfdsl ops on the **same session**
 * (sticky affinity) — each op's output is a new `(session_id, df_name)` workspace
 * DF. **Live Polars is integration-deferred** (planning-conventions §4); the unit
 * gate runs a [FakeWorkerClient].
 */
interface WorkerClient : AutoCloseable {
    suspend fun runDataFrame(op: DataFrameOp): DataFrameOutput

    /**
     * Push externally-produced rows (e.g. a `PgResultSnapshot` — Pythia's PG is never
     * a Charon connection, so it can't be Charon-staged) into a worker session as a
     * named DF. Used by the IN-list>500 materialise path. Live = `WorkerService.
     * ImportDataFrame` (Arrow IPC client-stream); the unit gate runs a fake.
     */
    suspend fun importDataFrame(
        sessionId: String,
        dfName: String,
        rows: kotlinx.serialization.json.JsonArray,
    ): DataFrameOutput

    override fun close() {}
}

/**
 * gRPC implementation. The live worker executes a dfdsl op over a session
 * workspace DF (`WorkspaceRef`) and streams Arrow IPC batches back, assigning the
 * result to `assign_to_workspace = outputDfName`. **Arrow-IPC→rows decoding and the
 * dfdsl→plan-node construction are integration-deferred** (the live transport is
 * not on the unit gate); this client collects the streamed row counts so the
 * resulting `WorkerSessionDF` handle is well-formed, and leaves `rows = null`
 * (the worker holds them — scan-on-demand).
 */
class GrpcWorkerClient(
    private val channel: ManagedChannel,
    private val deadlineSeconds: Long = 120,
    private val ownsChannel: Boolean = false,
) : WorkerClient {
    private val stub = WorkerServiceGrpcKt.WorkerServiceCoroutineStub(channel)

    override suspend fun runDataFrame(op: DataFrameOp): DataFrameOutput {
        val request =
            ExecuteRequest
                .newBuilder()
                .setContext(PipelineContext.newBuilder().setSessionId(op.sessionId))
                .setAssignToWorkspace(op.outputDfName)
                .build()
        val rows =
            stub
                .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
                .execute(request)
                .fold(0L) { acc, batch -> acc + batch.batchRowCount }
        return DataFrameOutput(sessionId = op.sessionId, dfName = op.outputDfName, rowCount = rows)
    }

    override suspend fun importDataFrame(
        sessionId: String,
        dfName: String,
        rows: kotlinx.serialization.json.JsonArray,
    ): DataFrameOutput {
        // Live: client-stream Arrow IPC via WorkerService.ImportDataFrame. The Arrow
        // encoding of the JSON rows is integration-deferred (the live transport is not
        // on the unit gate); the resulting handle metadata is well-formed regardless.
        return DataFrameOutput(sessionId = sessionId, dfName = dfName, rowCount = rows.size.toLong(), rows = rows)
    }

    override fun close() {
        if (ownsChannel) channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        fun forAddress(
            host: String,
            port: Int,
        ): GrpcWorkerClient =
            GrpcWorkerClient(
                ManagedChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext()
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveTimeout(10, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .build(),
                ownsChannel = true,
            )
    }
}
