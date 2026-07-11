package org.tatrman.kantheon.pythia.dataplane

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.tatrman.transfer.v1.DescribeResult
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.persistence.HandleRecipe

/**
 * Stage 4.1 T0 — PD-5 resume semantics (contracts §3a). On resume, a handle whose
 * Charon `Describe` reports `exists = false` is re-materialised from its checkpointed
 * recipe; a fingerprint mismatch (live or post-rematerialise) yields a Rule-6 warning
 * + a `LooseEnd` and continues — never a hard fail.
 */
class Pd5ResumeSpec :
    StringSpec({

        fun recipe(fingerprint: String): HandleRecipe =
            HandleRecipe(
                recipeKind = "charon_move",
                recipeJson =
                    Json.encodeToString(
                        CharonMoveRecipe.serializer(),
                        CharonMoveRecipe(
                            verb = "stage",
                            sourceConnection = "erp",
                            sourceSchema = "dbo",
                            sourceTable = "orders",
                        ),
                    ),
                arrowFingerprint = fingerprint,
            )

        "a dead handle is lazily re-materialised from its recipe (fingerprint unchanged → no warning)" {
            runTest {
                val handles = HandleTable()
                handles.putWorkerDf("h-dead", workerPod = "p", sessionId = "s1", dfName = "df1")
                val fixture = FixtureCharonService(stageFingerprint = "fp-orig")
                fixture.describeResults["worker:s1/df1"] = DescribeResult.newBuilder().setExists(false).build()
                val (server, channel) = startInProcess(fixture)
                try {
                    val result =
                        ResumeProber(GrpcCharonClient(channel))
                            .probe(listOf("h-dead"), handles, mapOf("h-dead" to recipe("fp-orig")))
                    result.rematerialised shouldContain "h-dead"
                    result.warnings.shouldBeEmpty()
                    fixture.calls shouldContain "stage:worker:s1/df1"
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                }
            }
        }

        "a live handle whose fingerprint drifted yields a warning + LooseEnd and continues" {
            runTest {
                val handles = HandleTable()
                handles.putWorkerDf("h-live", workerPod = "p", sessionId = "s1", dfName = "df2")
                val fixture = FixtureCharonService()
                fixture.describeResults["worker:s1/df2"] =
                    DescribeResult
                        .newBuilder()
                        .setExists(true)
                        .setSchemaFingerprint("fp-changed")
                        .build()
                val (server, channel) = startInProcess(fixture)
                try {
                    val result =
                        ResumeProber(GrpcCharonClient(channel))
                            .probe(listOf("h-live"), handles, mapOf("h-live" to recipe("fp-orig")))
                    result.rematerialised.contains("h-live") shouldBe false
                    result.warnings shouldContain "inputs changed during pause: h-live"
                    result.looseEnds.size shouldBe 1
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                }
            }
        }
    })
