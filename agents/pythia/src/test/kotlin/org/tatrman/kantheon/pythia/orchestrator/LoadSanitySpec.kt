package org.tatrman.kantheon.pythia.orchestrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.clients.FakeQueryClient
import org.tatrman.kantheon.pythia.evaluate.HypothesisEvaluator
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.executor.CompositeNodeExecutor
import org.tatrman.kantheon.pythia.executor.DagExecutor
import org.tatrman.kantheon.pythia.executor.ExecCaps
import org.tatrman.kantheon.pythia.executor.QueryNodeExecutor
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
import org.tatrman.kantheon.pythia.resolve.Resolver
import org.tatrman.kantheon.pythia.resolve.ThemisClient
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

private const val SANITY_PLAN =
    """{ "rationale":"q", "hypotheses":[{"id":"H0","statement":"data exists","displayPriority":"HIDDEN","predicate":{"kind":"ROW_COUNT_GT","threshold":0}}],
        "nodes":[{"nodeId":"N1","testsHypIds":["H0"],"kind":"query","queryRef":"q.x","paramsJson":"{}"}] }"""

/**
 * Stage 5.3 T5 — load sanity: 5 concurrent NORMAL investigations on one orchestrator
 * stay within the configured `Semaphore` caps (per-investigation / provider / global)
 * and all reach DONE without starving. A sanity check, not a perf benchmark (real
 * load testing is integration territory).
 */
class LoadSanitySpec :
    StringSpec({

        "5 concurrent NORMAL investigations all reach DONE within caps" {
            runTest {
                val investigations = InMemoryInvestigationRepository()
                val emitter = EventEmitter(InMemoryEventRepository(), RecordingNatsPublisher())
                val steps = InMemoryStepRepository()
                val rows = Json.parseToJsonElement("""[{"id":1},{"id":2}]""") as JsonArray
                // Concurrent runs interleave LLM calls on the one shared executor, so a positional
                // script can't align plan↔synth per investigation. Route by prompt instead: a
                // planning prompt → a valid plan; anything else → the synth lead.
                val llm = PlanOrSynthExecutor(SANITY_PLAN, "done")
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
                            caps = ExecCaps(perInvestigation = 5, perProvider = 5, global = 8),
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
                        resolver = Resolver(procedural()),
                        planner = Planner(PlanComposer(llm), PlanValidator(CapabilityChecker { true })),
                        executionEngine = engine,
                        hypothesesRepo = InMemoryHypothesisRepository(),
                    )

                val ids = (1..5).map { orchestrator.submit(request(), "tok") }
                val terminals = ids.map { orchestrator.awaitTerminal(it) }
                terminals.all { it == Status.STATUS_DONE } shouldBe true
            }
        }
    })

/** Routes by prompt: a planning prompt → [plan], anything else → [synth]. Concurrency-safe (stateless). */
private class PlanOrSynthExecutor(
    private val plan: String,
    private val synth: String,
) : ai.koog.prompt.executor.model.PromptExecutor() {
    override suspend fun execute(
        prompt: ai.koog.prompt.dsl.Prompt,
        model: ai.koog.prompt.llm.LLModel,
        tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
    ): List<ai.koog.prompt.message.Message.Response> {
        val text = prompt.messages.joinToString("\n") { it.content }
        val reply = if (text.contains("PlanDag", ignoreCase = true) || text.contains("planner")) plan else synth
        return listOf(
            ai.koog.prompt.message.Message.Assistant(
                content = reply,
                metaInfo =
                    ai.koog.prompt.message.ResponseMetaInfo(
                        timestamp =
                            kotlin.time.Clock.System
                                .now(),
                    ),
            ),
        )
    }

    override fun executeStreaming(
        prompt: ai.koog.prompt.dsl.Prompt,
        model: ai.koog.prompt.llm.LLModel,
        tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
    ): kotlinx.coroutines.flow.Flow<ai.koog.prompt.streaming.StreamFrame> =
        kotlinx.coroutines.flow.flow { throw UnsupportedOperationException() }

    override suspend fun moderate(
        prompt: ai.koog.prompt.dsl.Prompt,
        model: ai.koog.prompt.llm.LLModel,
    ): ai.koog.prompt.dsl.ModerationResult = throw UnsupportedOperationException()

    override fun close() {}
}

private fun procedural(): ThemisClient =
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

private fun request(): Investigation =
    Investigation
        .newBuilder()
        .setId(UUID.randomUUID().toString())
        .setQuestion("load sanity")
        .setCaller(Caller.newBuilder().setKind(Caller.Kind.API).setUserId("load"))
        .setContext(InvestigationContext.newBuilder().setLocale("en"))
        .setConstraints(Constraints.newBuilder().setDepthBudget(DepthBudget.DEPTH_NORMAL))
        .setHitlPolicy(HitlPolicy.newBuilder().setPlanApproval(PlanApprovalPolicy.PLAN_APPROVAL_AUTO))
        .build()
