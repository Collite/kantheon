# Stage 3.2 — Reviser + prioritisation

> **Phase 3, Stage 3.2.**
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §5 Stage 3.2, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §3 (`revise/`) + §5, [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §1 (`Hypothesis` scoring fields, `LooseEnd`, `StopReason`), [`../../../design/pythia/Pythia-v1-Design.md`](../../../design/pythia/Pythia-v1-Design.md) §3 (revision + prioritisation + stop conditions), [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

Prioritisation scoring orders the frontier and drives deepening decisions; the `PlanReviser` reshapes the plan (PRUNE/PIVOT/DECOMPOSE/HALT) under depth-budget caps, parking AWAITING_PLAN_REVISION_APPROVAL when policy requires; loose-ends are derived; the stop-condition spine decides when to stop. **End state:** scoring-formula spec green; `PlanReviserSpec` green on scripted outputs; stop-condition spec green.

## Pre-flight

- [ ] Stage 3.1 DONE — evaluation fallback + suspicion (the reviser reacts to hypothesis verdicts + suspicion findings).
- [ ] Branch `feat/pythia-p3-s3.2-reviser-prioritisation`.
- [ ] The Stage 2.2 executor accepts an injected `priorityOf(node)` comparator — this stage supplies the real scoring.

## Tasks (TDD-shaped: T1 written first)

- [ ] **T1 — Scoring formula spec (tests first).**

  Create `src/test/kotlin/.../revise/PrioritisationSpec.kt` pinning the design scoring formula: **`confidence × explanatory × 1/cost × diagnostic × novelty`** (the `Hypothesis` fields `confidence`, `estimated_explanatory_power`, `diagnostic_power` from contracts §1, plus cost + novelty terms). **Tie-break only the top-2 within 10 %** (design rule — don't globally re-sort near-ties). Assert: ranking order on a hand-built set; the tie-break applies only to the top-2-within-10 %; degenerate inputs (zero cost, zero novelty) don't divide-by-zero.

  Acceptance: spec compiles + fails.

- [ ] **T2 — Prioritisation + `deepening_decision` events.**

  Implement `revise/Prioritisation.kt` (the scoring) + supply the comparator to the executor; emit the prioritisation events (design §3.3 prioritisation group, 2 events) incl. `deepening_decision` (which hypothesis to deepen vs drop).

  Test: `PrioritisationSpec` green; a fixture emits a `deepening_decision` selecting the top-scored hypothesis.

  Acceptance: green.

- [ ] **T3 — Reviser prompt + `PlanReviserSpec` (tests first).**

  Author `prompts/reviser.md` (cs + en) — typed structured output choosing **PRUNE / PIVOT / DECOMPOSE / HALT** with the affected hypothesis/node ids + rationale. Create `revise/PlanReviserSpec.kt` over scripted STRONG outputs: one fixture per action produces the right plan mutation (PRUNE removes a hypothesis subtree; PIVOT swaps an approach; DECOMPOSE splits a hypothesis into children; HALT stops). Assert invalid reviser output feedback-retries (reuse the Stage 2.1 validator + retry-cap pattern).

  Acceptance: spec compiles + fails.

- [ ] **T4 — `PlanReviser`.**

  Implement `revise/PlanReviser.kt` (STRONG tier): apply the chosen mutation, bump `PlanDag.revision`, **revision caps per `depth_budget`** (SHALLOW/NORMAL/DEEP bound the number of revisions). `on_plan_revision` policy → park **AWAITING_PLAN_REVISION_APPROVAL** (resume via `/approve-revision`; APPROVE applies, REJECT keeps the prior plan). Checkpoint on each revision (`reason: plan_revised`, Stage 1.2). Emit planning events (design §3.3).

  Acceptance: `PlanReviserSpec` green; revision-cap + approval-park specs green.

- [ ] **T5 — Loose-end derivation.**

  Implement loose-end derivation (design §3): a **PLANNING_TIME** sweep (hypotheses the plan chose not to test) + an **EXECUTION_TIME** orphan sweep (results/anomalies not tied to a conclusion), producing `LooseEnd`s (contracts §1) with a `suggested_followup`. Emit the loose-end events (design §3.3 loose-ends group, 2 events).

  Test: a fixture with an untested hypothesis + an orphan anomaly yields two `LooseEnd`s with followups.

  Acceptance: `LooseEndSpec` green.

- [ ] **T6 — Stop-condition spine.**

  Implement the stop-condition spine (design §3.5): the **5 stop reasons** (`StopReason` enum) + per-intent-kind completion criteria + the **four RCA brakes** (the RCA-specific guards that stop deepening — confirm the four against design §3/§4.2). The orchestrator consults this after each batch/evaluation to decide CONTINUE vs SYNTHESIZE. 

  Test: each stop reason is reachable from a crafted state; the RCA brakes each trip on their condition; a healthy investigation continues until goal-reached.

  Acceptance: `StopConditionSpec` green; `just test-kt pythia` green.

## DONE — Stage 3.2

- [ ] All tasks checked; suite green.
- [ ] The executor now uses real prioritisation; revision is depth-capped + approval-gated.
- [ ] Integration carry-overs recorded (live STRONG reviser quality → Phase 5 eval gate).
- [ ] CI green on `[pythia-p3-s3.2] reviser + prioritisation`.

## Library / pattern references

- **contracts §1** (`Hypothesis` scoring fields, `LooseEnd`, `StopReason`), **design §3.5 / §4.2** (stop conditions + RCA brakes — authority).
- **Stage 2.1** validator + retry-cap pattern (reused for reviser output).
- **Koog** structured output (reviser action schema).

## Out of scope

- Replay/reproduce + RCA e2e — Stage 3.3.
- Honest variance decomposition — v1.5 (Stage 3.3 heuristic).
- Cross-domain / master-of-Golems prioritisation context — Phase 5.
