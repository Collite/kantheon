# Golem Phase 2 · Stage 2.3 — plan composer + gate

> **Arc.** Golem Phase 2 (template core). **Branch.** `feat/golem-p2-s2.3-composer-gate`.
> **Companions.** [`plan.md`](./plan.md) §4 Stage 2.3, [`../../../architecture/golem/architecture.md`](../../../architecture/golem/architecture.md) §4 (graph mapping), golem/v1 `MiniPlan` (contracts §1).
> **Goal.** Composer + gate green on all five plan sources (fixture Resolutions); `GolemGraph` skeleton wires the composed nodes.
>
> **⚠ Parity carry-forward (2026-06-24, plan.md §10 Δ1/Δ4).** Closed against the 2026-06-12 snapshot. Current ai-platform Golem prompt set is **`intent.yaml` / `free-sql.yaml` / `chip-topup.yaml`** (language-neutral filenames; locale via `GOLEM_PROMPTS_GIT_SUBDIR`; `{{ name }}` placeholders), and the intent prompt renders a **`params:` line per catalog row** (load-bearing for parametrization). `pick_plan` also gained **`_detect_amend`** (arg-merge) and **`_bind_selection_args`** (row-detail `drill` source). Reconcile the bundled prompt scaffolds + composer when the S2.4 parametrization/selection rail lands.

## Decisions + plan-vs-reality deltas (stage start, 2026-06-18)

- **LLM client → shared lib + migrate themis** (Bora, 2026-06-18). Extract `shared/libs/kotlin/llm-gateway-client` (`LlmGatewayClient` + the Koog `LlmGatewayPromptExecutor`) from agents/themis; Golem's composer consumes it; themis is migrated to consume it too. Same pattern as the ariadne-client extraction (Stage 2.2 T1).
- **No `task_kind` / `GOLEM_PLAN` and no model-tier enum exist** (verified). "CHEAP" = the gateway client's flat tier string `"haiku"` (→ Prometheus `cheap`/`fast` tag). The composer calls the shared client with `model = "haiku"`. The plan.md "`task_kind: GOLEM_PLAN`" is aspirational — dropped; revisit if a routing concept lands.
- **`themis.v1.Resolution` has no `intent_kind` and no continuation field** (verified — it stops at `rationale`). Plan-source selection derives from `function_id` + `bindings` + **`GolemContext.prior_view`/`handoff`** (AMEND/DRILL only possible when a prior view is present) + the `AwaitingClarification` path — not from a `Resolution.intent_kind`. The LLM composer picks the source; the validator + gate enforce consistency.
- **Decode is StructureFixingParser-free** — mirror envelope-render's `RenderCallCodec` / themis's `JointInferenceNode`: fence-strip + `Json{ignoreUnknownKeys}` + manual field reads → `MiniPlan`.
- **Bundled prompts have no in-repo verbatim source** — legacy `intent-cs.yaml`/`free-sql-cs.yaml` live in the external `ai-models`/legacy golem repo. T2 seeds **structural** `golem-plan-cs.yaml` / `free-sql-cs.yaml` scaffolds (system/user, `{{ }}` placeholders) as the offline fallback, flagged for Bora to fill from the real cs prompts; the live set is served by Ariadne `get_prompts`.

## Tasks

- [x] **T1 — extract `shared/libs/kotlin/llm-gateway-client` + migrate themis.** Move `LlmGatewayClient` + `LlmGatewayPromptExecutor` (and the generic endpoint config + OpenAI-shaped DTOs they need) into the new lib under `org.tatrman.kantheon.llm.client`; repoint themis's ~13 usages; themis depends on the lib. **DONE when** `:agents:themis:test` stays green and `:shared:libs:kotlin:llm-gateway-client:build` passes.
- [x] **T5 — `PlanValidatorSpec` + `PlanValidator` (deterministic, can land first).** nodes in 1..`max_step_count` (default 4); unique non-blank node ids; linear deps (`input_node_ids` reference earlier nodes); PATTERN `QueryNode.pattern_id` exists in `PackageContext`; required pattern params (those without a `default_value`) present in `params_json`; `FREE_SQL` ⇒ `compile_first`. Returns structured violations.
- [x] **T6 — `PlanGateSpec` + `gatePlan` (deterministic, can land first).** Config-driven thresholds (defaults auto 0.95 / warn 0.85 / clarify 0.6): `≥auto` execute clean; `≥warn` execute + mild Rule-6 warning; `≥clarify` execute + low-confidence warning; `<clarify` → clarify. Surfaces `losing_plan_summary`.
- [x] **T2 — seed bundled prompts.** `agents/golem/src/main/resources/prompts/{golem-plan-cs,free-sql-cs}.yaml` structural scaffolds (system/user + `{{ }}`), flagged for Bora content. `chip-topup-cs.yaml` is Phase 3. `ClasspathPromptFallback` already names these three.
- [x] **T3 — `PlanComposerSpec` (tests first) + `PlanComposer`.** Five plan sources on fixture Resolutions + GolemContext (pattern hit, free-sql fallback, amend-prior-view, drill-from-row, clarify-on-ambiguity); mock `PromptExecutor`/gateway. Composer: CHEAP tier (`"haiku"`), build the plan prompt from PromptStore + Resolution + PackageContext, decode → `MiniPlan` via a codec (StructureFixingParser-free), single-shot.
- [x] **T4 — `GolemGraph` skeleton.** Koog `AIAgentStrategy` (themis node-port pattern) wiring `composePlan → gatePlan → {execute | emitClarification}` as a skeleton; execution/format land in Stage 2.4. Compiles + a smoke test of the composer→gate edge.

> Order note: T5/T6 (deterministic) land first — they need only `MiniPlan` + `PackageContext`, no LLM and no cross-module change. T1 (lib + themis migration) precedes T3 (composer needs the shared client). Stage runs to 6 tasks.

## DONE

Composer + gate green on all five sources; `GolemGraph` skeleton compiles and routes composed → gated. Tag deferred to Stage 2.4 (`golem/v0.1.0` at Phase 2 exit).
