# Phase 2 — Integration harness + first context

> **Reads with.** [`plan.md`](./plan.md) (Phase 2), [`../../../architecture/testing/architecture.md`](../../../architecture/testing/architecture.md) §3–§8, [`../../../architecture/testing/contracts.md`](../../../architecture/testing/contracts.md), olymp [`docs/test-harness.md`](../../../../../collite-gh/olymp/docs/test-harness.md), [`../../planning-conventions.md`](../../planning-conventions.md).
>
> **Phase deliverable.** The full cross-repo loop runs green for one context: `just infra-up theseus-runquery` (olymp, scripted, **non-ArgoCD**) → `:integrationTest` (kantheon, fail-fast readiness gate + WireMock runtime-load) → `infra-down` (always, via trap). Driven nightly by `integration-nightly.yml`.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **2.1** — `integrationTest` source set + readiness gate | `./gradlew :…:integrationTest` **skips** with no `-Pcontext`; `@RequiresContext` gate + `ContextHandle` unit-tested against a fake k8s API; `ContextNameRegistrySpec` green | [`tasks-p2-s2.1-integration-harness.md`](./tasks-p2-s2.1-integration-harness.md) |
| **2.2** — `theseus-runquery` specs + fixtures | `RunQueryIntegrationSpec` compiles + is gated by `@RequiresContext`; WireMock fixtures for Prometheus + modeler present | [`tasks-p2-s2.2-theseus-runquery-specs.md`](./tasks-p2-s2.2-theseus-runquery-specs.md) |
| **2.3** — Cross-repo bring-up + nightly workflow | One green nightly end-to-end; a broken service turns nightly red with no leaked namespace | [`tasks-p2-s2.3-nightly-integration.md`](./tasks-p2-s2.3-nightly-integration.md) |

## Sequencing

```
Stage 2.1 ──► Stage 2.2 ──► Stage 2.3
  harness        specs+fixtures   nightly (needs olymp Phase A)
```

2.1 and 2.2 are kantheon-only and can overlap. **2.3 is cross-repo:** it cannot close until **olymp test-harness Phase A** is live (cluster mode + `infra-up/down` + `theseus-runquery` context + WireMock `platform/` component + namespace handshake).

## Pre-flight for the phase

- [ ] Phase 1 closed.
- [ ] **olymp test-harness Phase A** scheduled/landed (olymp `docs/test-harness.md` Stages 7.1–7.5) — gates Stage 2.3 only.
- [ ] The forked `run_query` chain (Theseus/Proteus/Argos/Kyklop/Brontes) builds (it does as of fork Phases 1–4, 2026-06-17).
- [ ] `Collite/olymp` read credential available as a CI secret (distinct from the `Collite/modeler` packages PAT).

## Aggregate progress

- [ ] **Stage 2.1** — `integrationTest` source set, `@RequiresContext` gate, `ContextHandle`, `ContextNameRegistrySpec`, in-cluster WireMock loader.
- [ ] **Stage 2.2** — `RunQueryIntegrationSpec` + OBO/RLS assertions + WireMock fixtures + request-journal verification.
- [ ] **Stage 2.3** — namespace handshake integration, `integration-nightly.yml`, olymp checkout, first green nightly, failure-UX (issue on red).

When all three are checked, Phase 2 is DONE: one context runs end-to-end nightly with no ArgoCD and no namespace leaks.
