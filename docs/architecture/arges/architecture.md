# Arges — Architecture (the Postgres worker)

> **Status:** arc architecture, created 2026-06-23 (fork decision). Source of truth for the shape of `workers/arges`.
>
> **Reads with.** [`contracts.md`](./contracts.md) (wire contracts + type table), [`../fork/architecture.md`](../fork/architecture.md) §2 (the forked pipeline), [`../midas/architecture.md`](../midas/architecture.md) §3.3/§12 (the consumer view), [`../../implementation/v1/arges/plan.md`](../../implementation/v1/arges/plan.md), [`../../implementation/v1/_archive/aip-v1-pg-worker-plan.md`](../../implementation/v1/_archive/aip-v1-pg-worker-plan.md) (the original ai-platform brief this forks).
>
> **One-line.** Arges is **Brontes adapted to PostgreSQL** plus a row-level-security session contract. Where the two diverge is the whole of the interesting design; everything else is a deliberate copy.

---

## 1. What Arges is

A stateless Kotlin/gRPC worker that:

1. receives a validated `org.tatrman.plan.v1.PlanNode` + `PipelineContext` from **Kyklop** (the dispatcher) via `WorkerService.Execute`,
2. asks **Proteus** (the translator) to unparse the plan to **PostgreSQL** SQL,
3. executes it against a Postgres connection (HikariCP pool, **read-only**), **inside a transaction that first issues `SET LOCAL app.tenant_id = <tenant>`**,
4. streams the `ResultSet` back as **Arrow IPC** `ResultBatch`es.

It advertises its engine, dialect, and the connection IDs it serves through `WorkerService.GetCapabilities`, and its health through `GetStatus` — identical surfaces to Brontes.

**It is the constellation's third Kyklops.** Brontes (MSSQL, Kotlin) and Steropes (Polars, Python) are the existing two; Arges (Postgres, Kotlin) is the bench name reserved for exactly this (`CLAUDE.md` §2, fork `architecture.md`). Naming: Greek transliteration, no persona beyond the Kyklops genus.

**It is read-only in v1.** Midas-core owns all writes via direct JDBC. Arges never writes. A future write-worker is a separate plan if Sysifos load ever justifies it (out of scope, mirrors the archived brief §7).

---

## 2. Shape — a deliberate mirror of Brontes

Arges copies Brontes's module structure 1:1; the package root is `org.tatrman.kantheon.arges.*`. The table below is the entire design: **same** = lift from Brontes unchanged in shape (re-typed for the new package); **changed** = the Postgres/RLS delta.

| Brontes component (`workers/brontes`) | Arges component (`workers/arges`) | Same / Changed |
|---|---|---|
| `Application.kt` (Ktor health + gRPC server bootstrap) | `Application.kt` | **Same** (ports + service name differ) |
| `grpc/WorkerServiceImpl.kt` | `grpc/WorkerServiceImpl.kt` | **Same** shape; `engine_name="postgres"`, `supported_dialects=["POSTGRESQL"]`, `supports_stateful_sessions=false` |
| `connection/ConnectionPoolManager.kt` + `ConnectionConfig` | `connection/ConnectionPoolManager.kt` + `ConnectionConfig` | **Changed**: builds `jdbc:postgresql://…` URLs; `default-schema="public"`; **adds the per-borrow `SET LOCAL app.tenant_id` discipline** (see §3) |
| `pipeline/ExecutePipeline.kt` (7-step pipeline) | `pipeline/ExecutePipeline.kt` | **Changed**: `SqlDialect.POSTGRESQL`; wraps execution in `BEGIN; SET LOCAL app.tenant_id=?; <query>; COMMIT;`; reads `tenant_id` from `PipelineContext` |
| `arrow/MssqlArrowTypeMapper.kt` | `arrow/PostgresArrowTypeMapper.kt` | **Changed**: Postgres native type names → Arrow (see contracts §3) |
| `arrow/ResultSetToArrow.kt` | `arrow/ResultSetToArrow.kt` | **Same** (generic JDBC→Arrow batcher; takes the type mapper as a dependency) |
| `arrow/ArrowIpcSerializer.kt` | `arrow/ArrowIpcSerializer.kt` | **Same** (engine-agnostic) |
| `client/TranslatorClient.kt` + `TranslatorHealth.kt` | `client/TranslatorClient.kt` + `TranslatorHealth.kt` | **Same** (calls Proteus `UnparseFromRelNode`; only the target dialect differs) |
| `k8s/` (Helm chart) | `k8s/` (Helm chart) | **Same** shape; image `arges:dev`, ports 7302/7303 |
| `src/componentTest` (Testcontainers MSSQL) | `src/componentTest` (Testcontainers Postgres) | **Changed**: `Containers.postgres()` + an RLS-leakage spec |
| `src/bench` (Arrow throughput) | `src/bench` (Arrow throughput) | **Same** (optional, not in CI gate) |

