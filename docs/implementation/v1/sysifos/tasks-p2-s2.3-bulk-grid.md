# Stage 2.3 — Bulk grid (S3)

> **T1 grid-lib decision (2026-06-24): PrimeVue editable DataTable — no new dep.**
> The FE already ships PrimeVue 4; its `DataTable` with `editMode="cell"` + cell
> editor slots covers the four needs (clipboard paste, tab/enter nav, per-cell
> error styling, virtual scroll) without pulling in ag-grid/revo-grid (~hundreds of
> KB + a second styling system). Clipboard paste of a multi-row/multi-col TSV block
> is handled with a plain `@paste` listener on the grid that splits the block and
> fills rows (header-row detection → field map, positional fallback) — the one
> capability DataTable doesn't give for free, and cheap to own. Cell nav is native.
> Per-cell Zod runs in `validation/transaction.ts`. Revisit only if a >5k-row paste
> shows perf problems the virtual scroller can't absorb (deferred to v1.x).

> **Phase 2, Stage 2.3.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4 (Stage 2.3), [`../../../architecture/sysifos/architecture.md`](../../../architecture/sysifos/architecture.md) §6 (async path), [`../../../architecture/sysifos/contracts.md`](../../../architecture/sysifos/contracts.md) §1 (`TransactionBatchForm`, `BatchRowResult`) + §3.2 (drafts), [`../midas/contracts.md`](../../../architecture/midas/contracts.md) §2.4 (`POST /transactions:batch`).

## Goal

Spreadsheet-style bulk entry: paste a block of trades, tab-through, per-cell validation, async batch commit with per-row results streamed back.

## Pre-flight

- [ ] **Stage 2.2 DONE** (quick-create modal reused here).
- [ ] **Midas-core derives cash legs** (baseline; batch insert derives cash legs too).
- [ ] **Branch**: `feat/p2-s2.3-bulk-grid`.

## Tasks

- [ ] **T1 — Grid lib decision (spike).** Evaluate PrimeVue editable DataTable vs a dedicated grid (e.g. revo-grid / ag-grid-community) for: clipboard paste of a multi-row/multi-col block, tab/enter cell navigation, per-cell error styling, virtual scroll. Record the call + rationale at the top of this file.

  Acceptance: lib chosen; spike branch shows paste + tab-through working.

- [ ] **T2 — Grid component tests first (Vitest).** Paste a 20-row TSV block → 20 rows populated; header-row detection maps columns to `TransactionForm` fields (fallback positional); per-cell Zod validation flags an invalid quantity/date live; add/remove row.

- [ ] **T3 — Bulk grid component.** `components/grids/BulkEntryGrid.vue` — portfolio-scoped (one portfolio per grid session); paste handler; keyboard nav; per-cell Zod (`validation/transaction.ts`); a totals/row-count footer; "Commit N rows" button (disabled while any cell is invalid).

- [ ] **T4 — Unknown-symbol path.** A cell with a symbol not in the dictionary marks the cell + offers quick-create; opening `AssetQuickCreate.vue` does not lose grid state; multiple unknown symbols queue into one modal pass; on resolve, cells update with the new `asset_id`.

- [ ] **T5 — Async commit tests first.** Submit grid → BFF `POST /drafts` (`DRAFT_TRANSACTION_BATCH`) → 202 + `draft_id` → `DraftAck` → per-row `BatchRowResult` over `/stream` → `DraftCommitted {committed_count, skipped_count}`. A row that fails midas-core validation streams `BR_FAILED` and stays editable; others commit.

- [ ] **T6 — Async commit (BFF + FE).** BFF: `DraftStateMachine` handles `DRAFT_TRANSACTION_BATCH` → maps `TransactionBatchForm` → Midas-core `POST /transactions:batch` (skip_existing) → streams `BatchRowResult` per row → `DraftCommitted`. FE: subscribe via `useSysifosStream`; render per-row status pills (committed/skipped/failed); keep failed rows editable for retry.

- [→] **T7 — Deploy + smoke → MOVED to Testing Stage 3.4.** The cluster-tier smoke (paste a real ~50-row block → commit → watch per-row pills resolve → confirm rows + derived cash sub-rows in Transactions; re-submit identical block → all skipped, idempotent on `external_id`; manual rows without `external_id` insert again — document the behaviour) is owned by the Testing arc — see [`../testing/tasks-p3-s3.4-sysifos-deploy-smoke.md`](../testing/tasks-p3-s3.4-sysifos-deploy-smoke.md) (T5 "Bulk grid" leg).

## DONE — Stage 2.3

- [x] T1–T6 done; Vitest green (the cluster smoke, T7, **moved to Testing Stage 3.4**).
- [x] A block commits via the async draft path; per-row outcomes stream back; failures correctable in place; cash legs derived for the batch.

## Library / pattern references

- Chosen grid lib docs (context7 for current API).
- Draft/SSE machinery from Phase 1 Stage 1.3 (reused).
- Midas-core `POST /transactions:batch` semantics — `../midas/contracts.md` §2.4.

## Out of scope

- Import (2.5) — that reuses the draft path for `DRAFT_LOADER_RUN_COMMIT` but is a different flow. CSV/Excel *file* import is 2.5, not the grid (grid is clipboard paste).
