# Kallimachos — the corpus warehouse (DocWH read path)

`services/kallimachos` is the Alexandria-librarian engine of the Librarian/DocWH
arc: the compiled-wiki corpus (sources · parts · pages) over **one Postgres**
with four planes (relational · full-text · vector · graph), plus graph-primary
retrieval. The write path is [`services/pinakes`](../pinakes/); the agent is
[`agents/kleio`](../../agents/kleio/) (P5).

- Architecture: [`docs/architecture/kleio/architecture.md`](../../docs/architecture/kleio/architecture.md)
- Contracts: [`docs/architecture/kleio/contracts.md`](../../docs/architecture/kleio/contracts.md)
- Plan: [`docs/implementation/v1/kleio/plan.md`](../../docs/implementation/v1/kleio/plan.md)

## Store profile

`kallimachos.storage.profile` selects the wired planes (architecture §3 — polyglot
behind the Ports):

- **`memory`** (local-dev default): in-memory adapters + a snapshot transactor.
  The pod runs with no database; ingest → keyword/graph query round-trips
  in-process. This is the mocked-unit stage gate (planning-conventions §4).
- **`postgres`** (deploy): Hikari + Flyway migrate + the Exposed/PG adapters on
  the single `kallimachos` database. Integration-verified.

## P2 Stage 2.2 — AGE spike verdict

**Verdict: the v1 graph plane is the adjacency-table fallback (architecture §14),
not Apache AGE.**

The `age` extension is confirmed available on the cluster PG (Bora 2026-06-20),
so AGE-over-JDBC (openCypher via `cypher()`) remains the intended production
plane — but the live spike (a clean openCypher-over-JDBC round-trip from Exposed,
`ag_catalog` / `search_path` / `agtype` parsing) must run **on-cluster**, in the
integration suite, where a real AGE-enabled PG exists. It cannot be validated in
the mocked-unit lane.

Rather than ship unvalidated AGE SQL, v1 implements the **adjacency-table**
fallback the architecture explicitly sanctions — `graph_nodes` / `graph_edges`
(migration `V4`) behind `GraphPort` (`ExposedGraphAdapter` for `postgres`,
`InMemoryGraphAdapter` for `memory`/tests). The `GraphPort` boundary keeps AGE a
one-adapter swap: when the on-cluster spike passes, an `AgeGraphAdapter` drops in
behind the same Port with no change to the fan-out, `GraphWalk`, or retrieval.

`CONTAINS` (Source → Part) is wired into the LOAD fan-out (four planes, one
transaction); the content links (`MENTIONS`/`ABOUT`/`RELATED`/`CONTRADICTS`) are
authored by the P3 compile.
