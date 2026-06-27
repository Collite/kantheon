# Phase 2 — Axes & profiles

> **Reads with.** [`plan.md`](./plan.md) §"Phase 2", [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §2 (axes-first, presets-second) + §6 (security split) + §7.1 (offline tolerance) + §8 (observability), [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §5 (config schema — the axis/preset matrix), [`../../planning-conventions.md`](../../planning-conventions.md) §4 (mocked-unit testing policy).

## Phase deliverable (deployable)

The **axis model** + four presets (`local` / `personal` / `server` / `k8s`) resolve at boot and are `hebe doctor`-verifiable against `deployment/local` infra. `local` behaves **byte-for-byte** like pre-merge Hebe. The LLM-gateway path, the console-auth/platform-identity split, restricted tool posture, and OTel are all configurable. The one piece of genuinely new engineering — `personal` **offline tolerance** (outbox, missed-trigger catch-up, circuit-breaker, byok fallback) — lands and is component-tested. **No PG storage yet** (that is Phase 3). Tag **`hebe/v0.2.0`**.

> **The load-bearing invariant for the whole phase:** subsystems read **axes**, never `when(profile)`. The profile name only selects a bundle of axis defaults at boot. Every stage's tests assert *axis* behaviour; a small set of "preset smoke tests" assert that each profile wires the right axis values (test the axes, not the profiles).

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **2.1** — Axis model + presets | `local` regression-identical; axis resolution + override precedence + fail-fast assertions unit-green; `hebe doctor` skeleton | [`tasks-p2-s2.1-axis-model.md`](./tasks-p2-s2.1-axis-model.md) |
| **2.2** — LLM via llm-gateway | Chat turn through a Wiremock'd gateway recorded in `llm_calls` with cost headers; BYOK untouched under `local` | [`tasks-p2-s2.2-llm-gateway.md`](./tasks-p2-s2.2-llm-gateway.md) |
| **2.3** — Security: console-auth vs platform-identity | OIDC console login + OBO mint (both grant paths) green against Wiremock'd Keycloak; `SecretsStore` three impls | [`tasks-p2-s2.3-security-split.md`](./tasks-p2-s2.3-security-split.md) |
| **2.4** — Tool posture + OTel | Restricted posture blocks shell/kubectl/git/fs with refusal receipts; `observability` becomes an `otel-config` adapter | [`tasks-p2-s2.4-posture-otel.md`](./tasks-p2-s2.4-posture-otel.md) |
| **2.5** — Offline tolerance (`personal`) | Component test proves a `personal` Hebe survives a connectivity gap across a scheduled fire and reconciles; tag `hebe/v0.2.0` | [`tasks-p2-s2.5-offline-tolerance.md`](./tasks-p2-s2.5-offline-tolerance.md) |

## Sequencing

```
Stage 2.1 ──► 2.2 ──► 2.3 ──► 2.4 ──► 2.5
 axis model   gateway  auth split  posture+otel  offline tolerance (wraps 2.2+2.3)
```

2.1 is the foundation every later stage reads. 2.5 has a hard pre-flight on 2.2 (gateway client) and 2.3 (OBO) — the outbox wraps both.

## Pre-flight for the phase

- [ ] **Phase 1 DONE** (`hebe/v0.1.0`).
- [ ] `deployment/local` provides: a reachable **Keycloak** realm `kantheon` for dev (needed from 2.3), a local **llm-gateway** (or a Wiremock stand-in for unit work; live verification is deferred to the integration suite per planning-conventions §4), and the local Grafana/Alloy/Loki/Tempo stack (2.4 T6).
- [ ] Standalone seams confirmed present (from `standalone-v1-architecture.md`): `MemoryStore`, `WorkspaceStore`, `ReceiptsStore`, the secrets abstraction, the LLM provider behind `KoogLlmProvider`, the `ToolDispatcher`. Phase 2 is **axis plumbing over existing seams** + the §7.1 machinery — not re-architecture.

## Testing policy (applies to every stage)

Mocked unit/component only inside stages: MockK, in-memory fakes, **Wiremock** for the gateway and Keycloak. No Testcontainers, no live-infra gating in CI. Live verification (real gateway via `just debug-tunnel`, real Keycloak, the simulated intermittent host) is the **separate integration-test suite** and does **not** gate stage DONE. CI matrix discipline: unit-test each seam's impls + a handful of cheap preset smoke tests — do **not** fan the suite ×4 across profiles.

## Aggregate progress

- [x] **Stage 2.1** — Axis model + profile presets.
- [x] **Stage 2.2** — LLM via llm-gateway + cost attribution.
- [x] **Stage 2.3** — Security: console-auth vs platform-identity split.
- [x] **Stage 2.4** — Tool posture + OTel.
- [x] **Stage 2.5** — Offline tolerance (`personal`).

When all five are checked, push tag `hebe/v0.2.0` and move to Phase 3.

## Up / across

- Up: [`./README.md`](./README.md). Neighbours: [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`tasks-p3-overview.md`](./tasks-p3-overview.md).
