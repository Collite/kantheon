# Stage 4.2 — Metis integration + ModelNode + forecast/sim e2e

> **Phase 4, Stage 4.2.** Closes Phase 4 — tag `pythia/v0.4.0`. Metis gate is **met** (`metis/v0.3.0` + `metis-mcp/v0.1.0` tagged); this stage depends on Stage 4.1's Charon staging path.
>
> **Reads with.** [`tasks-p4-overview.md`](./tasks-p4-overview.md), [`plan.md`](./plan.md) §6 Stage 4.2, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §3 (`dataplane/`), [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §7 (Metis), §3a (PD-5), [`../../../architecture/metis/contracts.md`](../../../architecture/metis/contracts.md) §1 (`MetisService`), §3 (numerical-fidelity goldens), §4 (ModelNode mapping), [`../../../design/pythia/Pythia-v1-Design.md`](../../../design/pythia/Pythia-v1-Design.md) §4.3 (forecast worked example), [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

ModelNode comes alive on Metis: `MetisClient` (gRPC, incl. the `GetStatus` resume probe); the ModelNode executor maps the design's per-kind vocabulary onto Metis's single-tool surface (Fit/Project/Simulate/Diagnose); the **forecast** + **simulation** worked examples (design §4.3) run end-to-end against a **mocked Metis fixture** pinned on the Metis goldens; ChartBlock renders forecast CI bands. **End state:** forecast + simulation scripted e2e green; tag `pythia/v0.4.0`.

## Pre-flight

- [x] Stage 4.1 DONE — Charon staging path live (Metis `Fit` consumes a Charon-staged session DF).
- [x] **`metis/v0.3.0` + `metis-mcp/v0.1.0` tagged** ✅ (met). Re-verify: `git tag | grep metis`.
- [x] Branch `feat/pythia-p4-s4.2-metis-model-forecast-sim`.
- [x] Read `metis/contracts.md` §1 (`MetisService`: `Fit`/`Diagnose`/`Project`/`SimulateScenario` + `GetStatus`/`ImportDataFrame`/`ExportDataFrame`), §3 (golden rtols — these pin the e2e assertions), §4 (the ModelNode→Metis mapping table). Pull the Metis golden fixtures (the numerical reference the e2e asserts on).

## Tasks (TDD-shaped: T3 e2e is the pinning test)

- [x] **T1 — `MetisClient` (gRPC).**

  Implement `dataplane/MetisClient.kt` against `org.tatrman.metis.v1.MetisService` (gRPC-direct, contracts §7). RPCs used: `Fit`, `Diagnose`, `Project`, `SimulateScenario`, plus the **`GetStatus` resume probe** (PD-5 — workspace dead → re-fit from the checkpointed fit spec). Map the error model (contracts §1.2 / metis §1): `NOT_FOUND` model → **re-fittable** (re-fit, don't fail); `FAILED_PRECONDITION` (e.g. Project horizon before series end) → INCONCLUSIVE + Rule-6. Specs via a **Metis fixture-server** (in-process gRPC double returning the pinned goldens; live Metis = integration suite).

  Acceptance: `MetisClientSpec` green incl. the NOT_FOUND→re-fit and GetStatus-resume paths.

- [x] **T2 — ModelNode executor.**

  Implement the ModelNode `NodeExecutor` mapping the design per-kind vocabulary onto Metis's single-tool surface (metis/contracts.md §4):
  - `model.fit.<kind>(input=Handle)` → Charon stages the handle → `Fit(input_df, model_name = node_id, model_kind = <kind>)`.
  - `model.project.<kind>(model=Handle, horizon)` → `Project(model_name, horizon, confidence_level)` → output DF → `WorkerSessionDF` handle (worker_kind METIS).
  - `model.simulate.scenario(forecast=Handle, deltas)` → `SimulateScenario(forecast_df, deltas_json)`.
  - diagnostics (design §4.3 N3) → `Diagnose(model_name)` then an **LLM-interpreted ReasoningNode** over `DiagnoseResult` (data deterministic, prose not).

  Test: each mapping issues the right Metis call with the right args (modelKind carried as argument, not capability id); `NOT_FOUND` model re-fits.

  Acceptance: `ModelNodeSpec` green.

- [x] **T3 — Forecast worked example e2e (scripted, on Metis goldens).**

  Build the **forecast worked example** (design §4.3) as a component e2e: scripted planner/synth + Charon fixture-server (Stage 4.1) + the **Metis fixture-server returning the pinned goldens** (metis/contracts.md §3 — ARIMA order+AIC `rtol 1e-6`, forecast points + CI bands `rtol 1e-4`). Assert the trace against design §4.3 and the artifact against `golden/forecast-artifact.json` (Stage 1.1 T5). **This is part of the Phase 4 DONE gate.** (Live-Metis run → integration suite, plan §6 Stage 4.2 T3.)

  Acceptance: forecast e2e green; CI-band numbers match the goldens within rtol.

- [x] **T4 — Simulation variant.**

  Extend the forecast e2e into the **simulation** variant: `scenario_params` (`Investigation.scenario_params` — deltas) → a `SimulateScenario` insertion after the forecast (`SimulateScenario(forecast_df, deltas_json)`, contracts §7/metis §4). Assert the scenario output diverges from the base forecast per the deltas.

  Acceptance: `SimulationVariantSpec` green.

- [x] **T5 — ChartBlock for forecast CI bands.**

  Activate the CHART RenderNode path stubbed in Stage 2.4 T1: render forecast point + CI bands as a `ChartBlock` via `envelope-render` (extend envelope-render chart kinds if a CI-band kind is missing — **coordinate with the Golem arc**, which owns envelope-render). 

  Test: a forecast ModelNode output renders to a ChartBlock with the CI-band series present.

  Acceptance: `ForecastChartSpec` green.

- [x] **T6 — Tag + close.**

  Full `just test-kt pythia`; update [`tasks-p4-overview.md`](./tasks-p4-overview.md) + [`plan.md`](./plan.md) §11; record integration carry-overs (live Metis fit/project numerical parity, live Charon→Metis staging, real ChartBlock render). **Tag `pythia/v0.4.0`.**

  Acceptance: tag pushed; CI green on `[pythia-p4-s4.2] metis + model + forecast/sim`.

## DONE — Stage 4.2 → Phase 4

- [x] All tasks checked; forecast + simulation scripted e2e green; full suite green.
- [x] **Tag `pythia/v0.4.0`.** **Phase 4 DONE — all four intent kinds ship.**

## Library / pattern references

- **metis/contracts.md** §1 (`MetisService` RPCs + error model), §3 (golden rtols — the e2e assertions), §4 (ModelNode mapping table).
- **contracts §7** (Metis Pythia-side notes), **§3a** (GetStatus resume probe); **design §4.3** (forecast worked example — authority).
- **envelope-render** (Golem arc) — ChartBlock CI-band rendering.

## Out of scope

- `model.decompose.variance` (honest RCA variance) — v1.5 backlog (Phase 3 used a heuristic).
- Sweeping/multi-scenario simulations — v1.5.
- Constellation integration (master-of-Golems, Iris, eval gate) — Phase 5.
