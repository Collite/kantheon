# Arges — Phased Implementation Plan (kantheon arc)

> **Scope.** From "kantheon has no Postgres worker" to "Arges live: Kyklop dispatches Postgres query plans to it, it executes them read-only under per-tenant RLS, and streams Arrow IPC back." **One phase, three stages, ~18 tasks.** Arges is the kantheon **fork** of the never-built ai-platform `workers/postgres` brief — built in-repo by mirroring **Brontes**, zero ai-platform coupling.
>
> **Companions.** [`../../../architecture/arges/architecture.md`](../../../architecture/arges/architecture.md), [`../../../architecture/arges/contracts.md`](../../../architecture/arges/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md), [`../_archive/aip-v1-pg-worker-plan.md`](../_archive/aip-v1-pg-worker-plan.md) (the originating brief), [`../midas/plan.md`](../midas/plan.md) (the consumer arc — Phase 3 Stage 3.2).
>
> **Stream / sequencing.** Stream B (the Body), like Charon/Metis/Midas. Must close before **Midas Phase 3 Stage 3.2** can use the live `pg-midas` path. Resolves master-plan §7 open item (decision 2026-06-23, Bora: fork now).
>
> **Status (2026-06-24): P1 CODE-COMPLETE (S1.1–S1.3, reviewed).** Skeleton + build wiring + capabilities/status (S1.1), PG execution pipeline + Arrow type mapper + Proteus audit (S1.2), RLS envelope (`SET LOCAL app.tenant_id`) + component specs + Kyklop registration (S1.3) — 52 unit specs + `@Tag("component")` specs (real-PG/RLS in the Stream-T integration suite). **Tag `arges/v0.1.0` on merge** → opens Midas P3 S3.2. Two follow-ons (below): Proteus PG unparse (gated S1.2 corner) + the Midas `midas_app_readonly` role (live path, not the arc gate).
>
> **Hierarchy** (per `planning-conventions.md`): task → stage (~6 tasks, testable) → phase (deployable).

---

## 1. Phase summary

| Phase | Goal — what deploys at the end | Stages | Status |
|---|---|---|---|
| **Phase 1 — Arges live** | `workers/arges` runs in local K3s / on bp-dsk; Kyklop dispatches a Postgres `PlanNode` to it; it unparses via Proteus to PostgreSQL, executes read-only under `SET LOCAL app.tenant_id`, streams Arrow IPC; component test proves a real round-trip **and** RLS cross-tenant isolation through the worker. | 1.1 / 1.2 / 1.3 | **Planned (2026-06-23)** |

**Total:** ~1–2 weeks (small — Brontes carries ~80% of the shape). Single phase, strictly sequential stages.

**Tag ladder:** `arges/v0.1.0` at Phase 1 exit.

---

## 2. Pre-flight — what must be true before Phase 1

| Pre-flight item | Source | Required by | Status (2026-06-23) |
|---|---|---|---|
| `workers/brontes` exists at HEAD as the structural template | fork Phase 1–3 | S1.1 | **MET** (Brontes live, `org.tatrman.worker.v1`) |
| `org.tatrman.worker.v1` + `plan/v1` + `proteus/v1` protos compiled | fork | S1.1 | **MET** (in `shared/proto`) |
| `PipelineContext` carries `tenant_id` (or add it additively) | worker pipeline | S1.1 T2 | **VERIFY** — if absent, additive proto field (archived brief §3.1 contingency) |
| **Proteus emits PostgreSQL** (`SqlDialect.POSTGRESQL` unparse for the v1 Midas query catalog) | Proteus | S1.2 | **MET (audited 2026-06-23, S1.2 T1)** — NOT a gap after all: `shared/libs/kotlin/query-translator` `Dialects.byCode` already maps `SqlDialectProto.POSTGRESQL → PostgresqlSqlDialect.DEFAULT`, the Optimizer has a POSTGRESQL branch, and library specs assert PG double-quoted output. The unparse path is dialect-parameterised end-to-end; the only MSSQL-specific quirk (DOUBLE→FLOAT cast) is isolated in the MSSQL dialect subclass. Pinned at the Proteus gRPC edge by a new `TranslatorServiceImplSpec` POSTGRESQL test |
| `component-testkit` has `Containers.postgres()` | testing arc | S1.3 | **MET** (used by Midas componentTests) |
| Midas operational PG up; RLS = `app.tenant_id` GUC + `app_current_tenant()` | Midas P1 | S1.3 (live path) | **MET** (V0001) |
| `midas_app_readonly` non-owner role + SELECT grants | Midas migration (coordinate) | live `pg-midas` only; **not** the arc gate (component test seeds its own role) | **OPEN — Midas-side coordination** (contracts §6) |
| Kyklop worker-slot config supports a third env-var-gated slot | fork | S1.3 | **MET** (existing `kyklop.workers` pattern) |

