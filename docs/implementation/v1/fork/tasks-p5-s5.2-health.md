# Fork — Stage 5.2: health service

> Branch: `feat/fork-p5-s5.2-health`. Pre-flight: Stage 5.0 (`infra/` accepted; both health deps — `otel-config`, `ktor-configurator` — already in-repo from Phase 1.3). Plan: [`plan.md`](./plan.md) Stage 5.2. Tracker: [`tasks.md`](./tasks.md).
>
> Source: `infra/health` (Kotlin/Ktor; TCP / Prometheus / native checkers → roll-up endpoint the landing page reads). Self-contained, **cleanest of the four**. Package `com.platform.health.*` → `org.tatrman.health.*`. Port 7000 (kept). Contracts §1, §7.1.

- [x] **T1 — Fork the module + package sweep.**
  `infra/health` → kantheon `infra/health`; included in `settings.gradle.kts`; provenance README. Swept `com.platform.health.*` → `org.tatrman.health.*`; Jib `mainClass` → `org.tatrman.health.ApplicationKt`, image → `health:dev` (kantheon convention), container port 7000, engine CIO. Deps → in-repo `otel-config`, `ktor-configurator` (+ ktor server/client, caffeine, coroutines, serialization).

- [x] **T2 — Forked suite green.**
  `PrometheusHealthCheckerTest` (PromQL percent-encoding regression + non-1 metric → unhealthy, `ktor-client-mock`) forked **unchanged** (sweep aside). `ConfigLoaderTest` forked then **re-pointed in lockstep with T3** (its asserted technology keys are config-driven, so they move with the config — not a test softened to pass).

- [x] **T3 — Re-point check targets.**
  `application.conf` rewritten: the legacy ai-platform / erp-sql targets (`metadata`, `sql-*`, `fuzzy-matcher`, `meta-mcp`, `fuzzy-mcp`, `erp-data-mcp`, `erp-agent-2`, `llm-gateway`) **dropped**; replaced by the **kantheon constellation** (iris-bff/themis/golem; ariadne/theseus/echo/kadmos/proteus/prometheus-gateway/argos/kyklop/charon/metis; brontes/steropes/arges; the MCP wrappers; whois/backstage) addressed by bare in-namespace names, plus the retained shared **fabric-infra** (data/monitoring/auth/gateway/middleware) namespace-qualified. `platform-postgres`→`kantheon-postgres`. A new `ConfigLoaderTest` case asserts **no `sql-*` / legacy key survives**. The roll-up landing (5.4) consumes this.

- [x] **T4 — k8s + deploy.**
  Argos-mirrored Helm chart (`helm template` clean). Single HTTP port 7000. `installKtorServerBase` adds no self `/health` route, so the pod probe uses `/health/all?threshold=0` (200 whenever the aggregator itself is alive, independent of downstream target state). Jib `health:dev`; `just deploy-kt infra/health` resolves (Jib build CI-gated on Rancher).

- [x] **T5 — component test: roll-up.**
  `HealthRollupComponentSpec` (mocked targets): one up (WireMock 200) + one configured-but-down (unreachable port) → `/health/all` reports total 2 / healthy 1 / unhealthy 1 and overall "unhealthy" (503) at the 100% threshold; per-technology `/health/{tech}` returns 200/503/404. **9 tests green** across the module. Live-service e2e deferred to the integration suite.

- [x] **T6 — Stage exit.**
  `:infra:health:test` (9) + `:infra:health:ktlintCheck` green. **Tag `health/v0.1.0` on merge.** Stage 5.2 checked in [`tasks.md`](./tasks.md).

**DONE means:** health aggregates kantheon service status. **✅ Met 2026-06-24** (engineering complete; live-on-K3s confirmation rides the cluster deploy).
