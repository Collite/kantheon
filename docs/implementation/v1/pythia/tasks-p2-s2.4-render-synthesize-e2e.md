# Stage 2.4 — Render + synthesize + e2e

> **Phase 2, Stage 2.4.** Closes Phase 2 — tag `pythia/v0.2.0`.
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4 Stage 2.4, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §3 (`synth/`) + §6 (Iris rendering), [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §1 (`Conclusion`/`RenderableArtifact`) + §9 (divergence 2/6 — envelope-render owns charts), [`../../../design/pythia/Pythia-v1-Design.md`](../../../design/pythia/Pythia-v1-Design.md) §4.1 (Nescafe-Maggi worked example — the e2e target), [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

RenderNode produces TABLE/NARRATIVE_FRAGMENT blocks via **envelope-render**; the Synthesizer v0 streams conclusion blocks (STRONG, `synthesizer_block_*` events) and assembles a `Conclusion` with honest `stop_reason`; the **Nescafe-Maggi worked example** (design §4.1) runs end-to-end against scripted/mocked LLM with its full event trace asserted. **End state:** the Nescafe-Maggi fixture e2e green; tag `pythia/v0.2.0`.

## Pre-flight

- [ ] Stage 2.3 DONE — query + evaluate + budget.
- [ ] **envelope-render** `v0.1.0` (Golem Phase 1) on the classpath — the RenderNode + synth blocks render through it (one chart pipeline constellation-wide; chart-formatter consolidated away — divergence 2).
- [ ] The three golden artifact fixtures from Stage 1.1 T5 are present (the Nescafe-Maggi one is the e2e reference).
- [ ] Branch `feat/pythia-p2-s2.4-render-synthesize-e2e`.

## Tasks (TDD-shaped: T4 e2e is the pinning test; T1–T3 build to it)

- [ ] **T1 — RenderNode (TABLE / NARRATIVE_FRAGMENT).**

  Implement the `NodeExecutor` for `RenderNode` (contracts §1 `RenderNode`: TABLE | CHART | NARRATIVE_FRAGMENT + handles + block_role + caption). At this phase implement **TABLE** and **NARRATIVE_FRAGMENT** via `envelope-render` (envelope/v1 `Block`s — divergence 6). **CHART path stubbed to Phase 4 data** (forecast CI bands need Metis output — Stage 4.2): a CHART RenderNode emits a placeholder Block + Rule-6 "chart rendering lands in Phase 4" and does not fail the plan.

  Test: a TABLE RenderNode over a `PgResultSnapshot` produces a `TableBlock`-shaped `Block`; a NARRATIVE_FRAGMENT produces a text Block; a CHART node degrades gracefully.

  Acceptance: `RenderNodeSpec` green.

- [ ] **T2 — Synthesizer v0.**

  Implement `synth/Synthesizer.kt`: STRONG-tier, **block streaming** — emit `synthesizer_block_started`/`_delta`/`_completed` events (confirm names against design §3.3 synthesis group, 4 events) as each conclusion block is produced. Assemble a `Conclusion` (`RenderableArtifact primary` + `evidence_step_ids` + `ConfidenceInfo` + `stop_reason` + `budget_truncated`). **stop_reason honesty:** the reason reflects how the investigation actually ended (goal reached vs hard cap vs user halt vs budget) — never claim STOP_GOAL_REACHED on a budget truncation. Author `prompts/synthesizer.md` (cs + en).

  Test (scripted STRONG fixture): block events stream in order; the `Conclusion` carries the right `stop_reason` for a goal-reached run and for a budget-truncated run (`budget_truncated = true`).

  Acceptance: `SynthesizerSpec` green.

- [ ] **T3 — NARRATIVE_FRAGMENT per-fragment CHEAP calls.**

  Where the plan includes NARRATIVE_FRAGMENT RenderNodes (short per-section prose), route those to **CHEAP** tier per-fragment (architecture §5 / design tiering), distinct from the STRONG synthesizer. Author `prompts/narrative-fragment.md` (cs + en).

  Test: a NARRATIVE_FRAGMENT node issues a CHEAP-tier call (assert the `GatewayClient` tag) and yields a text Block.

  Acceptance: `NarrativeFragmentSpec` green.

- [ ] **T4 — Nescafe-Maggi fixture e2e (scripted/mocked LLM).**

  Build the **Nescafe-Maggi worked example** (design §4.1) as a component e2e: Wiremock theseus-mcp + scripted-LLM fixtures for planner/synth/narrative + the trivial subsystems already built. Assert the **full event trace against design §4.1** (resolution → plan → batches → query steps → hypothesis verdicts → synthesis blocks → conclusion). Assert the final `InvestigationArtifact` matches the structure of `golden/nescafe-maggi-artifact.json` (Stage 1.1 T5) on the load-bearing fields.

  **This is the Phase 2 DONE gate.** (The former plan task "live-LLM manual run vs real platform" is **deferred to the integration suite** — plan §4 Stage 2.4 T5; the scripted e2e here satisfies DONE.)

  Acceptance: Nescafe-Maggi e2e green; event trace matches design §4.1.

- [ ] **T5 — Tag + close.**

  Run the full `just test-kt pythia`; update [`tasks-p2-overview.md`](./tasks-p2-overview.md) aggregate progress + [`plan.md`](./plan.md) §11 checklist; record integration-suite carry-overs (live-LLM Nescafe-Maggi run, live envelope-render chart output). **Tag `pythia/v0.2.0`.**

  Acceptance: tag pushed; CI green on `[pythia-p2-s2.4] render + synthesize + e2e`.

## DONE — Stage 2.4 → Phase 2

- [ ] All tasks checked; Nescafe-Maggi scripted e2e green; full suite green.
- [ ] **Tag `pythia/v0.2.0`.** **Phase 2 DONE — procedural investigations ship.**

## Library / pattern references

- **`shared/libs/kotlin/envelope-render`** (Golem Phase 1) — RenderNode + synth blocks → envelope/v1 `Block`s.
- **contracts §1** (`Conclusion`/`RenderableArtifact`/`RenderNode`), **§9** (divergences 2/6), **design §4.1** (the e2e target trace).
- **architecture §6** — how Iris will render these events/blocks (the event vocabulary already carries everything; the Iris wiring itself is Phase 5 Stage 5.2).

## Out of scope

- CHART rendering with real data (forecast CI bands) — Phase 4 Stage 4.2.
- Iris-bff integration — Phase 5 Stage 5.2.
- RCA revision/deepening machinery — Phase 3.
