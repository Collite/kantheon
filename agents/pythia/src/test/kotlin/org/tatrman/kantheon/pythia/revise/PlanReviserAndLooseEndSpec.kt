package org.tatrman.kantheon.pythia.revise

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.orchestrator.RecordingNatsPublisher
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.plan.CapabilityChecker
import org.tatrman.kantheon.pythia.plan.PlanValidator
import org.tatrman.kantheon.pythia.plan.ScriptedPromptExecutor
import org.tatrman.kantheon.pythia.v1.Constraints
import org.tatrman.kantheon.pythia.v1.DataDep
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.HypStatus
import org.tatrman.kantheon.pythia.v1.Hypothesis
import org.tatrman.kantheon.pythia.v1.LooseEndSource
import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.kantheon.pythia.v1.QueryNode
import org.tatrman.kantheon.pythia.v1.RevisionPolicy
import java.util.UUID

/**
 * Stage 3.2 T3/T4/T5 — the reviser (PRUNE / PIVOT / DECOMPOSE / HALT, caps,
 * approval-park) and loose-end derivation.
 */
class PlanReviserAndLooseEndSpec :
    StringSpec({

        val id = UUID.randomUUID()

        fun emitter() = EventEmitter(InMemoryEventRepository(), RecordingNatsPublisher())

        fun reviser(replies: List<String>) =
            PlanReviser(ScriptedPromptExecutor(replies), PlanValidator(CapabilityChecker { true }), emitter())

        fun queryNode(
            nodeId: String,
            tests: List<String>,
        ) = PlanNode
            .newBuilder()
            .setNodeId(nodeId)
            .addAllTestsHypIds(tests)
            .setQuery(QueryNode.newBuilder().setQueryRef("q.$nodeId"))
            .build()

        fun plan(): PlanDag =
            PlanDag
                .newBuilder()
                .setRevision(0)
                .addHypotheses(Hypothesis.newBuilder().setId("HA").setStatement("approach A"))
                .addHypotheses(Hypothesis.newBuilder().setId("HB").setStatement("approach B"))
                .addNodes(queryNode("N1", listOf("HA")))
                .addNodes(queryNode("N2", listOf("HB")))
                .build()

        fun constraints() = Constraints.newBuilder().setDepthBudget(DepthBudget.DEPTH_NORMAL).build()

        "DECOMPOSE adds child hypotheses and bumps the revision" {
            runTest {
                val reply = """{ "action":"DECOMPOSE", "affectedHypIds":["HB"],
                      "newHypotheses":[{"id":"HB1","parentId":"HB","statement":"B in SMB"}], "rationale":"split B" }"""
                val result =
                    reviser(listOf(reply))
                        .revise(
                            id,
                            plan(),
                            "supported=HB",
                            DepthBudget.DEPTH_NORMAL,
                            "en",
                            constraints(),
                            RevisionPolicy.REVISION_AUTO,
                        )
                result.shouldBeInstanceOf<ReviseResult.Revised>()
                result.plan.revision shouldBe 1
                result.plan.hypothesesList.any { it.id == "HB1" } shouldBe true
            }
        }

        "PRUNE removes the hypothesis and its node" {
            runTest {
                val reply = """{"action":"PRUNE","affectedHypIds":["HB"],"rationale":"refuted"}"""
                val result =
                    reviser(listOf(reply))
                        .revise(
                            id,
                            plan(),
                            "refuted=HB",
                            DepthBudget.DEPTH_NORMAL,
                            "en",
                            constraints(),
                            RevisionPolicy.REVISION_AUTO,
                        )
                result.shouldBeInstanceOf<ReviseResult.Revised>()
                result.plan.hypothesesList.map { it.id } shouldBe listOf("HA")
                result.plan.nodesList.map { it.nodeId } shouldBe listOf("N1") // N2 (tested HB only) pruned
            }
        }

        "PIVOT abandons the old approach and adds new hypotheses" {
            runTest {
                val reply = """{ "action":"PIVOT", "affectedHypIds":["HA"],
                      "newHypotheses":[{"id":"HC","statement":"approach C"}], "rationale":"pivot" }"""
                val result =
                    reviser(listOf(reply))
                        .revise(
                            id,
                            plan(),
                            "stuck",
                            DepthBudget.DEPTH_NORMAL,
                            "en",
                            constraints(),
                            RevisionPolicy.REVISION_AUTO,
                        )
                result.shouldBeInstanceOf<ReviseResult.Revised>()
                result.plan.hypothesesList
                    .first { it.id == "HA" }
                    .status shouldBe HypStatus.HYP_ABANDONED
                result.plan.hypothesesList.any { it.id == "HC" } shouldBe true
            }
        }

        "HALT stops deepening" {
            runTest {
                val result =
                    reviser(listOf("""{"action":"HALT","rationale":"enough"}"""))
                        .revise(
                            id,
                            plan(),
                            "diminishing",
                            DepthBudget.DEPTH_NORMAL,
                            "en",
                            constraints(),
                            RevisionPolicy.REVISION_AUTO,
                        )
                result.shouldBeInstanceOf<ReviseResult.Halt>()
            }
        }

        "an invalid reviser reply feedback-retries then succeeds" {
            runTest {
                val good = """{ "action":"DECOMPOSE", "affectedHypIds":["HB"],
                       "newHypotheses":[{"id":"HB1","statement":"x"}], "rationale":"ok" }"""
                val rev = reviser(listOf("garbage", good))
                rev
                    .revise(
                        id,
                        plan(),
                        "t",
                        DepthBudget.DEPTH_NORMAL,
                        "en",
                        constraints(),
                        RevisionPolicy.REVISION_AUTO,
                    ).shouldBeInstanceOf<ReviseResult.Revised>()
            }
        }

        "APPROVE policy returns NeedsApproval (parks AWAITING_PLAN_REVISION_APPROVAL)" {
            runTest {
                val reply = """{ "action":"DECOMPOSE", "affectedHypIds":["HB"],
                      "newHypotheses":[{"id":"HB1","statement":"x"}], "rationale":"r" }"""
                reviser(listOf(reply))
                    .revise(
                        id,
                        plan(),
                        "t",
                        DepthBudget.DEPTH_NORMAL,
                        "en",
                        constraints(),
                        RevisionPolicy.REVISION_APPROVE,
                    ).shouldBeInstanceOf<ReviseResult.NeedsApproval>()
            }
        }

        "the depth-budget revision cap stops further revision" {
            runTest {
                // SHALLOW cap = 0 → any revision is capped immediately
                reviser(listOf("unused"))
                    .revise(
                        id,
                        plan(),
                        "t",
                        DepthBudget.DEPTH_SHALLOW,
                        "en",
                        constraints(),
                        RevisionPolicy.REVISION_AUTO,
                    ).shouldBeInstanceOf<ReviseResult.CapReached>()
                // NORMAL cap = 2 → a plan already at revision 2 is capped
                val atCap = plan().toBuilder().setRevision(2).build()
                reviser(listOf("unused"))
                    .revise(id, atCap, "t", DepthBudget.DEPTH_NORMAL, "en", constraints(), RevisionPolicy.REVISION_AUTO)
                    .shouldBeInstanceOf<ReviseResult.CapReached>()
            }
        }

        "loose ends derive from untested hypotheses (planning-time) and inconclusive ones (execution-time)" {
            val planWithUntested =
                plan()
                    .toBuilder()
                    .addHypotheses(Hypothesis.newBuilder().setId("HX").setStatement("never tested"))
                    .build()
            val planning = LooseEndDeriver.planningTime(planWithUntested)
            planning shouldHaveSize 1
            planning.single().hypothesisId shouldBe "HX"
            planning.single().source shouldBe LooseEndSource.LOOSE_END_PLANNING_TIME
            planning.single().suggestedFollowup.isNotBlank() shouldBe true

            val execution =
                LooseEndDeriver.executionTime(
                    listOf(
                        Hypothesis
                            .newBuilder()
                            .setId(
                                "HI",
                            ).setStatement("inconclusive")
                            .setStatus(HypStatus.HYP_INCONCLUSIVE)
                            .build(),
                    ),
                )
            execution shouldHaveSize 1
            execution.single().source shouldBe LooseEndSource.LOOSE_END_EXECUTION_TIME
        }

        "an unused DataDep edge stays consistent after prune" {
            runTest {
                val withEdge =
                    plan()
                        .toBuilder()
                        .addEdges(
                            DataDep
                                .newBuilder()
                                .setFromNodeId("N1")
                                .setToNodeId("N2")
                                .setBinding("b"),
                        ).build()
                val reply = """{"action":"PRUNE","affectedHypIds":["HB"],"rationale":"refuted"}"""
                val result =
                    reviser(listOf(reply))
                        .revise(
                            id,
                            withEdge,
                            "refuted=HB",
                            DepthBudget.DEPTH_NORMAL,
                            "en",
                            constraints(),
                            RevisionPolicy.REVISION_AUTO,
                        )
                result.shouldBeInstanceOf<ReviseResult.Revised>()
                result.plan.edgesCount shouldBe 0 // the N1→N2 edge dropped with N2
            }
        }
    })
