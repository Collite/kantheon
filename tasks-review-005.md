# Tasks — Review 005 (Fork Stage 2.2 / Echo)

> Source: [`review-005.md`](./review-005.md). Branch: `feat/fork-p2-s2.2-echo`.
> **Stage 2.2 passed review — no blockers.** These are the two cosmetic cleanups only; fold into the next commit or carry into Stage 2.3. Nothing here gates the stage.

## 🟢 Optional cleanups

- [ ] **C1 — Rename the stale telemetry file.** `services/echo/src/main/kotlin/org/tatrman/kantheon/echo/telemetry/FuzzyMatcherTelemetry.kt` → `EchoTelemetry.kt` (the class inside is already `EchoTelemetry`; only the filename lags). Leave `core/FuzzyMatchResult.kt` and the `FuzzyMatch*` algorithm/domain types as-is — those are intentionally kept (algorithm terminology, not persona).
- [ ] **C2 — Load the manifest once at startup.** In `tools/echo-mcp/src/main/kotlin/org/tatrman/kantheon/echo/mcp/Application.kt`, `registerWithCapabilities` calls `ManifestLoader().loadAll()` twice (the `logger.info` arg + the `val manifests` line). Hoist to a single `val manifests = ManifestLoader().loadAll()` and log `manifests.size`.

## Carry-forward note for Stage 2.3+ (not a task)

- The live-K3s round-trip and the `echo/v0.1.0` / `echo-mcp/v0.1.0` tags are deferred to the deploy pipeline (same accepted precedent as Ariadne T5/T7). Tags don't exist yet — create them when the cluster deploy actually runs, alongside `ariadne/v0.1.0` + `ariadne-mcp/v0.1.0`.
