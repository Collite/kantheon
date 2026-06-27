package org.tatrman.kallimachos.adapters.graph

import org.tatrman.kallimachos.tx.SnapshotStore

/**
 * In-memory adjacency graph — the wired test/memory profile and the documented
 * adjacency fallback (architecture §14). Implements [SnapshotStore] so `CONTAINS`
 * writes participate in the one-tx four-plane fan-out rollback. The production
 * plane is `AgeGraphAdapter` (openCypher over JDBC).
 */
class InMemoryGraphAdapter :
    GraphPort,
    SnapshotStore {
    private val nodes = linkedSetOf<GraphNodeRef>()
    private val edges = mutableListOf<GraphEdgeRef>()

    override fun upsertNode(node: GraphNodeRef) {
        nodes.add(node)
    }

    override fun relate(
        from: GraphNodeRef,
        to: GraphNodeRef,
        kind: GraphEdgeKind,
        weight: Double,
    ) {
        nodes.add(from)
        nodes.add(to)
        if (edges.none { it.from == from && it.to == to && it.kind == kind }) {
            edges.add(GraphEdgeRef(from, to, kind, weight))
        }
    }

    override fun neighbors(
        node: GraphNodeRef,
        edges: Set<GraphEdgeKind>,
    ): List<GraphEdgeRef> = this.edges.filter { it.from == node && (edges.isEmpty() || it.kind in edges) }

    override fun incoming(
        node: GraphNodeRef,
        edges: Set<GraphEdgeKind>,
    ): List<GraphEdgeRef> = this.edges.filter { it.to == node && (edges.isEmpty() || it.kind in edges) }

    override fun snapshot(): () -> Unit {
        val n = LinkedHashSet(nodes)
        val e = ArrayList(edges)
        return {
            nodes.clear()
            nodes.addAll(n)
            edges.clear()
            edges.addAll(e)
        }
    }
}
