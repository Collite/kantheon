# Stage 2.4 — Balance entry screen

> **Phase 2, Stage 2.4.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4 (Stage 2.4), [`../midas/contracts.md`](../../../architecture/midas/contracts.md) §2.5 (balance-entries preview/commit), [`../../../design/sysifos/sysifos-design.md`](../../../design/sysifos/sysifos-design.md) §5.3.

## Goal

"Set position X to Q quantity as of D" → preview the derived `ADJUSTMENT` with a plain-language explanation → commit (race-safe).

## Pre-flight

- [ ] **Stage 2.2 DONE** (Transactions list to view the resulting ADJUSTMENT).
- [ ] **Branch**: `feat/p2-s2.4-balance-entry`.

## Tasks

- [ ] **T1 — Preview tests first (Vitest + MSW).** Fixture portfolio with 100 AAPL: target 120 → preview shows `ADJUSTMENT +20`; target 80 → `−20`; target == current → friendly "already at target, nothing to commit".

- [ ] **T2 — Preview flow.** `views/BalanceEntry.vue`: portfolio typeahead → asset typeahead → target quantity → as-of date → **Preview** (sync `POST /midas/balance-entries:preview`). Render the proposed `Transaction` with an explanation line ("Current 100 → Target 120 → Adjustment +20 AAPL").

- [ ] **T3 — Commit flow.** A separate **Commit** button on the preview → `POST /midas/balance-entries:commit` (Midas-core re-runs the diff server-side for race safety, then inserts). Success → toast + link to the ADJUSTMENT in Transactions.

- [ ] **T4 — Error UX.** No-diff (target == current) → friendly inline message, Commit disabled; invalid portfolio/asset → field validation; stale as-of (future date) blocked by Zod.

- [ ] **T5 — History tab.** List prior balance entries for the selected portfolio/asset — filter Transactions on `kind=ADJUSTMENT` + `source=DERIVATION`; show as-of, diff, reason, who/when.

## DONE — Stage 2.4

- [ ] All five tasks checked; Vitest green.
- [ ] Balance entry round-trips; derived `ADJUSTMENT` visible in Transactions; history tab lists prior entries.

## Library / pattern references

- Midas-core balance-entries preview/commit — `../midas/contracts.md` §2.5 (preview is read-only; commit re-runs diff).
- `derivation/BalanceToTransaction` lives in Midas-core — Sysifos only renders.

## Out of scope

- The derivation logic itself (Midas-core). Import (2.5). Reconcile (2.6).
