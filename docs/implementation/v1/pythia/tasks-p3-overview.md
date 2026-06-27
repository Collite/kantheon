# Phase 3 — Hypotheses + HITL complete

> **Reads with.** [`plan.md`](./plan.md) §5, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §3 (`revise/`, `suspicion/`) + §5, [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md) §4.
>
> **Phase deliverable.** The full hypothesis + HITL machinery: CHEAP-tier evaluation fallback, suspicion classifier, plan reviser (PRUNE/PIVOT/DECOMPOSE/HALT), prioritisation scoring, **all five AWAITING_* with drain + resume**, replay/reproduce. The **RCA worked example** (design §4.2) runs end-to-end with heuristic explained-variance. Tag `pythia/v0.3.0`. **Phases 1–3 together ship a useful RCA investigator (SQL-only plans) with no cross-repo dependency.**

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **3.1** — Evaluation LLM fallback + suspicion | CHEAP-tier verdict fallback for NON_APPLICABLE/fuzzy; `SuspicionClassifier` rules + CHEAP fallback; `on_suspicious_result` policy actions (incl. HALT→AWAITING_USER_INPUT) | [`tasks-p3-s3.1-eval-fallback-suspicion.md`](./tasks-p3-s3.1-eval-fallback-suspicion.md) |
| **3.2** — Reviser + prioritisation | Scoring formula spec; prioritisation + `deepening_decision` events; `PlanReviser` (PRUNE/PIVOT/DECOMPOSE/HALT) + AWAITING_PLAN_REVISION_APPROVAL; loose-end derivation; stop-condition spine | [`tasks-p3-s3.2-reviser-prioritisation.md`](./tasks-p3-s3.2-reviser-prioritisation.md) |
| **3.3** — Replay/reproduce + RCA e2e | `replay` (re-resolve) + `reproduce` (frozen params) + lineage; heuristic explained-variance; RCA worked example green | [`tasks-p3-s3.3-replay-reproduce-rca-e2e.md`](./tasks-p3-s3.3-replay-reproduce-rca-e2e.md) |

## Sequencing

```
Stage 3.1 ──► Stage 3.2 ──► Stage 3.3
  eval+suspicion  reviser+priority   replay + RCA e2e
```

## Pre-flight for the phase

- [ ] **Phase 2 DONE** — `pythia/v0.2.0` (procedural investigations end-to-end).
- [ ] Scripted-LLM fixtures harness from Phase 2 in place (CHEAP + STRONG scripts) — Phase 3 extends it for reviser/suspicion/fallback verdicts.
- [ ] No new external dependency — Phase 3 is pure Pythia logic over the Phase 1/2 substrate (replay/reproduce use the Stage 1.2 persistence; explained-variance is a heuristic, the honest `model.decompose.variance` is v1.5 backlog).

## Aggregate progress

- [ ] **Stage 3.1** — Evaluation LLM fallback + suspicion.
- [ ] **Stage 3.2** — Reviser + prioritisation.
- [ ] **Stage 3.3** — Replay/reproduce + RCA e2e.

When all three are checked: tag `pythia/v0.3.0`. **Phase 3 DONE — RCA ships.** (Phase 4 — data plane + models — gates on the Charon/Metis sibling arcs; see [`plan.md`](./plan.md) §6 pre-flight.)

## Up / across

- Up: [`./README.md`](./README.md). Neighbours: [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`tasks-p2-overview.md`](./tasks-p2-overview.md).
- Phase 4/5 task lists are written when their pre-flights (Charon/Metis arcs; Iris/capabilities content) come into range — per the team's just-in-time pattern.
