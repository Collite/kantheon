package org.tatrman.kantheon.pythia.plan

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.pythia.resolve.HandoffAnchor
import org.tatrman.kantheon.pythia.v1.Constraints
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.IntentKind
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.kantheon.pythia.v1.ResolutionResult
import org.tatrman.kantheon.pythia.v1.ResolvedIntent

private const val VALID_PLAN =
    """
    { "rationale": "fetch and render",
      "hypotheses": [ { "id": "H0", "statement": "data exists", "displayPriority": "HIDDEN" } ],
      "nodes": [
        { "nodeId": "N1", "testsHypIds": ["H0"], "kind": "query", "queryRef": "q.returns", "paramsJson": "{}" },
        { "nodeId": "N2", "kind": "render", "renderKind": "TABLE", "blockRole": "PRIMARY" }
      ],
      "edges": [ { "fromNodeId": "N1", "toNodeId": "N2", "binding": "N2.in = N1.out" } ] }
    """

// Parses, but fails validation (dangling edge) — drives the feedback-retry path.
private const val INVALID_PLAN_VALIDATION =
    """
    { "rationale": "broken",
      "nodes": [ { "nodeId": "N1", "kind": "query", "queryRef": "q.x", "paramsJson": "{}" } ],
      "edges": [ { "fromNodeId": "N1", "toNodeId": "GHOST", "binding": "b" } ] }
    """

private const val GARBAGE = "I'm sorry, I cannot do that."

/**
 * Stage 2.1 T3/T4 — `PlanComposer` decodes a typed PlanDag from a scripted STRONG
 * reply; `Planner` runs the bounded feedback-retry loop (valid → Drafted; invalid
 * then valid → Drafted after a retry; persistently invalid → HALT after 3).
 */
class PlanComposerAndPlannerSpec :
    StringSpec({

        val allExist = CapabilityChecker { true }
        val anchor = HandoffAnchor("", "", "", "", emptyList())

        fun context() =
            PlanContext(
                locale = "en",
                question = "{}",
                intent = "INTENT_PROCEDURAL",
                capabilities = listOf("q.returns"),
                anchor = "",
            )

        fun resolution() =
            ResolutionResult
                .newBuilder()
                .setResolvedIntent(
                    ResolvedIntent.newBuilder().setKind(IntentKind.INTENT_PROCEDURAL),
                ).build()

        fun constraints() = Constraints.newBuilder().setDepthBudget(DepthBudget.DEPTH_NORMAL).build()

        "composer decodes a valid scripted plan (and strips code fences)" {
            runTest {
                val fenced = "```json\n$VALID_PLAN\n```"
                val composer = PlanComposer(ScriptedPromptExecutor(listOf(fenced)))
                val plan = composer.compose(context())
                plan.nodesList.map { it.kindCase } shouldBe listOf(PlanNode.KindCase.QUERY, PlanNode.KindCase.RENDER)
                plan.nodesList
                    .first()
                    .query.queryRef shouldBe "q.returns"
                plan.edgesCount shouldBe 1
            }
        }

        "composer throws PlanDecodeException on a non-JSON reply" {
            runTest {
                val composer = PlanComposer(ScriptedPromptExecutor(listOf(GARBAGE)))
                shouldThrow<PlanDecodeException> { composer.compose(context()) }
            }
        }

        "planner returns Drafted on a first-try valid plan" {
            runTest {
                val planner = Planner(PlanComposer(ScriptedPromptExecutor(listOf(VALID_PLAN))), PlanValidator(allExist))
                val result = planner.plan(resolution(), anchor, constraints(), "en", listOf("q.returns"))
                result.shouldBeInstanceOf<PlanResult.Drafted>()
            }
        }

        "planner feedback-retries an invalid plan then succeeds" {
            runTest {
                val exec = ScriptedPromptExecutor(listOf(INVALID_PLAN_VALIDATION, VALID_PLAN))
                val planner = Planner(PlanComposer(exec), PlanValidator(allExist))
                val result = planner.plan(resolution(), anchor, constraints(), "en", listOf("q.returns"))
                result.shouldBeInstanceOf<PlanResult.Drafted>()
                exec.callCount shouldBe 2 // one retry
            }
        }

        "planner HALTs after 3 persistently-invalid attempts" {
            runTest {
                val exec = ScriptedPromptExecutor(listOf(GARBAGE, GARBAGE, GARBAGE))
                val planner = Planner(PlanComposer(exec), PlanValidator(allExist))
                val result = planner.plan(resolution(), anchor, constraints(), "en", listOf("q.returns"))
                result.shouldBeInstanceOf<PlanResult.Halt>()
                (result as PlanResult.Halt).reason shouldContain "after 3 attempts"
                exec.callCount shouldBe 3
            }
        }
    })
