package org.tatrman.kantheon.pythia.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
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
import org.tatrman.kantheon.pythia.orchestrator.ExecutionEngine
import org.tatrman.kantheon.pythia.orchestrator.InvestigationOrchestrator
import org.tatrman.kantheon.pythia.orchestrator.RecordingNatsPublisher
import org.tatrman.kantheon.pythia.persistence.Checkpointer
import org.tatrman.kantheon.pythia.persistence.InMemoryCheckpointRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryHypothesisRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryInvestigationRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryStepRepository
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
import org.tatrman.kantheon.pythia.api.ProtoJson
import org.tatrman.kantheon.pythia.v1.Caller
import org.tatrman.kantheon.pythia.v1.Constraints
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.HitlPolicy
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.InvestigationContext
import org.tatrman.kantheon.pythia.v1.PlanApprovalPolicy
import org.tatrman.kantheon.pythia.v1.StepRecord
import org.tatrman.kantheon.pythia.v1.Status
import org.tatrman.kantheon.themis.v1.Themis
import java.util.UUID

/** One scripted corpus entry: the plan/synth replies + the optional model-bucket rows + its `expected`. */
private data class EvalCase(
    val id: String,
    val intent: Themis.IntentKind,
    val replies: List<String>,
    val budgetMaxUsd: Double = 2.0,
    val metisRows: JsonArray? = null,
    /** The expected terminal status — verdict accuracy is `terminal == expected`, not "== DONE". */
    val expectedTerminal: Status = Status.STATUS_DONE,
)

/** An un-parseable planner reply — every attempt decode-fails, so the run fails closed (STATUS_FAILED). */
private const val INVALID_PLAN = "this is not a plan dag"

private val FORECAST_ROWS =
    Json.parseToJsonElement("""[{"date":"2026-12-31","yhat":18.2,"yhat_lower":16.4,"yhat_upper":20.0}]""") as JsonArray

private const val PROC_PLAN =
    """{ "rationale":"fetch + render", "hypotheses":[{"id":"H0","statement":"data exists","displayPriority":"HIDDEN","predicate":{"kind":"ROW_COUNT_GT","threshold":0}}],
        "nodes":[{"nodeId":"N1","testsHypIds":["H0"],"kind":"query","queryRef":"q.x","paramsJson":"{}"}] }"""

private const val FORECAST_PLAN =
    """{ "rationale":"fit + project", "hypotheses":[{"id":"HFit","statement":"fits cleanly","displayPriority":"PRIMARY","predicate":{"kind":"ROW_COUNT_GT","threshold":0}}],
        "nodes":[
          {"nodeId":"N1","testsHypIds":["HFit"],"kind":"query","queryRef":"q.series","paramsJson":"{}"},
          {"nodeId":"N2","testsHypIds":["HFit"],"kind":"model","capabilityId":"model.fit.arima","inputHandleIds":["h-N1"],"paramsJson":"{}"},
          {"nodeId":"N4","kind":"model","capabilityId":"model.project.arima","inputHandleIds":["h-N2"],"paramsJson":"{\"horizon\":\"2026-12-31\"}"} ],
        "edges":[{"fromNodeId":"N1","toNodeId":"N2","binding":"b"},{"fromNodeId":"N2","toNodeId":"N4","binding":"b"}] }"""

private const val SIM_PLAN =
    """{ "rationale":"forecast + scenario", "hypotheses":[{"id":"HFit","statement":"fits","displayPriority":"PRIMARY","predicate":{"kind":"ROW_COUNT_GT","threshold":0}}],
        "nodes":[
          {"nodeId":"N1","testsHypIds":["HFit"],"kind":"query","queryRef":"q.series","paramsJson":"{}"},
          {"nodeId":"N2","testsHypIds":["HFit"],"kind":"model","capabilityId":"model.fit.arima","inputHandleIds":["h-N1"],"paramsJson":"{}"},
          {"nodeId":"N4","kind":"model","capabilityId":"model.project.arima","inputHandleIds":["h-N2"],"paramsJson":"{\"horizon\":\"2026-12-31\"}"},
          {"nodeId":"N5","kind":"model","capabilityId":"model.simulate.scenario","inputHandleIds":["h-N4"],"paramsJson":"{\"deltasJson\":\"{}\"}"} ],
        "edges":[{"fromNodeId":"N1","toNodeId":"N2","binding":"b"},{"fromNodeId":"N2","toNodeId":"N4","binding":"b"},{"fromNodeId":"N4","toNodeId":"N5","binding":"b"}] }"""

/**
 * Stage 5.3 T1/T2 — the eval corpus + the `just eval-pythia` CI gate, run as a
 * deterministic Kotlin harness (the corpus is scripted-LLM, §4). Over the four
 * buckets it measures the architecture §9 metrics — **plan-validity rate**,
 * **verdict accuracy** (terminal status vs `expected`), **budget adherence**, and
 * **replay determinism** (a `reproduce()` run yields the same conclusion) — and
 * fails below the thresholds (1.0 for these deterministic fixtures). The corpus
 * files live in `agents/pythia/eval/corpus/`; Bora extends question selection.
 */
