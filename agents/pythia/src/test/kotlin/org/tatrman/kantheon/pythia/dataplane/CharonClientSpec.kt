package org.tatrman.kantheon.pythia.dataplane

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.charon.v1.Location
import org.tatrman.charon.v1.SeaweedBlob
import org.tatrman.charon.v1.WorkerSessionDf

/**
 * Stage 4.1 T1 — the gRPC `CharonClient` against an in-process Charon
 * fixture-server: each of the five RPCs round-trips, and a legality-matrix
 * rejection (the fixture throwing INVALID_ARGUMENT) surfaces as a [CharonException]
 * carrying the gRPC status code. Live Charon is the integration suite.
 */
class CharonClientSpec :
    StringSpec({

        fun seaweed(
            bucket: String,
            key: String,
        ) = Location.newBuilder().setSeaweed(SeaweedBlob.newBuilder().setBucket(bucket).setKey(key)).build()

        "materialize / stage / copy / evict / describe each round-trip through the gRPC client" {
            runTest {
                val fixture = FixtureCharonService(materializeFingerprint = "fp-m", stageFingerprint = "fp-s")
                val (server, channel) = startInProcess(fixture)
                val client = GrpcCharonClient(channel)
                try {
                    client
                        .materialize(
                            seaweed("b", "k"),
                            seaweed("pythia-evidence", "i/h.arrow"),
                        ).schemaFingerprint shouldBe
                        "fp-m"
                    val target =
                        WorkerSessionDf
                            .newBuilder()
                            .setSessionId("s1")
                            .setDfName("d1")
                            .build()
                    client.stage(seaweed("b", "k"), target).schemaFingerprint shouldBe "fp-s"
                    client.copy(seaweed("b", "k"), seaweed("b", "k2"))
                    client.evict(seaweed("b", "k")).existed shouldBe true
                    client.describe(seaweed("b", "k")).exists shouldBe true

                    fixture.calls shouldContain "stage:worker:s1/d1"
                    fixture.calls shouldContain "evict:seaweed:b/k"
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                }
            }
        }

        "a legality-matrix rejection surfaces as a CharonException with the gRPC code" {
            runTest {
                val fixture = FixtureCharonService(rejectMaterialize = true)
                val (server, channel) = startInProcess(fixture)
                val client = GrpcCharonClient(channel)
                try {
                    val ex =
                        shouldThrow<CharonException> {
                            client.materialize(seaweed("b", "k"), seaweed("pythia-evidence", "i/h.arrow"))
                        }
                    ex.code shouldBe "INVALID_ARGUMENT"
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                }
            }
        }
    })