> **Why mirror rather than abstract.** The two JDBC workers share ~80% of their code, but the fork philosophy is copy-paste over premature abstraction (`CLAUDE.md` §1). A shared `worker-jdbc-base` lib is a **v1.x consolidation trigger** — flagged in the plan, not built now. If Arges lands and the diff against Brontes is genuinely just the type mapper + RLS + driver, that's the signal to extract the base lib then (and the third JDBC worker — Pyrakmon — would justify it).

---

## 3. The one real new thing — the RLS session contract

This is the only piece Brontes does not have, and the reason Arges is more than a find-and-replace.

Midas's operational schema enforces tenant isolation with Postgres **row-level security**: every table has a policy `USING (tenant_id = app_current_tenant())`, where `app_current_tenant()` reads the `app.tenant_id` GUC and **raises if it is unset** (`agents/midas/core/.../V0001__schema.sql`). Midas-core itself sets this GUC per request on its write/read path. Arges, reading the same tables through the query pipeline, **must do the same** or every query errors (GUC unset) — and, critically, must do it as a role that **does not bypass RLS**.

Two design consequences:

1. **Per-borrow tenant binding, transaction-scoped.** `SET LOCAL` is only honoured inside an explicit transaction. So the Arges execution path is:

   ```
   conn = pool.acquire(connectionId)        // read-only HikariCP pool
   conn.autoCommit = false
   conn.prepareStatement("SET LOCAL app.tenant_id = ?").apply { setObject(1, tenantId); execute() }
   rs = conn.prepareStatement(sql).executeQuery()   // RLS now scopes every row
   ... stream Arrow batches ...
   conn.commit()                            // or rollback on error; pool recycles a clean conn
   ```

   `tenant_id` comes from the `PipelineContext` that travels with the plan (contracts §2.1). **No tenant → fail closed**: Arges emits a single `tenant_id_required` error batch and runs nothing. This is the worker-side mirror of Midas-core's "fail closed on missing tenant" discipline (`kantheon-security.md`).

2. **A non-owner read-only role.** Postgres table owners *bypass* RLS unless `FORCE ROW LEVEL SECURITY` is set. Midas migrations run as `midas_app` (the owner). Arges therefore connects as a **separate `midas_app_readonly` login role** that has `SELECT` only and is subject to the policies. Provisioning that role + grants is a Midas-side migration (cross-arc coordination — see plan pre-flight). Until it exists, Arges runs against a Testcontainers Postgres with the role created by the component-test seed.

```
                tenant-scoped query pipeline
  Golem-Investment / Theseus                Kyklop                       Arges
 ┌────────────────────────┐  PlanNode +   ┌──────────────┐  Execute    ┌────────────────────────┐
 │ compile plan            │  PipelineCtx  │ sticky route │  (plan +    │ Hikari RO pool          │
 │ (q.midas.positions_…)   │ ─────────────►│ by conn key  │   tenant)   │  BEGIN                  │
 │ tenant_id in context    │               │ pg-midas     │ ───────────►│  SET LOCAL app.tenant_id│
 │                         │ ◄─────────────│              │ ◄───────────│  SELECT … (RLS applies) │
 └────────────────────────┘   Arrow IPC   └──────────────┘  Arrow IPC  │  → ResultSet → Arrow    │
                                                                        │  COMMIT                 │
                                                                        └────────────────────────┘
```

