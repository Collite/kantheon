# Stage 2.1 — Resolution + planner

> **Phase 2, Stage 2.1.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4 Stage 2.1, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §3 (`resolve/`, `plan/`) + §5, [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §1 (`PlanDag`/`PlanNode`/`DataDep`), [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) (INVESTIGATION_DEEP profile + `Resolution`), [`../../../design/pythia/Pythia-v1-Design.md`](../../../design/pythia/Pythia-v1-Design.md) §3.1/§4.1, [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

Investigations seed from the typed handoff (PD-1); `ThemisClient` resolves INVESTIGATION_DEEP, parking clarifications and failing on refusal; the STRONG-tier `PlanComposer` produces a **valid typed `PlanDag`** (or feedback-retries up to 3, then HALT); `PlanValidator` enforces typed preconditions; the plan-approval path parks AWAITING_PLAN_APPROVAL honouring `hitl_policy`. **End state:** `PlanComposerSpec` + `PlanValidatorSpec` green on scripted STRONG outputs; handoff-seeding spec green on a Golem-escalation fixture.

## Pre-flight

- [ ] Phase 2 pre-flight (overview): Themis `v0.2.0` + theseus-mcp reachable (tests Wiremock both); GatewayClient shim standing.
- [ ] Stage 1.3 orchestrator stubs in place — this stage replaces `resolveStub` + `planStub` with real subsystems.
- [ ] Branch `feat/pythia-p2-s2.1-resolution-planner`.
- [ ] Read **Koog** `PromptExecutor` + `StructureFixingParser` at `~/Dev/view-only/koog/` (query `graphify-out/`) — the structured-output path the composer uses. Confirm the 0.8.x API surface (`ai.koog:koog-agents`).

## Tasks (TDD-shaped)

- [ ] **T0 — Handoff seeding (PD-1).**

  Implement seeding from `Investigation.context.handoff` (`common.v1.HandoffContext`): the anchor query/view (`handoff.view` — pattern/sql/args behind what the user was looking at), `handoff.entities`, and a `source_turn_ref` carried through to the `Conclusion` back-link. The seeded anchor becomes the planner's starting context and the first candidate `QueryNode` base.

  Test: a **Golem-escalation fixture** (an `Investigation` whose `handoff` carries a view + entities) seeds an investigation whose resolution context and initial plan candidate reference the anchor; assert `Conclusion.evidence`/back-link will carry `source_turn_ref` (assert the field is populated at seed time).

  Acceptance: handoff-seeding spec green.

- [ ] **T1 — `ThemisClient` (INVESTIGATION_DEEP).**

  Implement `resolve/ThemisClient.kt`: call Themis with profile INVESTIGATION_DEEP, thread `themis_prior_context` (continuation), map outcomes:
  - `Resolution` (resolved) → proceed to PLANNING.
  - `AwaitingClarification` / ClarificationRequest → park **AWAITING_RESOLUTION_INPUT** (resume via `/answer`).
  - `RefusalWithGaps` → terminal **FAILED** with the gaps surfaced as Rule-6 messages.

  **Identity:** calls carry the user's **OBO bearer, never service identity** (PD-8, kantheon-security). Specs: **Wiremock** Themis returning each of the three outcomes; assert the orchestrator status after each. (Live Themis = integration suite.)

  Acceptance: `ThemisClientSpec` green for all three branches.

- [ ] **T2 — Planner prompt v1.**

  Author `prompts/planner.md` (cs + en) — produces a typed `PlanDag` via a **tool/structured-output schema** matching contracts §1 (`hypotheses[]`, `nodes[]` with the five `PlanNode` kinds, `edges[]` `DataDep`, `rationale`). Externalised prompt (architecture §3 `prompts/`). Input context: resolution + handoff anchor + available capabilities (capability list injected; full capabilities-mcp search is T5's validator concern). Constrain at this phase to **QueryNode + ReasoningNode + RenderNode** (DataFrame/Model light up in Phase 4) — the prompt must degrade to SQL-only plans.

  Acceptance: prompt file present in both locales; the structured-output schema is checked in alongside (a Kotlin data class + Koog parser binding).

- [ ] **T3 — `PlanComposerSpec` (tests first).**

  Create `src/test/kotlin/.../plan/PlanComposerSpec.kt` driving the composer over **scripted STRONG LLM outputs** (fixtures, no live call):
  - Valid output → a well-formed `PlanDag` (assert node/edge shape).
  - Invalid output (e.g. dangling `DataDep`, unknown node kind) → triggers a **feedback-retry** (the validator's error is fed back into a re-prompt).
  - **Max-3 retries** exhausted → orchestrator **HALT** with a Rule-6 explanation.

  Acceptance: spec compiles + fails (no composer yet).

- [ ] **T4 — `PlanComposer`.**

  Implement `plan/PlanComposer.kt` using Koog `PromptExecutor` + `StructureFixingParser` (the structured-output coercion). task_kind = PLANNING, tier = STRONG (via `GatewayClient`, contracts §5). Feedback-retry loop bounded at 3, each retry appends the `PlanValidator` errors. Emits `plan_drafted`.

  Acceptance: `PlanComposerSpec` green.

- [ ] **T5 — `PlanValidator`.**

  Implement `plan/PlanValidator.kt`: typed preconditions (every `DataDep.from/to` resolves to a node; `binding` type-compat between producer output kind and consumer input kind), **capability existence** (each `QueryNode.queryRef` / node capability checked via `CapabilitiesReadClient` search against capabilities-mcp — Wiremock in tests), depth caps (`max_step_count`, `depth_budget`). Returns structured errors consumed by the T4 retry loop.

  Test: a plan with a missing capability, a type-mismatched binding, and an over-depth plan each produce the right structured error; a clean plan validates.

  Acceptance: `PlanValidatorSpec` green.

- [ ] **T6 — Approval path wired (AWAITING_PLAN_APPROVAL).**

  Wire the post-validation gate: if `hitl_policy.plan_approval` requires it, emit `plan_drafted` + park **AWAITING_PLAN_APPROVAL**; `/approve-plan` (APPROVE → EXECUTING; REJECT_WITH_COMMENT → re-plan with the comment as feedback, counting against the retry cap). Auto-approve path when policy doesn't require approval.

  Test (`testApplication` + mocked composer): policy=require → parks; APPROVE → proceeds; REJECT_WITH_COMMENT → re-plans once.

  Acceptance: approval-path component spec green.

## DONE — Stage 2.1

- [ ] All tasks checked; `just test-kt pythia` green.
- [ ] The `PlanDag` contract is frozen for Stage 2.2's executor to consume.
- [ ] Integration carry-overs recorded (live Themis resolution, live STRONG planner quality — the latter also feeds the Phase 5 eval gate).
- [ ] CI green on `[pythia-p2-s2.1] resolution + planner`.

## Library / pattern references

- **Koog 0.8.x** `~/Dev/view-only/koog/` — `PromptExecutor`, `StructureFixingParser` (structured output). Query `graphify-out/` for the call sites.
- **contracts §1** (`PlanDag`/`PlanNode`/`DataDep` shapes), **§5** (GatewayClient tier mapping), **themis/contracts.md** (Resolution outcomes).
- **kantheon-security** §3.x — OBO bearer on Themis calls.

## Out of scope

- DataFrame/Model planning — Phase 4 Stage 4.1 (planner gains those capabilities then).
- Plan **revision** (PRUNE/PIVOT/DECOMPOSE) — Phase 3 Stage 3.2 (this stage is initial composition only).
- Executing the plan — Stage 2.2/2.3.
