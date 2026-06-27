# Phase 4 — Data plane + models

> **Reads with.** [`plan.md`](./plan.md) §6, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §3 (`dataplane/`, `handles/`) + §5, [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §6 (Charon) + §7 (Metis) + §3a (PD-5 resume), [`../../../architecture/charon/contracts.md`](../../../architecture/charon/contracts.md), [`../../../architecture/metis/contracts.md`](../../../architecture/metis/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md) §4.
>
> **Phase deliverable.** All four intent kinds ship: DataFrameNode on Polars-Worker (Steropes) session DFs via Charon-mediated materialisation; ModelNode on Metis (Fit/Project/Simulate/Diagnose). The **forecast** + **simulation** worked examples (design §4.3) run end-to-end against mocked Charon/Metis fixtures. Tag `pythia/v0.4.0`.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **4.1** — Charon integration + DataFrameNode | PD-5 resume probes; `CharonClient` (gRPC) + four Charon-backed handle kinds; materialisation policy engine; `WorkerClient` + DataFrameNode; planner gains DataFrame composition; IN-list>500 materialise path | [`tasks-p4-s4.1-charon-dataframe.md`](./tasks-p4-s4.1-charon-dataframe.md) |
| **4.2** — Metis integration + ModelNode + forecast/sim e2e | `MetisClient` (gRPC, incl. GetStatus resume probe); ModelNode (Fit/Project/Simulate/Diagnose); forecast + simulation scripted e2e; ChartBlock CI bands | [`tasks-p4-s4.2-metis-model-forecast-sim.md`](./tasks-p4-s4.2-metis-model-forecast-sim.md) |

## Sequencing

```
Stage 4.1 ──► Stage 4.2
  charon + dataframe   metis + model + forecast/sim e2e
```
4.2 depends on 4.1: Metis `Fit` consumes a Charon-staged session DF, so the staging path (4.1) lands first.

## Pre-flight for the phase — ⚠️ partially unmet (status 2026-06-26)

| Gate | Required | Status 2026-06-26 |
|---|---|---|
| **Phase 3 DONE** — `pythia/v0.3.0` | yes | pending (Phases 1–3 execute first) |
| **Charon arc Phase 3 closed** — `charon/v0.3.0` + `charon-mcp/v0.1.0` | **gates Stage 4.1** | ✅ **MET 2026-06-26** — `charon/v0.1.1`+`v0.2.0`+`v0.3.0` + `charon-mcp/v0.1.0` tagged; all four `Location` kinds + `Describe` liveness + legality matrix + `move.*` capabilities live; CG1–CG3 green. **Cross-engine `Stage(→ worker_df)` works for BOTH METIS and POLARS** — the POLARS path landed via the worker-arc `worker.v1 ImportDataFrame`/`DropWorkspaceEntry` RPCs added to Steropes at the Charon 3.1 closeout. No outstanding caveats. |
| **Metis arc Phase 3 closed** — `metis/v0.3.0` + `metis-mcp/v0.1.0` | gates Stage 4.2 | ✅ **met** — `metis/v0.3.0` (+`v0.3.1`) and `metis-mcp/v0.1.0` (+`v0.1.1`) tagged |
| **Fork Phase 3** — in-repo query path + Polars worker (`theseus`/`theseus-mcp`/`steropes`/`kyklop`) | data plane substrate | code present in `services/`+`workers/` (fork P3 signed off 2026-06-17 per plan §2); confirm tags before execution |
| `pythia-evidence` Seaweed bucket + Charon connection registry provisioned | Stage 4.1 | fabric-infra change (Charon arc Stages 1.3/2.3) — tracks with the Charon gate |

> **Planning vs execution.** These task lists are written now (planning is unblocked — the Charon gate is an *execution* gate, plan §8 / planning-conventions allow parallel task-list writing). **Stage 4.1 must not begin coding until `charon/v0.3.0` + `charon-mcp/v0.1.0` land.** Stage 4.2 (Metis) is executable as soon as Phase 3 closes — though it consumes 4.1's staging path, so in practice 4.1 lands first.

> **Entry gate.** Stage 4.1 opens with an explicit **Upstream closeout gate (CG1–CG5)** — verification tasks confirming the Charon arc is genuinely closed (incl. the still-open Charon Stage 1.4 / review-006 multi-batch + fingerprint closeout, CG1) before any 4.1 implementation task starts. See [`tasks-p4-s4.1-charon-dataframe.md`](./tasks-p4-s4.1-charon-dataframe.md) → "Upstream closeout gate". Do not start T0 until all five CG boxes are green.

## Aggregate progress

- [x] **Stage 4.1** — Charon integration + DataFrameNode. _(CG1–CG5 green; gRPC `CharonClient` + `WorkerClient`, handle↔Location mapping, materialisation policy + evidence/GC, DataFrameNode, planner DataFrame composition, IN-list>500 materialise, PD-5 resume prober — all specs green against in-process Charon fixture-server + worker fake.)_
- [x] **Stage 4.2** — Metis integration + ModelNode + forecast/sim e2e. _(MetisClient gRPC + GetStatus/NOT_FOUND/FAILED_PRECONDITION mapping, ModelNode Fit/Project/Simulate/Diagnose with NOT_FOUND re-fit, forecast worked-example e2e on goldens, simulation variant, ChartBlock CI bands — all specs green.)_

When both are checked: tag `pythia/v0.4.0`. **Phase 4 DONE — all four intent kinds ship.** Move to [`tasks-p5-overview.md`](./tasks-p5-overview.md).

## Up / across

- Up: [`./README.md`](./README.md). Neighbours: [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`tasks-p5-overview.md`](./tasks-p5-overview.md).
- Sibling arcs: [`../charon/plan.md`](../charon/plan.md) (the blocking arc), [`../metis/plan.md`](../metis/plan.md) (done).
