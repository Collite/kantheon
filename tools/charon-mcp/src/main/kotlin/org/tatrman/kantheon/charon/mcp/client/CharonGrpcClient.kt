package org.tatrman.kantheon.charon.mcp.client

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.util.concurrent.TimeUnit
import org.tatrman.transfer.v1.CharonServiceGrpcKt
import org.tatrman.transfer.v1.CopyRequest
import org.tatrman.transfer.v1.DescribeRequest
import org.tatrman.transfer.v1.DescribeResult
import org.tatrman.transfer.v1.EvictRequest
import org.tatrman.transfer.v1.EvictResult
import org.tatrman.transfer.v1.MaterializeRequest
import org.tatrman.transfer.v1.MoveResult
import org.tatrman.transfer.v1.StageRequest

/** The gRPC seam to `org.tatrman.transfer.v1.CharonService` (coroutine stub). */
interface CharonGrpcClient : AutoCloseable {
    suspend fun materialize(req: MaterializeRequest): MoveResult

    suspend fun stage(req: StageRequest): MoveResult

    suspend fun copy(req: CopyRequest): MoveResult

    suspend fun evict(req: EvictRequest): EvictResult

    suspend fun describe(req: DescribeRequest): DescribeResult
}

class GrpcCharonGrpcClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 120,
) : CharonGrpcClient {
    private val channel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()

    private val stub = CharonServiceGrpcKt.CharonServiceCoroutineStub(channel)

    override suspend fun materialize(req: MaterializeRequest): MoveResult =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).materialize(req)

    override suspend fun stage(req: StageRequest): MoveResult =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).stage(req)

    override suspend fun copy(req: CopyRequest): MoveResult =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).copy(req)

    override suspend fun evict(req: EvictRequest): EvictResult =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).evict(req)

    override suspend fun describe(req: DescribeRequest): DescribeResult =
        stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).describe(req)

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
