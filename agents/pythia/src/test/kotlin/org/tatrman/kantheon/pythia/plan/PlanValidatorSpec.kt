package org.tatrman.kantheon.pythia.plan

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.pythia.v1.Constraints
import org.tatrman.kantheon.pythia.v1.DataDep
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.Hypothesis
import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.kantheon.pythia.v1.QueryNode
import org.tatrman.kantheon.pythia.v1.RenderNode

/**
 * Stage 2.1 T5 — `PlanValidator` typed preconditions: DataDep resolution + binding
 * presence, capability existence, hypothesis references, depth caps, acyclicity.
 */
class PlanValidatorSpec :
    StringSpec({

        val allExist = CapabilityChecker { true }

        fun queryNode(
            id: String,
            ref: String,
            tests: List<String> = emptyList(),
        ) = PlanNode
            .newBuilder()
            .setNodeId(id)
            .addAllTestsHypIds(tests)
            .setQuery(QueryNode.newBuilder().setQueryRef(ref))
            .build()

        fun renderNode(id: String) =
            PlanNode
                .newBuilder()
                .setNodeId(id)
                .setRender(RenderNode.newBuilder().setKind(RenderNode.RenderKind.RENDER_TABLE))
                .build()

        fun constraints(depth: DepthBudget = DepthBudget.DEPTH_NORMAL) =
            Constraints.newBuilder().setDepthBudget(depth).build()

        "a clean SQL-only plan validates" {
            runTest {
                val plan =
                    PlanDag
                        .newBuilder()
                        .addHypotheses(Hypothesis.newBuilder().setId("H0"))
                        .addNodes(queryNode("N1", "q.returns", tests = listOf("H0")))
                        .addNodes(renderNode("N2"))
                        .addEdges(
                            DataDep
                                .newBuilder()
                                .setFromNodeId("N1")
                                .setToNodeId("N2")
                                .setBinding("N2.in = N1.out"),
                        ).build()
                PlanValidator(allExist).validate(plan, constraints()).shouldBeEmpty()
            }
        }

        "a dangling DataDep is flagged" {
            runTest {
                val plan =
                    PlanDag
                        .newBuilder()
                        .addNodes(queryNode("N1", "q.x"))
                        .addEdges(
                            DataDep
                                .newBuilder()
                                .setFromNodeId("N1")
                                .setToNodeId("GHOST")
                                .setBinding("b"),
                        ).build()
                PlanValidator(allExist).validate(plan, constraints()).map { it.code } shouldBe listOf("dangling_dep")
            }
        }

        "a missing capability is flagged" {
            runTest {
                val checker = CapabilityChecker { it != "q.missing" }
                val plan = PlanDag.newBuilder().addNodes(queryNode("N1", "q.missing")).build()
                PlanValidator(checker).validate(plan, constraints()).map { it.code } shouldBe
                    listOf("unknown_capability")
            }
        }

        "a binding-less edge and an unknown hypothesis are flagged" {
            runTest {
                val plan =
                    PlanDag
                        .newBuilder()
                        .addNodes(queryNode("N1", "q.x", tests = listOf("HX")))
                        .addNodes(renderNode("N2"))
                        .addEdges(
                            DataDep
                                .newBuilder()
                                .setFromNodeId("N1")
                                .setToNodeId("N2")
                                .setBinding(""),
                        ).build()
                val codes = PlanValidator(allExist).validate(plan, constraints()).map { it.code }.toSet()
                codes shouldBe setOf("invalid_binding", "unknown_hypothesis")
            }
        }

        "an over-depth-budget plan (SHALLOW cap 3) is flagged" {
            runTest {
                val plan =
                    PlanDag
                        .newBuilder()
                        .apply { (1..5).forEach { addNodes(queryNode("N$it", "q.x")) } }
                        .build()
                PlanValidator(allExist).validate(plan, constraints(DepthBudget.DEPTH_SHALLOW)).map { it.code } shouldBe
                    listOf("over_depth_budget")
            }
        }

        "a cyclic plan is flagged" {
            runTest {
                val plan =
                    PlanDag
                        .newBuilder()
                        .addNodes(queryNode("N1", "q.x"))
                        .addNodes(queryNode("N2", "q.y"))
                        .addEdges(
                            DataDep
                                .newBuilder()
                                .setFromNodeId("N1")
                                .setToNodeId("N2")
                                .setBinding("b"),
                        ).addEdges(
                            DataDep
                                .newBuilder()
                                .setFromNodeId("N2")
                                .setToNodeId("N1")
                                .setBinding("b"),
                        ).build()
                PlanValidator(
                    allExist,
                ).validate(plan, constraints()).map { it.code }.toSet().contains("cyclic") shouldBe
                    true
            }
        }
    })
