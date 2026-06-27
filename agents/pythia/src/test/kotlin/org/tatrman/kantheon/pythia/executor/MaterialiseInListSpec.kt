package org.tatrman.kantheon.pythia.executor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.clients.FakeQueryClient
import org.tatrman.kantheon.pythia.dataplane.DefaultInListMaterialiser
import org.tatrman.kantheon.pythia.dataplane.FakeWorkerClient
import org.tatrman.kantheon.pythia.dataplane.Materialiser
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.kantheon.pythia.v1.QueryNode

/**
 * Stage 4.1 T7 — the IN-list>500 materialise path. With a materialiser wired, an
 * over-cap id-list is staged into a worker DF (pushed from the snapshot) and the
 * param rebound to a `$dfRef` (a semi-join) instead of inlining — no PERMANENT flag.
 * Without one, the over-cap list stays a PERMANENT materialise flag (Phase-2 guard).
 */
class MaterialiseInListSpec :
    StringSpec({

        fun rows(s: String) = Json.parseToJsonElement(s) as JsonArray

        fun queryNode(
            id: String,
            paramsJson: String,
        ) = PlanNode
            .newBuilder()
            .setNodeId(id)
            .setQuery(QueryNode.newBuilder().setQueryRef("q.x").setParamsJson(paramsJson))
            .build()

        val ref = """{ "${'$'}handleRef": { "handle": "H1", "projection": "customer_id" } }"""
        val params = """{ "ids": $ref }"""

        "an over-cap id-list materialises to a worker DF and binds via a ${'$'}dfRef (no PERMANENT)" {
            runTest {
                val handles = HandleTable()
                val big = (1..501).joinToString(",", "[", "]") { """{"customer_id":$it}""" }
                handles.putSnapshot("H1", rows(big))
                val worker = FakeWorkerClient()
                val materialiser =
                    Materialiser(
                        org.tatrman.kantheon.pythia.dataplane
                            .NoopCharonClient(),
                    )
                val client = FakeQueryClient(rows = rows("[]"))
                val executor =
                    QueryNodeExecutor(client, inListMaterialiser = DefaultInListMaterialiser(worker, materialiser))
                executor.execute(queryNode("N3", params), NodeContext(handles, "tok", sessionId = "inv-1"))

                worker.imports.single().let { (session, df, n) ->
                    session shouldBe "inv-1"
                    df shouldBe "H1-staged"
                    n shouldBe 501
                }
                client.lastParamsJson!! shouldContain "\$dfRef"
                client.lastParamsJson!! shouldContain "H1-staged"
                client.lastParamsJson!! shouldNotContain "customer_id"
            }
        }

        "without a materialiser, an over-cap list stays a PERMANENT materialise flag" {
            runTest {
                val handles = HandleTable()
                val big = (1..501).joinToString(",", "[", "]") { """{"customer_id":$it}""" }
                handles.putSnapshot("H1", rows(big))
                val ex =
                    shouldThrow<NodeExecutionException> {
                        QueryNodeExecutor(
                            FakeQueryClient(),
                        ).execute(queryNode("N3", params), NodeContext(handles, "tok"))
                    }
                ex.kind shouldBe FailureKind.PERMANENT
                ex.message!! shouldContain "materialise"
            }
        }
    })
