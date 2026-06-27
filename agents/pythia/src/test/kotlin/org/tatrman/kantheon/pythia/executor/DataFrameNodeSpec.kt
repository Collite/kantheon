package org.tatrman.kantheon.pythia.executor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.pythia.dataplane.FakeWorkerClient
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.v1.DataFrameNode
import org.tatrman.kantheon.pythia.v1.Handle
import org.tatrman.kantheon.pythia.v1.PlanNode

/**
 * Stage 4.1 T5 — the DataFrameNode executor: runs a dfdsl op over a staged session
 * DF and produces a `WorkerSessionDF` handle; a two-node chain reuses the session
 * (sticky affinity — Polars chains stay on one pod).
 */
class DataFrameNodeSpec :
    StringSpec({

        fun dfNode(
            id: String,
            source: String,
            dfdsl: String = "filter(x>0)",
        ) = PlanNode
            .newBuilder()
            .setNodeId(id)
            .setDataframe(DataFrameNode.newBuilder().setDfdsl(dfdsl).setSourceHandleId(source))
            .build()

        "runs a dfdsl op over a staged session DF → a WorkerSessionDF handle" {
            runTest {
                val handles = HandleTable()
                handles.putWorkerDf("h-src", workerPod = "pod-1", sessionId = "s1", dfName = "df0")
                val worker = FakeWorkerClient(rowCount = 5)
                val result = DataFrameNodeExecutor(worker).execute(dfNode("N1", "h-src"), NodeContext(handles, "tok"))
                result.outputHandle!!.kindCase shouldBe Handle.KindCase.WORKER_DF
                result.outputHandle!!.workerDf.sessionId shouldBe "s1"
                result.outputHandle!!.workerDf.dfName shouldBe "N1"
                handles.get("h-N1")!!.workerDf.workerPod shouldBe "pod-1"
            }
        }

        "a two-node DataFrame chain reuses the same worker session" {
            runTest {
                val handles = HandleTable()
                handles.putWorkerDf("h-src", workerPod = "pod-1", sessionId = "s1", dfName = "df0")
                val worker = FakeWorkerClient(rowCount = 5)
                val exec = DataFrameNodeExecutor(worker)
                exec.execute(dfNode("N1", "h-src"), NodeContext(handles, "tok"))
                exec.execute(dfNode("N2", "h-N1"), NodeContext(handles, "tok"))
                worker.ops.map { it.sessionId } shouldBe listOf("s1", "s1")
                worker.ops.map { it.outputDfName } shouldBe listOf("N1", "N2")
            }
        }

        "a missing / wrong-kind source handle is a PERMANENT failure" {
            runTest {
                val handles = HandleTable()
                handles.putSnapshot(
                    "h-sql",
                    kotlinx.serialization.json.Json
                        .parseToJsonElement("""[{"id":1}]""") as kotlinx.serialization.json.JsonArray,
                )
                val ex =
                    shouldThrow<NodeExecutionException> {
                        DataFrameNodeExecutor(FakeWorkerClient())
                            .execute(dfNode("N1", "h-sql"), NodeContext(handles, "tok"))
                    }
                ex.kind shouldBe FailureKind.PERMANENT
            }
        }
    })
