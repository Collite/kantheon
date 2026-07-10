package org.tatrman.kantheon.pythia.executor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.clients.FakeQueryClient
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.orchestrator.RecordingNatsPublisher
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryStepRepository
import org.tatrman.kantheon.pythia.v1.Handle
import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.kantheon.pythia.v1.QueryNode
import java.util.UUID

private fun queryNode(
    id: String,
    paramsJson: String = "{}",
    stack: List<String> = emptyList(),
): PlanNode =
    PlanNode
        .newBuilder()
        .setNodeId(id)
        .setQuery(
            QueryNode
                .newBuilder()
                .setQueryRef("q.x")
                .setParamsJson(paramsJson)
                .addAllStack(stack),
        ).build()

/**
 * Stage 2.3 T1/T2 — the QueryNode executor: runs query-mcp queries (compile-
 * before-run for stacks, OBO bearer, pipeline_warnings forwarded), resolves
 * HandleRef params, enforces the IN-list ≤ 500 rule, and parks on token expiry.
 */
class QueryNodeExecutorSpec :
    StringSpec({

        fun rows(s: String) = Json.parseToJsonElement(s) as JsonArray

        "runs a query, stores a PgResultSnapshot, and forwards pipeline_warnings" {
            runTest {
                val client = FakeQueryClient(rows = rows("""[{"id":1},{"id":2}]"""), warnings = listOf("rls applied"))
                val handles = HandleTable()
                val result = QueryNodeExecutor(client).execute(queryNode("N1"), NodeContext(handles, "tok"))
                result.warnings shouldContainExactly listOf("rls applied")
                result.outputHandle!!.kindCase shouldBe Handle.KindCase.PG_SNAPSHOT
                client.lastBearer shouldBe "tok"
                handles.get("h-N1") shouldBe result.outputHandle
            }
        }

        "compiles before running when the node carries a stack" {
            runTest {
                val client = FakeQueryClient(rows = rows("""[{"id":1}]"""))
                QueryNodeExecutor(
                    client,
                ).execute(queryNode("N1", stack = listOf("filter")), NodeContext(HandleTable(), "tok"))
                client.compiled shouldBe true
            }
        }

        "resolves a HandleRef param from an upstream handle before querying" {
            runTest {
                val handles = HandleTable()
                handles.putSnapshot("H1", rows("""[{"customer_id":10},{"customer_id":20}]"""))
                val client = FakeQueryClient(rows = rows("[]"))
                val ref = """{ "${'$'}handleRef": { "handle": "H1", "projection": "customer_id" } }"""
                val params = """{ "ids": $ref }"""
                QueryNodeExecutor(client).execute(queryNode("N2", paramsJson = params), NodeContext(handles, "tok"))
                client.lastParamsJson!! shouldContain "[10,20]"
                client.lastParamsJson!!.contains("handleRef") shouldBe false
            }
        }

        "an IN-list above 500 is flagged for materialise (permanent failure)" {
            runTest {
                val handles = HandleTable()
                val big = (1..501).joinToString(",", "[", "]") { """{"customer_id":$it}""" }
                handles.putSnapshot("H1", rows(big))
                val ref = """{ "${'$'}handleRef": { "handle": "H1", "projection": "customer_id" } }"""
                val params = """{ "ids": $ref }"""
                val ex =
                    shouldThrow<NodeExecutionException> {
                        QueryNodeExecutor(
                            FakeQueryClient(),
                        ).execute(queryNode("N3", paramsJson = params), NodeContext(handles, "tok"))
                    }
                ex.kind shouldBe FailureKind.PERMANENT
                ex.message!! shouldContain "materialise"
            }
        }

        "token expiry surfaces through the executor as ExecOutcome.NeedsReauth" {
            runTest {
                val emitter = EventEmitter(InMemoryEventRepository(), RecordingNatsPublisher())
                val executor =
                    DagExecutor(
                        emitter,
                        InMemoryStepRepository(),
                        QueryNodeExecutor(FakeQueryClient(throwTokenExpiry = true)),
                    )
                val plan = PlanDag.newBuilder().addNodes(queryNode("N1")).build()
                val outcome = executor.execute(UUID.randomUUID(), plan, mutableSetOf(), HandleTable(), bearer = "stale")
                outcome.shouldBeInstanceOf<ExecOutcome.NeedsReauth>()
            }
        }
    })
