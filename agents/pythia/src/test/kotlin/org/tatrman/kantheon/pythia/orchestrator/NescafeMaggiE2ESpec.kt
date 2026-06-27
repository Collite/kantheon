package org.tatrman.kantheon.pythia.orchestrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.clients.FakeQueryClient
import org.tatrman.kantheon.pythia.evaluate.HypothesisEvaluator
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.executor.CompositeNodeExecutor
import org.tatrman.kantheon.pythia.executor.DagExecutor
import org.tatrman.kantheon.pythia.executor.QueryNodeExecutor
import org.tatrman.kantheon.pythia.persistence.InMemoryCheckpointRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryHypothesisRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryInvestigationRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryStepRepository
import org.tatrman.kantheon.pythia.persistence.InvestigationRepository
import org.tatrman.kantheon.pythia.plan.CapabilityChecker
import org.tatrman.kantheon.pythia.plan.PlanComposer
import org.tatrman.kantheon.pythia.plan.PlanValidator
import org.tatrman.kantheon.pythia.plan.Planner
import org.tatrman.kantheon.pythia.plan.ScriptedPromptExecutor
import org.tatrman.kantheon.pythia.resolve.Resolver
import org.tatrman.kantheon.pythia.resolve.ThemisClient
import org.tatrman.kantheon.pythia.persistence.Checkpointer
import org.tatrman.kantheon.pythia.synth.ReasoningNodeExecutor
import org.tatrman.kantheon.pythia.synth.RenderNodeExecutor
import org.tatrman.kantheon.pythia.synth.Synthesizer
import org.tatrman.kantheon.pythia.v1.Caller
import org.tatrman.kantheon.pythia.v1.HitlPolicy
import org.tatrman.kantheon.pythia.v1.HypStatus
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.InvestigationContext
import org.tatrman.kantheon.pythia.v1.PlanApprovalPolicy
import org.tatrman.kantheon.pythia.v1.Status
import org.tatrman.kantheon.themis.v1.Themis
import java.util.UUID

private const val NESCAFE_PLAN =
    """
    { "rationale": "fetch returners, join Maggi revenue decline, render the table",
      "hypotheses": [
        { "id": "H0", "statement": "the data exists for this question", "displayPriority": "HIDDEN",
          "predicate": { "kind": "ROW_COUNT_GT", "threshold": 0 } } ],
      "nodes": [
        { "nodeId": "N1", "testsHypIds": ["H0"], "kind": "query",
          "queryRef": "returnsByCustomerForBrandInPeriod", "paramsJson": "{\"brand\":412}" },
        { "nodeId": "N2", "testsHypIds": ["H0"], "kind": "query",
          "queryRef": "revenueByCustomerByBrandByQuarter", "paramsJson": "{}" },
        { "nodeId": "N4", "kind": "render", "renderKind": "TABLE", "blockRole": "PRIMARY",
          "caption": "Customers with Maggi revenue decline", "inputHandleIds": ["h-N2"] } ],
      "edges": [
        { "fromNodeId": "N1", "toNodeId": "N2", "binding": "N2.customerIds = N1.customerId" },
        { "fromNodeId": "N2", "toNodeId": "N4", "binding": "N4.input = N2.output" } ] }
    """

private const val SYNTH_LEAD = "Found 23 customers matching the criteria."

/**
 * Stage 2.4 T4 — the Nescafe-Maggi worked example (design §4.1) end-to-end against
 * scripted LLM + a fake query edge: resolution → plan → execution → rules-first
 * evaluation → synthesis → conclusion, asserted on the load-bearing event trace
 * and the final artifact. The Phase-2 DONE gate.
 */
class NescafeMaggiE2ESpec :
    StringSpec({

        "the Nescafe-Maggi investigation runs end-to-end to DONE" {
            runTest {
                val events = InMemoryEventRepository()
                val emitter = EventEmitter(events, RecordingNatsPublisher())
                val investigations: InvestigationRepository = InMemoryInvestigationRepository()
                val hypotheses = InMemoryHypothesisRepository()
                val steps = InMemoryStepRepository()

                // One shared scripted executor: reply[0] is the plan, reply[1] the synth lead.
                val llm = ScriptedPromptExecutor(listOf(NESCAFE_PLAN, SYNTH_LEAD))
                val rows =
                    Json.parseToJsonElement(
                        (1..23).joinToString(",", "[", "]") { """{"customer_id":$it}""" },
                    ) as JsonArray

                val resolver = Resolver(resolvedThemis())
                val planner = Planner(PlanComposer(llm), PlanValidator(CapabilityChecker { true }))
                val engine =
                    ExecutionEngine(
                        DagExecutor(
                            emitter,
                            steps,
                            CompositeNodeExecutor(
                                query = QueryNodeExecutor(FakeQueryClient(rows = rows)),
                                render = RenderNodeExecutor(llm),
                                reasoning = ReasoningNodeExecutor(llm),
                            ),
                        ),
                        HypothesisEvaluator(emitter),
                        Synthesizer(llm, emitter),
                    )
                val orchestrator =
                    InvestigationOrchestrator(
                        investigations,
                        Checkpointer(InMemoryCheckpointRepository(), investigations),
                        emitter,
                        this,
                        awaitingTtlHours = 24,
                        resolver = resolver,
                        planner = planner,
                        executionEngine = engine,
                        hypothesesRepo = hypotheses,
                    )

                val id = orchestrator.submit(nescafeRequest(), "tok")
                orchestrator.awaitTerminal(id) shouldBe Status.STATUS_DONE

                // Full event trace milestones (design §4.1).
                events.replay(id, 0L).map { it.kind } shouldContainInOrder
                    listOf(
                        "INVESTIGATION_SUBMITTED",
                        "RESOLUTION_STARTED",
                        "PLAN_DRAFTED",
                        "BATCH_LAUNCHED",
                        "STEP_COMPLETED",
                        "HYPOTHESIS_SUPPORTED",
                        "SYNTHESIZER_DONE",
                        "CONCLUSION",
                        "INVESTIGATION_DONE",
                    )

                // The trivial hypothesis is SUPPORTED (23 returners > 0) and persisted.
                hypotheses.findByInvestigation(id).single().status shouldBe HypStatus.HYP_SUPPORTED.name

                // The conclusion carries the synth lead + the rendered TABLE block; goal-reached.
                val rec = investigations.findById(id)!!
                rec.conclusionJson!!.contains(SYNTH_LEAD) shouldBe true
                rec.conclusionJson!!.contains("STOP_GOAL_REACHED") shouldBe true
                rec.conclusionJson!!.contains("Customers with Maggi revenue decline") shouldBe true
            }
        }
    })

private fun resolvedThemis(): ThemisClient =
    object : ThemisClient {
        override suspend fun understand(
            request: Themis.ResolveRequest,
            bearer: String,
        ): Themis.ResolveResponse =
            Themis.ResolveResponse
                .newBuilder()
                .setResolution(Themis.Resolution.newBuilder().setIntentKind(Themis.IntentKind.PROCEDURAL))
                .build()
    }

private fun nescafeRequest(): Investigation =
    Investigation
        .newBuilder()
        .setId(UUID.randomUUID().toString())
        .setQuestion(
            "Customers who returned Nescafe in the last year and whose Maggi revenue dropped over the last 2 quarters.",
        ).setCaller(Caller.newBuilder().setKind(Caller.Kind.IRIS).setUserId("u1"))
        .setContext(InvestigationContext.newBuilder().setLocale("en"))
        .setHitlPolicy(HitlPolicy.newBuilder().setPlanApproval(PlanApprovalPolicy.PLAN_APPROVAL_AUTO))
        .build()
