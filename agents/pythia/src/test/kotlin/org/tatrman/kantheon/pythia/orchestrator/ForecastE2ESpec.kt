package org.tatrman.kantheon.pythia.orchestrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.clients.FakeQueryClient
import org.tatrman.kantheon.pythia.dataplane.FakeMetisClient
import org.tatrman.kantheon.pythia.evaluate.HypothesisEvaluator
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.executor.CompositeNodeExecutor
import org.tatrman.kantheon.pythia.executor.DagExecutor
import org.tatrman.kantheon.pythia.executor.ModelNodeExecutor
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
import org.tatrman.kantheon.pythia.synth.ReasoningNodeExecutor
import org.tatrman.kantheon.pythia.synth.RenderNodeExecutor
import org.tatrman.kantheon.pythia.synth.Synthesizer
import org.tatrman.kantheon.pythia.v1.Caller
import org.tatrman.kantheon.pythia.v1.Constraints
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.HitlPolicy
import org.tatrman.kantheon.pythia.v1.HypStatus
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.InvestigationContext
import org.tatrman.kantheon.pythia.v1.PlanApprovalPolicy
import org.tatrman.kantheon.pythia.v1.ScenarioSpec
import org.tatrman.kantheon.pythia.v1.Status
import org.tatrman.kantheon.pythia.v1.StyleHint
import org.tatrman.kantheon.themis.v1.Themis
import java.util.UUID

// design §4.3 — fit, diagnose, project, render the year-end margin forecast with CI bands.
private const val FORECAST_PLAN =
    """
    { "rationale": "fit ARIMA, diagnose, project to year-end, render forecast with CI bands",
      "hypotheses": [
        { "id": "HFit", "statement": "data fits ARIMA with seasonal=12 cleanly",
          "displayPriority": "PRIMARY", "predicate": { "kind": "ROW_COUNT_GT", "threshold": 0 } } ],
      "nodes": [
        { "nodeId": "N1", "testsHypIds": ["HFit"], "kind": "query",
          "queryRef": "marginByMonth", "paramsJson": "{\"period\":\"last 36 months\"}" },
        { "nodeId": "N2", "testsHypIds": ["HFit"], "kind": "model",
          "capabilityId": "model.fit.arima", "inputHandleIds": ["h-N1"], "paramsJson": "{\"seasonality\":12}" },
        { "nodeId": "N3", "testsHypIds": ["HFit"], "kind": "reasoning",
          "promptRef": "arima-diagnostics", "inputHandleIds": ["h-N2"], "tierHint": "CHEAP" },
        { "nodeId": "N4", "kind": "model",
          "capabilityId": "model.project.arima", "inputHandleIds": ["h-N2"], "paramsJson": "{\"horizon\":\"2026-12-31\",\"confidenceLevel\":0.9}" },
        { "nodeId": "N5", "kind": "render", "renderKind": "CHART", "blockRole": "PRIMARY",
          "caption": "Year-end margin forecast", "inputHandleIds": ["h-N4"] },
        { "nodeId": "N6", "kind": "render", "renderKind": "TABLE", "blockRole": "EVIDENCE", "inputHandleIds": ["h-N4"] },
        { "nodeId": "N7", "kind": "render", "renderKind": "NARRATIVE_FRAGMENT", "blockRole": "SUMMARY", "inputHandleIds": ["h-N4"] } ],
      "edges": [
        { "fromNodeId": "N1", "toNodeId": "N2", "binding": "N2.input = N1.out" },
        { "fromNodeId": "N2", "toNodeId": "N3", "binding": "N3.input = N2.model" },
        { "fromNodeId": "N2", "toNodeId": "N4", "binding": "N4.model = N2.model" },
        { "fromNodeId": "N4", "toNodeId": "N5", "binding": "N5.input = N4.out" },
        { "fromNodeId": "N4", "toNodeId": "N6", "binding": "N6.input = N4.out" },
        { "fromNodeId": "N4", "toNodeId": "N7", "binding": "N7.input = N4.out" } ] }
    """

private val FORECAST_ROWS =
    Json.parseToJsonElement(
        """[{"date":"2026-12-31","yhat":18.2,"yhat_lower":16.4,"yhat_upper":20.0}]""",
    ) as JsonArray

