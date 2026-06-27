# Arges — Contracts (the Postgres worker)

> **Status:** arc contracts, created 2026-06-23. Source of truth for every Arges boundary. Protobuf is the source of truth even where the wire is gRPC (kantheon wire policy).
>
> **Reads with.** [`architecture.md`](./architecture.md), [`../fork/contracts.md`](../fork/contracts.md) (pipeline packages + ports), [`shared/proto/.../org/tatrman/worker/v1/worker.proto`](../../../shared/proto/src/main/proto/org/tatrman/worker/v1/worker.proto), [`../midas/contracts.md`](../midas/contracts.md) §6 (the schema read).

---

## 1. Proto — reused as-is

Arges implements the **existing** `org.tatrman.worker.v1.WorkerService` with **no proto changes**. Brontes and Steropes already implement it; Arges is a third implementation of the same contract.

| RPC | Arges behaviour |
|---|---|
| `Execute(ExecuteRequest) → stream ResultBatch` | Unparse plan → PostgreSQL, RLS-bind tenant, execute read-only, stream Arrow IPC batches. |
| `GetCapabilities(…) → GetCapabilitiesResponse` | `engine_name="postgres"`, `engine_version` from `SELECT version()`, `supported_languages=["SQL"]`, `supported_dialects=["POSTGRESQL"]`, `supported_connections` from config, `supports_stateful_sessions=false`, `max_*_sessions=0`, one `ConnectionInfo` per connection (`database`, `default_schema="public"`). |
| `GetStatus(…) → GetStatusResponse` | `ready` once ≥1 pool is up + Proteus reachable; per-`connection_id` `ConnectionStatus` from a `SELECT 1` probe; `DependencyStatus` for Proteus; `OverallStatus` degrades on dep/DB loss. |

**Tenant source.** `ExecuteRequest.context` (`org.tatrman.plan.v1.PipelineContext`) carries the caller's `tenant_id` (the same context Brontes forwards untouched). Arges reads it for the `SET LOCAL` bind. The field already exists on `PipelineContext` from the forked pipeline; **no additive proto change is needed** (verify in Stage 1.1 — if absent at HEAD, add `tenant_id` to `PipelineContext` as an additive field, mirroring the archived brief §3.1's contingency).

**Errors** travel as a single error `ResultBatch` (`is_first=is_last=true`, empty `arrow_ipc`, populated `messages`), identical to Brontes:

| code | when |
|---|---|
| `connection_not_supported` | `connection_id` ∉ advertised connections |
| `translator_failed` | Proteus returns an ERROR (e.g. PG unparse gap) |
| `tenant_id_required` | **new** — `PipelineContext` has no `tenant_id` (fail closed; RLS would raise anyway) |
| `rls_set_failed` | **new** — `SET LOCAL app.tenant_id` itself failed (bad role / GUC) |
| `worker_execution_failed` | any other JDBC/execution error |

(`tenant_id_required` and `rls_set_failed` are Arges-specific reuses of the generic `ResponseMessage.code` string channel — no proto change.)

---

## 2. Connection config (HOCON)

Mirror of Brontes's `connections` block, Postgres flavour. In `workers/arges/src/main/resources/application.conf`; production overlays fill secrets via env vars.

```hocon
connections {
  pg-midas {
    host = ${?ARGES_PG_MIDAS_HOST}
    port = 5432
    database = "midas"
    default-schema = "public"
    username = ${?ARGES_PG_MIDAS_USER}      # midas_app_readonly
    password = ${?ARGES_PG_MIDAS_PASSWORD}
    max-pool-size = 10
    read-only = true                         # HikariCP read-only pool
    requires-tenant-id = true                # NEW — fail closed if PipelineContext has no tenant
    # built URL: jdbc:postgresql://<host>:<port>/<database>
  }
}
```

### 2.1 The RLS execution envelope (the load-bearing contract)

For any connection with `requires-tenant-id = true`, every `Execute` runs inside one transaction:

```sql
BEGIN;
SET LOCAL app.tenant_id = :tenant_id;   -- from PipelineContext; :tenant_id is a UUID
-- <unparsed PostgreSQL query>;          -- RLS policies now scope every row
COMMIT;
```

- `SET LOCAL` is transaction-scoped, so the next pool borrow starts clean (no tenant bleed across pooled connections).
- Missing `tenant_id` → `tenant_id_required` error batch, nothing executes.
- `SET LOCAL` failure → `rls_set_failed`, transaction rolled back.
- The role (`midas_app_readonly`) is **not** a table owner, so RLS is enforced (owners bypass RLS unless `FORCE ROW LEVEL SECURITY`).

---

## 3. Postgres → Arrow type table