**Two things to resolve, neither blocks Stage 1.1:**
1. **Proteus PG dialect** — the real upstream work item (Stage 1.2 audits + closes it for the v1 query catalog). If it's larger than expected, it becomes a sibling Proteus task; Arges Stage 1.2 depends on its output.
2. **`midas_app_readonly` role** — a small Midas migration, coordinated with the Midas arc. Component tests don't need it (they seed their own role); only the live `pg-midas` path does, before Midas P3 S3.2.

---

## 3. Phase 1 — Arges live

**Deployable at phase close.** Arges pod healthy on bp-dsk; `Kyklop.GetCapabilities` discovers it; a Postgres plan dispatched through Kyklop returns Arrow rows scoped to the request's tenant; `arges/v0.1.0` tagged.

### Stage 1.1 — Module skeleton + build wiring + capabilities/status

**Goal.** `workers/arges` compiles and boots (empty pipeline); `GetCapabilities`/`GetStatus` answer; Kyklop can see it; no real query execution yet. Mirror Brontes.

**Pre-flight.** Brontes readable; worker protos compiled; `PipelineContext.tenant_id` verified (T2).

**Tasks (6).**
1. **Module skeleton** — copy Brontes's layout into `workers/arges/`: `build.gradle.kts` (swap `mssql.jdbc` → `libs.postgresql`; keep arrow + grpc + ktor deps; `mainClass = org.tatrman.kantheon.arges.ApplicationKt`; image `arges:dev`; ports 7302/7303; `componentTest` wiring + Arrow `--add-opens`), `src/{main,test,componentTest,bench}` trees, `k8s/` Helm chart (mirror Brontes; rename + reports ports). Package root `org.tatrman.kantheon.arges`.
2. **`PipelineContext.tenant_id` verification** — confirm the field exists on `org.tatrman.plan.v1.PipelineContext`; if not, add it additively + regenerate (`just proto`). Document the field name used by `Execute`.
3. **Settings + CI** — add `workers:arges` to `settings.gradle.kts`; confirm root `subprojects {}` auto-wires kotest + the `componentTest`/`integrationTest` source sets; CI auto-detects the module; `just build-kt workers/arges` green on stubs.
4. **`Application.kt` + gRPC bootstrap** — copy Brontes's `Application.kt`; Ktor health on 7302, gRPC `WorkerService` on 7303; `application.conf` with the `pg-midas` connection block (contracts §2) + telemetry off by default.
5. **`ConnectionPoolManager` + `ConnectionConfig` (PG)** — adapt Brontes's: build `jdbc:postgresql://host:port/database` URLs, `default-schema="public"`, `read-only=true`, `requires-tenant-id` flag parsed; `SELECT 1` probe. Unit tests for URL build + config parse.
6. **`WorkerServiceImpl` — capabilities/status only** — `engine_name="postgres"`, `supported_dialects=["POSTGRESQL"]`, `supports_stateful_sessions=false`; `GetStatus` readiness/probe/dep shape from Brontes. `execute` returns a `not_implemented` error batch until Stage 1.2. Unit tests for the capability/status shape.

**Stage 1.1 DONE.** `just build-kt workers/arges` + unit tests green; pod boots; `GetCapabilities` advertises `postgres`/`POSTGRESQL`; `GetStatus` reports the configured connection. Kyklop with `KYKLOP_WORKER_ARGES_ENDPOINT` set discovers it as a candidate.

**Dependencies.** None internal.

### Stage 1.2 — Postgres execution pipeline + Arrow type mapper

**Goal.** Real read execution: plan → Proteus(PostgreSQL) → JDBC → Arrow IPC stream. No RLS yet (added in 1.3); runs against a plain Postgres.

**Pre-flight.** Stage 1.1 DONE; **Proteus PG dialect audited** (T1).

