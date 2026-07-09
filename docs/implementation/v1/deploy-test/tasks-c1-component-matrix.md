# WS-C Stage 1 — Component-tier real-dep matrix

> **Workstream C (Test suite), Stage 1.** Broaden the component tier (real backing deps in Testcontainers, no cluster) from the two existing specs (Brontes ✓, Midas ✓) to the full real-dep matrix. **Startable immediately** — no cluster, no olymp. TDD-first by nature (the spec *is* the deliverable).
>
> **Reads with.** [`tasks-overview.md`](./tasks-overview.md) §0, [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §5 (the matrix), [`../testing/plan.md`](../testing/plan.md) (Phase 1, the existing plumbing).
> **Existing plumbing.** `componentTest` source set, `@Tag("component")`, `just test-component`, `shared/libs/kotlin/component-testkit` (`Containers.{postgres,mssql,wiremock}`, `WireMockAdmin`, `SqlScripts`, `CiOnly`). Repo: **[K]** kantheon.

## Goal

Each high-value real-dep seam has a green `src/componentTest` spec running in CI on every PR; `just test-all` still collects **zero** `@Tag("component")` (the mocked-only merge gate holds).

## Pre-flight

- [ ] Docker available locally + in CI (✓ — Brontes spec already runs in CI).
- [ ] Each target module builds.
- [ ] Branch `feat/c1-component-matrix` (or per-spec branches).

> **STATUS: DONE — MP-3 closed 2026-07-09.** T1–T5 landed (7 specs across arges/proteus/argos/
> ariadne/report-renderer), the matrix runs via `just test-component` in CI on **every PR + merge**
> (`.github/workflows/ci.yml` `test-component` step), and the mocked-only `test-all` gate still
> collects zero `@Tag("component")` (root `build.gradle.kts` leak guard). Latest master CI green
> (incl. `test-component`). T6 (Prometheus) + the Charon/Kleio/Hebe rows stay **deferred as arc
> follow-ups** (contracts §5) — not MP-3 blockers per master-plan §5 (MP-3 = "component matrix green
> in CI"). The one CI-only wrinkle fixed post-landing: `AriadneModelLoadComponentSpec` resolved the
> bundled model via a `jar:` classpath URI (fine locally, `FileSystemNotFoundException` on CI where
> the module rides the suite classpath as a JAR) → now fed the authored resources dir on disk.

## Tasks (each = write the spec first, run `just test-component` locally + CI)

- [x] **T1 — `ArgesPostgresComponentSpec`.** `Containers.postgres()` seeded with a **TPC-DS subset** (a few small tables via `SqlScripts`): assert the PG type mapper (incl. `numeric` boundary), the trailing-pipe-safe load (shared with WS-T1 T1), read-only execution, and **no** RLS envelope when `requires-tenant-id=false` (the `pg-tpcds` case) vs the `SET LOCAL app.tenant_id` path when true. *(Highest value — it underpins the `tpcds-query` context.)*
- [x] **T2 — `ProteusUnparseComponentSpec`.** No container — golden SQL. Assert RelNode → **PostgreSQL** for the four TPC-DS curated-query shapes (join+agg, agg+limit, **window**, **CTE**) + the named-parameter `{name}→?` rewrite. Golden files under `src/componentTest/resources/proteus/`. *(The Proteus PG-unparse exercise; pairs with WS-T2.)*
- [x] **T3 — `ArgosRlsComponentSpec`.** `Containers.postgres()` (and/or mssql, CI-only): a policy denies cross-tenant rows end-to-end; a permitted query returns rows. Asserts the RLS/policy enforcement against a real DB.
- [x] **T4 — `AriadneModelLoadComponentSpec`.** *(investment model deferred — unauthored; lands with Midas. CI jar-URI fix applied 2026-07-09.)* Classpath/fixture models: load the TPC-DS + investment + ucetnictvi models; assert `ListQueries` returns the curated sets and `ResolveArea("tpcds"/"investment")` resolves packages. *(Guards the WS-T2 / Midas-S3.1 model authoring.)*
- [x] **T5 — `ReportRendererComponentSpec`.** *(XLSX engine only — PPTX/PDF/HTML deferred: engines + Playwright unwired, no vendored templates.)*
- [ ] **T6 — `PrometheusGatewayComponentSpec` (DEFERRED).** Moved to Prometheus's own Spring integration suite: the module build policy keeps WireMock/Testcontainers/SpringBootTest out of its in-module tiers, and Spring AI 2.0.0-M2's official Anthropic SDK + full-context deps (PG/Redis/OAuth2/gRPC) make an in-tier hermetic stub inappropriate (contracts §5 row). **Not an MP-3 blocker.**
- [x] **T7 — Matrix wiring + isolation guard.** All C1 specs run in `componentTest`; CI `test-component` step runs the matrix on every PR + merge; the `test-all`-collects-zero-`@Tag("component")` guard holds (root `build.gradle.kts` `test`-classpath leak check); MSSQL specs `CiOnly`. Contracts §5 matrix updated.

## DONE (MP-3 closed 2026-07-09)

- [x] T1–T5 + T7 green in `just test-component` locally and in CI; T6 deferred (own suite).
- [x] `just test-all` collection unchanged (zero component specs — structural guard).
- [x] Contracts §5 matrix reflects what shipped.
- [x] Component matrix green on **master** CI (`test-component` step) → **MP-3 met** (master-plan §5).

## Follow-ups → next stage

- **C2** — the integration contexts (`tpcds-query` + finishing golem-erp/themis-routing/pythia-rca), which consume R1's `--kube dsk` mode.
- Deferred component specs (Charon DB edge, Kleio pgvector/AGE, Hebe PG) land as those arcs' DB edges mature (track in contracts §5).
