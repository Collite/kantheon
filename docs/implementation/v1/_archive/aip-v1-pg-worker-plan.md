# ai-platform — `workers/postgres` implementation plan

> **Scope.** Cross-repo doc tracking the ai-platform-side work required to read the kantheon-owned Midas operational Postgres via the standard query pipeline (`query-mcp` → `services/dispatcher` → `workers/postgres`). Mirrors the existing `workers/mssql` shape. Lives in `kantheon/docs/implementation/v1/` for cross-repo traceability from the Midas arc; once work starts, this doc will be ported (or referenced) from `ai-platform/docs/`.
>
> **Owns the work.** ai-platform team. This plan is a brief, not a binding spec — `ai-platform/CLAUDE.md` conventions win where they conflict.
>
> **Reads with.** [`../../architecture/midas/architecture.md`](../../architecture/midas/architecture.md) §3.3 and §12 (the kantheon-side consumer view), [`../../architecture/midas/contracts.md`](../../architecture/midas/contracts.md) §6 (the schema the worker reads), [`./aip-v1-gap-closure-plan.md`](./aip-v1-gap-closure-plan.md) (sibling cross-repo plan).

---

## 1. Goal

Ship `workers/postgres` in ai-platform: a Kotlin/Ktor worker mirroring `workers/mssql`, capable of executing compiled query plans against a PostgreSQL connection profile, streaming results back over Arrow IPC. Plus the dispatcher/translator/validator tweaks needed to route to it.

**Deliverable.** `workers/postgres` deployed in local K3s, registered with `services/dispatcher`, routing key `pg-midas` resolving to the kantheon-owned operational Postgres. `query-mcp.compile(q.midas.positions_current)` followed by `query-mcp.run(...)` returns row data from `mv_position_current` via Arrow IPC.

**Critical-path slot in Midas arc.** Required by Midas Phase 3 Stage 3.2. Not required for Phases 1 or 2. If this work slips, Midas Phase 3 starts with non-blocked stages (3.1, 3.3, 3.4, 3.5) and defers 3.2.

---

## 2. Architectural shape — strict mirror of `workers/mssql`

```
   query-mcp                          dispatcher                            workers/postgres
┌─────────────────┐                ┌────────────────┐                  ┌──────────────────────┐
│ compile plan    │  Arrow plan    │ sticky routing │  Arrow plan       │ HikariCP pool        │
│ + cache         │ ──────────────►│ by conn key    │ ────────────────► │ → Postgres JDBC      │
│                 │                │ pg-midas       │                   │ → ResultSet          │
│                 │ ◄──────────────│                │ ◄───────────────  │ → Arrow IPC stream   │
└─────────────────┘    Arrow IPC   └────────────────┘   Arrow IPC       └──────────────────────┘
```

