# Stage 1.1 — `pythia/v1` proto

> **Phase 1, Stage 1.1.** First task list of the Pythia arc.
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3.1, [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §1 (proto skeleton — the authority for this stage), [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §5, [`../../../design/pythia/Pythia-v1-Design.md`](../../../design/pythia/Pythia-v1-Design.md) §3.1–3.3 (the semantic authority — every field this stage adds must trace to a design §3 field), [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

`shared/proto/.../pythia/v1/pythia.proto` is **field-complete** per contracts §1 expanded to the full design §3.1–3.3 vocabulary; `just proto` regenerates Kotlin bindings that compile; serialisation round-trips (incl. forward-compat unknown-enum tolerance) pass; three abbreviated golden-JSON artifact fixtures exist for the worked examples. **End state:** `./gradlew :shared:proto:assemble` green; `just test-kt shared:proto` (round-trip specs) green.

## Pre-flight

- [ ] Phase pre-flight (see [`tasks-p1-overview.md`](./tasks-p1-overview.md)) — `common/v1`, `envelope/v1`, `themis/v1` proto packages already generate in `shared/proto` (Iris/Themis/Golem arcs landed them). Verify: `ls shared/proto/src/main/proto/org/tatrman/kantheon/{common,envelope,themis}/v1/`.
- [ ] Branch `feat/pythia-p1-s1.1-proto` from `main`.
- [ ] Read design §3.1 (Investigation request), §3.2 (InvestigationArtifact, PlanDag, Hypothesis, StepRecord, Handle, Conclusion, LooseEnd, ResourceUsage), §3.3 (the event vocabulary). **This stage is a faithful proto projection of those three sections** — contracts §1 is the abbreviated skeleton, design §3 is field-complete.

## Tasks (TDD-shaped: T1 writes the proto; T2–T3 are the tests that pin it; T4–T5 docs + fixtures)

- [ ] **T1 — Write `pythia.proto` field-complete.**

  Create `shared/proto/src/main/proto/org/tatrman/kantheon/pythia/v1/pythia.proto`. Header exactly:
  ```proto
  syntax = "proto3";
  package org.tatrman.kantheon.pythia.v1;

  import "org/tatrman/kantheon/envelope/v1/envelope.proto";
  import "org/tatrman/kantheon/themis/v1/themis.proto";
  import "org/tatrman/kantheon/common/v1/handoff.proto";
  import "org/tatrman/kantheon/common/v1/response_message.proto";
  ```
  Expand the contracts §1 skeleton to field-complete. Required message types and the design §3 section each traces to:
  - `Investigation` (§3.1) — incl. `Caller{ kind: IRIS|HEBE|API|SCHEDULED, user_id, tenant_id, correlation_id }`, `InvestigationContext{ entity_context, conversation_excerpt, locale, themis_prior_context, handoff (common.v1.HandoffContext) }`, `StyleHint`, `ScenarioSpec`, `Constraints{ max_llm_cost_usd, max_llm_tokens, latency_budget_ms, max_step_count, depth_budget: SHALLOW|NORMAL|DEEP }`, `HitlPolicy{ plan_approval, on_suspicious_result, on_plan_revision, on_budget_threshold, disambiguation }`, `LlmOverrides`.
  - `InvestigationArtifact` (§3.2) — all 14 fields + `repeated common.v1.ResponseMessage messages = 99;` (Rule 6, field number reserved across the board — see [`../../../AGENTS.md`](../../../AGENTS.md#wire-format-rules)).
  - `Status` enum — **all 12 values**, `_UNSPECIFIED = 0` first, the **five** AWAITING_*: `AWAITING_RESOLUTION_INPUT`, `AWAITING_PLAN_APPROVAL`, `AWAITING_PLAN_REVISION_APPROVAL`, `AWAITING_USER_INPUT`, `AWAITING_BUDGET_DECISION` (PD-11). Plus `SUBMITTED`, `RESOLVING`, `PLANNING`, `EXECUTING`, `SYNTHESIZING`, `DONE`, `FAILED`/`HALTED`/`INCONCLUSIVE` per design §3.4 (confirm the exact terminal set against design §3.4 — that table is authoritative).
  - `PlanDag`, `PlanNode` (oneof: `QueryNode`/`DataFrameNode`/`ModelNode`/`ReasoningNode`/`RenderNode` — field numbers 3–7 fixed per contracts §1), `DataDep`.
  - `Hypothesis` (§3.2 — verbatim fields incl. `estimated_explanatory_power`, `diagnostic_power`, `DisplayPriority`), `EvidenceLink`, `Predicate` (incl. predicate kinds `ROW_COUNT_*`, `METRIC_DELTA_RATIO`, `NULL_RATE_LT`, `CORRELATION_STRENGTH` — these are exercised by the evaluator in Phase 2 Stage 2.3, so the enum must be complete now).
  - `StepRecord` (§3.2 — incl. `cost{ tier_used, cached }` and `error{ recoverable }`).
  - `Handle` oneof — **six** kinds with **fixed field numbers** (contracts §1, §9 divergences 1/1b): `LiveQueryRef=1`, `PgResultSnapshot=2`, `WorkerSessionDF=3` (fields `worker_pod, session_id, df_name` — `df_name` not `df_id`, divergence 5), `SeaweedArrowBlob=4`, `RedisArrowEntry=5`, `DbTableRef=6`. Include `PgResultSnapshot` carrying inline Arrow IPC bytes (capped at runtime by `pythia.handles.inline-max-bytes`).
  - `Conclusion`, `RenderableArtifact{ repeated envelope.v1.Block blocks }`, `Finding`, `LooseEnd`, `ResourceUsage`, `ConfidenceInfo`, `StopReason` enum (5 reasons per design §3 — `STOP_GOAL_REACHED`, `STOP_HARD_CAP`, `STOP_USER`, etc.; confirm full set against design §3.5/§4).
  - `InvestigationEvent{ investigation_id, sequence (int64, PG-assigned), emitted_at, oneof event }` — the **full design §3.3 vocabulary**, one message type per event kind: lifecycle (3), resolution (4), planning (4), hypotheses (6), prioritisation (2), execution (6), suspicion/findings (2), loose ends (2), budget (3), synthesis (4), conclusion (1). Enumerate each from design §3.3; do not collapse.
  - `InvestigationLifecycleEvent{ investigation_id, user_id, old_status, new_status, ts }` (contracts §3 — published on the coarse `pythia.lifecycle.{user_id}` subject).
  - `InvestigationSummary{ id, question, status, created_at, updated_at, resource_usage, caller }` (contracts §2 — the `GET /v1/investigations` list row, PD-2).

  **Enum discipline:** every enum carries `_UNSPECIFIED = 0` (field-number policy, contracts §1). **Rule 7** (function-call args ride as `string argsJson`, camelCase keys — AGENTS.md) applies to `QueryNode.params_json` / `ReasoningNode` arg passing: model those as `string`, not `google.protobuf.Struct`.

  Acceptance: file parses under `protoc`; all six `Handle` kinds and all five AWAITING_* present; `grep -c "AWAITING_" pythia.proto` ≥ 5.

- [ ] **T2 — `just proto`; KT bindings compile.**

  Add `:shared:proto` proto-task config if a sub-include is needed (the package is picked up automatically by the existing `protobuf {}` block — see [`../themis/tasks-p1-s1.1-repo-bootstrap.md`](../themis/tasks-p1-s1.1-repo-bootstrap.md) T5 for the `protobuf-gradle-plugin` shape). Run `just proto` then `./gradlew :shared:proto:assemble`.

  Acceptance: generated Kotlin appears under `shared/proto/build/generated/source/proto/main/kotlin/org/tatrman/kantheon/pythia/v1/`; `./gradlew :shared:proto:compileKotlin` green; no import-resolution errors against `envelope/v1`, `themis/v1`, `common/v1`.

- [ ] **T3 — Serialisation round-trip specs (tests-first pin).**

  Create `shared/proto/src/test/kotlin/org/tatrman/kantheon/pythia/v1/PythiaProtoRoundTripSpec.kt` (Kotest `StringSpec`). Cover:
  - Build a full `InvestigationArtifact` (one of each `PlanNode` kind, one of each `Handle` kind, ≥2 hypotheses, a `Conclusion` with one `envelope.v1.Block`), serialise to bytes, parse back, assert structural equality.
  - **Unknown-enum tolerance:** craft a wire message whose `Status` field carries an int value beyond the known set (e.g. 99), parse it, assert it round-trips as `UNRECOGNIZED` without throwing (proto3 forward-compat — verify the generated Kotlin exposes `*_UNRECOGNIZED`). Same for `StopReason`.
  - JSON projection: `JsonFormat`-equivalent serialise/parse of one artifact (the golden fixtures in T5 are the canonical examples).
  - `messages = 99` survives round-trip on the artifact.

  Use the kantheon serialization idiom from [`../../../EXAMPLES.md`](../../../EXAMPLES.md) (ai-platform `EXAMPLES.md` §2 — kotlinx + proto). Acceptance: `just test-kt shared:proto` green.

- [ ] **T4 — Proto-doc comments matching design prose.**

  Add `//` doc comments to every message and to the non-obvious fields, paraphrasing the design §3 prose (e.g. on `Status` enumerate what parks each AWAITING_* and which control endpoint resumes it — cross-ref contracts §2; on `Handle.PgResultSnapshot` note "divergence 1, Pythia-private, capped inline Arrow"). Keep comments terse; they are the proto's self-documentation for downstream consumers (iris-bff, eval harness).

  Acceptance: every top-level message has a leading doc comment; `protoc` still parses.

- [ ] **T5 — Golden JSON fixtures for the three worked examples.**

  Create hand-built, **abbreviated** artifact fixtures (status `DONE`, a few steps/hypotheses each — not full traces) under `shared/proto/src/test/resources/golden/`:
  - `nescafe-maggi-artifact.json` (procedural — design §4.1)
  - `rca-channel-artifact.json` (RCA — design §4.2)
  - `forecast-artifact.json` (forecast — design §4.3)

  Each must parse into `InvestigationArtifact` (assert in a `GoldenFixtureSpec.kt` that iterates the directory). These become the reference inputs for the renderer/synth specs in Phase 2 Stage 2.4 and the e2e fixtures in 2.4/3.3 — keep field names exactly as generated.

  Acceptance: `GoldenFixtureSpec` parses all three with zero unknown-field warnings.

## DONE — Stage 1.1

- [ ] All tasks above checked.
- [ ] `just proto && ./gradlew :shared:proto:assemble && just test-kt shared:proto` all green on a clean checkout.
- [ ] CI green on the PR `[pythia-p1-s1.1] pythia/v1 proto`.
- [ ] No `cz.dfpartner.*` imports introduced (kantheon protos import only `org.tatrman.kantheon.*` — CLAUDE.md §4).

## Library / pattern references

- **contracts §1** — the proto skeleton (authority); **design §3.1–3.3** — field-complete semantics.
- **ai-platform `EXAMPLES.md` §2** — proto + kotlinx serialization patterns; **CLAUDE.md §4 / AGENTS.md Wire-format rules** — Rule 6 (`messages = 99`), Rule 7 (`argsJson`).
- **Reference task list** — [`../themis/tasks-p1-s1.1-repo-bootstrap.md`](../themis/tasks-p1-s1.1-repo-bootstrap.md) T5 for the `protobuf-gradle-plugin` Kotlin codegen config.

## Out of scope for Stage 1.1

- jOOQ / Flyway / repositories — Stage 1.2.
- Any runtime logic (orchestrator, REST handlers) — Stage 1.3.
- Charon/Metis `ToolCapability` schemas — those are Phase 4 / sibling arcs (contracts §6/§7).
