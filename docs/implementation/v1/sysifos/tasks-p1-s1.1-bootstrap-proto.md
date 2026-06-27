# Stage 1.1 — Arc bootstrap + `sysifos/v1` proto relocation

> **Phase 1, Stage 1.1.**
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3 (Stage 1.1), [`../../../architecture/sysifos/architecture.md`](../../../architecture/sysifos/architecture.md) §3 (module map), [`../../../architecture/sysifos/contracts.md`](../../../architecture/sysifos/contracts.md) §1 (`sysifos/v1`).

## Goal

The two Sysifos modules exist and compile on stubs; `sysifos/v1` lives in its new home and generates Kotlin + TS bindings (including the four Sysifos-arc additions); CI knows both modules.

## Pre-flight

- [x] **Midas arc P1 S1.2 DONE** — `midas/v1` exists in `shared/proto` (imported by `sysifos/v1`). *(Met — whole Midas Phase 1 done 2026-06-21.)*
- [x] **Branch**: `feat/sysifos-p1-p2-workbench` from `main` (whole-arc branch — P1+P2 ship in one PR per Bora, 2026-06-23).

## Tasks

- [x] **T1 — Module skeletons.** Create:
  - `agents/sysifos-bff/` — `build.gradle.kts`, `src/main/kotlin/org/tatrman/kantheon/sysifos/bff/App.kt` (empty `main` + `module`), `src/main/resources/application.conf`, `src/test/kotlin/`, `k8s/{base,overlays/local}/` placeholders.
  - `frontends/sysifos/` — `package.json`, `vite.config.ts`, `src/main.ts`, `src/App.vue`, `public/`.

  Acceptance: directories present; `agents/sysifos-bff` compiles empty.

- [x] **T2 — Relocate `sysifos/v1`.** Create `shared/proto/src/main/proto/org/tatrman/kantheon/sysifos/v1/sysifos.proto` from [`contracts.md`](../../../architecture/sysifos/contracts.md) §1 **byte-for-byte**. Confirm the Sysifos-arc additions vs the old Midas listing:
  - `DraftKind` += `DRAFT_TRANSACTION_BATCH = 6`, `DRAFT_ASSET = 7`.
  - `DraftStatus` += `DRAFT_COMMITTING = 4`.
  - `SysifosStreamEvent` += `BatchRowResult batch_row_result = 8`, `SessionHeartbeat heartbeat = 9`.
  - `FieldValidationError.row_index = 4`.
  - new messages `AssetForm`, `TransactionBatchForm`, `ReconciliationDecisionForm`, `BatchRowResult`, `SessionHeartbeat`, `BatchRowOutcome`.
  - `PortfolioForm.track_cash = 6`.

  Imports: `common/v1/response_message.proto`, `envelope/v1/envelope.proto`, `midas/v1/midas.proto`.

  Acceptance: `just proto` emits Kotlin under `shared/proto/build/generated/.../sysifos/v1/` and the TS package.

- [x] **T3 — Remove `sysifos/v1` from the Midas arc listing.** In `docs/architecture/midas/contracts.md` §1.2, replace the inline proto with a one-line pointer to `../sysifos/contracts.md` §1 (the relocation note). _This is a doc-only edit; the proto file itself is the single physical source._ (Coordinated with the integration task in the design session.)

  Acceptance: no duplicated `sysifos.proto` body across the two contracts docs.

- [x] **T4 — `libs.versions.toml` + FE deps.** Confirm Ktor/Kotlin/coroutines/serialization versions match the rest of kantheon (no DB libs needed). In `frontends/sysifos/package.json` add: `vue@^3`, `primevue@^4`, `@primevue/themes`, `@tanstack/vue-query`, `pinia`, `vue-router@^4`, `zod`, `vite`, `typescript`, `vitest`, `@vue/test-utils`, `msw`.

  Acceptance: `npm install` resolves in `frontends/sysifos`.

- [x] **T5 — Settings + root build + justfile + CI.**
  - `settings.gradle.kts`: `include(":agents:sysifos-bff")`.
  - Root `build.gradle.kts`: apply the kotlin-ktor convention to `sysifos-bff`; exclude the FE from Kotlin.
  - `justfile`: `sysifos-dev` (Vite dev w/ proxy), `build-fe sysifos`, `build-kt sysifos-bff`, `deploy-kt sysifos-bff`, `test-kt sysifos-bff`.
  - `.github/workflows/ci.yml`: both modules picked up by auto-detect (no hardcoded list).

  Acceptance: `just build-kt sysifos-bff` green; CI green on the branch.

- [x] **T6 — Proto-shape test.** `shared/proto/src/test/kotlin/.../SysifosProtoSpec.kt`:
  ```kotlin
  class SysifosProtoSpec : StringSpec({
      "PortfolioForm.track_cash defaults true via builder convention" {
          val f = PortfolioForm.newBuilder().setName("X").build()
          // proto3 bool default is false; assert the BFF default-application instead in 1.2.
          f.name shouldBe "X"
      }
      "TransactionBatchForm carries rows + skip_existing" {
          val b = TransactionBatchForm.newBuilder()
              .setPortfolioId("p").addRows(TransactionForm.getDefaultInstance()).setSkipExisting(true).build()
          b.rowsCount shouldBe 1; b.skipExisting shouldBe true
      }
      "SysifosStreamEvent round-trips a BatchRowResult" {
          val e = SysifosStreamEvent.newBuilder().setBatchRowResult(
              BatchRowResult.newBuilder().setRowIndex(3).setOutcome(BatchRowOutcome.BR_COMMITTED).build()).build()
          SysifosStreamEvent.parseFrom(e.toByteArray()).batchRowResult.rowIndex shouldBe 3
      }
  })
  ```
  _Note: proto3 has no field defaults; `track_cash` default-true is applied in BFF mapping (Stage 2.1) and the Zod schema, not the proto. The contract documents intent._

  Acceptance: `just test-kt shared:proto` green; `just proto` idempotent.

## DONE — Stage 1.1

- [x] All six tasks checked.
- [x] `just build-kt sysifos-bff` + `just proto` green; CI green.
- [ ] Tag `sysifos/bootstrap-v0.1.0`. _(Deferred to post-merge — the whole arc ships in one PR; tags are cut off `main` after merge, per the Arges pattern.)_

## Library / pattern references

- EXAMPLES.md §1b — `App.kt` skeleton shape.
- EXAMPLES.md §3 — proto wire rules (`messages = 99;`, argsJson).
- Themis `tasks-p1-s1.1-repo-bootstrap.md` — the analogous bootstrap stage.

## Out of scope

- BFF behaviour (Stage 1.2). FE screens (Phase 2). Any Midas-arc code change (the cash-leg amendment is a separate Midas-arc task).
