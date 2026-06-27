package org.tatrman.kallimachos.retrieval

import org.tatrman.kallimachos.adapters.graph.GraphEdgeKind
import org.tatrman.kallimachos.adapters.graph.GraphNodeRef
import org.tatrman.kallimachos.adapters.graph.GraphPort
import org.tatrman.kallimachos.adapters.relational.RelationalPort

/**
 * The PRIMARY retrieval primitive (architecture §8 step 2): a breadth-first walk
 * of the wiki-graph from seed nodes along the content links, to depth
 * `graph_hops`, scoped to the mart subgraph. At P2 only `CONTAINS` exists (so the
 * walk reaches a source's parts); once the P3 compile authors pages, the same
 * walk follows `ABOUT`/`RELATED`/`MENTIONS`/`SAME_AS` and their `DERIVED_FROM`
 * source parts — page-aware by construction.
 *
 * Mart scope: a node is in-scope iff it (a source) or its source (a part) is a
 * member of the mart; the walk never crosses out of the mart subgraph.
 */
class GraphWalk(
    private val graph: GraphPort,
    private val relational: RelationalPort,
) {
    fun walk(
        seeds: List<GraphNodeRef>,
        edges: Set<GraphEdgeKind>,
        hops: Int,
        allowedSourceIds: Set<Long>?,
    ): List<GraphNodeRef> {
        val visited = LinkedHashSet<GraphNodeRef>()
        var frontier = seeds.filter { inMart(it, allowedSourceIds) }
        visited.addAll(frontier)

        repeat(hops.coerceAtLeast(0)) {
            val next = mutableListOf<GraphNodeRef>()
            for (node in frontier) {
                for (edge in graph.neighbors(node, edges)) {
                    if (edge.to !in visited && inMart(edge.to, allowedSourceIds)) {
                        visited.add(edge.to)
                        next.add(edge.to)
                    }
                }
            }
            if (next.isEmpty()) return visited.toList()
            frontier = next
        }
        return visited.toList()
    }

    private fun inMart(
        node: GraphNodeRef,
        allowedSourceIds: Set<Long>?,
    ): Boolean {
        if (allowedSourceIds == null) return true
        return when (node.kind) {
            "source" -> node.id in allowedSourceIds
            "part" -> relational.getPart(node.id)?.sourceId in allowedSourceIds
            // A page belongs to the mart iff it was DERIVED_FROM a part/source in the
            // mart. Without this the walk would cross out of the mart through pages
            // compiled from other marts' sources (a cross-mart leak once P3 pages exist).
            "page" -> graph.neighbors(node, DERIVED_FROM).any { sourceOf(it.to) in allowedSourceIds }
            else -> false
        }
    }

    /** The source id a provenance target resolves to (a source node, or a part's source). */
    private fun sourceOf(node: GraphNodeRef): Long? =
        when (node.kind) {
            "source" -> node.id
            "part" -> relational.getPart(node.id)?.sourceId
            else -> null
        }

    private companion object {
        val DERIVED_FROM = setOf(GraphEdgeKind.DERIVED_FROM)
    }
}
