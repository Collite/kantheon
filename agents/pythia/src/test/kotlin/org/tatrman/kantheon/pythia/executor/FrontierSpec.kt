package org.tatrman.kantheon.pythia.executor

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.pythia.v1.DataDep
import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.PlanNode

/**
 * Stage 2.2 T1 — frontier computation over canonical DAG shapes (linear, fan-out,
 * fan-in, diamond) + cycle detection. The frontier is exactly the nodes whose
 * incoming deps are all satisfied; nothing enters before its deps complete.
 */
class FrontierSpec :
    StringSpec({

        fun node(id: String) = PlanNode.newBuilder().setNodeId(id).build()

        fun dag(
            nodes: List<String>,
            edges: List<Pair<String, String>>,
        ): PlanDag =
            PlanDag
                .newBuilder()
                .apply {
                    nodes.forEach { addNodes(node(it)) }
                    edges.forEach { (f, t) ->
                        addEdges(
                            DataDep
                                .newBuilder()
                                .setFromNodeId(f)
                                .setToNodeId(t)
                                .setBinding("b"),
                        )
                    }
                }.build()

        fun frontierIds(
            plan: PlanDag,
            completed: Set<String>,
        ) = Topology.frontier(plan, completed).map { it.nodeId }

        "linear chain releases one node at a time in order" {
            val plan = dag(listOf("A", "B", "C"), listOf("A" to "B", "B" to "C"))
            frontierIds(plan, emptySet()) shouldBe listOf("A")
            frontierIds(plan, setOf("A")) shouldBe listOf("B")
            frontierIds(plan, setOf("A", "B")) shouldBe listOf("C")
            frontierIds(plan, setOf("A", "B", "C")) shouldBe emptyList()
        }

        "fan-out releases all children once the root completes" {
            val plan = dag(listOf("A", "B", "C", "D"), listOf("A" to "B", "A" to "C", "A" to "D"))
            frontierIds(plan, emptySet()) shouldBe listOf("A")
            frontierIds(plan, setOf("A")) shouldContainExactlyInAnyOrder listOf("B", "C", "D")
        }

        "fan-in releases the sink only when all parents complete" {
            val plan = dag(listOf("A", "B", "C"), listOf("A" to "C", "B" to "C"))
            frontierIds(plan, emptySet()) shouldContainExactlyInAnyOrder listOf("A", "B")
            frontierIds(plan, setOf("A")) shouldBe listOf("B") // C still blocked on B
            frontierIds(plan, setOf("A", "B")) shouldBe listOf("C")
        }

        "diamond schedules in topological order" {
            val plan = dag(listOf("A", "B", "C", "D"), listOf("A" to "B", "A" to "C", "B" to "D", "C" to "D"))
            frontierIds(plan, emptySet()) shouldBe listOf("A")
            frontierIds(plan, setOf("A")) shouldContainExactlyInAnyOrder listOf("B", "C")
            frontierIds(plan, setOf("A", "B")) shouldBe listOf("C") // C's dep (A) is satisfied; D still needs C
            frontierIds(plan, setOf("A", "B", "C")) shouldBe listOf("D")
        }

        "a cyclic graph is detected" {
            val acyclic = dag(listOf("A", "B"), listOf("A" to "B"))
            val cyclic = dag(listOf("A", "B"), listOf("A" to "B", "B" to "A"))
            Topology.hasCycle(acyclic) shouldBe false
            Topology.hasCycle(cyclic) shouldBe true
        }
    })
