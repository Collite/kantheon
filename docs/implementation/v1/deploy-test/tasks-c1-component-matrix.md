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

## Tasks (each = write the spec first, run `just test-component` locally + CI)

- [ ] **T1 — `ArgesPostgresComponentSpec`.** `Containers.postgres()` seeded with a **TPC-DS subset** (a few small tables via `SqlScripts`): assert the PG type mapper (incl. `numeric` boundary), the trailing-pipe-safe load (shared with WS-T1 T1), read-only execution, and **no** RLS envelope when `requires-tenant-id=false` (the `pg-tpcds` case) vs the `SET LOCAL app.tenant_id` path when true. *(Highest value — it underpins the `tpcds-query` context.)*
- [ ] **T2 — `ProteusUnparseComponentSpec`.** No container — golden SQL. Assert RelNode → **PostgreSQL** for the four TPC-DS curated-query shapes (join+agg, agg+limit, **window**, **CTE**) + the named-parameter `{name}→?` rewrite. Golden files under `src/componentTest/resources/proteus/`. *(The Proteus PG-unparse exercise; pairs with WS-T2.)*
- [ ] **T3 — `ArgosRlsComponentSpec`.** `Containers.postgres()` (and/or mssql, CI-only): a policy denies cross-tenant rows end-to-end; a permitted query returns rows. Asserts the RLS/policy enforcement against a real DB.
- [ ] **T4 — `AriadneModelLoadComponentSpec`.** Classpath/fixture models: load the TPC-DS + investment + ucetnictvi models; assert `ListQueries` returns the curated sets and `ResolveArea("tpcds"/"investment")` resolves packages. *(Guards the WS-T2 / Midas-S3.1 model authoring.)*
- [ ] **T5 — `ReportRendererComponentSpec`.** No DB — POI + Playwright: render one template to XLSX + PPTX + PDF + HTML; assert named-range fill + byte-size/content-type. *(Pairs with Midas S3.4.)*
- [ ] **T6 — `PrometheusGatewayComponentSpec`.** `Containers.wiremock()` + `WireMockAdmin`: stub an LLM upstream, drive a gateway call, assert the request/response + cost capture. *(Reuses the runtime-load helper the integration tier needs.)*
- [ ] **T7 — Matrix wiring + isolation guard.** Add all specs to `componentTest`; extend the `test-all`-collects-zero-`@Tag("component")` regression guard; mark MSSQL-dependent specs `CiOnly` (arm64 qemu). Update the contracts §5 matrix table with the landed specs.

## DONE

- [ ] T1–T7 specs green in `just test-component` locally and in CI.
- [ ] `just test-all` collection unchanged (zero component specs).
- [ ] Contracts §5 matrix reflects what shipped.

## Follow-ups → next stage

- **C2** — the integration contexts (`tpcds-query` + finishing golem-erp/themis-routing/pythia-rca), which consume R1's `--kube dsk` mode.
- Deferred component specs (Charon DB edge, Kleio pgvector/AGE, Hebe PG) land as those arcs' DB edges mature (track in contracts §5).
