package org.tatrman.kantheon.pythia.dataplane

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.v1.DataFrameNode
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.PlanNode

/**
 * Stage 4.1 T3 — the materialisation policy engine: each trigger (cross-engine
 * staging, evidence-persist, TTL-approach) fires the expected move on a crafted
 * state; nothing spurious otherwise. Pythia issues the move; Charon never decides.
 */
class MaterialisationPolicySpec :
    StringSpec({

        val policy = MaterialisationPolicy()

        fun rows(s: String) = Json.parseToJsonElement(s) as JsonArray

        fun dataframeNode(
            id: String,
            source: String,
        ) = PlanNode
            .newBuilder()
            .setNodeId(id)
            .setDataframe(DataFrameNode.newBuilder().setDfdsl("filter").setSourceHandleId(source))
            .build()

        "cross-engine staging: a DataFrameNode over an SQL snapshot fires a Stage move" {
            val handles = HandleTable()
            handles.putSnapshot("h1", rows("""[{"id":1}]"""))
            val move = policy.stageForCrossEngine(dataframeNode("N1", "h1"), handles, sessionId = "s1")
            move.shouldNotBeNull()
            move.shouldBeInstanceOf<MaterialisationMove.Stage>()
            move.sessionId shouldBe "s1"
            move.dfName shouldBe "h1"
        }

        "no spurious staging when the DataFrameNode source is already a worker DF" {
            val handles = HandleTable()
            handles.putWorkerDf("h1", workerPod = "p", sessionId = "s1", dfName = "df1")
            policy.stageForCrossEngine(dataframeNode("N1", "h1"), handles, sessionId = "s1").shouldBeNull()
        }

        "evidence-persist: a load-bearing snapshot fires a Persist with the depth retention tag" {
            val handles = HandleTable()
            val snap = handles.putSnapshot("h1", rows("""[{"id":1}]""")).handle
            val normal = policy.persistEvidence(snap, "inv-1", DepthBudget.DEPTH_NORMAL, loadBearing = true)
            normal.shouldNotBeNull()
            normal.bucket shouldBe "pythia-evidence"
            normal.key shouldBe "inv-1/h1.arrow"
            normal.retentionTag shouldBe RetentionTag.PRODUCTION

            val shallow = policy.persistEvidence(snap, "inv-1", DepthBudget.DEPTH_SHALLOW, loadBearing = true)
            shallow!!.retentionTag shouldBe RetentionTag.SHALLOW
        }

        "no evidence persist for a non-load-bearing handle, nor for an already-durable blob" {
            val handles = HandleTable()
            val snap = handles.putSnapshot("h1", rows("""[{"id":1}]""")).handle
            policy.persistEvidence(snap, "inv-1", DepthBudget.DEPTH_NORMAL, loadBearing = false).shouldBeNull()
            val blob = handles.putSeaweed("h2", url = "pythia-evidence/inv-1/h2.arrow")
            policy.persistEvidence(blob, "inv-1", DepthBudget.DEPTH_NORMAL, loadBearing = true).shouldBeNull()
        }

        "TTL-approach: a worker DF nearing its TTL fires a Persist; not-approaching does not" {
            val handles = HandleTable()
            val df = handles.putWorkerDf("h1", workerPod = "p", sessionId = "s1", dfName = "df1")
            policy.ttlPersist(df, "inv-1", ttlApproaching = true).shouldNotBeNull()
            policy.ttlPersist(df, "inv-1", ttlApproaching = false).shouldBeNull()
        }
    })
