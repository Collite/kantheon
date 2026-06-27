package org.tatrman.kantheon.pythia.dataplane

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.v1.DepthBudget

/**
 * Stage 4.1 T4 — evidence persistence + GC at finalisation: load-bearing handles are
 * persisted to `pythia-evidence` with the depth-derived retention tag; transient
 * handles are evicted. (Sticky-affinity scheduling is asserted in DataFrameNodeSpec.)
 */
class AffinityEvidenceGcSpec :
    StringSpec({

        "finalisation persists load-bearing handles (production tag) and evicts transient ones" {
            runTest {
                val handles = HandleTable()
                handles.putWorkerDf("h-keep", workerPod = "p", sessionId = "s1", dfName = "keep")
                handles.putWorkerDf("h-drop", workerPod = "p", sessionId = "s1", dfName = "drop")
                val fixture = FixtureCharonService(materializeFingerprint = "fp")
                val (server, channel) = startInProcess(fixture)
                try {
                    val charon = GrpcCharonClient(channel)
                    val policy = MaterialisationPolicy()
                    val mgr = EvidenceManager(charon, policy, Materialiser(charon))
                    val result =
                        mgr.finalise(
                            investigationId = "inv-1",
                            handles = handles,
                            loadBearingHandleIds = setOf("h-keep"),
                            depth = DepthBudget.DEPTH_NORMAL,
                            allHandleIds = listOf("h-keep", "h-drop"),
                        )
                    result.persisted shouldContain "h-keep"
                    result.evicted shouldContain "h-drop"
                    fixture.calls shouldContain "materialize:seaweed:pythia-evidence/inv-1/h-keep.arrow"
                    fixture.calls shouldContain "evict:worker:s1/drop"
                    fixture.materializeRetentionTags["seaweed:pythia-evidence/inv-1/h-keep.arrow"] shouldBe
                        RetentionTag.PRODUCTION
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                }
            }
        }

        "a SHALLOW investigation persists evidence with the shallow retention tag" {
            runTest {
                val handles = HandleTable()
                handles.putWorkerDf("h-keep", workerPod = "p", sessionId = "s1", dfName = "keep")
                val fixture = FixtureCharonService()
                val (server, channel) = startInProcess(fixture)
                try {
                    val charon = GrpcCharonClient(channel)
                    val mgr = EvidenceManager(charon, MaterialisationPolicy(), Materialiser(charon))
                    mgr.finalise("inv-2", handles, setOf("h-keep"), DepthBudget.DEPTH_SHALLOW, listOf("h-keep"))
                    fixture.materializeRetentionTags["seaweed:pythia-evidence/inv-2/h-keep.arrow"] shouldBe
                        RetentionTag.SHALLOW
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                }
            }
        }
    })
