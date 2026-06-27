package org.tatrman.kantheon.pythia.dataplane

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
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
import org.tatrman.charon.v1.WorkerSessionDf
import java.util.concurrent.TimeUnit

/**
 * Raised when Charon rejects a move (legality-matrix violation, RESOURCE_EXHAUSTED,
 * etc.). The caller (materialisation policy / IN-list path) decides whether to
 * degrade (Rule-6 warning + LooseEnd) or fail the node.
 */
class CharonException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The gRPC seam to `org.tatrman.charon.v1.CharonService` (contracts §6 — Pythia
 * calls Charon **gRPC-direct**; the MCP wrapper is not on Pythia's path). The five
 * RPCs Pythia uses: `Materialize` (persist to a durable tier), `Stage` (load into a
 * worker session DF — the make-it-hot verb), `Copy` (generic legal pair), `Evict`
 * (GC a transient handle), `Describe` (PD-5 resume-time liveness probe).
 *
 * Charon never decides on its own to move data (contracts §6) — Pythia issues the
 * move via the materialisation policy engine. **Live Charon is integration-deferred**
 * (planning-conventions §4); the unit gate runs an in-process gRPC fixture-server.
 */
interface CharonClient : AutoCloseable {
    suspend fun materialize(
        source: Location,
        target: Location,
        expectedFingerprint: String? = null,
    ): MoveResult

    suspend fun stage(
        source: Location,
        target: WorkerSessionDf,
        expectedFingerprint: String? = null,
    ): MoveResult

    suspend fun copy(
        source: Location,
        target: Location,
    ): MoveResult

    suspend fun evict(location: Location): EvictResult

    suspend fun describe(location: Location): DescribeResult

    override fun close() {}
}

/**
 * gRPC implementation over a [ManagedChannel]. The primary constructor takes a
 * channel (so in-process fixture-servers inject one in tests); [forAddress] builds
 * a plaintext netty channel for the live edge. Errors map to [CharonException]
 * carrying the gRPC status code (so the policy engine can branch on it).
 */
class GrpcCharonClient(
    private val channel: ManagedChannel,
    private val deadlineSeconds: Long = 120,
    private val ownsChannel: Boolean = false,
) : CharonClient {
    private val stub = CharonServiceGrpcKt.CharonServiceCoroutineStub(channel)

    override suspend fun materialize(
        source: Location,
        target: Location,
        expectedFingerprint: String?,
    ): MoveResult =
        call {
            it.materialize(
                MaterializeRequest
                    .newBuilder()
                    .setSource(source)
                    .setTarget(target)
                    .apply { expectedFingerprint?.let { fp -> optionsBuilder.expectedSchemaFingerprint = fp } }
                    .build(),
            )
        }

    override suspend fun stage(
        source: Location,
        target: WorkerSessionDf,
        expectedFingerprint: String?,
    ): MoveResult =
        call {
            it.stage(
                StageRequest
                    .newBuilder()
                    .setSource(source)
                    .setTarget(target)
                    .apply { expectedFingerprint?.let { fp -> optionsBuilder.expectedSchemaFingerprint = fp } }
                    .build(),
            )
        }

    override suspend fun copy(
        source: Location,
        target: Location,
    ): MoveResult =
        call {
            it.copy(
                CopyRequest
                    .newBuilder()
                    .setSource(source)
                    .setTarget(target)
                    .build(),
            )
        }

    override suspend fun evict(location: Location): EvictResult =
        call { it.evict(EvictRequest.newBuilder().setLocation(location).build()) }

    override suspend fun describe(location: Location): DescribeResult =
        call { it.describe(DescribeRequest.newBuilder().setLocation(location).build()) }

    private suspend fun <T> call(block: suspend (CharonServiceGrpcKt.CharonServiceCoroutineStub) -> T): T =
        try {
            block(stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS))
        } catch (e: StatusException) {
            throw CharonException(e.status.code.name, e.status.description ?: e.message ?: "charon error", e)
        } catch (e: StatusRuntimeException) {
            throw CharonException(e.status.code.name, e.status.description ?: e.message ?: "charon error", e)
        }

    override fun close() {
        if (ownsChannel) channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        fun forAddress(
            host: String,
            port: Int,
        ): GrpcCharonClient =
            GrpcCharonClient(
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
