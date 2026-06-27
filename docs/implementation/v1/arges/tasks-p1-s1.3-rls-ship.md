# Stage 1.3 — RLS tenant contract + component test + Kyklop reg + ship

> **Phase 1, Stage 1.3.** The novel bit (per-tenant `SET LOCAL app.tenant_id`, fail-closed) + real-PG component proof + Kyklop registration + deploy + tag.
>
> **Reads with.** [`plan.md`](./plan.md) §3 (Stage 1.3), [`../../../architecture/arges/architecture.md`](../../../architecture/arges/architecture.md) §3, [`../../../architecture/arges/contracts.md`](../../../architecture/arges/contracts.md) §2.1 (RLS envelope), §4 (Kyklop), §6 (role/grants). **Template:** Midas `RlsLeakageComponentSpec` (the consumer-side twin).

## Goal

Per-tenant RLS binding enforced; component tests prove round-trip + cross-tenant isolation through the worker; Kyklop dispatches to Arges; `arges/v0.1.0` shipped.

## Pre-flight

- [ ] Stage 1.2 DONE; `Containers.postgres()` available (**met**).
- [ ] Branch `feat/p1-s1.3-arges-rls-ship` from `main`.

## Tasks

- [ ] **T1 — RLS envelope tests first (unit).** Against a fake `Connection` capturing statements in order: `Execute` with a tenant in `PipelineContext` issues `BEGIN` → `SET LOCAL app.tenant_id=?` → `<query>` → `COMMIT`. Missing tenant → `tenant_id_required` error batch and **no query runs**. `SET LOCAL` throw → `rls_set_failed` + rollback.
- [ ] **T2 — RLS envelope impl.** Wire the transaction-scoped tenant bind into `ExecutePipeline` + the `ConnectionPoolManager` borrow path; only `requires-tenant-id` connections enforce it; read-only pool; `autoCommit=false` per execute.
- [ ] **T3 — Component test: round-trip.** `src/componentTest/.../ArgesPostgresComponentSpec.kt` via `Containers.postgres()`: seed a tiny RLS-enabled schema + a non-owner `midas_app_readonly` role; dispatch a plan; assert Arrow rows round-trip. `@Tags("component")`; `componentTestImplementation(project(":shared:libs:kotlin:component-testkit"))`.
- [ ] **T4 — Component test: RLS leakage.** `RlsLeakageComponentSpec.kt`: seed two tenants' rows; a query under tenant A returns **zero** of tenant B's rows through Arges. Proves the read-only role does **not** bypass RLS (the worker-side twin of Midas-core's spec).
- [ ] **T5 — Kyklop registration + metrics + OTel.** Add the Arges slot to Kyklop `application.conf` (`role-hint="postgres"`, `endpoint=${?KYKLOP_WORKER_ARGES_ENDPOINT}`); confirm capability discovery; route `pg-midas` qnames via `world.table-connections` (overlay). Emit `arges_query_duration_seconds`, `arges_rows_returned_total`, `arges_pool_in_use`, `arges_rls_set_failures_total`; OTel spans `kyklop→arges→jdbc` (OTel gated on kantheon#27).
- [ ] **T6 — Deploy + smoke + ship.** Jib + Helm to bp-dsk; smoke: Kyklop → Arges → PG returns tenant-scoped rows. Tag `arges/v0.1.0`. Open the Midas-side `midas_app_readonly` migration coordination ticket (contracts §6) for the live `pg-midas` path.

## DONE

- [ ] Unit + component suites green (round-trip + RLS leakage, real PG, in CI).
- [ ] Kyklop dispatches a Postgres plan to Arges end-to-end (component level).
- [ ] Tag `arges/v0.1.0`. **Phase 1 DONE.** Midas P3 S3.2 unblocked (pending live `midas_app_readonly`).
