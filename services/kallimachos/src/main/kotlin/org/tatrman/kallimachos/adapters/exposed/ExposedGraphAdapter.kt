package org.tatrman.kallimachos.adapters.exposed

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tatrman.kallimachos.adapters.graph.GraphEdgeKind
import org.tatrman.kallimachos.adapters.graph.GraphEdgeRef
import org.tatrman.kallimachos.adapters.graph.GraphNodeRef
import org.tatrman.kallimachos.adapters.graph.GraphPort

/**
 * The live graph plane — the adjacency-table fallback on Postgres (spike verdict;
 * `graph_nodes` / `graph_edges`, V4). Node/edge writes are idempotent
 * (`insertIgnore`). Runs inside the caller's `Transactor` (the four-plane
 * `CONTAINS` fan-out). Integration-verified; AGE-over-JDBC is the future swap.
 */
class ExposedGraphAdapter : GraphPort {
    override fun upsertNode(node: GraphNodeRef) {
        GraphNodes.insertIgnore {
            it[kind] = node.kind
            it[id] = node.id
        }
    }

    override fun relate(
        from: GraphNodeRef,
        to: GraphNodeRef,
        kind: GraphEdgeKind,
        weight: Double,
    ) {
        upsertNode(from)
        upsertNode(to)
        GraphEdges.insertIgnore {
            it[fromKind] = from.kind
            it[fromId] = from.id
            it[toKind] = to.kind
            it[toId] = to.id
            it[GraphEdges.kind] = kind.name
            it[GraphEdges.weight] = weight
        }
    }

    override fun neighbors(
        node: GraphNodeRef,
        edges: Set<GraphEdgeKind>,
    ): List<GraphEdgeRef> {
        val rows =
            GraphEdges
                .selectAll()
                .where {
                    val base = (GraphEdges.fromKind eq node.kind) and (GraphEdges.fromId eq node.id)
                    if (edges.isEmpty()) base else base and (GraphEdges.kind inList edges.map { it.name })
                }
        return rows.map { it.toEdge() }
    }

    override fun incoming(
        node: GraphNodeRef,
        edges: Set<GraphEdgeKind>,
    ): List<GraphEdgeRef> {
        val rows =
            GraphEdges
                .selectAll()
                .where {
                    val base = (GraphEdges.toKind eq node.kind) and (GraphEdges.toId eq node.id)
                    if (edges.isEmpty()) base else base and (GraphEdges.kind inList edges.map { it.name })
                }
        return rows.map { it.toEdge() }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toEdge() =
        GraphEdgeRef(
            from = GraphNodeRef(this[GraphEdges.fromKind], this[GraphEdges.fromId]),
            to = GraphNodeRef(this[GraphEdges.toKind], this[GraphEdges.toId]),
            kind = GraphEdgeKind.valueOf(this[GraphEdges.kind]),
            weight = this[GraphEdges.weight],
        )
}