`PostgresArrowTypeMapper` (replaces `MssqlArrowTypeMapper`). Reads `ResultSetMetaData.getColumnTypeName()` for Postgres-specific disambiguation, falls back to JDBC type codes. Decimals preserved exactly; NULLs via Arrow validity bitmap; no-native-Arrow types → VARBINARY/VARCHAR + `pg.original_type` field metadata + one `unsupported_type_as_binary` warning on the first batch (same discipline as Brontes' `unsupportedBinaryFallbacks`).

| Postgres type(s) | Arrow type | Notes |
|---|---|---|
| `int2` / `smallint` | `SMALLINT` | |
| `int4` / `integer` | `INT` | |
| `int8` / `bigint` | `BIGINT` | |
| `bool` / `boolean` | `BIT` | |
| `real` / `float4` | `FloatingPoint(SINGLE)` | |
| `double precision` / `float8` | `FloatingPoint(DOUBLE)` | |
| `numeric` / `decimal` | `Decimal(p, s, 128)` | clamp `p∈1..38`, `s∈0..p`; **Midas `NUMERIC(20,4)` → `Decimal(20,4,128)`** (test the precision boundary) |
| `money` | `Decimal(19, 2, 128)` | PG `money` is locale-scaled; v1 Midas does not use it — map + warn if seen |
| `char` / `bpchar` / `varchar` / `text` / `name` | `VARCHAR` | |
| `date` | `Date(DAY)` | |
| `time` (without tz) | `Time(NANOSECOND, 64)` | |
| `timestamp` (without tz) | `Timestamp(NANOSECOND, null)` | |
| `timestamptz` | `Timestamp(NANOSECOND, "UTC")` | metadata `pg.original_type=timestamptz`; normalise to UTC instant |
| `uuid` | `VARCHAR` | metadata `pg.original_type=uuid` (Midas keys + `tenant_id` are UUID) |
| `bytea` | `VARBINARY` | faithful binary (not a fallback) |
| `json` / `jsonb` | `VARCHAR` | metadata `pg.original_type=json(b)`; v1 surfaces as text (Midas `audit` before/after) |
| `numrange` / `tstzrange` / `inet` / `cidr` / arrays / `tsvector` | `VARBINARY` + `pg.original_type=<type>` | `unsupported_type_as_binary` warning; none in the v1 Midas query catalog |
| unrecognised | JDBC-code fallback → else `VARBINARY` + `pg.original_type=<jdbc>` | last resort, mirrors Brontes |

**v1 coverage bar.** The five v1 Midas curated queries (`q.midas.positions_current`, `…transactions_recent`, `…dividends_period`, `…fees_period`, `…realised_pnl_period`, per Midas contracts §9 / plan S3.1) read only: `int*`, `numeric(20,4)`, `varchar/text`, `date`, `timestamptz`, `uuid`, `bool`. Those rows are the must-pass set in `PostgresArrowTypeMapperSpec`; the rest are defensive.

---

## 4. Kyklop registration

Arges adds a third worker slot to Kyklop's `application.conf` `kyklop.workers` list (the existing env-var-gated pattern — empty endpoint → skipped at boot):

```hocon
{
  # Arges — the Postgres worker (workers/arges; default gRPC 7303).
  endpoint = ${?KYKLOP_WORKER_ARGES_ENDPOINT}
  role-hint = "postgres"
}
```

Connection routing: `pg-midas` table/qname patterns map to the Arges-served connection via Kyklop's `world.table-connections` (production overlay), exactly as MSSQL connections route to Brontes today. Kyklop's capability poller discovers Arges's `GetCapabilities` and adds it to the healthy candidate set; sticky routing is irrelevant (Arges is stateless).

---

## 5. Ports

| Service | HTTP/admin | gRPC | Note |
|---|---|---|---|
| Brontes | 7295 | 7296 | |
| Steropes | 7300 | 7301 | |
| **Arges** | **7302** | **7303** | reserved Kyklops gap; 7304 still reserved |
| Theseus | 7305 | 7306 | (not a worker — shown to explain why Arges isn't at 7305) |
| Midas-core | 7310 | 7311 (MCP) | (constellation — shown for the same reason) |

Recorded in fork `contracts.md` §"Ports". Arges takes the reserved Kyklops gap (7302/7303) to stay adjacent to Brontes/Steropes, since 7305+ and 7310/7311 are already taken. Do not reassign without updating that table.

---

## 6. Read-only role + grants (Midas-side coordination)

Arges connects as a **non-owner** role. The DDL lives in a **Midas migration** (cross-arc coordination — Arges does not own the Midas schema):

```sql
-- Midas migration (e.g. V0004__arges_readonly_role.sql), coordinated with this arc.
CREATE ROLE midas_app_readonly LOGIN PASSWORD :'pw';   -- pw from sealed secret
GRANT CONNECT ON DATABASE midas TO midas_app_readonly;
GRANT USAGE  ON SCHEMA public TO midas_app_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO midas_app_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO midas_app_readonly;
GRANT EXECUTE ON FUNCTION app_current_tenant() TO midas_app_readonly;
-- Crucially: midas_app_readonly is NOT the table owner, so RLS policies apply to it.
```

In component tests the role is created by the Testcontainers seed, so the arc is **not blocked** on the Midas migration landing — but the live `pg-midas` path needs it before Midas Phase 3 Stage 3.2 can use Arges.

---

## 7. What is explicitly unchanged

- `ResultBatch` framing, `schema_fingerprint`, batching, cancellation (`Statement.cancel()`), options clamp — identical to Brontes.
- The `worker/v1` proto, `plan/v1`, `proteus/v1` packages — reused, no edits (except the Stage-1.1 `PipelineContext.tenant_id` verification).
- Arrow IPC serialization (`ArrowIpcSerializer`) — engine-agnostic, copied verbatim.
