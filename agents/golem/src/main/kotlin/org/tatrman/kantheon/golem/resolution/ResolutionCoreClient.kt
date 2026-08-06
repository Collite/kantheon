package org.tatrman.kantheon.golem.resolution

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.ResolverServiceGrpcKt
import java.util.concurrent.TimeUnit

/**
 * RV-P5.1 — the seam to `org.tatrman.resolver.v1.ResolverService`, the deterministic
 * resolution core. One rpc at this phase: `Resolve` (= `resolve.bind:v1`), which returns
 * the whole annotation lattice. `Gate` (`resolve.gate:v1`) joins in RV-P5.2, when there is
 * a ladder to propose hypotheses for it to gate — adding it here would be a method nothing
 * calls.
 *
 * **The Golem never binds** (RV-7). This client reads a lattice and hands it on; nothing
 * downstream of it constructs a `Binding`, and P5.2 adds a grep-fence test that says so.
 *
 * Shaped after `pythia/dataplane/CharonClient.kt` — interface + channel-taking impl +
 * `forAddress`, so tests inject an in-process fixture server rather than mocking the stub.
 * (⚑ Correcting the P5.1 T1 recon, which named `themis/client/FuzzyServiceClient.kt` as the
 * precedent: that client is **REST/JSON over Ktor**, not gRPC. The gRPC precedents in this
 * repo are pythia's three dataplane clients and the two MCP wrappers.)
 */
interface ResolutionCoreClient : AutoCloseable {
    /** `resolve.bind:v1` — text in, annotation lattice out. Spends no LLM budget. */
    suspend fun resolve(request: ResolveRequest): ResolveResponse

    override fun close() {}
}

/**
 * The core door refused or was unreachable. Carries the gRPC status name so the caller can
 * branch — a turn degrades on this, it does not throw (P5.1 T2(c)): an exception escaping a
 * Koog node aborts the strategy, and "the resolver is down" is a thing the conversation
 * should be able to say.
 */
class ResolutionCoreException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** gRPC implementation over a [ManagedChannel]. */
class GrpcResolutionCoreClient(
    private val channel: ManagedChannel,
    private val deadlineSeconds: Long = 30,
) : ResolutionCoreClient {
    private val stub = ResolverServiceGrpcKt.ResolverServiceCoroutineStub(channel)

    override suspend fun resolve(request: ResolveRequest): ResolveResponse =
        try {
            stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).resolve(request)
        } catch (e: StatusException) {
            throw ResolutionCoreException(e.status.code.name, e.status.description ?: "resolve failed", e)
        } catch (e: StatusRuntimeException) {
            throw ResolutionCoreException(e.status.code.name, e.status.description ?: "resolve failed", e)
        }

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        fun forAddress(
            host: String,
            port: Int,
            deadlineSeconds: Long = 30,
        ): GrpcResolutionCoreClient =
            GrpcResolutionCoreClient(
                ManagedChannelBuilder.forAddress(host, port).usePlaintext().build(),
                deadlineSeconds,
            )
    }
}
