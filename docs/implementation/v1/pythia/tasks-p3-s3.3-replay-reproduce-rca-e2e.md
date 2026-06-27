# Stage 3.3 — Replay/reproduce + RCA e2e

> **Phase 3, Stage 3.3.** Closes Phase 3 — tag `pythia/v0.3.0`.
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §5 Stage 3.3, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §5, [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §2 (`/replay`, `/reproduce`), [`../../../design/pythia/Pythia-v1-Design.md`](../../../design/pythia/Pythia-v1-Design.md) §3.6 (replay/reproduce) + §4.2 (RCA worked example — the e2e target), [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

`replay` re-runs an investigation re-resolving relative params; `reproduce` re-runs with frozen resolved params (reusing retained blobs); parent/child lineage is tracked; a heuristic explained-variance feeds `ConfidenceInfo` caveats; the **RCA worked example** (design §4.2) runs end-to-end as a scripted fixture with its full revision/deepening trace asserted. **End state:** replay/reproduce specs green; RCA e2e green; tag `pythia/v0.3.0`.

## Pre-flight

- [ ] Stage 3.2 DONE — reviser + prioritisation + stop conditions (the RCA e2e exercises all of it).
- [ ] Branch `feat/pythia-p3-s3.3-replay-reproduce-rca-e2e`.
- [ ] The Stage 1.3 `/replay` + `/reproduce` endpoints are currently **stubbed to 501** — this stage implements them.
- [ ] The Stage 1.1 `golden/rca-channel-artifact.json` fixture is the RCA e2e reference.

## Tasks (TDD-shaped: T3 e2e is the pinning test)

- [ ] **T1 — `replay` + `reproduce`.**

  Implement the two endpoints (design §3.6, contracts §2), each creating a **new investigation id** with `parent_id` set:
  - **`replay`** — re-resolve **relative params** (e.g. "last quarter" re-resolves against today): re-run resolution + planning from the parent's question + overrides, producing fresh resolved params. `POST …/replay { overrides? }`.
  - **`reproduce`** — **frozen resolved_params**: skip re-resolution, reuse the parent's resolved params verbatim; **reuse retained blobs** if still present (a blob-reuse check — if a `PgResultSnapshot` is retained, reuse it; if a Phase-4 blob is gone, this stage records a Rule-6 "blob expired, would re-materialise" note — full re-materialise is Phase 4). `POST …/reproduce {}`.

  Test (`testApplication`): `replay` of a parent with a relative date produces different resolved params; `reproduce` produces identical resolved params; both set `parent_id`.

  Acceptance: `ReplayReproduceSpec` green.

- [ ] **T2 — Parent/child lineage + heuristic explained-variance.**

  Persist + expose the parent/child lineage (the `parent_id` chain queryable). Implement a **heuristic explained-variance** (design §3 / plan §5 Stage 3.3 T2): a **capped sum** of per-hypothesis `estimated_explanatory_power` for SUPPORTED hypotheses, fed into `Conclusion.confidence` (`ConfidenceInfo`) as a **caveat** — explicitly labelled heuristic, not the honest `model.decompose.variance` (which is v1.5 backlog). Cap at 1.0; surface "explained variance is approximate" as a caveat string.

  Test: a multi-hypothesis fixture yields a capped explained-variance caveat; the cap holds when the raw sum exceeds 1.0.

  Acceptance: `ExplainedVarianceSpec` green.

- [ ] **T3 — RCA worked example e2e (scripted fixture).**

  Build the **RCA worked example** (design §4.2) as a scripted component e2e: Wiremock theseus-mcp + scripted-LLM fixtures for planner/evaluator/reviser/synth. The trace must exercise the **full revision + deepening** path: initial plan → hypothesis verdicts → prioritisation/deepening decision → a plan **revision** (DECOMPOSE or PIVOT) → further evidence → stop-condition (RCA brake or goal-reached) → synthesis with the heuristic explained-variance caveat. Assert the event trace against design §4.2 and the artifact structure against `golden/rca-channel-artifact.json`.

  **This is the Phase 3 DONE gate.** (The former plan task "live-LLM RCA dev run + prompt iteration" is **deferred to the integration suite** — plan §5 Stage 3.3 T4.)

  Acceptance: RCA e2e green; revision + deepening trace matches design §4.2.

- [ ] **T4 — Tag + close.**

  Full `just test-kt pythia`; update [`tasks-p3-overview.md`](./tasks-p3-overview.md) + [`plan.md`](./plan.md) §11 checklist; record integration carry-overs (live-LLM RCA run; replay/reproduce determinism under real LLM → the Phase 5 eval gate's "replay determinism" metric). **Tag `pythia/v0.3.0`.**

  Acceptance: tag pushed; CI green on `[pythia-p3-s3.3] replay/reproduce + RCA e2e`.

## DONE — Stage 3.3 → Phase 3

- [ ] All tasks checked; RCA scripted e2e green; full suite green.
- [ ] **Tag `pythia/v0.3.0`.** **Phase 3 DONE — RCA ships.** Phases 1–3 together are a useful, self-contained RCA investigator (SQL-only plans).

## Library / pattern references

- **design §3.6** (replay vs reproduce semantics), **§4.2** (the RCA e2e trace — authority), **contracts §2** (endpoint shapes).
- **architecture §5** (lineage); **plan §5 Stage 3.3** (heuristic explained-variance — capped sum).

## Out of scope

- **Phase 4** data plane: real blob re-materialisation on reproduce, Charon/Metis, DataFrame/Model nodes, forecast/simulation e2e — gated on the Charon/Metis sibling arcs ([`plan.md`](./plan.md) §6 pre-flight).
- **Phase 5** constellation integration: master-of-Golems, Iris UX, manifest content, CI eval gate.
- Honest variance decomposition (`model.decompose.variance`) — v1.5 backlog.
