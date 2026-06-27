# Stage 2.1 — `integrationTest` source set + readiness gate

> **Phase 2, Stage 2.1.** The kantheon-side harness — exists, gates by context name, skips cleanly when no cluster is present. Kantheon-only; no olymp dependency yet.
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`../../../architecture/testing/architecture.md`](../../../architecture/testing/architecture.md) §7 (lifecycle) + §3 (ownership), [`../../../architecture/testing/contracts.md`](../../../architecture/testing/contracts.md) §2 + §4, [`shared/libs/kotlin/integration-harness/README.md`](../../../../shared/libs/kotlin/integration-harness/README.md) (the authoring recipe).
>
> **Status.** ✅ Done 2026-06-20 (Bora / Claude). All logic unit/component-tested locally; the live-cluster path is exercised in Stage 2.2/2.3.

## Goal

A `integrationTest` source set + task that accepts `-Pcontext`/`-Pnamespace` and **skips** without them; a `@RequiresContext` gate that asserts the named context is up and **fails fast** (never self-deploys); a `ContextHandle` exposing resolved in-cluster URLs; a `ContextNameRegistrySpec` catching drift vs olymp's `test-contexts/`.

## Tasks

- [x] **T1 — `integrationTest` source set + task (skips without context).**
  Added to the root convention (alongside `componentTest`) via the JVM Test
  Suite plugin: every Kotlin module gets `src/integrationTest/kotlin` + an
  `integrationTest` task. The task `onlyIf { -Pcontext present }` (so
  `./gradlew check` / local builds never need a cluster) and forwards
  `context`/`namespace`/`olympDir` to the test JVM as system properties.
  Catalog: `fabric8-kubernetes-client = 7.7.0`. Verified: no props → **SKIPPED**;
  `-Pcontext=…` with no specs → NO-SOURCE (no failure).

- [x] **T2 — `@RequiresContext` gate (fail-fast), unit-tested vs a mocked k8s port.**
  `ContextGate(ClusterReader)` resolves namespace (explicit `-Pnamespace` →
  sysprop, else by `olymp.collite/context` label), asserts each readiness check,
  returns a `ContextHandle` or throws `ContextNotReadyException` — **never
  provisions**. `RequiresContextExtension` is the Kotest `BeforeSpecListener`
  wrapping it (registered via `@ApplyExtension` — Kotest 6 removed `@AutoScan`).
  `ContextGateSpec` (5) covers ready / no-namespace / not-ready / no-checks /
  explicit-ns; `RequiresContextExtensionSpec` (2) covers the listener wiring +
  the "no annotation → cluster untouched" path. **Read-only is structural**: the
  `ClusterReader` port has no write methods.

- [x] **T3 — `ContextHandle` injection.**
  `ContextHandle` exposes `namespace`, `url(service)` (`http://<svc>.<ns>.svc:<port>`,
  resolved read-only), and `wireMockAdmin`. Read in a spec via `spec.contextHandle()`.

- [x] **T4 — `ContextNameRegistrySpec` (drift guard, component-tier).**
  `@Tags("component") @EnabledIf(OlympCheckoutPresent)` — scans `src/integrationTest/`
  for `@RequiresContext("…")` and asserts each name has `test-contexts/<name>/context.yaml`
  under `-PolympDir`. Skips with no olymp checkout (PR component runs). Verified
  both ways: green with a checkout + no orphans; **red** when a throwaway
  `@RequiresContext("nope")` is introduced.

- [x] **T5 — In-cluster WireMock loader.**
  Reuses `component-testkit`'s `WireMockAdmin` against `handle.wireMockAdmin`.
  `InClusterWireMockLoaderSpec` proves the `ContextHandle → WireMockAdmin →
  import/serve/journal/reset` path against a WireMock **container** (no cluster).
  Fixture layout seeded at `…/resources/wiremock/<context>/<scenario>/` per contracts §3.1.

- [x] **T6 — Harness lib + docs.**
  New `shared/libs/kotlin/integration-harness` (consumed via
  `integrationTestImplementation`); README documents the authoring recipe so a
  second service adds a spec by depending + annotating — no gate-logic copy-paste.

## DONE criteria

- [x] `./gradlew :…:integrationTest` **skips** with no props; NO-SOURCE/runs with `-Pcontext`.
- [x] gate + `ContextHandle` resolution unit-tested vs a fake k8s API; read-only by construction.
- [x] `ContextNameRegistrySpec` green vs an olymp checkout; red on an orphan name.
- [x] In-cluster WireMock loader proven against a WireMock container.
- [x] `test-all` (158 tasks) green; the isolation guard now excludes **both** component & integration tiers from the unit `test` classpath.

## Notes

- The drift guard runs where olymp is checked out (the nightly / a dedicated step with `-PolympDir=`); the PR `test-component` step has no olymp checkout, so it skips there — by design.
- `@RequiresContext` registration is `@ApplyExtension(RequiresContextExtension::class)` (Kotest 6 has no classpath scanning). The README shows the full spec shape.
- Live-cluster reachability of the in-cluster `url(...)` is a Stage 2.2/2.3 concern (the gate itself only needs the k8s API, reachable via kubeconfig).
