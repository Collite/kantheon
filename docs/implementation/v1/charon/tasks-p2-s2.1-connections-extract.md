# Charon P2 Stage 2.1 — connection registry + ADBC spike gate + extract

> **Phase 2, Stage 2.1.** Opens the database-edge phase.
>
> **Reads with.** [`plan.md`](./plan.md) §4 Stage 2.1, [`../../../architecture/charon/architecture.md`](../../../architecture/charon/architecture.md), [`../../../architecture/charon/contracts.md`](../../../architecture/charon/contracts.md) §1 (`DbTable`, `MoveOptions.db_write_mode`), §2 (legality matrix), §4 (connection-registry schema), §5 (type mapping), [`../../planning-conventions.md`](../../planning-conventions.md) §4.
>
> **Why Pythia cares.** Pythia Phase 4.1 reads ERP/analytics tables as investigation sources — the `DbTableRef` → `DbTable` mapping ([pythia contracts §6/§7](../../../architecture/pythia/contracts.md)). This stage delivers the **DB read (extract) path + `Describe(db_table)`** that Pythia's materialisation policy and PD-5 liveness probe depend on.

## Goal

A named-connection registry (no inline credentials); the `AdbcReader` extracting DB tables to Arrow streams (PG + MSSQL) behind a driver-agnostic interface; `Describe(db_table)`; `Materialize`/`Copy` with `db_table` sources wired (db→seaweed, db→redis). **End state:** extract path component-green on both dialects against a mocked ADBC driver; spike verdict documented. (Real-driver dialect fidelity against live PG/MSSQL → integration suite, §4.)

## Pre-flight

- [x] **Phase 1 closed** — `charon/v0.1.0` re-applied after the review-006 R1–R6 closeout + `tasks-p1-s1.4-integration.md` (the multi-batch move pipe must be correct before DB edges build on `ArrowPipe`). Verify: `git tag | grep charon` shows `charon/v0.1.0` (or `v0.1.1`).
- [x] Arrow Java / ADBC / driver versions pinned in `gradle/libs.versions.toml` (Stage 1.1 T1).
- [x] Branch `feat/charon-p2-s2.1-connections-extract`.
- [x] Read contracts §4 (registry YAML), §5 (Arrow→DDL matrix — the extract direction maps inversely), §1 (`DbTable`, `DescribeResult.row_count` / `row_count_exact`).

## Tasks (TDD-shaped: T1, T3, T4 are tests-first)

- [x] **T1 — Tests first: `ConnectionRegistrySpec`.**

  Cover (contracts §4): YAML parse + `${ENV}` substitution; unknown `connection_id` → `INVALID_ARGUMENT`; allow-list enforcement (read / write / schemas — exact list, glob-free); pool config parse; `POST /refresh` reload swaps the live set. **Pythia's internal PG is never a listed connection** — assert a config that tries to list it is rejected (or simply that no such id resolves).

  Acceptance: spec compiles + fails.

- [x] **T2 — `ConnectionRegistry` implementation.**

  Implement `core/ConnectionRegistry.kt`: load `/etc/charon/connections.yaml`, env-substitute secrets (never inline; never logged), expose `resolve(connection_id) → ConnectionHandle` with the allow-list attached; `/refresh` reload (atomic swap). Credentials live only in memory; `DbTable` requests carry **only the id** (contracts §1).

  Acceptance: `ConnectionRegistrySpec` green.

- [x] **T3 — ADBC spike (gate): `AdbcReader/Writer` behind one interface, mocked driver.**

  Unit-test both impls behind a common `AdbcReader`/`AdbcWriter` interface with a **mocked ADBC-JDBC driver**: bulk extract to Arrow; type-mapping coverage on the contracts §5 matrix at the driver boundary. **Record the verdict in the module README:** ADBC-per-dialect vs `arrow-jdbc` fallback per dialect — both live behind the same interface either way. This is the Phase-2 gate (Bora-confirmable; fallback locked, plan §8).

  Acceptance: spike specs green for both candidate paths; verdict written to `services/charon/README.md`.

- [x] **T4 — Tests first: `AdbcReaderSpec`.**

  Reference tables exercising the full §5 type matrix (all mapped Arrow types, NULLs, empty table, wide rows) extract to the expected Arrow schemas **+ fingerprints** (the canonical, cross-engine fingerprint from Stage 1.2 — the DB-extracted schema must fingerprint identically to the same logical schema from any engine, so Pythia's PD-5 drift check is meaningful). Unmapped driver-specific type → `FAILED_PRECONDITION` naming the column.

  Acceptance: spec compiles + fails.

- [x] **T5 — `AdbcReader` (DB → Arrow stream).**

  Implement per the spike verdict: **streaming, not full materialisation** (bounded memory, feeds `ArrowPipe`). Chunk-aligned with `charon.move.chunk-rows`. Reads map inversely on the §5 matrix.

  Acceptance: `AdbcReaderSpec` green.

- [x] **T6 — `Describe(db_table)`.**

  Implement `Describe` for `DbTable`: schema from the catalog → `schema_json` + `schema_fingerprint`; `row_count` estimate with `row_count_exact = false` (or exact when cheap), `size_bytes = -1`. **This is the Pythia PD-5 liveness probe for DB-backed handles** — `exists` reflects table presence under the read-allowed connection.

  Test: `Describe` on a present table returns schema + fingerprint + a row-count flag; on an absent table `exists = false`; on a non-allow-listed schema → `INVALID_ARGUMENT`.

  Acceptance: `Describe(db_table)` spec green.

- [x] **T7 — Wire `Materialize`/`Copy` with `db_table` sources.**

  Wire db→seaweed and db→redis through the executor (legality matrix §2: db_table source → seaweed/redis = Materialize/Copy). Result carries fingerprint + row/byte counts. **db→seaweed is Pythia's evidence-persist-from-DB path** (e.g. ERP-replica table → `pythia-evidence` blob) — assert the `retention_tag` flows to the Seaweed object (Stage 1.2 T4 tagging).

  Test (component, mocked driver + mocked S3/Redis): db→seaweed and db→redis produce verified blobs; an illegal pairing is rejected pre-I/O.

  Acceptance: db-source component suite green; `just test-kt services:charon` green.

## DONE — Stage 2.1

- [x] All tasks checked; extract path green on PG + MSSQL (mocked driver); spike verdict in README.
- [x] `Describe(db_table)` + db→seaweed/redis wired (Pythia DB-read + evidence-from-DB paths live).
- [x] Integration carry-overs recorded (live PG/MSSQL driver fidelity, real catalog row counts, real ADBC streaming).
- [x] CI green on `[charon-p2-s2.1] connections + extract`.

## Library / pattern references

- **contracts §4** (registry), **§5** (type matrix), **§1** (`DbTable`, `DescribeResult`), **§2** (legality matrix).
- **ADBC / arrow-jdbc** — the spike (T3) picks per dialect; both behind `AdbcReader`/`AdbcWriter`.
- **Stage 1.2 `Integrity.kt`** — the canonical fingerprint applied to DB-extracted Arrow (cross-engine identity).

## Out of scope

- Arrow→DB **ingest** (write) — Stage 2.2.
- Provisioned connection content + live deploy — Stage 2.3.
- Worker / MCP wrapper — Phase 3.
- Predicate pushdown on extract — v1.x (plan §7); table-level reads only (security: no query-path bypass).
