-- Kleio/DocWH P2 Stage 2.2 — the GRAPH plane (contracts §1 EdgeKind / §3).
--
-- SPIKE VERDICT (services/kallimachos/README.md): the v1 graph plane is the
-- ADJACENCY-TABLE fallback (architecture §14), not Apache AGE. The `age`
-- extension is confirmed available on-cluster, but the live openCypher-over-JDBC
-- spike is deferred there; the adjacency tables behind `GraphPort` are correct
-- and testable now, and AGE remains a one-adapter swap. So this migration
-- creates relational adjacency tables rather than `create_graph('kallimachos_graph')`.

CREATE TABLE graph_nodes (
    kind TEXT   NOT NULL,   -- 'source' | 'part' | 'page' | 'entity'
    id   BIGINT NOT NULL,
    PRIMARY KEY (kind, id)
);

CREATE TABLE graph_edges (
    from_kind TEXT             NOT NULL,
    from_id   BIGINT           NOT NULL,
    to_kind   TEXT             NOT NULL,
    to_id     BIGINT           NOT NULL,
    kind      TEXT             NOT NULL,   -- EdgeKind name (CONTAINS, DERIVED_FROM, …)
    weight    DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    PRIMARY KEY (from_kind, from_id, to_kind, to_id, kind)
);

-- The walk traverses out-edges from a node filtered by kind.
CREATE INDEX idx_graph_edges_from ON graph_edges (from_kind, from_id, kind);
