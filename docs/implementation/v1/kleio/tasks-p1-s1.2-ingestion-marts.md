# Stage 1.2 — relational + full-text planes + one-tx ingestion + marts

> **Phase 1, Stage 1.2.** Branch `feat/docwh-p1-s1.2-ingestion-marts`.
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3 Stage 1.2, [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §3 (the `kallimachos` DB schema) + §1 (`QuerySpec`/`Hit`/`Notebook`), [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §6 (corpus model) + §9 (marts) + §13 (the one-tx fan-out test).

## Goal

The relational + full-text planes land on the single Postgres; ingestion fans a `DocNode` out to **both planes in one transaction** (rollback leaves nothing); notebooks (marts) with m:n membership; `getById` + a keyword/metadata `query` scoped to a mart. DONE = ingest → keyword query round-trips on `Port` fakes; one-tx rollback proven.

## Tasks (7)

- [ ] **T1 — Flyway `V1__sources_parts_notebooks.sql` + `V2__fulltext_tsv.sql` (contracts §3).**

  Author migrations under `services/kallimachos/src/main/resources/db/migration/`:

  - `V1` — `notebooks`, `sources`, `parts` (incl. `content_tsv tsvector`, `metadata jsonb`), `pages` (with `concept_ref jsonb null` — the §6 seam, present + null at v1), `notebook_members` (m:n), `request_log` (audit). IDs `bigserial`, globally unique across sources+parts+pages.
  - `V2` — the `GIN` index on `parts.content_tsv` (full-text plane). PG text-search config fixed in the migration (not per-instance).

  Acceptance: migrations parse + Flyway validate clean (against integration PG in the separate suite; locally SQL lints).

- [ ] **T2 — Tests first: `ExposedRelationalAdapterSpec`.**

  Spec the relational adapter against an in-memory fake / mocked driver (planning-conventions §4 — no live PG): id allocation (DB-generated, globally unique across the three node families), source+part insert, retrieval by id. Assert the generated Exposed-DSL statements + params.

  Acceptance: spec written and failing. Commit `[docwh-p1-s1.2] failing relational adapter spec`.

- [ ] **T3 — Port `RelationalPort` + `PostgresFullTextAdapter` onto single-PG.**

  Port doc-store's `RelationalPort` + the full-text adapter (`PostgresFullTextAdapter`) onto the single-PG profile (architecture §3, §13 "port the Port/adapter code as-is"). Use Exposed DSL (not ORM; AGENTS rule). The fulltext adapter writes `content_tsv` and queries via `ts_rank_cd`.

  Acceptance: T2 relational spec green; fulltext adapter unit-tested against the fake.

- [ ] **T4 — Tests first: `IngestionServiceSpec` — one-tx fan-out + rollback.**

  Spec the ingestion service: one `DocNode` fans out to the relational **and** fulltext `Port` fakes **in one transaction**; an induced failure in either plane **rolls back** and leaves nothing in either (the architecture §13 invariant — "ingestion fan-out is one transaction; the only non-atomic edge is the embedding call, which is P2"). 

  Acceptance: spec written and failing. Commit `[docwh-p1-s1.2] failing ingestion one-tx spec`.

- [ ] **T5 — `IngestionService` + `POST /documents` (requires `notebook_id`).**

  Implement `IngestionService` (relational + fulltext fan-out in one tx) and the `POST /documents` route (`SearchRoutes`/`LoadRoutes` per §4). The request **requires** a `notebook_id` (mart scope is mandatory — contracts §7). Parser → `DocNode` → parts → fan-out.

  Acceptance: T4 one-tx + rollback spec green; `POST /documents` ingests into a mart.

- [ ] **T6 — `NotebookService` + routes + m:n `notebook_members`.**

  Implement `NotebookService` (architecture §9): create/list notebooks, m:n membership (`notebook_members`), owner from a **fixture principal** (the real OBO bearer + `visibility_roles` enforcement is Phase 4 — here a fixture user). `Notebook` carries `owner_user_id` + `visibility_roles` (stored, not yet enforced).

  Acceptance: notebook CRUD + membership work against the fake; spec green.

- [ ] **T7 — `getById` + `POST /query` (keyword/metadata, mart-scoped) + `DocumentQueryServiceSpec`.**

  Implement `getById` (source/part/page) and `POST /query` (keyword via tsvector + metadata filter, **scoped to `QuerySpec.notebook_id`** — no un-scoped search at v1, contracts §7). `DocumentQueryServiceSpec`: a query returns `Hit`s only from the scoped mart; metadata filter narrows; `limit` defaults to 10.

  Acceptance: keyword query round-trips (ingest → query) on fakes; mart scoping enforced in the query. PR `[docwh-p1-s1.2] ingestion + fulltext + marts + keyword query`.

## DONE — Stage 1.2

- [ ] All seven tasks checked.
- [ ] `V1`/`V2` migrations authored (sources/parts/pages/notebooks/members + GIN tsv index).
- [ ] Ingest → keyword query round-trips on `Port` fakes; **one-tx rollback proven**.
- [ ] Marts (notebooks) with m:n membership; every query mart-scoped.
- [ ] `pages.concept_ref jsonb null` seam present.
- [ ] PR merged.

## Library / pattern references

- **contracts.md §3** — the `kallimachos` DB schema (the authority). **§1** — `QuerySpec`/`Hit`/`Notebook`.
- **architecture.md §13** — the one-tx fan-out invariant + the "port the Port/adapter as-is" rule.
- doc-store `RelationalPort` / `PostgresFullTextAdapter` — the ports being ported.
- Kantheon Exposed-DSL + Flyway convention (any kantheon `services/*` PG module).

## Out of scope for Stage 1.2

- vector + AGE planes (Phase 2) — fan-out is 2 planes here, becomes 4 in P2.
- Real OBO/`visibility_roles` enforcement (Phase 4) — fixture principal here.
- Pinakes asset staging (Stage 1.3).
- Real-PG verification (integration suite).
