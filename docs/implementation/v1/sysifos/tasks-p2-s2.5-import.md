# Stage 2.5 — Statement import end-to-end

> **Phase 2, Stage 2.5.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4 (Stage 2.5), [`../../../architecture/sysifos/contracts.md`](../../../architecture/sysifos/contracts.md) §3.5 (loader proxy) + §3.4 (`/screens/import`), [`../midas/contracts.md`](../../../architecture/midas/contracts.md) §4.1 (Excel loader), [`../../../design/sysifos/sysifos-design.md`](../../../design/sysifos/sysifos-design.md) §6.

## Goal

The Excel loader's upload→preview→commit becomes a real Sysifos flow with **inline correction** of ERROR rows and quick-create for unknown symbols. Idempotent re-uploads.

## Pre-flight

- [ ] **Stage 2.2 DONE** (quick-create modal reused).
- [ ] **Excel loader live** (Midas P1 S1.5) with ≥2 fixture broker templates.
- [ ] **Branch**: `feat/p2-s2.5-import`.

## Tasks

- [ ] **T1 — Upload tests first.** File picker → broker selector (from `/dictionaries/brokers`) → portfolio selector → upload → SSE `LoaderProgress` drives a bar → `LoaderPreviewReady` navigates to preview.

- [ ] **T2 — Upload screen.** `views/Import.vue` + PrimeVue FileUpload (chunked for large XLSX); proxy `POST /loaders/excel/uploads` (multipart: file, broker_id, portfolio_id) → `loader_run_id`; subscribe `useSysifosStream` for `LoaderProgress`; navigate to `ImportPreview` keyed by `loader_run_id`.

- [ ] **T3 — Preview tests first.** Fixture preview with N new + M duplicate + K error rows; grouped by decision (NEW/DUPLICATE/ERROR); per-row include/exclude checkbox; diff note rendered ("duplicate of {existing_id}").

- [ ] **T4 — Preview view.** `views/ImportPreview.vue` via `/screens/import/{loader_run_id}` fan-out (loader run + preview rows + diff). DataTable of `PreviewRow`s: source row #, decision badge, proposed-transaction summary, note.

- [ ] **T5 — Inline correction.** ERROR rows are fixable: unknown symbol → `AssetQuickCreate.vue` (re-runs that row's mapping on resolve); bad date/missing field → editable cell with Zod; per-row exclude. This is the Sysifos-specific value-add over a raw loader. Tests: correcting an unknown symbol flips the row NEW.

- [ ] **T6 — Commit flow.** "Commit" → `POST /drafts` (`DRAFT_LOADER_RUN_COMMIT`) → BFF dispatches to the loader's `POST /runs/{id}/commit` (skip_existing) → loader batches to Midas-core → `DraftCommitted {committed, skipped}`. Success → navigate to Transactions filtered to imported rows; banner "12 new, 340 already imported".

- [x] **T7a — History tab (in-arc).** Past runs per portfolio (loader `/runs`); click → read-only preview. _(Built: `views/Import.vue` past-runs table + `views/ImportPreview.vue`; also surfaced on `views/Loaders.vue` in S2.6.)_
- [→] **T7b — Deploy + smoke → MOVED to Testing Stage 3.4.** The cluster-tier smoke (both fixture brokers end-to-end; re-upload the same file → all DUPLICATE, commit skips zero new, idempotent on `external_id`) is owned by the Testing arc — see [`../testing/tasks-p3-s3.4-sysifos-deploy-smoke.md`](../testing/tasks-p3-s3.4-sysifos-deploy-smoke.md) (T5 "Statement import" leg).

## DONE — Stage 2.5

- [x] T1–T6 + T7a done; Vitest green (the cluster smoke, T7b, **moved to Testing Stage 3.4**).
- [x] Full import path works for both fixture brokers; ERROR rows correctable inline; re-runs idempotent; cash legs derived on commit (via Midas-core). _(End-to-end against both brokers verified in Testing 3.4.)_

## Library / pattern references

- PrimeVue FileUpload (chunked) — context7.
- Excel loader lifecycle — `../midas/contracts.md` §4.1 (`/uploads`, `/runs/{id}/preview`, `/runs/{id}/commit`).
- Draft/SSE machinery — Phase 1 Stage 1.3.

## Out of scope

- Interactive column-mapping for non-template Excel (S4 → v1.x). Non-Excel loaders (loader-status only, 2.6). Reconcile (2.6).
