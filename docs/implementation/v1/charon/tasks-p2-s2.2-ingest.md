# Charon P2 Stage 2.2 — ingest (Arrow → DB)

> **Phase 2, Stage 2.2.** Completes the DB edge — the write direction + the full legality matrix.
>
> **Reads with.** [`plan.md`](./plan.md) §4 Stage 2.2, [`../../../architecture/charon/contracts.md`](../../../architecture/charon/contracts.md) §1 (`DbWriteMode`), §2 (legality matrix), §5 (Arrow→DDL mapping), [`../../planning-conventions.md`](../../planning-conventions.md) §4.
>
> **Why Pythia cares.** Pythia 4.1's primary DB need is **read** (Stage 2.1). Ingest matters for **legality-matrix completeness** (so Charon rejects illegal pairings cleanly — Pythia relies on those errors) and for the `analytics-staging` write-back path a future Pythia step may use. The **security sign-off** here (table-level only, no query bypass) is the invariant Pythia's data plane depends on.

## Goal

`AdbcWriter` ingesting Arrow → DB (PG + MSSQL) with CREATE/REPLACE/APPEND semantics, allow-list enforcement, deterministic type mapping, and no-partial-write transactionality; the full legality matrix complete; `Evict(db_table)` rejected; the security note signed off. **End state:** ingest component-green both dialects (mocked driver); the full §2 matrix suite green.

## Pre-flight

- [x] Stage 2.1 DONE — registry + extract + spike verdict.
- [x] Branch `feat/charon-p2-s2.2-ingest`.
- [x] Read contracts §1 (`DbWriteMode` CREATE/REPLACE/APPEND), §5 (Arrow→DDL — the write direction is authoritative here), §2 (the cells this stage completes: *→db_table).

## Tasks (TDD-shaped: T1, T2 tests-first)

- [x] **T1 — Tests first: `AdbcWriterSpec`.**

  Cover (contracts §1 write modes): **CREATE** (fail-if-exists honoured → `FAILED_PRECONDITION`), **REPLACE** (transactional swap — a concurrent reader sees old-or-new, never partial), **APPEND** (schema-compat check; incompatible → `FAILED_PRECONDITION` naming the mismatch), unmappable Arrow type → `FAILED_PRECONDITION` naming the column, mid-stream fault → clean rollback (no partial table). All against the mocked ADBC driver.

  Acceptance: spec compiles + fails.

- [x] **T2 — Tests first: Arrow→DDL type mapping (property tests).**

  Property tests on round-trip schema identity: `extract(ingest(x))` is schema-equal for the full §5 matrix, both dialects. Decimal precision/scale, timestamp tz, and the `List/Struct/Map → FAILED_PRECONDITION` rejections all pinned.

  Acceptance: spec compiles + fails.

- [x] **T3 — `AdbcWriter` (Arrow → DB).**

  Implement per the spike verdict: bulk ingest in a **single transaction** (the REPLACE swap + APPEND are atomic; rollback on any error). Deterministic DDL generation from the Arrow schema (§5). Feeds from `ArrowPipe` (streaming).

  Acceptance: `AdbcWriterSpec` + the mapping property tests green.

- [x] **T4 — Write allow-list enforcement.**

  Integrate the registry allow-list (Stage 2.1): a write to a `allow.write: false` connection or a schema outside the list is rejected **`INVALID_ARGUMENT` before any DB I/O is attempted** (never a half-attempt). 

  Test: write to a read-only connection rejected pre-I/O; write to an out-of-list schema rejected pre-I/O.

  Acceptance: allow-list write spec green.

- [x] **T5 — Wire `Materialize`/`Copy` with `db_table` targets — full matrix.**

  Wire seaweed→db, redis→db, and db→db cross-connection (legality matrix §2). With this the **full legality matrix is complete** — add a table-driven component suite that exercises every legal cell and asserts every illegal cell is `INVALID_ARGUMENT`. (This matrix completeness is what lets Pythia trust Charon's pairing errors.)

  Acceptance: full-matrix component suite green.

- [x] **T6 — `Evict(db_table)` rejection + security sign-off.**

  `Evict` on a `db_table` location → `INVALID_ARGUMENT` (DB cleanup is the owner's job — contracts §1). Conduct + record the **security-note review**: Charon DB access is **table-level only via named connections** (no predicate/user-scoped access — that stays on the query path / theseus-mcp); Pythia's internal PG is never a connection; credentials never travel in requests. Sign-off documented in `services/charon/README.md` (this is the invariant pythia/architecture §10 risk row "Charon DB write/read becomes a query-path bypass" closes against).

  Acceptance: `Evict(db_table)` rejection spec green; security sign-off recorded.

## DONE — Stage 2.2

- [x] All tasks checked; ingest green both dialects (mocked); full legality matrix suite green.
- [x] Security sign-off recorded (table-level only, no query bypass).
- [x] Integration carry-overs recorded (live transactional swap, real rollback under fault, real cross-connection db→db).
- [x] CI green on `[charon-p2-s2.2] ingest`.

## Library / pattern references

- **contracts §1** (`DbWriteMode`), **§5** (Arrow→DDL — write direction), **§2** (legality matrix — completed here).
- **Stage 2.1** `AdbcWriter` interface + spike verdict; `ConnectionRegistry` allow-lists.

## Out of scope

- Provisioned connection content + live deploy — Stage 2.3.
- Worker endpoint / MCP — Phase 3.
- List/Struct/Map over DB edges — v1.x (plan §7).
