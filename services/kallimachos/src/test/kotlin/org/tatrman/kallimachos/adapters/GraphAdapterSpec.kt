package org.tatrman.kallimachos.adapters

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.kallimachos.adapters.graph.GraphEdgeKind
import org.tatrman.kallimachos.adapters.graph.GraphNodeRef
import org.tatrman.kallimachos.adapters.graph.InMemoryGraphAdapter

/**
 * P2 Stage 2.2 T2 — the graph port contract on the in-memory adjacency adapter
 * (spike verdict; the Exposed adjacency adapter is integration-verified):
 * `CONTAINS` on ingest, idempotent node/edge upsert, neighbour fetch by kind.
 */
class GraphAdapterSpec :
    StringSpec({
        val source = GraphNodeRef("source", 1)
        val partA = GraphNodeRef("part", 2)
        val partB = GraphNodeRef("part", 3)

        "relate writes CONTAINS and neighbours fetch by kind" {
            val g = InMemoryGraphAdapter()
            g.relate(source, partA, GraphEdgeKind.CONTAINS)
            g.relate(source, partB, GraphEdgeKind.CONTAINS)

            val ns = g.neighbors(source, setOf(GraphEdgeKind.CONTAINS))
            ns.map { it.to } shouldContainExactlyInAnyOrder listOf(partA, partB)
            ns.all { it.kind == GraphEdgeKind.CONTAINS } shouldBe true
        }

        "edge upsert is idempotent" {
            val g = InMemoryGraphAdapter()
            g.relate(source, partA, GraphEdgeKind.CONTAINS)
            g.relate(source, partA, GraphEdgeKind.CONTAINS) // no duplicate
            g.neighbors(source, emptySet()).size shouldBe 1
        }

        "neighbours are filtered by the requested edge kinds" {
            val g = InMemoryGraphAdapter()
            g.relate(source, partA, GraphEdgeKind.CONTAINS)
            g.relate(source, GraphNodeRef("page", 9), GraphEdgeKind.RELATED)
            g.neighbors(source, setOf(GraphEdgeKind.CONTAINS)).size shouldBe 1
            g.neighbors(source, emptySet()).size shouldBe 2 // empty == all kinds
        }
    })
