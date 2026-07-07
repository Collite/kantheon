# WS-T Stage 2 — Ariadne TPC-DS model + curated queries + `pg-tpcds` connection

> **Workstream T (TPC-DS), Stage 2.** Put the query path over the `tpc-ds-1g` warehouse: an Ariadne model + four Proteus-stressing curated queries, the `pg-tpcds` Arges connection, and Kyklop routing. After this a manual `theseus query` against TPC-DS returns rows (**MP-2**).
>
> **Reads with.** [`tasks-overview.md`](./tasks-overview.md) §0, [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §4.4/§4.5, [`tasks-t1-test-pg-load.md`](./tasks-t1-test-pg-load.md).
> **Reference.** Ariadne model seed: `services/ariadne/src/main/resources/model-ttr/<package>/` + areas at `model-ttr-areas/areas/` (see `ResolveAreaSpec`, `ModelTtrLoadSpec`); the `ucetnictvi` package is the self-contained precedent. Arges connection: `workers/arges` HOCON `connections { pg-midas {…} }`. Kyklop dispatch map. Repo: **[K]** kantheon.
> **Model-source note.** TPC-DS is a **test** warehouse → commit it as a **self-contained Ariadne seed package** under `model-ttr` (no dependency on the external `ai-models` Git source). Optionally also author in `ai-models` later; the committed seed is what the `tpcds-query` context loads.

## Goal

Ariadne serves a `tpcds` area + four curated queries on connection `pg-tpcds`; Proteus unparses each to valid PostgreSQL; Arges executes them read-only against `test-pg`; Kyklop routes `pg-tpcds` → Arges.

## Pre-flight

- [ ] WS-T1 DONE (`tpc-ds-1g` loaded; row counts verified).
- [ ] Proteus PG-unparse baseline (Arges S1.2 follow-on) reachable.
- [ ] Branch `feat/t2-tpcds-model-connection`.

## Tasks

- [x] **T1 — TPC-DS model seed (tests first).** Author a self-contained `tpcds` package under `services/ariadne/src/main/resources/model-ttr/tpcds/` covering the entities the four queries need (`store_sales`, `catalog_sales`, `web_sales`, `date_dim`, `item`, `customer`, `store` + the join keys), via the `just yaml-to-ttr` pipeline. **Tests first:** extend `ModelTtrLoadSpec`-style coverage — the `tpcds` package loads cleanly (every referenced entity resolves), mirroring the `ucetnictvi` completeness guard.
- [x] **T2 — `tpcds` area (tests first).** Add `model-ttr-areas/areas/tpcds.ttrm` (`AreaDef`) → the `tpcds` package(s). **Tests first:** a `ResolveAreaSpec` case — `resolveArea("tpcds")` returns the packages/description/tags, `found=true`.
- [x] **T3 — Four curated queries (tests first).** Author `q.tpcds.store_sales_by_month` (join+group-by), `q.tpcds.top_items_by_revenue` (join+agg+ORDER/LIMIT), `q.tpcds.customer_running_total` (**window**), `q.tpcds.channel_revenue_cte` (**CTE**) as Ariadne queries on connection `pg-tpcds` with `{name}` params where parameterised (contracts §4.4). **Tests first:** `ListQueries` returns all four with declared params + target connection `pg-tpcds`.
- [x] **T4 — Proteus PG-unparse for the four shapes (tests first).** Pair with `ProteusUnparseComponentSpec` (WS-C1 T2): golden PostgreSQL for each query incl. the `{name}→?` rewrite; close any window/CTE unparse gap (extends the Arges S1.2 PG-unparse follow-on). Tests are the golden files; make them green.
- [x] **T5 — `pg-tpcds` Arges connection (tests first + impl).** Add the named connection to `workers/arges` config (contracts §4.5): `read-only=true`, `requires-tenant-id=false`, env `ARGES_PG_TPCDS_{HOST,USER,PASSWORD}`. **Tests first:** a `ConnectionConfig.fromConfig("pg-tpcds", …)` spec — `readOnly=true`, `requiresTenantId=false`; and an `ExecutePipeline` spec asserting **no** `SET LOCAL app.tenant_id` is issued for this connection (contrast `pg-midas`). — DONE (commit `c403aa4`): active `pg-tpcds` block with boot-safe defaults + env override; two specs green.
- [x] **T6 — Kyklop routing for `pg-tpcds` (tests first + impl).** Kyklop dispatch map routes `connection_id = pg-tpcds` → Arges. **Tests first:** a dispatch spec asserting the route. Wire the chart `extraEnv` so a deployed Arges gets the `pg-tpcds` envs (from `pg-tpcds-ro-cred`). — DONE (commit `d9208fe`): `world.table-connections` maps the 7 modelled TPC-DS tables → `pg-tpcds`; `WorldConfigSpec` green; arges chart `values.yaml` documents the concrete `extraEnv` wiring (host/user + password via `pg-tpcds-ro-cred` `secretKeyRef`; `extraEnv` is `toYaml`, so `valueFrom` works).
- [x] **T7 — Local query smoke (run locally).** With WS-T1's `test-pg` reachable (k3d or port-forward) + the model loaded, drive each curated query through `theseus query` (or a direct Arges execute against `pg-tpcds`) and assert rows return — the pre-cluster proof before the `tpcds-query` integration context (C2). *(Full live-on-bp-dsk run is the C2 `tpcds-query` context.)* — DONE (2026-07-07): ran all four curated shapes against `tpc-ds-1g` on bp-dsk as `tpcds_readonly` (`SET ROLE` via the `test-pg-1` local socket) — every query returned rows. `d_year=2002` presence: 549,330 `store_sales`×`date_dim` rows. Q1 monthly totals; Q2 top items by revenue; Q3 running total (window); Q4 store/catalog/web revenue (CTE + UNION ALL). **MP-2 proven pre-cluster.**

## DONE

- [x] `tpcds` model + area + four curated queries load and resolve (`ModelTtrLoadSpec`/`ResolveAreaSpec`/`ListQueries` green).
- [x] Proteus emits valid PostgreSQL for all four (golden specs green) — **MP-2 unblocked**.
- [x] `pg-tpcds` connection: read-only, no tenant envelope (specs green); Kyklop routes it.
- [x] Local query smoke returns correct rows for the four queries. — **MP-2 proven pre-cluster (2026-07-07)**; full live-on-bp-dsk run is the C2 `tpcds-query` context.

## Follow-ups → next stage

- **C2** builds the `tpcds-query` integration context (the four queries asserted live on bp-dsk = part of MP-4).
- Grow the curated set beyond four later if more Proteus coverage is wanted.
