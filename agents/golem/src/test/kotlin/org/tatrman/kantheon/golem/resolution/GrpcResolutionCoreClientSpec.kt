package org.tatrman.kantheon.golem.resolution

import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.ResolverServiceGrpcKt

/**
 * RV-P5.1 T2(c) — the OTHER half of "a door error is a degrade, not an exception".
 *
 * `CallResolutionCoreSpec` proves the *step* degrades when the client raises
 * [ResolutionCoreException]. This proves the *client* raises it — that a real gRPC status
 * off a real (in-process) server is translated rather than escaping as a
 * `StatusRuntimeException` that no caller catches. Mocking the stub would have proven
 * neither, which is why pythia's dataplane clients use an in-process fixture server too.
 */
private class FixtureResolver(
    private val answer: (ResolveRequest) -> ResolveResponse,
) : ResolverServiceGrpcKt.ResolverServiceCoroutineImplBase() {
    override suspend fun resolve(request: ResolveRequest): ResolveResponse = answer(request)
}

private fun serve(
    name: String,
    impl: FixtureResolver,
): Server =
    InProcessServerBuilder
        .forName(name)
        .directExecutor()
        .addService(impl)
        .build()
        .start()

private fun clientFor(name: String) =
    GrpcResolutionCoreClient(InProcessChannelBuilder.forName(name).directExecutor().build())

class GrpcResolutionCoreClientSpec :
    StringSpec({

        "a recorded lattice survives the wire intact" {
            runTest {
                val name = InProcessServerBuilder.generateName()
                val server = serve(name, FixtureResolver { RecordedResolutionCore.response("h1-cs") })
                try {
                    val response = clientFor(name).resolve(ResolveRequest.getDefaultInstance())

                    // Round-tripped through real protobuf serialisation, not handed over in-memory.
                    response.resolutionState shouldBe RecordedResolutionCore.lattice("h1-cs")
                } finally {
                    server.shutdownNow()
                }
            }
        }

        "a gRPC status becomes a ResolutionCoreException carrying the code" {
            runTest {
                val name = InProcessServerBuilder.generateName()
                val server =
                    serve(
                        name,
                        FixtureResolver { throw StatusException(Status.UNAVAILABLE.withDescription("nlp is down")) },
                    )
                try {
                    val thrown =
                        shouldThrow<ResolutionCoreException> {
                            clientFor(name).resolve(ResolveRequest.getDefaultInstance())
                        }

                    thrown.code shouldBe "UNAVAILABLE"
                    thrown.message shouldBe "nlp is down"
                } finally {
                    server.shutdownNow()
                }
            }
        }

        "a resolver that answers without a resolution_state degrades rather than faking an empty lattice" {
            runTest {
                // Reachable in practice: `resolution_state` is field 7 and additive, so a
                // pre-RV-P2 server answers 200 with the field simply absent. An empty lattice
                // would read downstream as "understood you, found nothing".
                val name = InProcessServerBuilder.generateName()
                val server = serve(name, FixtureResolver { ResolveResponse.getDefaultInstance() })
                try {
                    val result =
                        callResolutionCoreStep(
                            question = "q",
                            conversationId = "conv",
                            locale = "cs",
                            referenceDatetime = "2026-08-06T00:00:00Z",
                            tenant = "hartland",
                            callerSubject = "user-1",
                            client = clientFor(name),
                        )

                    result.degrade shouldBe
                        CoreDegrade("NO_LATTICE", "the resolver answered without a resolution_state")
                    result.lattice shouldBe null
                } finally {
                    server.shutdownNow()
                }
            }
        }
    })