class EvalGateSpec :
    StringSpec({

        val cases =
            listOf(
                EvalCase("procedural-001", Themis.IntentKind.PROCEDURAL, listOf(PROC_PLAN, "Found rows.")),
                EvalCase("rca-001", Themis.IntentKind.RCA, listOf(PROC_PLAN, "Root cause found.")),
                EvalCase(
                    "forecast-001",
                    Themis.IntentKind.FORECAST,
                    listOf(FORECAST_PLAN, "Forecast done."),
                    metisRows = FORECAST_ROWS,
                ),
                EvalCase(
                    "simulation-001",
                    Themis.IntentKind.SIMULATION,
                    listOf(SIM_PLAN, "Scenario done."),
                    metisRows = FORECAST_ROWS,
                ),
                // Negative bucket: an un-plannable question must fail closed (STATUS_FAILED), not
                // surface a confident DONE. Keeps verdict-accuracy honest — it can now catch a
                // regression where a should-fail run reports success.
                EvalCase(
                    "refusal-001",
                    Themis.IntentKind.RCA,
                    listOf(INVALID_PLAN),
                    expectedTerminal = Status.STATUS_FAILED,
                ),
            )

        "the eval corpus gates plan-validity / verdict-accuracy / budget-adherence / replay-determinism" {
            runTest {
                var planValid = 0
                var planExpected = 0
                var verdictOk = 0
                var budgetOk = 0
                var replayOk = 0

                for (c in cases) {
                    val r = runCase(c, this)
                    // Plan-validity only applies to cases meant to produce a plan; the refusal
                    // bucket fails closed by design and is excluded from this denominator.
                    if (c.expectedTerminal == Status.STATUS_DONE) {
                        planExpected++
                        if (r.planValid) planValid++
                    }
                    if (r.terminal == c.expectedTerminal) verdictOk++
                    if (r.costUsd <= c.budgetMaxUsd) budgetOk++
                    if (r.replayStable) replayOk++
                }
                val n = cases.size.toDouble()
                val planValidityRate = planValid / planExpected.toDouble()
                val verdictAccuracy = verdictOk / n
                val budgetAdherence = budgetOk / n
                val replayDeterminism = replayOk / n

                // CI thresholds (deterministic scripted fixtures → all 1.0).
                planValidityRate shouldBeGreaterThanOrEqual 0.95
                verdictAccuracy shouldBeGreaterThanOrEqual 0.95
                budgetAdherence shouldBeGreaterThanOrEqual 0.95
                replayDeterminism shouldBeGreaterThanOrEqual 0.95
            }
        }
    })

private data class CaseResult(
    val planValid: Boolean,
    val terminal: Status,
    val costUsd: Double,
    val replayStable: Boolean,
)

private suspend fun runCase(
    c: EvalCase,
    scope: kotlinx.coroutines.CoroutineScope,
): CaseResult {
    val investigations = InMemoryInvestigationRepository()
    val emitter = EventEmitter(InMemoryEventRepository(), RecordingNatsPublisher())
    val steps = InMemoryStepRepository()
    // Doubled so the reproduce() re-plan/re-synth pass has fresh scripted replies.
    val llm = ScriptedPromptExecutor(c.replies + c.replies)
    val rows = Json.parseToJsonElement("""[{"id":1},{"id":2}]""") as JsonArray
    val composer = PlanComposer(llm)
    val validator = PlanValidator(CapabilityChecker { true })
    val planner = Planner(composer, validator)
    val engine =
        ExecutionEngine(
            DagExecutor(
                emitter,
                steps,
                CompositeNodeExecutor(
                    query = QueryNodeExecutor(FakeQueryClient(rows = rows)),
                    render = RenderNodeExecutor(llm),
                    reasoning = ReasoningNodeExecutor(llm),
                    model = c.metisRows?.let { ModelNodeExecutor(FakeMetisClient(it)) },
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
            scope,
            awaitingTtlHours = 24,
            resolver = Resolver(themis(c.intent)),
            planner = planner,
            executionEngine = engine,
            hypothesesRepo = InMemoryHypothesisRepository(),
        )
    val id = orchestrator.submit(request(c.id), "tok")
    val terminal = orchestrator.awaitTerminal(id)
    val rec = investigations.findById(id)!!
    val planValid = rec.planJson?.contains("nodes") == true
    val conclusion = rec.conclusionJson
    // Budget adherence over the *real* spend: sum the persisted per-step costs (StepRecord.cost.usd)
    // rather than a hardcoded 0.0, so a cost regression actually trips the gate.
    val costUsd =
        steps.findByInvestigation(id).sumOf { row ->
            runCatching {
                ProtoJson
                    .parseInto(row.bodyJson, StepRecord.newBuilder())
                    .build()
                    .cost.usd
            }.getOrDefault(0.0)
        }

    // Replay determinism: reproduce() re-runs frozen → the same conclusion text.
    val reproId = orchestrator.reproduce(id, "tok")!!
    val reproTerminal = orchestrator.awaitTerminal(reproId)
    val replayStable =
        reproTerminal == terminal &&
            investigations.findById(reproId)?.conclusionJson?.contains(c.replies.last()) ==
            conclusion?.contains(c.replies.last())

    return CaseResult(planValid, terminal, costUsd = costUsd, replayStable = replayStable)
}

private fun themis(kind: Themis.IntentKind): ThemisClient =
    object : ThemisClient {
        override suspend fun understand(
            request: Themis.ResolveRequest,
            bearer: String,
        ): Themis.ResolveResponse =
            Themis.ResolveResponse
                .newBuilder()
                .setResolution(Themis.Resolution.newBuilder().setIntentKind(kind))
                .build()
    }

private fun request(id: String): Investigation =
    Investigation
        .newBuilder()
        .setId(UUID.randomUUID().toString())
        .setQuestion("eval $id")
        .setCaller(Caller.newBuilder().setKind(Caller.Kind.API).setUserId("eval"))
        .setContext(InvestigationContext.newBuilder().setLocale("en"))
        .setConstraints(Constraints.newBuilder().setDepthBudget(DepthBudget.DEPTH_NORMAL))
        .setHitlPolicy(HitlPolicy.newBuilder().setPlanApproval(PlanApprovalPolicy.PLAN_APPROVAL_AUTO))
        .build()
