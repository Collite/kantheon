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
import org.tatrman.kantheon.pythia.persistence.Checkpointer
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
import org.tatrman.kantheon.pythia.revise.PlanReviser
import org.tatrman.kantheon.pythia.suspicion.SuspicionClassifier
import org.tatrman.kantheon.pythia.suspicion.SuspicionPolicyHandler
import org.tatrman.kantheon.pythia.synth.ReasoningNodeExecutor
import org.tatrman.kantheon.pythia.synth.RenderNodeExecutor
import org.tatrman.kantheon.pythia.synth.Synthesizer
import org.tatrman.kantheon.pythia.v1.Caller
import org.tatrman.kantheon.pythia.v1.Constraints
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.HitlPolicy
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.InvestigationContext
import org.tatrman.kantheon.pythia.v1.PlanApprovalPolicy
import org.tatrman.kantheon.pythia.v1.Status
import org.tatrman.kantheon.themis.v1.Themis
import java.util.UUID

// Initial RCA plan: two drivers, each supported (ROW_COUNT_GT 0), explanatory 0.4 + 0.2 = 0.6 (< 0.75 goal).
private const val RCA_PLAN =
    """
    { "rationale": "decompose the YoY Private-channel drop",
      "hypotheses": [
        { "id": "HB", "statement": "lower order value", "displayPriority": "PRIMARY",
          "estimatedExplanatoryPower": 0.4, "predicate": { "kind": "ROW_COUNT_GT", "threshold": 0 } },
        { "id": "HD", "statement": "product mix shift", "displayPriority": "PRIMARY",
          "estimatedExplanatoryPower": 0.2, "predicate": { "kind": "ROW_COUNT_GT", "threshold": 0 } } ],
      "nodes": [
        { "nodeId": "N1", "testsHypIds": ["HB"], "kind": "query", "queryRef": "avgOrderValue", "paramsJson": "{}" },
        { "nodeId": "N2", "testsHypIds": ["HD"], "kind": "query", "queryRef": "mixByFamily", "paramsJson": "{}" },
        { "nodeId": "N3", "kind": "render", "renderKind": "TABLE", "blockRole": "PRIMARY", "inputHandleIds": ["h-N1"] } ],
      "edges": [] }
    """

// Reviser DECOMPOSE: a child (explanatory 0.2) pushes explained variance to 0.8 (>= 0.75 → goal next round).
private const val REVISER_DECOMPOSE =
    """
    { "action": "DECOMPOSE", "affectedHypIds": ["HB"],
      "newHypotheses": [ { "id": "HB1", "parentId": "HB", "statement": "AOV drop in SMB",
        "estimatedExplanatoryPower": 0.2, "predicate": { "kind": "ROW_COUNT_GT", "threshold": 0 } } ],
      "rationale": "deepen HB into the SMB segment" }
    """

private const val SYNTH_LEAD = "Private-channel revenue fell mainly on lower order value, concentrated in SMB."

/**
 * Stage 3.3 T3 — the RCA worked example (design §4.2) end-to-end: initial plan →
 * verdicts → prioritisation/deepening → a plan revision (DECOMPOSE) → further
 * evidence → goal reached → synthesis with the heuristic explained-variance caveat.
 * The Phase-3 DONE gate.
 */
class RcaE2ESpec :
    StringSpec({

        "the RCA investigation deepens once and concludes with a heuristic confidence" {
            runTest {
                val events = InMemoryEventRepository()
                val emitter = EventEmitter(events, RecordingNatsPublisher())
                val investigations: InvestigationRepository = InMemoryInvestigationRepository()
                val hypotheses = InMemoryHypothesisRepository()

                // reply[0]=plan, reply[1]=reviser DECOMPOSE, reply[2]=synth lead.
                val llm = ScriptedPromptExecutor(listOf(RCA_PLAN, REVISER_DECOMPOSE, SYNTH_LEAD))
                val rows = Json.parseToJsonElement((1..30).joinToString(",", "[", "]") { """{"v":$it}""" }) as JsonArray

                val engine =
                    ExecutionEngine(
                        DagExecutor(
                            emitter,
                            InMemoryStepRepository(),
                            CompositeNodeExecutor(
                                query = QueryNodeExecutor(FakeQueryClient(rows = rows)),
                                render = RenderNodeExecutor(llm),
                                reasoning = ReasoningNodeExecutor(llm),
                            ),
                        ),
                        HypothesisEvaluator(emitter, executor = llm),
                        Synthesizer(llm, emitter),
                        suspicionClassifier = SuspicionClassifier(),
                        suspicionPolicy = SuspicionPolicyHandler(emitter),
                        reviser = PlanReviser(llm, PlanValidator(CapabilityChecker { true }), emitter),
                    )
                val orchestrator =
                    InvestigationOrchestrator(
                        investigations,
                        Checkpointer(InMemoryCheckpointRepository(), investigations),
                        emitter,
                        this,
                        awaitingTtlHours = 24,
                        resolver = Resolver(rcaThemis()),
                        planner = Planner(PlanComposer(llm), PlanValidator(CapabilityChecker { true })),
                        executionEngine = engine,
                        hypothesesRepo = hypotheses,
                    )

                val id = orchestrator.submit(rcaRequest(), "tok")
                orchestrator.awaitTerminal(id) shouldBe Status.STATUS_DONE

                // The deepening trace: verdicts → prioritisation → deepening → plan revision → more verdicts.
                events.replay(id, 0L).map { it.kind } shouldContainInOrder
                    listOf(
                        "PLAN_DRAFTED",
                        "HYPOTHESIS_SUPPORTED",
                        "HYPOTHESES_PRIORITIZED",
                        "DEEPENING_DECISION",
                        "PLAN_REVISED",
                        "HYPOTHESIS_SUPPORTED",
                        "CONCLUSION",
                        "INVESTIGATION_DONE",
                    )

                val rec = investigations.findById(id)!!
                // the plan was revised once (DECOMPOSE bumped revision to 1)…
                rec.planJson!!.contains("\"revision\":1") shouldBe true
                // …the child hypothesis was added + supported…
                hypotheses.findByInvestigation(id).any { it.hypId == "HB1" && it.status == "HYP_SUPPORTED" } shouldBe
                    true
                // …and the conclusion carries the heuristic explained-variance caveat.
                rec.conclusionJson!!.contains("CONFIDENCE_HEURISTIC") shouldBe true
                rec.conclusionJson!!.contains("approximate") shouldBe true
                rec.conclusionJson!!.contains(SYNTH_LEAD) shouldBe true
            }
        }
    })

private fun rcaThemis(): ThemisClient =
    object : ThemisClient {
        override suspend fun understand(
            request: Themis.ResolveRequest,
            bearer: String,
        ) = Themis.ResolveResponse
            .newBuilder()
            .setResolution(Themis.Resolution.newBuilder().setIntentKind(Themis.IntentKind.RCA))
            .build()
    }

private fun rcaRequest(): Investigation =
    Investigation
        .newBuilder()
        .setId(UUID.randomUUID().toString())
        .setQuestion("Proč je naše tržba YoY nižší pro kanál Private?")
        .setCaller(Caller.newBuilder().setKind(Caller.Kind.IRIS).setUserId("u1"))
        .setContext(InvestigationContext.newBuilder().setLocale("cs"))
        .setConstraints(Constraints.newBuilder().setDepthBudget(DepthBudget.DEPTH_NORMAL))
        .setHitlPolicy(HitlPolicy.newBuilder().setPlanApproval(PlanApprovalPolicy.PLAN_APPROVAL_AUTO))
        .build()
