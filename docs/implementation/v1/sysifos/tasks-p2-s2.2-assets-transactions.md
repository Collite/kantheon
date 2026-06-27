# Stage 2.2 — Assets + quick-create modal + Transactions screen

> **Phase 2, Stage 2.2.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4 (Stage 2.2), [`../../../architecture/sysifos/contracts.md`](../../../architecture/sysifos/contracts.md) §3.3–3.4, [`../midas/contracts.md`](../../../architecture/midas/contracts.md) §2.3–2.4, [`../../../design/sysifos/sysifos-design.md`](../../../design/sysifos/sysifos-design.md) §4.2–4.3 + §5.4 (cash legs, quick-create).

## Goal

Asset master (read all, write `midas:admin`) + the S6 quick-create modal; Transactions list with single manual entry, inline edit (reverse+replace), and **derived cash sub-rows**.

## Pre-flight

- [ ] **Stage 2.1 DONE.**
- [ ] **Midas-core derives cash legs** (baseline, built in Midas Stage 1.3: `TX_CASH_*`, `track_cash`, `CashLegDerivation`, cash-asset provisioning). _Hard gate — cash sub-rows (T5/T6) cannot land without it._
- [ ] **Branch**: `feat/p2-s2.2-assets-transactions`.

## Tasks

- [ ] **T1 — Assets screen tests first.** List; filter by symbol/kind/exchange; non-admin sees read-only; `midas:admin` sees create/edit form.

- [ ] **T2 — Assets screen.** `views/Assets.vue` + `AssetForm` (admin). DataTable (server-side filter). Role gate from the Pinia session store.

- [ ] **T3 — Quick-create modal tests first.** From a transaction's asset typeahead, an unknown symbol shows a "Create asset" affordance → `AssetQuickCreate.vue` opens with the typed symbol prefilled → on submit the new asset returns to the originating field and entry resumes; the surrounding form/grid state is preserved. Multiple unknowns queue into one pass.

- [ ] **T4 — `AssetQuickCreate.vue`.** Modal with minimal `AssetForm` (symbol, name, kind, currency; ISIN/exchange optional), Zod-validated; writes via sync `POST /midas/assets`; emits the created asset to the caller. Reusable from the form, the bulk grid (2.3), and import preview (2.5).

- [ ] **T5 — Transactions list tests first.** List per portfolio via `/screens/transactions` fan-out; filter date/kind/asset; virtual scroll >1000 rows; **cash legs (`TX_CASH_*`, `source=DERIVATION`) render as linked sub-rows under their security leg, dimmed.**

- [ ] **T6 — Transactions list + cash sub-rows.** `views/Transactions.vue` + virtualized PrimeVue DataTable; date-range picker; quick filters ("this month", "YTD"); group derived cash legs by `correlation_id` under the security row.

- [ ] **T7 — Single manual entry + inline edit.** "Add transaction" → `TransactionForm` modal (sync write, **security leg only**); confirmation shows the security + derived cash legs returned by Midas-core. Pencil-edit → `PATCH /midas/transactions/{id}` → reversal + replacement; original row dimmed "Reversed", replacement appears; cash legs follow the reversal.

## DONE — Stage 2.2

- [ ] All seven tasks checked; Vitest green.
- [ ] Asset CRUD (admin); quick-create works mid-entry; single transaction CRUD + reversals correct; cash sub-rows render for `track_cash` portfolios.

## Library / pattern references

- PrimeVue DataTable virtual scroll + row grouping/expansion (context7).
- Midas-core transactions PATCH semantics — `../midas/contracts.md` §2.4 (reverse+replace invariants).
- Derived cash legs — `../midas/contracts.md` §1.1.A (baseline behaviour); architecture §10.

## Out of scope

- Bulk grid (2.3) — single entry only here. Balance entry (2.4). Import (2.5).
