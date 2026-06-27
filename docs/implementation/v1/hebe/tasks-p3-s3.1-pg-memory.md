# Stage 3.1 — PG MemoryStore backend

> **Phase 3, Stage 3.1.**
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §"Stage 3.1", [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §5.1–§5.2 (one PG, DB per agent, schema-per-instance; dual memory backend + RRF parity), [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §4.1 (ported tables + type mapping) + §4.2 (memory search DDL — `memory_docs`/`memory_chunks`, RRF k₀=60).

## Goal

A second `MemoryStore` implementation on Postgres, behind the existing seam. The **RRF parity gate** is the stage's defining test: a golden fixture corpus (queries → expected ranked doc/chunk ids) runs against **both** backends and divergence fails the build. The ported tables and the PG memory-search schema land as Flyway migrations in `db/migration-pg/`. Per the testing policy, all of this is exercised at the **unit level against a mocked driver / in-memory fake**; real-Postgres boot + parity-against-live-PG is the integration suite.

## Pre-flight

- [ ] **Phase 3 pre-flight** met (Kantheon PG + pgvector + `hebe` DB available for the integration suite; unit work uses fakes).
- [ ] **Branch**: `feat/hebe-p3-s3.1-pg-memory`.
- [ ] Locate the `MemoryStore` seam + the SQLite impl (FTS5 + sqlite-vec + RRF) in `:agents:hebe:modules:memory`. The PG impl mirrors its public contract exactly.

## Tasks

- [ ] **T1 — Golden fixture corpus + RRF parity harness (tests first — this is the stage gate).**

  Create `agents/hebe/modules/memory/src/test/resources/parity/` — a fixture corpus (documents + a query set) and `expected-rankings.json` (query → ranked doc/chunk ids). Build `RrfParityHarness` that runs the **same** corpus + queries through both backends and asserts identical rankings. Because the PG impl doesn't exist yet, the harness runs SQLite now and is wired for PG in T4; commit the SQLite-side baseline as the oracle.

  Tokenizer caveat to encode in the fixtures (contracts §4.2): FTS5 `porter unicode61` vs PG `simple`/`english` will diverge at the margin — the golden set **defines** the accepted behaviour, and the PG text-search config is **fixed in the migration, never per-instance**. Pick fixtures that are stable across both tokenizers; record any deliberately-excluded edge query with a comment.

  Acceptance: harness + golden set committed; SQLite side green; PG side pending. Commit `[hebe-p3-s3.1] rrf parity harness + golden corpus`.

- [ ] **T2 — PG migration set V1–V5.**

  Author `agents/hebe/modules/memory/src/main/resources/db/migration-pg/`:

  - `V1` … the ported tables from contracts §4.1 with the type mapping (`TEXT` pk/refs → `text`, epoch `INTEGER` → `timestamptz`, `*_json TEXT` → `jsonb`, `INTEGER` bool → `boolean`): `conversations`, `messages`, `settings`, `jobs`, `routines`, `llm_calls`, `tool_calls`, `pending_approvals`. Carry indexes over. Apply the two content changes: `routines.body_kind` gains `kantheon_question` + `session_ref text` + `last_turn_ref text`; `jobs` rows of `kind=routine` gain `turn_ref text`.
  - `V2__memory.sql` (PG variant) — `memory_docs` + `memory_chunks` exactly as contracts §4.2 (the `tsv` generated column, `gin(tsv)` index, `vector(1536)` + `hnsw (embedding vector_cosine_ops)` index).
  - `V3`–`V5` — any remaining standalone projections.

  Flyway config: `flyway.schemas=hebe_<id>`, `search_path` pinned. Append-only invariants (`messages`/`llm_calls`/`tool_calls`) preserved.

  Acceptance: migrations parse + Flyway validate clean (against the integration PG in the separate suite; locally, SQL lints/parses). Committed.

- [ ] **T3 — Exposed-DSL PG impl: CRUD + append-only.**

  Implement the relational side of the PG `MemoryStore`: docs/chunks CRUD + append-only conversations/messages, using kantheon's Exposed-DSL convention. No raw SQL strings for app queries — Exposed table objects + DSL. Unit-test the **query construction** against a mocked driver / in-memory fake (assert the generated statements + parameters; this is the planning-conventions §4 approach — no live PG).

  Acceptance: CRUD + append-only specs green against the fake driver.

- [ ] **T4 — Hybrid retrieval: `ts_rank_cd` + pgvector HNSW → shared RRF.**

  Implement PG hybrid retrieval: top-k by `ts_rank_cd` (FTS) + top-k by cosine distance (pgvector HNSW) → feed the **shared** RRF code (k₀ = 60, identical to SQLite — only the two candidate queries differ, architecture §5.2). Wire this backend into the RRF parity harness (T1). Run the parity gate over the fixture corpus using the fake/driver-level query results captured from fixtures.

  Acceptance: the RRF parity gate passes at the unit level on fixtures (same corpus + query ⇒ same ranking across backends).

- [ ] **T5 — Remaining tables on PG + append-only grants.**

  Implement `jobs` / `routines` / `llm_calls` / `tool_calls` / `pending_approvals` / `settings` PG access through the seam. Encode the append-only grant intent for `messages`/`llm_calls`/`tool_calls` (the app role gets no UPDATE/DELETE — enforced fully at provisioning in Stage 3.3, declared here). Unit-test against the fake.

  Acceptance: all tables reachable through the PG backend; append-only declared.

- [ ] **T6 — Backend selection wiring + parity gate in CI.**

  Wire `storage.backend = postgres` to select the PG `MemoryStore` (resolved `Axes`, Stage 2.1 — not profile name). Assert the wiring with a mocked driver (real-Postgres boot → integration suite). Add the RRF parity gate to the CI unit suite.

  Acceptance: `storage.backend=postgres` selects the PG impl (mocked-driver test); RRF parity gate runs in CI. PR `[hebe-p3-s3.1] pg memorystore + rrf parity`.

## DONE — Stage 3.1

- [ ] All six tasks checked.
- [ ] RRF parity test green at the unit level on the golden fixtures.
- [ ] `storage.backend=postgres` wiring asserted with a mocked driver.
- [ ] `db/migration-pg/` V1–V5 authored (incl. the `routines`/`jobs` content changes + the `memory_*` DDL).
- [ ] Append-only invariants + grant intent declared.
- [ ] PR merged. (Real-Postgres boot + live parity → integration suite.)

## Library / pattern references

- **contracts.md §4.1 + §4.2** — the table set, type mapping, and the `memory_docs`/`memory_chunks` DDL **byte-for-byte** (incl. the generated `tsv` column and the two indexes).
- **architecture.md §5.2** — the RRF parity semantics contract (same corpus + query ⇒ same ranking, k₀=60).
- Kantheon Exposed-DSL + Flyway convention — any existing kantheon `services/*` PG module is the live reference.
- **plan.md risks note (P3.1)** — "RRF parity across tokenizers": the golden set is the arbiter; budget a tuning pass.

## Out of scope for Stage 3.1

- `workspace_files` (V6) + `receipts` (V7) — Stage 3.2.
- Real-PG / pgvector verification (integration suite).
- Schema/role provisioning (Stage 3.3).
