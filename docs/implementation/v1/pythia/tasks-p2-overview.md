# Phase 2 — Procedural investigations end-to-end

> **Reads with.** [`plan.md`](./plan.md) §4, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §5 (execution model), [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md) §4.
>
> **Phase deliverable.** The **Nescafe-Maggi worked example** (design §4.1, SQL-only plans) runs end-to-end against **scripted/mocked LLM** and a **Wiremock theseus-mcp**: Themis INVESTIGATION_DEEP resolution → STRONG-tier planner → coroutine DAG executor → QueryNode via theseus-mcp → rules-first evaluator → budget tracker → synthesizer v0 → envelope blocks. Tag `pythia/v0.2.0`.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **2.1** — Resolution + planner | Handoff-seeded investigation; `ThemisClient` parks clarification / fails on refusal; `PlanComposer` emits a valid typed `PlanDag` (or feedback-retries, max 3); `PlanValidator` green; AWAITING_PLAN_APPROVAL wired | [`tasks-p2-s2.1-resolution-planner.md`](./tasks-p2-s2.1-resolution-planner.md) |
| **2.2** — DAG executor | Frontier property tests green; executor runs batches under `Semaphore` caps with priority + drain/resume; `HandleTable` v0 (`LiveQueryRef` + `PgResultSnapshot`) | [`tasks-p2-s2.2-dag-executor.md`](./tasks-p2-s2.2-dag-executor.md) |
| **2.3** — Query + evaluate + budget | QueryNode via theseus-mcp (OBO bearer); predicate evaluator (rules-first, no LLM); `BudgetTracker` ladder + AWAITING_BUDGET_DECISION parking | [`tasks-p2-s2.3-query-evaluate-budget.md`](./tasks-p2-s2.3-query-evaluate-budget.md) |
| **2.4** — Render + synthesize + e2e | RenderNode + Synthesizer v0 (block streaming); Nescafe-Maggi scripted e2e green | [`tasks-p2-s2.4-render-synthesize-e2e.md`](./tasks-p2-s2.4-render-synthesize-e2e.md) |

## Sequencing

```
Stage 2.1 ──► Stage 2.2 ──► Stage 2.3 ──► Stage 2.4
  resolve+plan   executor      query/eval/budget   synth + e2e
```
2.1 and 2.2 may overlap on the executor scaffolding, but 2.2's executor consumes 2.1's `PlanDag` shape — keep 2.1's plan contract frozen before 2.2 closes.

## Pre-flight for the phase

- [ ] **Phase 1 DONE** — `pythia/v0.1.0` (proto, persistence, lifecycle, events, control surface on stubs).
- [ ] **Themis** `themis/v0.2.0` reachable — INVESTIGATION_DEEP resolution live (the `ThemisClient` calls it; tests Wiremock it).
- [ ] **The in-repo query path** — `theseus/v0.1.0` + `theseus-mcp/v0.1.0` reachable (fork Phase 3 closed 2026-06-17). QueryNode calls **theseus-mcp**, not ai-platform query-mcp (plan §2). Tests Wiremock it.
- [ ] **Prometheus (LLM gateway)** reachable, or the `GatewayClient` tag-mapping shim (contracts §5) standing in. Planner/synth tests use **scripted-LLM fixtures**, never live calls (§4).
- [ ] **envelope-render** `v0.1.0` available (Golem Phase 1) — consumed by Stage 2.4 RenderNode/synth.

## Aggregate progress

- [ ] **Stage 2.1** — Resolution + planner.
- [ ] **Stage 2.2** — DAG executor.
- [ ] **Stage 2.3** — Query + evaluate + budget.
- [ ] **Stage 2.4** — Render + synthesize + e2e.

When all four are checked: tag `pythia/v0.2.0`. **Phase 2 DONE — procedural investigations ship.** Move to [`tasks-p3-overview.md`](./tasks-p3-overview.md).

## Up / across

- Up: [`./README.md`](./README.md). Neighbours: [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`tasks-p3-overview.md`](./tasks-p3-overview.md).
