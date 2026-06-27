package org.tatrman.kallimachos.retrieval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.kallimachos.adapters.graph.GraphEdgeKind
import org.tatrman.kallimachos.adapters.graph.GraphNodeRef
import org.tatrman.kallimachos.adapters.graph.InMemoryGraphAdapter
import org.tatrman.kallimachos.adapters.relational.InMemoryRelationalAdapter
import org.tatrman.kallimachos.adapters.relational.NewPart
import org.tatrman.kallimachos.adapters.relational.NewSource

/**
 * P2 Stage 2.2 T5 — the graph walk: traverse `CONTAINS`/links from seed nodes to
 * depth `graph_hops`, honouring the mart subgraph. At P2 the walk reaches a
 * source's parts; once pages exist (P3) the same walk follows the content links.
 */
class GraphWalkSpec :
    StringSpec({
        // source 1 → parts 2,3 ; source 4 → part 5 (a different mart).
        fun fixture(): Pair<GraphWalk, InMemoryGraphAdapter> {
            val relational = InMemoryRelationalAdapter()
            val s1 = relational.insertSource(NewSource(title = "A"))
            val p1 = relational.insertParts(s1.id, listOf(NewPart(0, "paragraph", "a0"), NewPart(1, "paragraph", "a1")))
            val s2 = relational.insertSource(NewSource(title = "B"))
            val p2 = relational.insertParts(s2.id, listOf(NewPart(0, "paragraph", "b0")))

            val graph = InMemoryGraphAdapter()
            val sn1 = GraphNodeRef("source", s1.id)
            p1.forEach { graph.relate(sn1, GraphNodeRef("part", it.id), GraphEdgeKind.CONTAINS) }
            val sn2 = GraphNodeRef("source", s2.id)
            p2.forEach { graph.relate(sn2, GraphNodeRef("part", it.id), GraphEdgeKind.CONTAINS) }
            return GraphWalk(graph, relational) to graph
        }

        "walk reaches a source's parts within graph_hops" {
            val (walk, _) = fixture()
            val reached =
                walk.walk(
                    listOf(GraphNodeRef("source", 1)),
                    setOf(GraphEdgeKind.CONTAINS),
                    hops = 2,
                    allowedSourceIds = null,
                )
            reached.map { it.id } shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L) // source + its 2 parts
        }

        "hops = 0 returns only the seeds" {
            val (walk, _) = fixture()
            walk
                .walk(
                    listOf(GraphNodeRef("source", 1)),
                    setOf(GraphEdgeKind.CONTAINS),
                    hops = 0,
                    allowedSourceIds = null,
                ).map {
                    it.id
                } shouldBe
                listOf(1L)
        }

        "the walk never crosses out of the mart subgraph" {
            val (walk, _) = fixture()
            // Mart contains only source 1; seeding source 4 (other mart) yields nothing in-scope.
            val reached =
                walk.walk(
                    listOf(GraphNodeRef("source", 4)),
                    setOf(GraphEdgeKind.CONTAINS),
                    hops = 2,
                    allowedSourceIds = setOf(1L),
                )
            reached.shouldContainExactlyInAnyOrder(emptyList())
        }
    })
