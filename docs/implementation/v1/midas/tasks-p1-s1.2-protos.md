# Stage 1.2 — Proto packages (`midas/v1` + `report/v1`)

> **Phase 1, Stage 1.2.**
>
> **Reads with.** [`plan.md`](./plan.md) §3 (Stage 1.2 + the **Revision 2026-06-21** banner — decision #4: Midas ships `midas/v1` + `report/v1`; `sysifos/v1` is the Sysifos arc), [`../../../architecture/midas/contracts.md`](../../../architecture/midas/contracts.md) §1.1 / §1.1.A / §1.3 / §4.1.

## Goal

`midas/v1` + `report/v1` compile to Kotlin; `just proto` is idempotent; an enum↔DDL-string drift test guards the §6.1 mapping. No consumer yet. **`sysifos/v1` is NOT written here** (Sysifos arc).

## Pre-flight

- [x] Stage 1.1 DONE (modules + build green).
- [x] Branch `feat/midas-p1-s1.1-bootstrap` (continued; protos land on the same Phase-1 branch).

## Tasks

- [x] **T1 — Write `midas.proto`.** `shared/proto/src/main/proto/org/tatrman/kantheon/midas/v1/midas.proto` per contracts §1.1 + §1.1.A (cash-leg baseline) + §4.1 (loader types fold into the package). Entities, REST request/response wrappers, reconciliation, the five MCP tool I/O types, loader types. Root `org.tatrman.kantheon.midas.v1`; `java_multiple_files` + `java_package` (golem precedent). Added `ListLoaderRunsResponse` (referenced by §4.1 GET /runs but undefined in the doc) for completeness.

  Acceptance: file compiles via `just proto`; 127 generated classes.

- [x] **T2 — Write `report.proto`.** `…/report/v1/report.proto` per contracts §1.3: `ReportTemplate`, `ReportFormat`/`OutputFormat`, `ParamDef`/`ParamKind`, `RenderReportRequest` (Rule-7 `args_json`), `RenderReportResponse`, `ListTemplatesResponse`.

  Acceptance: compiles via `just proto`; 14 generated classes.

- [x] **T3 — Codegen (automatic).** No `shared/proto/build.gradle.kts` change — codegen is registered globally (`generateProtoTasks { all() }`) for Kotlin + Python + gRPC. Confirm `just proto` regenerates cleanly and is idempotent (second run UP-TO-DATE).

  Acceptance: `just proto` green + idempotent; Kotlin classes under `shared/proto/build/generated/.../{midas,report}/v1/`.

- [x] **T4 — Proto-shape unit test.** `shared/proto/src/test/kotlin/.../midas/v1/MidasEnumDdlMappingSpec.kt` — asserts each enum, prefix stripped, equals the §6.1 DDL CHECK set (`CLIENT_ACTIVE`→`ACTIVE`, `TX_CASH_DEBIT`→`CASH_DEBIT`, …). Locks the proto ↔ DDL ↔ Exposed mapping ahead of V0001 (Stage 1.3).

  Acceptance: `just test-kt shared/proto` green; ktlint clean.

- [x] **T5 — TS package: deferred.** No Phase-1 TS consumer (Sysifos FE is the Sysifos arc, which owns `sysifos/v1`). A Midas-domain TS package is emitted when Phase 3 needs it, per the `shared/libs/ts/envelope-ts` buf pattern.

  Acceptance: n/a (documented deferral).

## DONE — Stage 1.2

- [x] `midas/v1` + `report/v1` compile to Kotlin; `just proto` idempotent.
- [x] Enum↔DDL drift test green; ktlint clean.
- [ ] Tag `shared-proto/midas-v0.1.0` (at Phase-1 close, with the rest of the arc).
