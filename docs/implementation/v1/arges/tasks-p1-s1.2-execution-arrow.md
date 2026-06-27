# Stage 1.2 — PG execution pipeline + Arrow type mapper

> **Phase 1, Stage 1.2.** Real read execution: plan → Proteus(PostgreSQL) → JDBC → Arrow IPC. No RLS yet (Stage 1.3).
>
> **Reads with.** [`plan.md`](./plan.md) §3 (Stage 1.2), [`../../../architecture/arges/contracts.md`](../../../architecture/arges/contracts.md) §3 (type table), §1 (error codes). **Template:** `workers/brontes/src/main/kotlin/.../{pipeline/ExecutePipeline.kt, arrow/*.kt, client/*.kt}`.

## Goal

A fixture plan unparses to PostgreSQL and executes; `ResultSet` → Arrow IPC batches stream back; the type mapper passes the v1 must-pass set.

## Pre-flight

- [ ] Stage 1.1 DONE.
- [ ] **Proteus PG-dialect audited** (T1) — the gating upstream item.
- [ ] Branch `feat/p1-s1.2-arges-execution` from `main`.

## Tasks

- [ ] **T1 — Proteus PostgreSQL unparse audit + close.** Verify `Proteus.UnparseFromRelNode(plan, SQL, POSTGRESQL)` yields valid PG for the five v1 Midas queries (`positions_current`, `transactions_recent`, `dividends_period`, `fees_period`, `realised_pnl_period`): CTEs, window functions, `numeric`, date filters, `uuid` predicates. **Tests first:** golden SQL per query. Close gaps (likely enabling Calcite `PostgresqlSqlDialect` on the unparse path). *If the gap is large, split into a Proteus sibling task and depend on its output.*
- [ ] **T2 — `PostgresArrowTypeMapper` tests first.** `PostgresArrowTypeMapperSpec` over contracts §3. Must-pass: `int2/4/8`, `numeric(20,4)`→`Decimal(20,4,128)` (boundary value), `varchar/text`, `date`, `timestamptz`→`Timestamp(NANO,"UTC")`+`pg.original_type`, `uuid`→`VARCHAR`+meta, `bool`→`BIT`. Defensive: `bytea`→VARBINARY, `jsonb`→VARCHAR+meta, ranges/arrays→VARBINARY+`unsupported_type_as_binary`.
- [ ] **T3 — `PostgresArrowTypeMapper` impl.** By native type name + JDBC-code fallback + `unsupportedBinaryFallbacks` (mirror `MssqlArrowTypeMapper`). Make the spec pass.
- [ ] **T4 — `ResultSetToArrow` + `ArrowIpcSerializer`.** Copy verbatim from Brontes (engine-agnostic); inject the PG type mapper; batching + schema fingerprint. Reuse Brontes's tests.
- [ ] **T5 — `ExecutePipeline` (no RLS) tests first + impl.** 7-step pipeline against `SqlDialect.POSTGRESQL`; error batches `connection_not_supported` / `translator_failed` / `worker_execution_failed`; options clamp; cancellation via `Statement.cancel()`. Unit tests with a fake translator + fake `Connection`.
- [ ] **T6 — `TranslatorClient` + `TranslatorHealth`.** Copy from Brontes; target dialect `POSTGRESQL`; feed dep-health into `GetStatus`.

## DONE

- [ ] Unit suite green; type mapper passes the v1 must-pass set (incl. the `numeric(20,4)` boundary).
- [ ] A fixture plan executes against a fake/embedded PG path and returns the expected Arrow schema + rows.
- [ ] Proteus emits valid PostgreSQL for the five v1 queries (golden SQL tests green).
