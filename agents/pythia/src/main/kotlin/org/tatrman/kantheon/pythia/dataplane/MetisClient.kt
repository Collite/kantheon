package org.tatrman.kantheon.pythia.dataplane

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import org.tatrman.metis.v1.DiagnoseRequest
import org.tatrman.metis.v1.DiagnoseResult
import org.tatrman.metis.v1.FitRequest
import org.tatrman.metis.v1.FitResult
import org.tatrman.metis.v1.GetStatusRequest
import org.tatrman.metis.v1.GetStatusResponse
import org.tatrman.metis.v1.MetisServiceGrpcKt
import org.tatrman.metis.v1.ProjectRequest
import org.tatrman.metis.v1.ProjectResult
import org.tatrman.metis.v1.SimulateScenarioRequest
import java.util.concurrent.TimeUnit

/** A Metis model isn't in the session (NOT_FOUND) — **re-fittable** from the checkpointed fit spec (PD-5). */
class MetisModelNotFoundException(
    val modelName: String,
    message: String,
) : RuntimeException(message)

/** A Metis precondition failed (e.g. Project horizon before series end) — → INCONCLUSIVE + Rule-6. */
class MetisException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The gRPC seam to `org.tatrman.metis.v1.MetisService` (contracts §7 — gRPC-direct).
 * RPCs Pythia uses: `Fit` / `Diagnose` / `Project` / `SimulateScenario` + the
 * `GetStatus` resume probe (PD-5 — a dead workspace → re-fit from the checkpointed
 * fit spec). The error model (metis §1): `NOT_FOUND` model → [MetisModelNotFoundException]
 * (re-fittable, not a failure); `FAILED_PRECONDITION` → [MetisException]. **Live Metis
 * is integration-deferred** (planning-conventions §4); the unit gate runs an
 * in-process Metis fixture-server returning the pinned goldens.
 */
interface MetisClient : AutoCloseable {
    suspend fun fit(req: FitRequest): FitResult

    suspend fun diagnose(req: DiagnoseRequest): DiagnoseResult

    suspend fun project(req: ProjectRequest): ProjectResult

    suspend fun simulateScenario(req: SimulateScenarioRequest): ProjectResult

    suspend fun getStatus(): GetStatusResponse

    /** Push rows into the Metis session as a named DF (live = `ImportDataFrame` Arrow client-stream). */
    suspend fun importDataFrame(
        sessionId: String,
        dfName: String,
        rows: kotlinx.serialization.json.JsonArray,
    )

    /** Read a session DF's rows back out (live = `ExportDataFrame` Arrow server-stream — decode deferred). */
    suspend fun exportDataFrame(
        sessionId: String,
        dfName: String,
    ): kotlinx.serialization.json.JsonArray

    override fun close() {}
}

class GrpcMetisClient(
    private val channel: ManagedChannel,
    private val deadlineSeconds: Long = 120,
    private val ownsChannel: Boolean = false,
) : MetisClient {
    private val stub = MetisServiceGrpcKt.MetisServiceCoroutineStub(channel)

    override suspend fun fit(req: FitRequest): FitResult = call(req.modelName) { it.fit(req) }

    override suspend fun diagnose(req: DiagnoseRequest): DiagnoseResult = call(req.modelName) { it.diagnose(req) }

    override suspend fun project(req: ProjectRequest): ProjectResult = call(req.modelName) { it.project(req) }

    override suspend fun simulateScenario(req: SimulateScenarioRequest): ProjectResult =
        call(req.forecastDf) { it.simulateScenario(req) }

    override suspend fun getStatus(): GetStatusResponse =
        call("") { it.getStatus(GetStatusRequest.getDefaultInstance()) }

    override suspend fun importDataFrame(
        sessionId: String,
        dfName: String,
        rows: kotlinx.serialization.json.JsonArray,
    ) {
        // Live: client-stream Arrow IPC via MetisService.ImportDataFrame (Arrow encoding
        // of the JSON rows is integration-deferred — the live transport is not on the
        // unit gate). Charon is the usual mediator (Stage → worker_df METIS); this direct
        // push handles a Pythia-internal source (a PgResultSnapshot) that Charon can't read.
    }

    override suspend fun exportDataFrame(
        sessionId: String,
        dfName: String,
    ): kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.JsonArray(emptyList())

    private suspend fun <T> call(
        modelName: String,
        block: suspend (MetisServiceGrpcKt.MetisServiceCoroutineStub) -> T,
    ): T =
        try {
            block(stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS))
        } catch (e: StatusException) {
            throw map(e.status, modelName, e)
        } catch (e: StatusRuntimeException) {
            throw map(e.status, modelName, e)
        }

    private fun map(
        status: Status,
        modelName: String,
        cause: Throwable,
    ): RuntimeException =
        when (status.code) {
            Status.Code.NOT_FOUND ->
                MetisModelNotFoundException(modelName, status.description ?: "model '$modelName' not found")
            else -> MetisException(status.code.name, status.description ?: cause.message ?: "metis error", cause)
        }

    override fun close() {
        if (ownsChannel) channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        fun forAddress(
            host: String,
            port: Int,
        ): GrpcMetisClient =
            GrpcMetisClient(
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
