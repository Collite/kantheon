package org.tatrman.kantheon.pythia.plan

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.pythia.resolve.HandoffAnchor
import org.tatrman.kantheon.pythia.v1.Constraints
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.IntentKind
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.kantheon.pythia.v1.ResolutionResult
import org.tatrman.kantheon.pythia.v1.ResolvedIntent

// Capability present → the planner may compose a DataFrameNode (Polars worker op).
private const val DATAFRAME_PLAN =
    """
    { "rationale": "fetch then a DataFrame group-by",
      "hypotheses": [ { "id": "H0", "statement": "data exists", "displayPriority": "HIDDEN" } ],
      "nodes": [
        { "nodeId": "N1", "testsHypIds": ["H0"], "kind": "query", "queryRef": "q.returns", "paramsJson": "{}" },
        { "nodeId": "N2", "kind": "dataframe", "dfdsl": "groupBy(channel).agg(sum(amount))", "sourceHandleId": "h-N1" }
      ],
      "edges": [ { "fromNodeId": "N1", "toNodeId": "N2", "binding": "N2.src = N1.out" } ] }
    """

// Capability absent → an SQL-only plan (the Phase-2 behaviour; no DataFrame node).
private const val SQL_ONLY_PLAN =
    """
    { "rationale": "SQL-only",
      "hypotheses": [ { "id": "H0", "statement": "data exists", "displayPriority": "HIDDEN" } ],
      "nodes": [
        { "nodeId": "N1", "testsHypIds": ["H0"], "kind": "query", "queryRef": "q.returns", "paramsJson": "{}" } ] }
    """

/**
 * Stage 4.1 T6 — the planner gains DataFrame composition. With the DataFrame
 * capability present a plan carrying a DataFrameNode validates; absent (or for an
 * SQL-sufficient question) the planner stays SQL-only (the Phase-2 degradation).
 */
class PlannerDataFrameSpec :
    StringSpec({

        val anchor = HandoffAnchor("", "", "", "", emptyList())

        fun resolution() =
            ResolutionResult
                .newBuilder()
                .setResolvedIntent(ResolvedIntent.newBuilder().setKind(IntentKind.INTENT_PROCEDURAL))
                .build()

        fun constraints() = Constraints.newBuilder().setDepthBudget(DepthBudget.DEPTH_NORMAL).build()

        "capability present → a plan with a DataFrameNode validates" {
            runTest {
                val checker = CapabilityChecker { it == "q.returns" || it == "df.compose.polars" }
                val planner =
                    Planner(PlanComposer(ScriptedPromptExecutor(listOf(DATAFRAME_PLAN))), PlanValidator(checker))
                val result =
                    planner.plan(resolution(), anchor, constraints(), "en", listOf("q.returns", "df.compose.polars"))
                result.shouldBeInstanceOf<PlanResult.Drafted>()
                result.plan.nodesList.map { it.kindCase } shouldContain PlanNode.KindCase.DATAFRAME
            }
        }

        "no DataFrame capability → the planner produces an SQL-only plan" {
            runTest {
                val checker = CapabilityChecker { it == "q.returns" }
                val planner =
                    Planner(PlanComposer(ScriptedPromptExecutor(listOf(SQL_ONLY_PLAN))), PlanValidator(checker))
                val result = planner.plan(resolution(), anchor, constraints(), "en", listOf("q.returns"))
                result.shouldBeInstanceOf<PlanResult.Drafted>()
                result.plan.nodesList.map { it.kindCase } shouldNotContain PlanNode.KindCase.DATAFRAME
            }
        }
    })
