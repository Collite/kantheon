package org.tatrman.kantheon.golem.graph

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.golem.execution.CompileResult
import org.tatrman.kantheon.golem.execution.MiniPlanExecutor
import org.tatrman.kantheon.golem.execution.QueryClient
import org.tatrman.kantheon.golem.execution.QueryResult
import org.tatrman.kantheon.golem.plan.GateDecision
import org.tatrman.kantheon.golem.plan.GateThresholds
import org.tatrman.kantheon.golem.plan.PlanComposer
import org.tatrman.kantheon.golem.plan.PlanValidator
import org.tatrman.kantheon.golem.prompts.PromptStore
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.kantheon.golem.v1.MiniPlanNode
import org.tatrman.kantheon.golem.v1.PlanSource
import org.tatrman.kantheon.golem.v1.QueryNode
import org.tatrman.llm.client.LlmGatewayClient
import org.tatrman.llm.client.LlmGatewayPromptExecutor
import java.nio.file.Path

private const val PROMPT = "system: \"x\"\nuser: \"{{ question }}\""

private fun request(): GolemRequest =
    GolemRequest
        .newBuilder()
        .setId("t1")
        .setQuestion("kolik?")
        .build()

private fun validPlan(confidence: Double): MiniPlan =
    MiniPlan
        .newBuilder()
        .setSource(PlanSource.FREE_SQL)
        .setConfidence(confidence)
        .addNodes(
            MiniPlanNode
                .newBuilder()
                .setNodeId("q1")
                .setQuery(QueryNode.newBuilder().setSourceLanguage("sql").setCompileFirst(true)),
        ).build()

private fun store(): PromptStore =
    PromptStore(
        shemDir = Path.of("/nonexistent-golem-shem"),
        locale = "cs",
        fallback = { mapOf("intent" to PROMPT) },
    ).also { it.refresh() }

private val stubQueryClient =
    object : QueryClient {
        override suspend fun query(
            source: String,
            sourceLanguage: String,
            paramsJson: String,
            rowLimit: Int?,
            bearer: String?,
        ): QueryResult = QueryResult(JsonArray(emptyList()), emptyList(), 0, false)

        override suspend fun compile(
            source: String,
            sourceLanguage: String,
            targetDialect: String,
            bearer: String?,
        ): CompileResult = CompileResult("SELECT 1", true)
    }

private suspend fun depsReturning(replyJson: String): GolemGraphDeps {
    val gateway = mockk<LlmGatewayClient>()
    coEvery { gateway.complete(any(), any(), any(), any(), any()) } returns Result.success(replyJson)
    val promptExecutor = LlmGatewayPromptExecutor(gateway)
    return GolemGraphDeps(
        composer = PlanComposer(promptExecutor, store()),
        validator = PlanValidator(),
        miniPlanExecutor = MiniPlanExecutor(stubQueryClient),
        promptExecutor = promptExecutor,
    )
}

private const val FREE_SQL_JSON =
    """{"source":"FREE_SQL","confidence":CONF,"nodes":[
       {"node_id":"q1","query":{"source":"SELECT 1","source_language":"sql","params_json":"{}","compile_first":true}}]}"""

class GolemGraphSpec :
    StringSpec({

        "gatePlanStep executes a valid high-confidence plan" {
            val s = GolemTurnState(request(), plan = validPlan(0.97))
            gatePlanStep(s, PlanValidator(), GateThresholds()).decision.shouldBeInstanceOf<GateDecision.Execute>()
        }

        "gatePlanStep clarifies a low-confidence plan" {
            val s = GolemTurnState(request(), plan = validPlan(0.50))
            gatePlanStep(s, PlanValidator(), GateThresholds()).decision.shouldBeInstanceOf<GateDecision.Clarify>()
        }

        "gatePlanStep clarifies when no plan was composed" {
            val s = GolemTurnState(request(), plan = null)
            gatePlanStep(s, PlanValidator(), GateThresholds()).decision.shouldBeInstanceOf<GateDecision.Clarify>()
        }

        "gatePlanStep clarifies a plan that fails validation" {
            val empty =
                MiniPlan
                    .newBuilder()
                    .setSource(PlanSource.PATTERN)
                    .setConfidence(0.99)
                    .build()
            val s = GolemTurnState(request(), plan = empty)
            gatePlanStep(s, PlanValidator(), GateThresholds()).decision.shouldBeInstanceOf<GateDecision.Clarify>()
        }

        "composePlanStep leaves plan null on an undecodable reply" {
            runTest {
                val deps = depsReturning("not json")
                composePlanStep(GolemTurnState(request()), deps.composer).plan shouldBe null
            }
        }

        "runGolemGraph: a high-confidence plan reaches the execute node" {
            runTest {
                val deps = depsReturning(FREE_SQL_JSON.replace("CONF", "0.97"))
                runGolemGraph(GolemTurnState(request()), deps).outcome shouldBe TurnOutcome.EXECUTED
            }
        }

        "runGolemGraph: a low-confidence plan reaches the clarification node" {
            runTest {
                val deps = depsReturning(FREE_SQL_JSON.replace("CONF", "0.50"))
                runGolemGraph(GolemTurnState(request()), deps).outcome shouldBe TurnOutcome.CLARIFY
            }
        }
    })
