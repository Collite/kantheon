# Sysifos — Phase 2 (Data entry) — task-list overview

> Entry point for Phase 2 executors. Per `planning-conventions.md` §4.
>
> **Reads with.** [`plan.md`](./plan.md) §4, [`../../../architecture/sysifos/architecture.md`](../../../architecture/sysifos/architecture.md) §8 (screens), [`../../../architecture/sysifos/contracts.md`](../../../architecture/sysifos/contracts.md).

## Phase 2 goal

Every v1 screen end-to-end against Midas-core: Clients, Portfolios (`track_cash`), Assets + quick-create, Transactions (form + inline edit + cash sub-rows), bulk grid, Balance entry, Import (upload→preview→correct→commit), Reconcile, Loader status, Audit. Sysifos operationally complete.

## Stage map

| Stage | Title | File | DONE |
|---|---|---|---|
| 2.1 | Clients + Portfolios screens | [`tasks-p2-s2.1-clients-portfolios.md`](./tasks-p2-s2.1-clients-portfolios.md) | ☐ |
| 2.2 | Assets + quick-create + Transactions | [`tasks-p2-s2.2-assets-transactions.md`](./tasks-p2-s2.2-assets-transactions.md) | ☐ |
| 2.3 | Bulk grid | [`tasks-p2-s2.3-bulk-grid.md`](./tasks-p2-s2.3-bulk-grid.md) | ☐ |
| 2.4 | Balance entry | [`tasks-p2-s2.4-balance-entry.md`](./tasks-p2-s2.4-balance-entry.md) | ☐ |
| 2.5 | Statement import | [`tasks-p2-s2.5-import.md`](./tasks-p2-s2.5-import.md) | ☐ |
| 2.6 | Reconcile + Loader status + Audit | [`tasks-p2-s2.6-reconcile-audit.md`](./tasks-p2-s2.6-reconcile-audit.md) | ☐ |

## Hard gate inside Phase 2

**Midas-core's derived-cash-leg behaviour** (baseline in the Midas arc: `TX_CASH_*`, `track_cash`, `CashLegDerivation`, cash-asset provisioning — built in Midas Stage 1.3) must be live before **Stage 2.2** (cash sub-rows) and **Stage 2.3** (batch derives cash). If Midas-core slips: run 2.1 → 2.4 → 2.5 (minus cash rendering) → 2.2 → 2.3 → 2.6.

## Phase 2 DONE

- [ ] All six stage DONE criteria checked.
- [ ] Demo: fresh tenant → clients → portfolios → single + bulk entry (with cash legs) → import with inline correction → balance entry → reconcile → audit.
- [ ] Tag `sysifos-arc/phase-2-data-entry-v1`.
