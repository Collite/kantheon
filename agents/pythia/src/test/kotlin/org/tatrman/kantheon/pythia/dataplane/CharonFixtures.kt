package org.tatrman.kantheon.pythia.dataplane

import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.serialization.json.JsonArray
import org.tatrman.charon.v1.CharonServiceGrpcKt
import org.tatrman.charon.v1.CopyRequest
import org.tatrman.charon.v1.DescribeRequest
import org.tatrman.charon.v1.DescribeResult
import org.tatrman.charon.v1.EvictRequest
import org.tatrman.charon.v1.EvictResult
import org.tatrman.charon.v1.Location
import org.tatrman.charon.v1.MaterializeRequest
import org.tatrman.charon.v1.MoveResult
import org.tatrman.charon.v1.StageRequest
import org.tatrman.kantheon.pythia.dataplane.DataFrameOp
import org.tatrman.kantheon.pythia.dataplane.DataFrameOutput
import org.tatrman.kantheon.pythia.dataplane.WorkerClient

/** A signature for a Charon [Location] (test assertion key). */
fun locKey(location: Location): String =
    when (location.kindCase) {
        Location.KindCase.SEAWEED -> "seaweed:${location.seaweed.bucket}/${location.seaweed.key}"
        Location.KindCase.REDIS -> "redis:${location.redis.key}"
        Location.KindCase.WORKER_DF -> "worker:${location.workerDf.sessionId}/${location.workerDf.dfName}"
        Location.KindCase.DB_TABLE ->
            "db:${location.dbTable.connectionId}.${location.dbTable.schema}.${location.dbTable.table}"
        else -> "unknown"
    }

/**
 * In-process Charon fixture-server (Stage 4.1 mocked suite). Records calls and
 * returns configurable `MoveResult` / `DescribeResult` fixtures; live Charon is the
 * integration suite (planning-conventions §4).
 */
class FixtureCharonService(
    var stageFingerprint: String = "fp-new",
    var materializeFingerprint: String = "fp-new",
    var rejectMaterialize: Boolean = false,
) : CharonServiceGrpcKt.CharonServiceCoroutineImplBase() {
    /** Per-location Describe overrides, keyed by [locKey]; default = exists with "fp-orig". */
    val describeResults = mutableMapOf<String, DescribeResult>()

    /** Every RPC issued, as "<rpc>:<targetOrLocationKey>" — assertion surface. */
    val calls = mutableListOf<String>()

    /** Retention tag carried on each Materialize-to-Seaweed target, keyed by [locKey]. */
    val materializeRetentionTags = mutableMapOf<String, String>()

    override suspend fun materialize(request: MaterializeRequest): MoveResult {
        calls += "materialize:${locKey(request.target)}"
        if (request.target.kindCase == Location.KindCase.SEAWEED) {
            materializeRetentionTags[locKey(request.target)] = request.target.seaweed.retentionTag
        }
        if (rejectMaterialize) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("illegal source→target pairing"))
        }
        return MoveResult
            .newBuilder()
            .setTarget(request.target)
            .setSchemaFingerprint(materializeFingerprint)
            .setRowCount(10)
            .build()
    }

    override suspend fun stage(request: StageRequest): MoveResult {
        calls += "stage:worker:${request.target.sessionId}/${request.target.dfName}"
        return MoveResult
            .newBuilder()
            .setTarget(Location.newBuilder().setWorkerDf(request.target))
            .setSchemaFingerprint(stageFingerprint)
            .setRowCount(10)
            .build()
    }

    override suspend fun copy(request: CopyRequest): MoveResult {
        calls += "copy:${locKey(request.target)}"
        return MoveResult
            .newBuilder()
            .setTarget(request.target)
            .setSchemaFingerprint(materializeFingerprint)
            .build()
    }

    override suspend fun evict(request: EvictRequest): EvictResult {
        calls += "evict:${locKey(request.location)}"
        return EvictResult.newBuilder().setExisted(true).build()
    }

    override suspend fun describe(request: DescribeRequest): DescribeResult {
        calls += "describe:${locKey(request.location)}"
        return describeResults[locKey(request.location)]
            ?: DescribeResult
                .newBuilder()
                .setExists(true)
                .setSchemaFingerprint("fp-orig")
                .build()
    }
}

/** A [CharonClient] that returns defaults — for paths that never reach Charon (internal-source pushes). */
class NoopCharonClient : CharonClient {
    override suspend fun materialize(
        source: Location,
        target: Location,
        expectedFingerprint: String?,
    ) = MoveResult.getDefaultInstance()

    override suspend fun stage(
        source: Location,
        target: org.tatrman.charon.v1.WorkerSessionDf,
        expectedFingerprint: String?,
    ) = MoveResult.getDefaultInstance()

    override suspend fun copy(
        source: Location,
        target: Location,
    ) = MoveResult.getDefaultInstance()

    override suspend fun evict(location: Location) = EvictResult.getDefaultInstance()

    override suspend fun describe(location: Location) = DescribeResult.getDefaultInstance()
}

/** Start an in-process gRPC server for [service] and return (server, channel). */
fun startInProcess(service: io.grpc.BindableService): Pair<Server, ManagedChannel> {
    val name = "pythia-dataplane-${System.nanoTime()}"
    val server =
        InProcessServerBuilder
            .forName(name)
            .directExecutor()
            .addService(service)
            .build()
            .start()
    val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
    return server to channel
}

/**
 * A [WorkerClient] fake: returns the configured row output for each DataFrame op,
 * reusing the op's session (sticky affinity). Records every op for chain/session
 * assertions; live Steropes is the integration suite.
 */
class FakeWorkerClient(
    private val rows: JsonArray? = null,
    private val rowCount: Long = 3,
) : WorkerClient {
    val ops = mutableListOf<DataFrameOp>()

    val imports = mutableListOf<Triple<String, String, Int>>()

    override suspend fun runDataFrame(op: DataFrameOp): DataFrameOutput {
        ops += op
        return DataFrameOutput(
            sessionId = op.sessionId,
            dfName = op.outputDfName,
            rowCount = rows?.size?.toLong() ?: rowCount,
            rows = rows,
        )
    }

    override suspend fun importDataFrame(
        sessionId: String,
        dfName: String,
        rows: JsonArray,
    ): DataFrameOutput {
        imports += Triple(sessionId, dfName, rows.size)
        return DataFrameOutput(sessionId = sessionId, dfName = dfName, rowCount = rows.size.toLong(), rows = rows)
    }
}
