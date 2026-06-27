package org.tatrman.kantheon.pythia.evaluate

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.clients.FakeQueryClient
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.executor.DagExecutor
import org.tatrman.kantheon.pythia.executor.ExecOutcome
import org.tatrman.kantheon.pythia.executor.QueryNodeExecutor
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.orchestrator.RecordingNatsPublisher
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryStepRepository
import org.tatrman.kantheon.pythia.v1.HypStatus
import org.tatrman.kantheon.pythia.v1.Hypothesis
import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.kantheon.pythia.v1.Predicate
import org.tatrman.kantheon.pythia.v1.QueryNode
import java.util.UUID

/**
 * Stage 2.3 T6 — the plan → execute → evaluate spine on a trivial-hypothesis
 * fixture: one hypothesis, one QueryNode, one predicate. The executor runs the
 * query (storing a handle), and the rules-first evaluator returns a verdict from
 * the handle's rows. This is the spine the Nescafe-Maggi e2e (Stage 2.4) extends.
 */
class ExecuteEvaluateSpec :
    StringSpec({

        "plan → execute → evaluate yields a SUPPORTED verdict on the trivial fixture" {
            runTest {
                val id = UUID.randomUUID()
                val emitter = EventEmitter(InMemoryEventRepository(), RecordingNatsPublisher())
                val handles = HandleTable()

                // 23 returner rows → ROW_COUNT_GT 0 should be SUPPORTED.
                val rows =
                    Json.parseToJsonElement(
                        (1..23).joinToString(",", "[", "]") { """{"customer_id":$it}""" },
                    ) as JsonArray
                val executor =
                    DagExecutor(emitter, InMemoryStepRepository(), QueryNodeExecutor(FakeQueryClient(rows = rows)))

                val plan =
                    PlanDag
                        .newBuilder()
                        .addNodes(
                            PlanNode
                                .newBuilder()
                                .setNodeId("N1")
                                .addTestsHypIds("H1")
                                .setQuery(QueryNode.newBuilder().setQueryRef("q.returns").setParamsJson("{}")),
                        ).build()

                val outcome = executor.execute(id, plan, mutableSetOf(), handles, bearer = "tok")
                outcome.shouldBeInstanceOf<ExecOutcome.Completed>()

                // Roll the produced handle's rows up by step id, then evaluate the hypothesis.
                val rowsByStep = mapOf("step-N1" to (handles.rows("h-N1") ?: JsonArray(emptyList())))
                val predicate =
                    Predicate
                        .newBuilder()
                        .setKind(Predicate.Kind.ROW_COUNT_GT)
                        .setThreshold(0.0)
                        .build()
                val hypothesis =
                    Hypothesis
                        .newBuilder()
                        .setId("H1")
                        .setPredicate(predicate)
                        .addTestStepIds("step-N1")
                        .build()

                val verdicts = HypothesisEvaluator(emitter).evaluate(id, listOf(hypothesis), rowsByStep)
                verdicts.single().status shouldBe HypStatus.HYP_SUPPORTED
                verdicts.single().confidence shouldBe 0.9
            }
        }
    })
