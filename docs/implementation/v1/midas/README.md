# Midas — v1 Implementation

Phased plan for the Midas arc (the brokerage-domain agent constellation). The arc is **consolidated** — Midas-core + loaders + Sysifos + report-renderer + Iris dashboards + Golem-Investment Shem all live in one plan because the work is tightly interleaved.

## Active

3 phases · 16 stages · ~95 tasks. Phased plan landed 2026-06-02; per-stage task lists are written from the plan during execution.

- **[`plan.md`](./plan.md)** — the phased plan. Read first. Phase summary, pre-flight, per-stage goal + DONE criteria + dependencies, risks, out-of-scope, sequencing diagram.

### Phase 1 — Foundation (~5 weeks)

Deployable at close: Midas-core + Excel loader + Sysifos-BFF + Sysifos FE shell in local K3s; operational Postgres provisioned + Flyway-migrated + RLS active; Midas-core's five tool capabilities registered in capabilities-mcp.

Stages: 1.1 Arc bootstrap + Postgres infra · 1.2 Proto packages · 1.3 Midas-core DB schema + write API + RLS · 1.4 Materialized views + MCP tool stubs + capabilities registration · 1.5 Excel loader · 1.6 Sysifos-BFF + FE shell.

### Phase 2 — Sysifos data entry (~4 weeks)

Deployable at close: Sysifos serves every v1 data-entry screen end-to-end. Operational from a user's perspective.

Stages: 2.1 Clients + Portfolios · 2.2 Assets + Transactions · 2.3 Balance entry · 2.4 Statement import end-to-end · 2.5 Reconciliation · 2.6 Loader status + audit polish.

### Phase 3 — Q&A + reports + dashboards (~5 weeks)

Deployable at close: Iris answers investment Q&A via Themis → Golem-Investment; reports render in XLSX/PPTX/PDF/HTML; Iris dashboard system live with a v1 template; Google Finance poller updates FX rates + market prices daily.

Stages: 3.1 Golem-Investment ShemManifest + curated queries · 3.2 Q&A green path (depends on ai-platform `workers/postgres`) · 3.3 Midas-core complex calc tools · 3.4 Report renderer + v1 templates · 3.5 Iris dashboard system · 3.6 Google Finance poller.

## Cross-repo dependency

- **[`../aip-v1-pg-worker-plan.md`](../_archive/aip-v1-pg-worker-plan.md)** — ai-platform-side `workers/postgres` plan. Required by Phase 3 Stage 3.2. Runs in parallel to Phases 1 + 2; not on the critical path until Stage 3.2.

## What's elsewhere

- **Architecture + contracts**: [`../../../architecture/midas/`](../../../architecture/midas/) — implementation shape and wire contracts.
- **Brief**: [`../../../architecture/midas/midas-brief.md`](../../../architecture/midas/midas-brief.md) — the originating goals.
- **Themis arc** (the reference implementation of the planning convention this arc mirrors): [`../themis/`](../themis/).
- **Planning conventions**: [`../../planning-conventions.md`](../../planning-conventions.md).

## Up / across

- Up: [`../README.md`](../README.md) — v1 implementation entry point.
- Across: [`../../../architecture/midas/`](../../../architecture/midas/), [`../../../design/`](../../../design/) (no Midas design folder; the brief + architecture cover what would normally live in design).