/**
 * Stage 4.2 T3 — the forecast worked example (design §4.3) end-to-end against scripted
 * LLM + a fake query edge + a mocked Metis (pinned goldens): query → fit → diagnose →
 * project → chart/table/narrative → synthesis, with the CI-band numbers carried into
 * the chart. Part of the Phase 4 DONE gate.
 */
class ForecastE2ESpec :
    StringSpec({

        "the year-end margin forecast runs end-to-end to DONE with CI bands" {
            runTest {
                val events = InMemoryEventRepository()
                val emitter = EventEmitter(events, RecordingNatsPublisher())
                val investigations: InvestigationRepository = InMemoryInvestigationRepository()
                val hypotheses = InMemoryHypothesisRepository()
                val steps = InMemoryStepRepository()

                // reply[0] plan, [1] diagnostics, [2] narrative, [3] synth lead.
                val llm =
                    ScriptedPromptExecutor(
                        listOf(
                            FORECAST_PLAN,
                            "PASS: residuals normal, no autocorrelation.",
                            "Margin is forecast to land near 18.2% by year end.",
                            "Year-end margin forecast: 18.2% (90% CI 16.4%–20.0%).",
                        ),
                    )
                val series =
                    Json.parseToJsonElement(
                        (1..36).joinToString(",", "[", "]") { """{"month":$it,"margin":${18 + it % 3}}""" },
                    ) as JsonArray

                val resolver = Resolver(forecastThemis())
                val planner = Planner(PlanComposer(llm), PlanValidator(CapabilityChecker { true }))
                val engine =
                    ExecutionEngine(
                        DagExecutor(
                            emitter,
                            steps,
                            CompositeNodeExecutor(
                                query = QueryNodeExecutor(FakeQueryClient(rows = series)),
                                render = RenderNodeExecutor(llm),
                                reasoning = ReasoningNodeExecutor(llm),
                                model = ModelNodeExecutor(FakeMetisClient(FORECAST_ROWS)),
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

                val id = orchestrator.submit(forecastRequest(), "tok")
                orchestrator.awaitTerminal(id) shouldBe Status.STATUS_DONE

                events.replay(id, 0L).map { it.kind } shouldContainInOrder
                    listOf(
                        "INVESTIGATION_SUBMITTED",
                        "PLAN_DRAFTED",
                        "BATCH_LAUNCHED",
                        "HYPOTHESIS_SUPPORTED",
                        "SYNTHESIZER_DONE",
                        "CONCLUSION",
                        "INVESTIGATION_DONE",
                    )

                hypotheses.findByInvestigation(id).single().status shouldBe HypStatus.HYP_SUPPORTED.name

                val rec = investigations.findById(id)!!
                // The chart block carries the forecast CI-band numbers (the Metis goldens).
                rec.conclusionJson!! shouldContain "16.4"
                rec.conclusionJson!! shouldContain "20.0"
                rec.conclusionJson!! shouldContain "STOP_GOAL_REACHED"
            }
        }
    })

private fun forecastThemis(): ThemisClient =
    object : ThemisClient {
        override suspend fun understand(
            request: Themis.ResolveRequest,
            bearer: String,
        ): Themis.ResolveResponse =
            Themis.ResolveResponse
                .newBuilder()
                .setResolution(Themis.Resolution.newBuilder().setIntentKind(Themis.IntentKind.FORECAST))
                .build()
    }

private fun forecastRequest(): Investigation =
    Investigation
        .newBuilder()
        .setId(UUID.randomUUID().toString())
        .setQuestion("What will our margin be at end of year?")
        .setCaller(Caller.newBuilder().setKind(Caller.Kind.API).setUserId("u1"))
        .setContext(InvestigationContext.newBuilder().setLocale("en"))
        .setStyleHint(StyleHint.STYLE_FORECAST)
        .setScenarioParams(
            ScenarioSpec
                .newBuilder()
                .setHorizon("2026-12-31")
                .setConfidenceLevel(0.90)
                .setIncludeSeasonality(true),
        ).setConstraints(Constraints.newBuilder().setDepthBudget(DepthBudget.DEPTH_NORMAL))
        .setHitlPolicy(HitlPolicy.newBuilder().setPlanApproval(PlanApprovalPolicy.PLAN_APPROVAL_AUTO))
        .build()