**Tasks (6).**
1. **Proteus PG-dialect audit + close** — verify `Proteus.UnparseFromRelNode(plan, SQL, POSTGRESQL)` produces valid PG for the five v1 Midas queries (CTEs, window functions, `numeric`, date filters). Close gaps (likely just enabling Calcite's `PostgresqlSqlDialect` for the unparse path). Tests first: golden SQL per query. *(If the gap is large, split into a Proteus task and depend on it.)*
2. **`PostgresArrowTypeMapper` tests first** — `PostgresArrowTypeMapperSpec` over the contracts §3 table; must-pass set = the v1-query types (`int*`, `numeric(20,4)`→`Decimal(20,4,128)`, `varchar/text`, `date`, `timestamptz`, `uuid`, `bool`); defensive rows for `bytea`/`jsonb`/ranges → VARBINARY/VARCHAR + `pg.original_type`.
3. **`PostgresArrowTypeMapper` implementation** — by native type name with JDBC-code fallback + `unsupportedBinaryFallbacks` (mirror Brontes). Make the spec pass.
4. **`ResultSetToArrow` + `ArrowIpcSerializer` reuse** — copy from Brontes verbatim (engine-agnostic), wired to the PG type mapper; batching + fingerprint.
5. **`ExecutePipeline` (no RLS yet) tests first + impl** — the 7-step pipeline against `SqlDialect.POSTGRESQL`; `connection_not_supported` / `translator_failed` / `worker_execution_failed` error batches; options clamp; cancellation via `Statement.cancel()`. Unit tests with a fake translator + fake connection.
6. **`TranslatorClient` + `TranslatorHealth`** — copy from Brontes; target dialect `POSTGRESQL`; dependency-health into `GetStatus`.

**Stage 1.2 DONE.** Unit suite green; a fixture plan unparsed + executed against an embedded/fake PG path returns the expected Arrow schema + rows; type mapper passes the v1 must-pass set.

**Dependencies.** Stage 1.1; Proteus PG unparse (T1).

### Stage 1.3 — RLS tenant contract + component test + Kyklop reg + ship

**Goal.** The novel bit: per-tenant `SET LOCAL app.tenant_id`, fail-closed; real-PG component proof incl. RLS leakage; Kyklop registration; deploy; tag.

**Pre-flight.** Stage 1.2 DONE; `Containers.postgres()` available.

**Tasks (6).**
1. **RLS envelope tests first (unit)** — assert `Execute` with a tenant in `PipelineContext` issues `BEGIN; SET LOCAL app.tenant_id=?; …; COMMIT;` (against a fake connection, capturing statements in order); missing tenant → `tenant_id_required` and **nothing runs**; `SET LOCAL` failure → `rls_set_failed` + rollback.
2. **RLS envelope implementation** — wire the transaction-scoped tenant bind into `ExecutePipeline` + `ConnectionPoolManager.acquire` borrow path; `requires-tenant-id` connections enforce it; read-only pool.
3. **Component test — round-trip** — `src/componentTest/.../ArgesPostgresComponentSpec.kt` via `Containers.postgres()`: seed a tiny schema with RLS + the `midas_app_readonly` role, dispatch a plan, assert Arrow rows round-trip. `@Tags("component")`.
4. **Component test — RLS leakage** — `RlsLeakageComponentSpec.kt`: seed two tenants' rows; a query under tenant A returns **zero** of tenant B's rows through the worker (the worker-side twin of Midas-core's RLS spec). Proves the role doesn't bypass RLS.
5. **Kyklop registration + metrics** — add the Arges worker slot to Kyklop's `application.conf` (`role-hint="postgres"`, `KYKLOP_WORKER_ARGES_ENDPOINT`); confirm capability discovery; emit `arges_query_duration_seconds`, `arges_rows_returned_total`, `arges_pool_in_use`, `arges_rls_set_failures_total`; OTel spans `kyklop→arges→jdbc`.
6. **Deploy + smoke + ship** — Jib + Helm to bp-dsk; smoke: Kyklop → Arges → Testcontainers/local PG returns tenant-scoped rows; OTel gated on kantheon#27. Tag `arges/v0.1.0`. Open the Midas-side `midas_app_readonly` migration coordination ticket (contracts §6) for the live `pg-midas` path.

**Stage 1.3 DONE.** Component suite green (round-trip + RLS leakage, real PG, in CI); Kyklop dispatches to Arges; `arges/v0.1.0` tagged. **Phase 1 DONE.**

**Dependencies.** Stage 1.2; testing-arc component tier.

---

## 4. Cross-cutting / coordination

| Item | Where |
|---|---|
| Proteus PostgreSQL unparse (the upstream gap) | Stage 1.2 T1; may spin a sibling Proteus task |
| `midas_app_readonly` role + grants | Midas migration (contracts §6) — needed for live `pg-midas`, not for the arc gate |
| Kyklop `world.table-connections` routing of `pg-midas` qnames to Arges | production overlay; Stage 1.3 T5 |
| Midas Phase 3 Stage 3.2 consumes this (the e2e Q&A path) | `midas/plan.md` §5 — Arges replaces the archived ai-platform `workers/postgres` dependency |
| `worker-jdbc-base` shared lib (Brontes∩Arges) | **v1.x consolidation trigger** — not now (see architecture §2) |

---

## 5. Out of scope (v1)

- Postgres **write** worker (Midas-core writes directly via JDBC).
- PG sources other than `pg-midas` (new sources = new connection profiles, same worker).
- Read-replica routing; stateful sessions / workspace store (that's Steropes's lane).
- Full e2e through a live Iris→Themis→Golem-Investment→Kyklop→Arges chain — that lives in the integration suite + Midas P3 S3.2's demo (testing policy, planning-conventions §4).

---

## 6. Risks

- **Proteus PG unparse larger than expected.** Mitigation: the v1 query catalog is 5 queries; audit each up front (Stage 1.2 T1); Calcite handles most PG output natively. If big, it's a Proteus sibling task and Arges S1.2 waits on it.
- **RLS + pooling bleed.** `SET LOCAL` outside a transaction is statement-scoped and can leak across pooled borrows. Mitigation: always `BEGIN…COMMIT` per query (contracts §2.1); the RLS-leakage component test is the guard.
- **Owner bypasses RLS.** If Arges ever connects as `midas_app` (owner), RLS is silently bypassed. Mitigation: contracts §6 mandates the non-owner `midas_app_readonly`; the component test would catch a leak.
- **`numeric(20,4)` precision.** Postgres NUMERIC → Arrow Decimal128 boundary. Mitigation: explicit boundary-value test in `PostgresArrowTypeMapperSpec`.

---

## 7. Phase progression checklist

- [x] **Stage 1.1** — skeleton + build wiring + capabilities/status. **DONE (2026-06-23):** `workers/arges` compiles + boots (Ktor 7302 / gRPC 7303); `GetCapabilities` advertises `postgres`/`POSTGRESQL`/stateful=false, `GetStatus` reports the configured connection + translator dep; `execute` returns a `not_implemented` error batch until Stage 1.2; 23 unit specs green (`ConnectionPoolManagerSpec` ×15, `WorkerServiceImplSpec` ×8). T2 resolved: `PipelineContext` had **no** `tenant_id` → added additively as field 9 (`reserved` shrunk to 10–12).
- [x] **Stage 1.2** — PG execution pipeline + Arrow type mapper (+ Proteus PG unparse). **DONE (2026-06-23):** T1 Proteus PG-unparse **audited → already supported** (no gap; pinned by a new Proteus gRPC POSTGRESQL `TranslatorServiceImplSpec` test). `PostgresArrowTypeMapper` (contracts §3 table; v1 must-pass set incl. `NUMERIC(20,4)→Decimal128(20,4)`), `ResultSetToArrow` + `ArrowIpcSerializer` copied from Brontes (engine-agnostic) wired to the PG mapper, real 7-step `ExecutePipeline` targeting `SqlDialect.POSTGRESQL` (no RLS yet — Stage 1.3). 50 Arges unit specs green incl. a fake-PG round-trip (mocked `ResultSet` → decoded Arrow rows); `ExecutePipelineSpec` covers `connection_not_supported`/`translator_failed`. Proteus suite green (15).
- [x] **Stage 1.3** — RLS contract + component tests + Kyklop reg + deploy. **CODE-COMPLETE (2026-06-23):** RLS envelope in `ExecutePipeline` — `requires-tenant-id` connections run inside a transaction that binds `SET LOCAL app.tenant_id = '<uuid>'` (UUID-validated literal, injection-safe, matching Midas-core `TenantContext`); missing/invalid tenant → `tenant_id_required` (nothing runs), `SET LOCAL` failure → `rls_set_failed` + rollback, success commits. `RlsEnvelopeSpec` (4 unit specs: statement order, fail-closed, rollback). Component tier (`@Tags("component")`, Testcontainers Postgres): `ArgesPostgresComponentSpec` (real round-trip incl. `NUMERIC(20,4)`) + `RlsLeakageComponentSpec` (tenant A sees zero of B's rows through the worker + fail-closed) — both **CI-gated** (local Testcontainers port-forward unavailable on Rancher Desktop, identical to Midas's spec; the RLS SQL contract — non-owner role + `SET LOCAL` scoping + fail-closed `app_current_tenant()` — was verified locally against real PG via `docker exec`). Kyklop registration: third env-gated worker slot (`KYKLOP_WORKER_ARGES_ENDPOINT`, `role-hint="postgres"`). **Metrics:** explicit Prometheus counters deferred with cluster-wide OTel (kantheon#27) — parity with Brontes (no explicit metrics; OTel spans via the SDK + gRPC interceptors). 54 Arges unit specs + Kyklop suite green. **Remaining ship steps (post-merge):** Jib image build + Helm deploy to bp-dsk (chart renders; Jib config is a verbatim Brontes mirror — local build blocked by the same Rancher/Jib daemon nuance, builds in CI) and the `arges/v0.1.0` tag. **Phase 1 engineering DONE; tag + deploy on merge.**

---

*Plan owner: Bora. Arc decided + planned 2026-06-23 (fork-now). Per-stage task lists at `docs/implementation/v1/arges/tasks-p1-s1.m-*.md`.*