---

## 4. Dependencies (in and out)

**Inbound:** only **Kyklop** calls Arges (`WorkerService.Execute` / `GetCapabilities` / `GetStatus`). Kyklop gains a third worker slot (contracts §4).

**Outbound:**
- **Proteus** (translator) — `UnparseFromRelNode(plan, SQL, POSTGRESQL)`. **Risk/dependency:** the `POSTGRESQL` enum value exists in `proteus/v1`, but Proteus has no PG unparse rules wired yet (no `postgres` references in `services/proteus/src/main`). Calcite's `PostgresqlSqlDialect` does most of this natively; the arc's Stage 1.2 audits + closes the gap for the v1 Midas query catalog. **This is the gating upstream item.**
- **Midas Postgres** (the `midas` database in the shared Kantheon PG) — read via the `midas_app_readonly` role.
- Shared libs: `shared/proto`, `shared/libs/kotlin/{ktor-configurator, otel-config, logging-config}`, `org.postgresql:postgresql`, `hikaricp`, `arrow-vector` + `arrow-memory-netty` (all already in the version catalog; consumed by Brontes today).

**No** ai-platform coupling — Arges is born in-repo (fork end-state, `CLAUDE.md` §7).

---

## 5. Deployment topology

- One Kotlin module `workers/arges`, Jib-built image `arges:dev`, deployed via a Helm chart `workers/arges/k8s` (mirror Brontes's chart).
- Ports: **7302** (Ktor health/admin), **7303** (gRPC) — the reserved Kyklops gap adjacent to Brontes (7295/7296) and Steropes (7300/7301); 7304 stays reserved. (7305+ is Theseus, 7310/7311 is Midas-core — hence the gap, not a new block.) Recorded in fork `contracts.md` port table.
- Runs on the olymp `bp-dsk` cluster (kantheon-owned, no ai-platform), GitOps/ArgoCD like the rest of the constellation.
- `imagePullPolicy: Never` in the local overlay.
- Activated in Kyklop via `KYKLOP_WORKER_ARGES_ENDPOINT` (empty → slot skipped at boot, so local dev without the env var is unaffected — the existing Kyklop slot-skip discipline).

---

## 6. Testing posture

Per the testing policy (`planning-conventions.md` §4) and the testing arc:

- **Unit (stage gate):** type-mapper table (`PostgresArrowTypeMapperSpec`), pipeline error paths, options-clamp, the RLS `SET LOCAL` issuance against a mocked/fake connection — all `src/test`, MockK + fakes.
- **Component (CI on every PR, not the gate):** `src/componentTest` with `Containers.postgres()` (`shared/libs/kotlin/component-testkit`): a real round-trip (plan → Arrow rows) **and** an `RlsLeakageComponentSpec` proving tenant A's query returns zero of tenant B's rows through the worker — the worker-side twin of Midas-core's `RlsLeakageComponentSpec`.
- **Integration (separate suite, nightly on olymp):** the live `Kyklop → Arges → midas-postgres` path is exercised by Midas Phase 3 Stage 3.2's component/integration tests and the closing demo, not here.

---

## 7. Observability

Mirror Brontes + the worker metrics named in the archived brief §4: `arges_query_duration_seconds`, `arges_rows_returned_total`, `arges_pool_in_use`, and the RLS-specific `arges_rls_set_failures_total` (increments when `SET LOCAL` fails — the canary for a misconfigured role or GUC). OTel spans `kyklop → arges → jdbc`, correlation IDs from `PipelineContext`. Grafana dashboard JSON ships in the chart (gated on the cluster-wide OTel enablement, kantheon#27, like iris-bff).

---

## 8. Out of scope (v1)

- Postgres **write** worker (Midas-core writes directly).
- Other PG consumers (Arges serves `pg-midas` only in v1; new sources = new connection profiles, same worker).
- Read-replica routing (single-instance PG in v1).
- Stateful sessions / workspace store (Arges advertises `supports_stateful_sessions=false`; that lane is Steropes's).
- A shared `worker-jdbc-base` lib (v1.x consolidation trigger; see §2).
