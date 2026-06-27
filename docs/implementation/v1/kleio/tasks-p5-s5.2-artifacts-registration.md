# Stage 5.2 — artifacts + capabilities registration

> **Phase 5, Stage 5.2.** Branch `feat/docwh-p5-s5.2-artifacts-registration`.
>
> **Reads with.** [`tasks-p5-overview.md`](./tasks-p5-overview.md), [`plan.md`](./plan.md) §7 Stage 5.2, [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §8 (`ArtifactRequest`/`ArtifactKind`/`ArtifactResponse`) + §9 (additive `capabilities/v1` enums + Kleio's `AgentCapability`) + §3 (`notebook_artifacts`).

## Goal

Artifact generation (SUMMARY / FAQ / TIMELINE / BRIEFING) as cited map-reduce over a mart; the additive `capabilities/v1` enums (`KNOWLEDGE_QA` / `KNOWLEDGE`) land; Kleio registers as an `AgentCapability` (routable) + heartbeats; the Iris notebook picker binds a `notebook_id` into context. DONE = artifacts generate; Kleio registered + discoverable.

## Tasks (6)

- [ ] **T1 — Tests first: `ArtifactNodeSpec`.**

  Spec the artifact node: SUMMARY/FAQ/TIMELINE/BRIEFING each map-reduce over a mart (Wiremock'd Prometheus + mocked `getContext`) → a cited MARKDOWN/TABLE `envelope`. Every artifact claim is grounded in retrieved sources (the same grounding invariant as the turn; `sources_used` populated).

  Acceptance: spec written and failing. Commit `[docwh-p5-s5.2] failing artifact node spec`.

- [ ] **T2 — `ArtifactNode` + request/response; `notebook_artifacts` persistence.**

  Implement `nodes/ArtifactNode.kt` + the `ArtifactRequest`/`ArtifactResponse` handling (contracts §8). Persist generated artifacts to `notebook_artifacts` (contracts §3: artifact_id, notebook_id, kind, envelope jsonb, sources_used jsonb, created_by_user_id).

  Acceptance: T1 artifact spec green; an artifact persists one `notebook_artifacts` row.

- [ ] **T3 — `AgentCapability` registration (`KNOWLEDGE_QA`, `[KNOWLEDGE]`, router copy + few-shots).**

  Register Kleio's `AgentCapability` (contracts §9): `agent_kind = KNOWLEDGE_QA`, `agent_id = "kleio"`, `intent_kinds_supported = [KNOWLEDGE]`, `non_routable = false`, `visibility_roles` per the mart-set, `capability_refs = ["library.getContext:v1", …]`, router copy + few-shots. Heartbeat into capabilities-mcp.

  Acceptance: Kleio appears in capabilities-mcp `list`/`search`/`listAgents`; heartbeat ticks.

- [ ] **T4 — Additive enum landing: `KNOWLEDGE_QA` / `KNOWLEDGE` in `capabilities/v1`.**

  Add `KNOWLEDGE_QA = 4` to `AgentKind` and `KNOWLEDGE = 5` to `IntentKind` in `capabilities.proto` (contracts §9); regen; **confirm existing manifests unaffected** (proto3-additive — a regression test over the existing Golem/Pythia/Hebe manifests). Coordinate with Themis (the routing side is S5.3).

  Acceptance: enums added; existing manifests regression-green; codegen clean.

- [ ] **T5 — Iris surface: notebook picker → `listNotebooks` → bind `notebook_id`.**

  Wire the Iris notebook picker: `library.listNotebooks` returns the caller's visible marts (RLS, P4); the user's pick binds `notebook_id` into the turn context / `HandoffContext` (contracts §8 `KleioContext`). Soft Spine dependency — if the Iris surface isn't live, land the binding contract + a fixture-driven test.

  Acceptance: a picked notebook binds into `KleioRequest.notebook_id`; spec green.

- [ ] **T6 — Deploy; smoke (direct turn + artifact).**

  Deploy `agents/kleio` to local K3s; smoke a **direct** grounded turn + an artifact generation (Themis routing is S5.3 — call Kleio directly here). Deployment smoke.

  Acceptance: direct turn + artifact work in-cluster. PR `[docwh-p5-s5.2] artifacts + capabilities registration`.

## DONE — Stage 5.2

- [ ] All six tasks checked.
- [ ] Artifacts (SUMMARY/FAQ/TIMELINE/BRIEFING) map-reduce over a mart → cited envelopes; `notebook_artifacts` persisted.
- [ ] `KNOWLEDGE_QA`/`KNOWLEDGE` enums added (additive; existing manifests unaffected).
- [ ] Kleio registered (`agent_kind = KNOWLEDGE_QA`, routable) + heartbeating + discoverable.
- [ ] Iris notebook picker binds `notebook_id` (or the binding contract + fixture test if the surface isn't live).
- [ ] PR merged.

## Library / pattern references

- **contracts.md §8** — `ArtifactRequest`/`ArtifactKind`/`ArtifactResponse`. **§9** — the additive enums + Kleio's `AgentCapability` (authority for T3/T4). **§3** — `notebook_artifacts`.
- **EXAMPLES.md §4** — capabilities heartbeat. **§5** — Koog node (the `ArtifactNode`).

## Out of scope for Stage 5.2

- Themis `KNOWLEDGE` routing + counter-examples (Stage 5.3 — the enum is added here, the routing logic lands there).
- The eval corpus + the E2E smoke (Stage 5.3).
