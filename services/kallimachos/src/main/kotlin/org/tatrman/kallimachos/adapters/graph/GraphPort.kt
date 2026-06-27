package org.tatrman.kallimachos.adapters.graph

/**
 * The wiki-graph plane (contracts §1 `EdgeKind`, §3 the AGE graph
 * `kallimachos_graph`). The PRIMARY retrieval structure (architecture §8) — the
 * graph leads, vector + keyword boost. Ported in spirit from doc-store's
 * `CypherGraphPort`, reshaped to the Kleio node/edge model.
 *
 * Behind the Port (spike verdict, README): the v1 plane is the **adjacency-table
 * fallback** (architecture §14) — `ExposedGraphAdapter` on `graph_nodes`/
 * `graph_edges` (postgres profile) + `InMemoryGraphAdapter` (test/memory). AGE
 * over JDBC is the documented future swap (the `age` extension is confirmed
 * available; the live openCypher-over-JDBC spike runs on-cluster). The Port keeps
 * AGE a one-adapter swap. Writes happen inside the caller's `Transactor` (the
 * four-plane fan-out for `CONTAINS`).
 */
enum class GraphEdgeKind {
    CONTAINS, // Source → Part (structural; wired P2)
    DERIVED_FROM, // Page → Source/Part (provenance; P3)
    MENTIONS, // Page → Entity/Page (P3)
    ABOUT, // Page → Concept (P3)
    RELATED, // Page ↔ Page (P3)
    SAME_AS, // Page ↔ Page (P3)
    CONTRADICTS, // Page ↔ Page (P3)
}

data class GraphNodeRef(
    val kind: String, // "source" | "part" | "page" | "entity"
    val id: Long,
)

data class GraphEdgeRef(
    val from: GraphNodeRef,
    val to: GraphNodeRef,
    val kind: GraphEdgeKind,
    val weight: Double = 1.0,
)

interface GraphPort {
    fun upsertNode(node: GraphNodeRef)

    fun relate(
        from: GraphNodeRef,
        to: GraphNodeRef,
        kind: GraphEdgeKind,
        weight: Double = 1.0,
    )

    /** 1-hop out-edges from [node] whose kind is in [edges] (all kinds if empty). */
    fun neighbors(
        node: GraphNodeRef,
        edges: Set<GraphEdgeKind>,
    ): List<GraphEdgeRef>

    /** 1-hop in-edges to [node] (reverse) — e.g. the pages that DERIVED_FROM a part. */
    fun incoming(
        node: GraphNodeRef,
        edges: Set<GraphEdgeKind>,
    ): List<GraphEdgeRef>
}
