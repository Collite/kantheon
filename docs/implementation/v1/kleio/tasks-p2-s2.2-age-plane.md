# Stage 2.2 — Apache AGE plane (spike-gated)

> **Phase 2, Stage 2.2.** Branch `feat/docwh-p2-s2.2-age-plane`.
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4 Stage 2.2, [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §6 (edges) + §8 (graph-primary) + §14 ("AGE adapter is net-new" risk), [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §1 (`EdgeKind`, browse messages) + §3 (the AGE graph `kallimachos_graph`).

## Goal

The Apache AGE graph plane (`kallimachos_graph` in the same PG): `CONTAINS` (source→part) wired into ingest so the fan-out is now **four planes in one transaction**; a `GraphWalk` primitive traversing the wiki-graph. **Spike-gated:** T1 decides AGE-over-JDBC vs an adjacency-table fallback behind `GraphPort` (the only net-new adapter — doc-store has no AGE; risks §14).

## Tasks (6)

- [ ] **T1 — AGE spike (the gate): openCypher over JDBC.**

  Spike: can we run openCypher over JDBC against AGE (`ag_catalog`, `search_path` setup, `cypher(...)` calls) cleanly from Exposed/JDBC? Write the **verdict in the module README**: either the AGE adapter proceeds, or we fall back to an **adjacency-table** implementation behind the same `GraphPort` (architecture §14, contracts decision). Reuse the doc-store `CypherGraphPort` DSL either way — only the driver layer is new.

  Acceptance: verdict recorded (AGE adapter | adjacency fallback); the chosen path is what T2–T6 implement.

- [ ] **T2 — Tests first: `AgeGraphAdapterSpec` (fake).**

  Spec the graph adapter against a fake `GraphPort`: `CONTAINS` edge written on ingest; node upsert (idempotent); neighbour fetch from a node. Reuse the doc-store `CypherGraphPort` DSL shapes.

  Acceptance: spec written and failing. Commit `[docwh-p2-s2.2] failing age adapter spec`.

- [ ] **T3 — `AgeGraphAdapter` (new driver; ported DSL) per the verdict.**

  Implement the adapter per T1's verdict: the AGE driver layer (openCypher over JDBC) **or** the adjacency-table fallback, both behind `GraphPort`. The DSL (Cypher query construction) is ported from doc-store's `CypherGraphPort`; only the execution layer differs.

  Acceptance: T2 adapter spec green.

- [ ] **T4 — Wire `CONTAINS` (source→part) into the LOAD stage; four-plane atomic fan-out.**

  Extend the ingestion fan-out (Stage 1.2 was 2 planes) so `LOAD` now writes relational + fulltext + vector + **graph** (`CONTAINS`) **in one transaction**. Update `IngestionServiceSpec` (the one-tx invariant now spans four `Port` fakes; rollback leaves nothing in any of the four).

  Acceptance: four-plane ingest atomic; the one-tx rollback spec extended + green.

- [ ] **T5 — Tests first: `GraphWalkSpec`.**

  Spec graph traversal: from seed parts/sources walk along `CONTAINS`/links to depth `graph_hops` (config, default 2); honour the mart subgraph (notebook scope); collect reachable nodes + their `DERIVED_FROM` source parts (the latter matters once pages exist in P3 — assert the walk is page-aware).

  Acceptance: spec written and failing. Commit.

- [ ] **T6 — `GraphWalk` retrieval primitive.**

  Implement `GraphWalk` (architecture §8 step 2) — the **primary** retrieval primitive that S2.3's fusion leads with. It traverses from seed nodes along the content links (`ABOUT`/`RELATED`/`MENTIONS`/`SAME_AS` once pages exist; `CONTAINS` now), scoped to the mart.

  Acceptance: T5 `GraphWalkSpec` green; `GraphWalk` returns reachable nodes within `graph_hops`. PR `[docwh-p2-s2.2] apache age plane + graph walk`.

## DONE — Stage 2.2

- [ ] All six tasks checked.
- [ ] AGE spike verdict recorded; the chosen adapter (AGE-over-JDBC | adjacency fallback) implemented behind `GraphPort`.
- [ ] `CONTAINS` wired into LOAD; **four-plane ingest atomic** (one-tx rollback spans all four).
- [ ] `GraphWalk` traverses to `graph_hops`, mart-scoped.
- [ ] PR merged.

## Library / pattern references

- **architecture.md §6/§8/§14** — edges, graph-primary walk, the AGE-is-net-new risk + adjacency fallback.
- **contracts.md §1** — `EdgeKind` + `TraverseRequest`/`GraphNode`/`GraphEdge`. **§3** — the AGE graph definition.
- doc-store `CypherGraphPort` — the openCypher DSL being ported (driver layer new).
- Apache AGE docs — openCypher-on-PG, `ag_catalog`, `cypher()`.

## Out of scope for Stage 2.2

- Wiki pages + the content edges (`MENTIONS`/`ABOUT`/`RELATED`/`CONTRADICTS`) — Phase 3 compile; here only `CONTAINS` (structural) is wired.
- `getContext` fusion (Stage 2.3).
- Real AGE openCypher round-trips (integration suite).
