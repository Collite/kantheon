# Midas — Phase 1 Stage 1.4 task list

**Materialized views + MCP tool surface + capabilities registration.**

Companions: [`plan.md`](./plan.md) §Stage 1.4, [`../../../architecture/midas/contracts.md`](../../../architecture/midas/contracts.md) §3 (MCP tools) + §6.2/§6.3 (MVs/refresh).

> **Status: DONE (2026-06-21).** Shipped in two commits — T1–T3 (`feat(midas): P1 S1.4 (T1–T3)`) and T4–T6 (this stage's MCP + capabilities work). Two deliberate deltas from the original plan text, both documented in `contracts.md` §6.3:
> 1. **Refresh is synchronous, not async NOTIFY/LISTEN.** `MvRefresher` issues a plain `REFRESH MATERIALIZED VIEW mv_position_current` once per write request (the trigger + `pg_notify` ship for the future async path but no listener is attached). The debounced `PGNotificationListener` + `REFRESH … CONCURRENTLY` is deferred to v1.x.
> 2. **The MCP surface is a second listener, not the REST port.** REST stays on Netty:7310; MCP runs on CIO:7311 in the same process (the REST error-envelope stack and the `installMcpKtorBase` stack can't share one Ktor application). Service exposes both ports.

## Tasks

- [x] **T1 — V0002 + V0003 migrations.** `mv_position_current` (kind-signed net quantity) + `mv_portfolio_value_daily` skeleton, both `WITH NO DATA` and owned by the BYPASSRLS `midas_mv_owner`; V0003 installs the `pg_notify` trigger (async path, dormant).
- [x] **T2 — refresh tests.** Unit `MvRefresherSpec` (REFRESH SQL + fail-open). The live cross-tenant refresh (REFRESH reads across tenants via the BYPASSRLS owner) is proven by `RlsLeakageComponentSpec` (real Postgres, CI) — folded into the RLS proof during review hardening.
- [x] **T3 — refresher.** `infra/MvRefresher` — synchronous per-write refresh, warn-and-continue. (Async debounced listener deferred to v1.x — see delta 1.)
- [x] **T4 — MCP server bootstrap.** `mcp/MidasMcpServer.kt` — standalone CIO server via `installMcpKtorBase` + `mcpStreamableHttp`, registers the five tools wrapped in `safeMcpTool` (timeout + structured error). Boot proven by `MidasMcpServerSmokeSpec` (`/health` 200).
- [x] **T5 — tool implementations.** `mcp/MidasTools.kt` — `position_valuation` **real** (reads `mv_position_current` via `PositionRepository`); `portfolio_performance` / `cost_basis` / `fee_allocation` **stubs** (zeros/empties + INFO message); `reconcile_statement` returns an empty reconciliation until statement import (Stage 2.x). Output is proto-faithful `structuredContent`. Unit: `MidasToolsSpec` (input validation + shape, mocked repo).
- [x] **T6 — capabilities registration.** `mcp/Capabilities.kt` + copied `mcp/ManifestLoader.kt` + five `manifests/tools/*.yaml` → one `ToolCapability` per tool (`midas.*:v1`, search tags per contracts §3.6). `CapabilitiesClient.startupRegister` per capability (warn-and-continue). Unit: `ManifestLoaderSpec`.

## DONE criteria (plan §Stage 1.4)

- [x] capabilities-mcp would list `midas.*:v1` (five manifests load into `ToolCapability`s — `ManifestLoaderSpec`; live registration is warn-and-continue).
- [x] `position_valuation` returns quantities for a seeded portfolio (`MidasToolsSpec`, mocked repo returning MV rows).
- [x] MV refresh reads across tenants — proven by `RlsLeakageComponentSpec` (real PG). (The original "within 5s via async NOTIFY" target is met more simply by the synchronous refresh; async is v1.x.)

## Follow-ups (tracked, not in this stage)

- Async debounced `PGNotificationListener` + `REFRESH … CONCURRENTLY` (v1.x; contention mitigation under load).
- Real TWR/MWR/FIFO/fee-allocation (Phase 3 Stage 3.3) replacing the three stubs.
- MCP-surface tenant authorization (tool calls currently scope by the globally-unique `portfolio_id`; identity-at-the-edge is the constellation-wide follow-up).