Same protocol as `workers/mssql`. Same gRPC surface (or REST + Arrow if that's where the worker protocol stands at HEAD — verify against current ai-platform). Same OTel patterns, same `mcp-server-base` (where applicable), same K3s Kustomize overlays.

What changes:

- **JDBC driver.** `org.postgresql:postgresql:42.7.x` (or current).
- **Dialect translation.** Translator/validator output PG syntax.
- **Connection profile registration.** `pg-midas` profile pointing at the kantheon-owned `midas-postgres` service.
- **RLS contract.** Worker must execute `SET LOCAL app.tenant_id = ?` per connection borrow, using the tenant_id forwarded from `query-mcp` (which gets it from the calling agent's context). This is new — `workers/mssql` doesn't have an analogous session-var contract today; PG worker adds it.

---

## 3. Wire contracts

### 3.1 Worker dispatch protocol

Reuse whatever protocol `workers/mssql` uses. Verify against ai-platform HEAD before starting Stage 1. Expected (subject to confirmation):

- gRPC service `cz.dfpartner.worker.v1.WorkerService` (or current package), with `Execute(stream PlanFrame) → stream ResultFrame`.
- Plan frames carry compiled-query intermediates (Calcite RelNode JSON or platform-equivalent).
- Result frames carry Arrow IPC chunks plus a terminal `Done` frame with row count + duration.

If the existing protocol does **not** support an optional `tenant_id` field on the plan frame, this arc adds it (additive proto change in `cz.dfpartner.worker.v1`). Otherwise, reuses the existing field.

### 3.2 Connection profile registration

`services/dispatcher` reads connection profiles from config. Add:

```yaml
connections:
  pg-midas:
    kind: POSTGRES
    jdbc_url: jdbc:postgresql://midas-postgres.kantheon.svc.cluster.local:5432/midas
    username_secret: midas-pg-readonly-username
    password_secret: midas-pg-readonly-password
    pool_size_min: 2
    pool_size_max: 10
    session_init_sql:
      - "SET application_name = 'workers-postgres'"
    requires_tenant_id: true               # NEW — enables the SET LOCAL app.tenant_id contract
```

`requires_tenant_id: true` is the new flag; dispatcher refuses to route a plan without `tenant_id` to such profiles.

### 3.3 Read-only role

A dedicated PG role `midas_app_readonly`:

```sql
CREATE ROLE midas_app_readonly LOGIN PASSWORD '<from-secret>';
GRANT CONNECT ON DATABASE midas TO midas_app_readonly;
GRANT USAGE ON SCHEMA public TO midas_app_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO midas_app_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO midas_app_readonly;
-- RLS still applies; the role does NOT bypass row policies.
```

Kantheon-side Flyway migrations create the role (initial migration ships in Midas Phase 1 Stage 1.1, awaiting this worker plan).

---

## 4. Phased plan

Single phase (~2 stages, ~12 tasks). Lives in ai-platform's existing implementation-plan structure if one exists, or under `ai-platform/docs/v1/workers-postgres-plan.md` if not.

### Stage A — Worker skeleton + dispatcher integration

**Goal.** `workers/postgres` builds, runs in local K3s, executes a trivial SELECT against a Testcontainers Postgres via the worker dispatch protocol.

**Pre-flight.**
- ai-platform Maven publishing live (closed).
- `workers/mssql` exists at HEAD and its protocol is well-understood (verify before starting).
- Kantheon Midas Phase 1 Stage 1.1 closed (midas-postgres exists locally for integration).

**Tasks (~6).**
1. **Module skeleton** — `workers/postgres/build.gradle.kts`, src layout, Kustomize manifests. Convention plugins consumed from ai-platform Maven. Mirror `workers/mssql` directory structure.
2. **Dialect tests first** — verify translator/validator emit PG SQL for at least the v1 query catalog: `q.midas.positions_current`, `q.midas.transactions_recent`. CTEs, window functions, JSON paths.
3. **Translator/validator PG paths** — extend `services/translator` + `services/validator` for PG dialect where gaps exist (verify against current state — Calcite supports PG output mostly natively).
4. **Worker execution tests first** — Testcontainers-Postgres-driven spec: dispatcher → worker → result frames; assert row count + Arrow schema correct.
5. **Worker execution implementation** — JDBC `PreparedStatement` execution; `ResultSet` → Arrow column batches; backpressure via the existing worker streaming primitives.
6. **Tenant-ID session var contract** — implement `SET LOCAL app.tenant_id = ?` per connection borrow; tests verify RLS-leakage protection (cross-tenant query returns 0 rows).

**Stage A DONE.** `workers/postgres` runs against Testcontainers + against the local `midas-postgres`; trivial query returns Arrow data.

### Stage B — End-to-end integration + observability

**Goal.** Full path green: `query-mcp.compile + run` → `services/dispatcher` → `workers/postgres` → `midas-postgres` → Arrow stream back to `query-mcp` caller.

**Tasks (~6).**
1. **Connection-profile registration** — `services/dispatcher` config update for `pg-midas` (§3.2); secret wiring; smoke that dispatcher knows the profile.
2. **`query-mcp` profile passthrough** — verify `query-mcp` forwards `connection_profile = pg-midas` from compiled plans through to dispatcher; tests against the v1 query catalog from kantheon Midas Stage 3.1.
3. **OTel propagation** — traces span `query-mcp → dispatcher → worker → JDBC`; correlation IDs preserved; spans named per ai-platform convention.
4. **Metrics + Grafana dashboard** — Prometheus metrics: `pg_worker_query_duration_seconds`, `pg_worker_rows_returned_total`, `pg_worker_pool_in_use`, `pg_worker_rls_set_failures_total`. Grafana dashboard JSON in `workers/postgres/k8s/base/grafana/`.
5. **Cross-repo integration smoke** — from kantheon's Midas Phase 3 Stage 3.2: Golem-Investment-shaped fixture call → query-mcp.compile(q.midas.positions_current) → run with tenant_id X → rows returned. Document the call sequence in this plan.
6. **Deploy + handoff** — Jib + Kustomize apply; mark stage DONE only after kantheon's Midas Stage 3.2 successfully exercises this path end-to-end.

**Stage B DONE.** kantheon Midas Phase 3 Stage 3.2 unblocked. Grafana dashboard for the worker visible in the platform dashboard panel.

---

## 5. Pre-flight checklist

| Item | Source | Status |
|---|---|---|
| `workers/mssql` protocol shape documented or readable at HEAD | ai-platform | verify before Stage A |
| ai-platform Maven publishing live (incl. worker base libs) | aip-v1-gap-closure Gap 1 | **closed** |
| `services/translator` PG dialect verified | ai-platform | verify in Stage A Task 2 |
| `services/validator` PG dialect verified | ai-platform | verify in Stage A Task 2 |
| `services/dispatcher` connection-profile config slot exists | ai-platform | verify (likely yes; check Gap 5 or current state) |
| Tenant-id flow in plan frame | ai-platform protocol | verify; add field if missing |
| midas-postgres cluster reachable from worker pod | kantheon | confirmed via Midas Phase 1 Stage 1.1 |
| Read-only PG role `midas_app_readonly` provisioned by Flyway | kantheon Midas Stage 1.3 | tracked in Midas plan |

---

## 6. Risks and unknowns

- **Worker protocol drift.** If ai-platform refactors the worker protocol after this plan is written, Stage A's Task 1 grows. Mitigation: stage starts with the protocol audit; protocol fixes are a sibling work item.
- **Translator gaps for PG-specific SQL.** Calcite handles most PG output, but `JSONB` operators, `tsvector`, or `LATERAL` joins may need targeted work. Mitigation: the v1 query catalog is small (5 queries); audit each ahead of time.
- **RLS + connection pooling.** HikariCP recycles connections; `SET LOCAL app.tenant_id` is statement-scoped if used outside an explicit BEGIN. Mitigation: worker wraps each query in `BEGIN; SET LOCAL …; <query>; COMMIT;`. Test explicitly.
- **Arrow type fidelity.** Postgres NUMERIC vs Arrow Decimal mapping — choose decimal128(precision, scale) to match `NUMERIC(20,4)`. Test with values at the precision boundary.
- **Cross-repo handoff timing.** kantheon Phase 3 cannot start Stage 3.2 until this work is green. Mitigation: start this work in parallel with kantheon Phase 1; budget 1.5–2 weeks; the parallel kantheon track can absorb up to ~6 weeks of slip before it bottlenecks (Phase 1 + Phase 2 are 7–9 weeks).

---

## 7. Out of scope

- **Postgres write-path worker.** Midas-core uses direct JDBC for writes through v1. Future write-worker is a separate plan if/when Sysifos load patterns justify it.
- **Other Postgres consumers.** This worker is provisioned with `pg-midas` only in v1. Other kantheon-owned PG sources (when they appear) reuse the same worker with new connection profiles.
- **Read-replica routing.** Midas-postgres v1 is single-instance. Read-replicas are a v1.x topic.

---

## 8. Coordination notes

- **Sequencing.** This work runs in parallel with kantheon Midas Phase 1. Target: Stage A done before kantheon Phase 2 close; Stage B done before kantheon Phase 3 Stage 3.2 needs to start.
- **Hand-off touchpoint.** Stage B Task 5 is a joint smoke test with kantheon's Midas team (Bora). Schedule that call before declaring the stage DONE.
- **Documentation.** When this plan is ported to ai-platform, leave a stub here pointing to the live doc.

---

*Doc owner: Bora (cross-repo). Lives in `kantheon/docs/implementation/v1/` as a coordination doc; ports to ai-platform once work begins. Update before any cross-repo schedule change.*
