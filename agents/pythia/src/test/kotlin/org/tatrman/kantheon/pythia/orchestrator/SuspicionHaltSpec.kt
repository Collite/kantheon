package org.tatrman.kantheon.pythia.orchestrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.tatrman.kantheon.pythia.suspicion.SuspicionClassifier
import org.tatrman.kantheon.pythia.suspicion.SuspicionPolicyHandler
import org.tatrman.kantheon.pythia.synth.ReasoningNodeExecutor
import org.tatrman.kantheon.pythia.synth.RenderNodeExecutor
import org.tatrman.kantheon.pythia.synth.Synthesizer
import org.tatrman.kantheon.pythia.v1.Caller
import org.tatrman.kantheon.pythia.v1.HitlPolicy
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.InvestigationContext
import org.tatrman.kantheon.pythia.v1.PlanApprovalPolicy
import org.tatrman.kantheon.pythia.v1.Status
import org.tatrman.kantheon.pythia.v1.SuspicionPolicy
import org.tatrman.kantheon.themis.v1.Themis
import java.util.UUID

private const val EMPTY_RESULT_PLAN =
    """{ "rationale": "r",
        "hypotheses": [ { "id": "H0", "statement": "data exists", "displayPriority": "HIDDEN" } ],
        "nodes": [ { "nodeId": "N1", "testsHypIds": ["H0"], "kind": "query", "queryRef": "q.returns", "paramsJson": "{}" } ],
        "edges": [] }"""

/**
 * Stage 3.1 — an `on_suspicious_result = HALT` policy parks AWAITING_USER_INPUT on
 * a dodgy (empty) result; `/answer` resumes and the investigation completes
 * without re-halting.
 */
class SuspicionHaltSpec :
    StringSpec({

        "an empty result under HALT policy parks AWAITING_USER_INPUT, then /answer resumes to DONE" {
            runTest {
                val emitter = EventEmitter(InMemoryEventRepository(), RecordingNatsPublisher())
                val investigations: InvestigationRepository = InMemoryInvestigationRepository()
                val llm = ScriptedPromptExecutor(listOf(EMPTY_RESULT_PLAN, "Conclusion."))
                val engine =
                    ExecutionEngine(
                        DagExecutor(
                            emitter,
                            InMemoryStepRepository(),
                            CompositeNodeExecutor(
                                query =
                                    QueryNodeExecutor(
                                        FakeQueryClient(rows = Json.parseToJsonElement("[]") as JsonArray),
                                    ),
                                render = RenderNodeExecutor(llm),
                                reasoning = ReasoningNodeExecutor(llm),
                            ),
                        ),
                        HypothesisEvaluator(emitter, executor = llm),
                        Synthesizer(llm, emitter),
                        suspicionClassifier = SuspicionClassifier(),
                        suspicionPolicy = SuspicionPolicyHandler(emitter),
                    )
                val orchestrator =
                    InvestigationOrchestrator(
                        investigations,
                        Checkpointer(InMemoryCheckpointRepository(), investigations),
                        emitter,
                        this,
                        awaitingTtlHours = 24,
                        resolver = Resolver(resolved()),
                        planner = Planner(PlanComposer(llm), PlanValidator(CapabilityChecker { true })),
                        executionEngine = engine,
                    )

                val id = orchestrator.submit(request(), "tok")
                advanceUntilIdle()
                Status.valueOf(investigations.findById(id)!!.status) shouldBe Status.STATUS_AWAITING_USER_INPUT

                orchestrator.answer(id)
                orchestrator.awaitTerminal(id) shouldBe Status.STATUS_DONE
            }
        }
    })

private fun resolved(): ThemisClient =
    object : ThemisClient {
        override suspend fun understand(
            request: Themis.ResolveRequest,
            bearer: String,
        ) = Themis.ResolveResponse
            .newBuilder()
            .setResolution(Themis.Resolution.newBuilder().setIntentKind(Themis.IntentKind.PROCEDURAL))
            .build()
    }

private fun request(): Investigation =
    Investigation
        .newBuilder()
        .setId(UUID.randomUUID().toString())
        .setQuestion("q")
        .setCaller(Caller.newBuilder().setKind(Caller.Kind.IRIS).setUserId("u1"))
        .setContext(InvestigationContext.newBuilder().setLocale("en"))
        .setHitlPolicy(
            HitlPolicy
                .newBuilder()
                .setPlanApproval(PlanApprovalPolicy.PLAN_APPROVAL_AUTO)
                .setOnSuspiciousResult(SuspicionPolicy.SUSPICION_HALT),
        ).build()
