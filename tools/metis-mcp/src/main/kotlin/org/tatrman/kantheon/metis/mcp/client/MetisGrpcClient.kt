package org.tatrman.kantheon.metis.mcp.client

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.tatrman.metis.v1.ArrowChunk
import org.tatrman.metis.v1.DiagnoseRequest
import org.tatrman.metis.v1.DiagnoseResult
import org.tatrman.metis.v1.DropRequest
import org.tatrman.metis.v1.DropResult
import org.tatrman.metis.v1.ExportRequest
import org.tatrman.metis.v1.FitRequest
import org.tatrman.metis.v1.FitResult
import org.tatrman.metis.v1.GetStatusRequest
import org.tatrman.metis.v1.GetStatusResponse
import org.tatrman.metis.v1.ImportResult
import org.tatrman.metis.v1.MetisServiceGrpcKt
import org.tatrman.metis.v1.ProjectRequest
import org.tatrman.metis.v1.ProjectResult
import org.tatrman.metis.v1.SimulateScenarioRequest
import java.util.concurrent.TimeUnit

interface MetisGrpcClient : AutoCloseable {
    suspend fun fit(req: FitRequest): FitResult

    suspend fun diagnose(req: DiagnoseRequest): DiagnoseResult

    suspend fun project(req: ProjectRequest): ProjectResult

    suspend fun simulateScenario(req: SimulateScenarioRequest): ProjectResult

    suspend fun importDataFrame(chunks: Flow<ArrowChunk>): ImportResult

    fun exportDataFrame(req: ExportRequest): Flow<ArrowChunk>

    suspend fun dropWorkspaceEntry(req: DropRequest): DropResult

    suspend fun getStatus(req: GetStatusRequest): GetStatusResponse
}

class GrpcMetisGrpcClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 30,
) : MetisGrpcClient {
    private val channel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()

    private val stub = MetisServiceGrpcKt.MetisServiceCoroutineStub(channel)

    override suspend fun fit(req: FitRequest): FitResult =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).fit(req)

    override suspend fun diagnose(req: DiagnoseRequest): DiagnoseResult =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).diagnose(req)

    override suspend fun project(req: ProjectRequest): ProjectResult =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).project(req)

    override suspend fun simulateScenario(req: SimulateScenarioRequest): ProjectResult =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).simulateScenario(req)

    override suspend fun importDataFrame(chunks: Flow<ArrowChunk>): ImportResult =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).importDataFrame(chunks)

    override fun exportDataFrame(req: ExportRequest): Flow<ArrowChunk> =
        flow {
            stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).exportDataFrame(req).collect { emit(it) }
        }

    override suspend fun dropWorkspaceEntry(req: DropRequest): DropResult =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).dropWorkspaceEntry(req)

    override suspend fun getStatus(req: GetStatusRequest): GetStatusResponse =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).getStatus(req)

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
