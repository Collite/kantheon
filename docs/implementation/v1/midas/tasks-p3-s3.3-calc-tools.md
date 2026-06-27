# Stage 3.3 — Midas-core complex calc tools

> **Phase 3, Stage 3.3.** Complete the three calc tools the S1.4 stage left as stubs: `CostBasisTool` (FIFO), `FeeAllocationTool`, and MWR (money-weighted return) inside `PortfolioPerformanceTool`. After this, all five `midas.*:v1` tools serve real data.
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §Stage 3.3, [`../../../architecture/midas/architecture.md`](../../../architecture/midas/architecture.md) §6.1 (`calc/` layout), [`../../../architecture/midas/contracts.md`](../../../architecture/midas/contracts.md) §3.3/§3.4 (tool I/O).
> **Templates.** `agents/midas/core/.../mcp/MidasTools.kt` (the five tools — three still stubs per the S1.4 task list), `calc/Twr.kt`+`calc/Fifo.kt` (created in S3.2 T5). Kotest: EXAMPLES.md §9.

## Goal

`MwrSpec`, `FifoSpec` (extended), and the fee-allocation spec pass at 4-decimal precision against reference portfolios; the three stub tools in `MidasTools.kt` are replaced with real implementations reading the transaction log.

## Pre-flight

- [ ] S3.2 DONE (validates the real flow + reference-portfolio fixtures exist; `calc/Twr.kt`+`calc/Fifo.kt` in place).
- [ ] Reference-portfolio fixtures with hand-computed MWR (IRR) + multi-lot FIFO + pro-rata fee results (Bora-owned; overview §4).
- [ ] Branch `feat/p3-s3.3-calc-tools` from `main`.

## Tasks

- [ ] **T1 — MWR tests first.** `MwrSpec` with reference portfolios (dated cashflows + known IRR results). Cover: positive/negative returns, near-zero return stability, single-cashflow degenerate case, and a non-convergence guard. Define expected IRRs before coding.
- [ ] **T2 — MWR impl.** `calc/Mwr.kt` — Newton-Raphson IRR solver with a bisection fallback for stability; documented convergence tolerance + max-iterations. Integrate into `PortfolioPerformanceTool` (alongside the S3.2 TWR) so `midas.portfolio.performance:v1` returns both TWR and MWR. Make `MwrSpec` pass.
- [ ] **T3 — FIFO cost-basis tests first.** Extend `FifoSpec` (from S3.2) for the cost-basis *tool* path: multi-buy / partial-sell lot ordering, remaining quantities, and the **reversal-entry interaction** (reversing a sale must release the consumed lot — risk §FIFO+reversal). Reference portfolios with hand-computed remaining lots.
- [ ] **T4 — FIFO cost-basis impl + tool wiring.** Finalise `calc/Fifo.kt` as a lot ledger (derived, not denormalised onto positions — risk mitigation). Wire `CostBasisTool` (`midas.position.cost_basis:v1`, contracts §3.3 input incl. optional `asset_id`) to return real lots/gain. Make `FifoSpec` pass.
- [ ] **T5 — Fee allocation tests first + impl.** `FeeAllocationSpec`: pro-rata allocation of a transaction's fee across positions by market value at trade date. `calc/` fee-allocation logic + wire `FeeAllocationTool` (`midas.transaction.fee_allocation:v1`, contracts §3.4 input `{transaction_id}`). Make the spec pass.
- [ ] **T6 — Tool-output shape conformance.** Kotest spec asserting each of the three completed tools emits proto-faithful `structuredContent` (`CostBasisToolOutput`, `FeeAllocationToolOutput`, the MWR field on `PortfolioPerformanceToolOutput`) — guards against drift from contracts §1.1/§3 (mirror the S1.4 `MidasToolsSpec` shape checks).

## DONE (plan §Stage 3.3)

- [ ] All five `midas.*:v1` MCP tools serve real data (no remaining stubs).
- [ ] `MwrSpec` / `FifoSpec` / `FeeAllocationSpec` pass at 4-decimal precision against reference portfolios.
- [ ] FIFO + reversal round-trip proven (T3).
- [ ] Tool outputs are proto-faithful (T6).

## Follow-ups (not in this stage)

- Cost-basis methods beyond FIFO — out of v1 scope (plan §7).
- Corporate-actions effects on lots — out of v1 scope.
