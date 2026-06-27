# Sysifos — implementation (v1)

Phased plan + per-stage task lists for the Sysifos arc — the data-entry workbench for Midas. Own arc (S1, 2026-06-13); split out of the Midas arc.

## Files

- [`plan.md`](./plan.md) — 2 phases × 9 stages × ~60 tasks; pre-flight, dependencies on Midas-core + the cash-leg amendment, DONE criteria, risks, sequencing.
- **Phase 1 — Foundation:** [`tasks-p1-overview.md`](./tasks-p1-overview.md) → [`tasks-p1-s1.1-bootstrap-proto.md`](./tasks-p1-s1.1-bootstrap-proto.md), [`tasks-p1-s1.2-bff-skeleton.md`](./tasks-p1-s1.2-bff-skeleton.md), [`tasks-p1-s1.3-write-fe-shell.md`](./tasks-p1-s1.3-write-fe-shell.md).
- **Phase 2 — Data entry:** [`tasks-p2-overview.md`](./tasks-p2-overview.md) → [`tasks-p2-s2.1-clients-portfolios.md`](./tasks-p2-s2.1-clients-portfolios.md), [`tasks-p2-s2.2-assets-transactions.md`](./tasks-p2-s2.2-assets-transactions.md), [`tasks-p2-s2.3-bulk-grid.md`](./tasks-p2-s2.3-bulk-grid.md), [`tasks-p2-s2.4-balance-entry.md`](./tasks-p2-s2.4-balance-entry.md), [`tasks-p2-s2.5-import.md`](./tasks-p2-s2.5-import.md), [`tasks-p2-s2.6-reconcile-audit.md`](./tasks-p2-s2.6-reconcile-audit.md).

## Dependencies

Sysifos → Midas-core (write API, P1 S1.3), Excel loader (P1 S1.5), and the **cash-leg amendment** (gates Sysifos Stage 2.2/2.3). See [`../midas/plan.md`](../midas/plan.md).

## Across

- [`../../../architecture/sysifos/`](../../../architecture/sysifos/) — *how* it's built. [`../../../design/sysifos/`](../../../design/sysifos/) — *what* it is.
