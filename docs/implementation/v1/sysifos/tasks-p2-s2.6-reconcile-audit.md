# Stage 2.6 — Reconcile + Loader status + Audit

> **Phase 2, Stage 2.6.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4 (Stage 2.6), [`../../../architecture/sysifos/contracts.md`](../../../architecture/sysifos/contracts.md) §1 (`ReconciliationDecisionForm`), [`../midas/contracts.md`](../../../architecture/midas/contracts.md) §2.8 (reconcile) + §11 (audit_log).

## Goal

Reconciliation diff + per-diff decision; loader-run overview; audit viewer. Closes the operational loop.

## Pre-flight

- [ ] **Stage 2.5 DONE.**
- [ ] **Branch**: `feat/p2-s2.6-reconcile-audit`.

## Tasks

- [ ] **T1 — Reconcile tests first.** `POST /midas/reconcile` fixture returning 3 system-only, 2 statement-only, 1 value-mismatch; assert grouping by `ReconcileDiffKind` and decision dropdown per row.

- [ ] **T2 — Reconcile screen.** `views/Reconcile.vue`: select portfolio + loader_run + period → `POST /midas/reconcile`; a section per diff kind (system-only / statement-only / value-mismatch with field deltas); per-diff decision dropdown (EXPECTED / INVESTIGATE / RESOLVED) → `POST /midas/reconcile/{diff_id}/decision` via `ReconciliationDecisionForm`.

- [ ] **T3 — Decision persistence + summary.** Decisions persist (Midas-core `reconciliation_decisions`); resolved diffs aren't re-prompted on the next run; top-of-page summary widget (total / open / by-status) + "show only open" filter.

- [ ] **T4 — Loader status screen.** `views/Loaders.vue`: last-50 runs across all loaders (Excel now, pollers later) via `/loaders/*/runs`; status pills; a run-details modal (rows summary, error_summary, links to preview / transactions); admin-only "trigger" button for pollers (no-op until a poller exists).

- [ ] **T5 — Audit viewer tests first.** Admin-only route guard; filter by entity_type / actor_user_id / time range; before/after JSON rendered as a diff.

- [ ] **T6 — Audit viewer.** `views/Audit.vue` (gated `midas:admin`); read `audit_log` via a Midas-core read endpoint; side-by-side before/after JSON (jsondiffpatch); link-out to the Grafana trace via `trace_id`.

- [→] **T7 — Phase 2 deploy + smoke → MOVED to Testing Stage 3.4.** The full fresh-tenant round-trip (create client → portfolio → import statement with a correction → edit a transaction → bulk-grid a block → balance entry → reconcile, decide each diff → review audit) is owned by the Testing arc — see [`../testing/tasks-p3-s3.4-sysifos-deploy-smoke.md`](../testing/tasks-p3-s3.4-sysifos-deploy-smoke.md) (T5 "Full operational round-trip" leg). The arc tag `sysifos-arc/phase-2-data-entry-v1` lands with Testing 3.4 T6.

## DONE — Stage 2.6

- [x] T1–T6 done; tests green (the cluster round-trip smoke, T7, **moved to Testing Stage 3.4**).
- [x] Reconcile + loader status + audit live. _(Full operational round-trip on a fresh tenant verified in Testing 3.4.)_
- [ ] Tag `sysifos-arc/phase-2-data-entry-v1` — **lands with Testing Stage 3.4 T6** (post-merge, with the cluster deploy). **Arc code-complete.**

## Library / pattern references

- Midas-core reconcile + decision endpoints — `../midas/contracts.md` §2.8.
- audit_log shape — `../midas/contracts.md` §11.
- jsondiffpatch for the before/after panel (context7).

## Out of scope

- LLM-based auto-resolve (v1.x). Poller trigger behaviour beyond the button (Google Finance loader is a Midas-arc Phase 3 item). Dashboards (Iris).
