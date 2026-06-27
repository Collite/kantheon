# Stage 2.2 — LLM via llm-gateway + cost attribution

> **Phase 2, Stage 2.2.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §"Stage 2.2", [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §2.1 (`llm.source` axis) + §7.1 (the byok-fallback rule, implemented in 2.5), [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §5.2 (`[llm]` block, cost-attribution headers / PD-11).

## Goal

When `llm.source = gateway` (the `server`/`k8s` default; `personal` is `gateway_with_byok_fallback`), Hebe's reasoning loop targets the Kantheon **llm-gateway** through the **same OpenAI-compat client** it already uses for BYOK — only the base URL + auth header swap. Cost-attribution headers ride every gateway call (degrading gracefully if the gateway ignores them). The chat turn is recorded in `llm_calls` with cost where the gateway returns it. **BYOK under `local` is untouched.** Testing is mocked at the unit/component level (Wiremock'd gateway); live-gateway verification is deferred to the integration suite (planning-conventions §4).

## Pre-flight

- [x] **Stage 2.1 DONE** — `llm.source` axis resolves.
- [x] **Branch**: `feat/hebe-p2-s2.2-llm-gateway`.
- [x] Confirm the existing LLM provider seam (the OpenAI-compat client behind `KoogLlmProvider`) — the gateway path is a config of this client, not a new client. Locate it in `:agents:hebe:modules:providers`.

## Tasks

- [x] **T1 — Tests first: Wiremock'd gateway client.**

  Create `agents/hebe/modules/providers/src/test/kotlin/.../GatewayLlmProviderSpec.kt`. Stub an OpenAI-compat endpoint with Wiremock (`ktor-client` against the stub; pattern in `EXAMPLES.md` §9). Cover:

  - **Base-URL + auth swap** — `llm.source = gateway` points the client at `[llm].base_url`, sends the gateway key from `api_key_ref`, and hits `/v1/chat/completions`.
  - **Streaming** — SSE `data:` chunks assemble into the full completion (Hebe's loop streams).
  - **Tool use** — a tool-call response round-trips through the client (Hebe uses function-calling).
  - **Capability probe** — a `GET /v1/models` (or equivalent) returns the model list used by `doctor` (T5).

  Acceptance: specs written and failing. Commit `[hebe-p2-s2.2] failing gateway specs`.

- [x] **T2 — Gateway config defaults + secret-ref resolution.**

  Implement the `gateway` branch of the provider selection (driven by the `llm.source` axis — not a profile check). Resolve `[llm].base_url`, `default_model`, and the key from `api_key_ref` via the `SecretsStore` seam (`secret:` / `keychain:` schemes — the full three-impl `SecretsStore` lands in Stage 2.3; here, use the existing keychain impl + a thin `secret:`/`env:` resolver). Under `local`, the provider stays BYOK — assert the gateway branch is **not** selected.

  Acceptance: T1 base-URL/auth/streaming/tool-use specs pass.

- [x] **T3 — Cost-attribution headers (PD-11, graceful degrade).**

  Attach to every gateway request (contracts §5.2):

  - `X-Cost-Center: hebe/<instance_id>`
  - `X-Turn-Ref: <turn/job id>` (the firing job/turn id; empty for ad-hoc console chat)

  The gateway may ignore them — Hebe must not depend on a response echo. Add a spec asserting the headers are present on the outbound request and that a gateway response **without** any cost metadata still succeeds (graceful degrade).

  Acceptance: header spec green; no-metadata response handled.

- [x] **T4 — `llm_calls` cost capture.**

  When the gateway response carries usage/cost metadata (token counts, and cost if present), persist it to the existing `llm_calls` table (append-only, standalone §5). Where the gateway returns tokens but not a money figure, store tokens and leave cost null. Spec: a Wiremock response with usage metadata produces an `llm_calls` row with the right token counts + `X-Turn-Ref` linkage.

  Acceptance: `llm_calls` row written from a gateway turn; spec green.

- [x] **T5 — `doctor`: gateway reachability + model availability.**

  Register two checks into the Stage 2.1 doctor matrix: gateway endpoint reachable, and `default_model` present in the gateway's model list. Required when `platform.availability = always`; **probed** (DEGRADED on failure) when `intermittent`. Stub the probe in the unit test.

  Acceptance: `doctor` reports gateway health with correct required/probed semantics per axis.

- [x] **T6 — Component test: full gateway turn.**

  A component test wiring the provider + persistence: drive one chat turn through the Wiremock'd gateway (base-URL/auth swap, streaming, cost-header capture) and assert the conclusion plus the `llm_calls` row. Per planning-conventions §4 this is mocked at the component level; manual verification against the real local llm-gateway via `just debug-tunnel` is a deploy-time check, **deferred to the integration suite**.

  Acceptance: component test green. PR `[hebe-p2-s2.2] llm via gateway + cost`.

## DONE — Stage 2.2

- [x] All six tasks checked.
- [x] Chat turn through the Wiremock'd gateway recorded in `llm_calls` with cost at the component level.
- [x] BYOK path under `local` untouched (asserted).
- [x] Cost headers present + graceful degrade proven.
- [x] `doctor` gateway checks registered with required/probed split.
- [x] PR merged. (Live-gateway verification → integration suite.)

## Library / pattern references

- **EXAMPLES.md §9** — Kotest unit spec + Wiremock HTTP stub (the gateway stub pattern).
- **architecture.md §7.1** — the byok-fallback *rule* (Hebe's own routines fall back; constellation turns defer). The fallback **wiring** is Stage 2.5 T5; here only the `gateway` source is implemented.
- **contracts.md §5.2** — `[llm]` block + cost-attribution header names.

## Out of scope for Stage 2.2

- The byok-fallback runtime behaviour + circuit-breaker (Stage 2.5).
- The full three-impl `SecretsStore` (Stage 2.3) — here a minimal `secret:`/`keychain:` resolver suffices.
- Real-gateway integration verification (separate suite).
