# Midas — Phased Implementation Plan (kantheon arc)

> **Scope.** The phased plan for the **Midas** domain (Midas-core + Excel loader + Golem-Investment + reports + dashboards) in local K3s. After the Sysifos split (decision S1), this arc is **two live phases**: **Phase 1 — Foundation (DONE 2026-06-21)** and **Phase 3 — Q&A + reports + dashboards (gated)**. The old **Phase 2 (Sysifos data entry) and old Stage 1.6 (Sysifos shell) belong to the [Sysifos arc](../sysifos/plan.md)** and are kept here only as relocated pointers (§4, §3 Stage 1.6). Phase numbering is preserved (no renumber) so historical cross-references stay valid.
>
> **Companions.** [`../../../architecture/midas/architecture.md`](../../../architecture/midas/architecture.md), [`../../../architecture/midas/contracts.md`](../../../architecture/midas/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md), [`../../../architecture/midas/midas-brief.md`](../../../architecture/midas/midas-brief.md).
>
> **Hierarchy** (per `planning-conventions.md`): task → stage (~6 tasks, testable) → phase (set of stages, deployable). All planning in this repo follows this convention.

---

## 1. Phase summary

| Phase | Goal — what deploys at the end | Stages | Estimated effort | Status |
|---|---|---|---|---|
| **Phase 1 — Foundation** | `agents/midas/core` running in local K3s with the operational Postgres provisioned + Flyway-migrated + RLS active; Excel loader writing through Midas-core's write API; proto packages `midas/v1`, `report/v1` published; Midas-core registered in capabilities-mcp. *(Old S1.6 — Sysifos-BFF + FE shell — split to the Sysifos arc per decision S1; Phase 1 now closes at S1.5.)* | 1.1 / 1.2 / 1.3 / 1.4 / 1.5 | ~4–5 weeks | **DONE (2026-06-21)** — `midas-arc/phase-1-foundation-v1` |
| **Phase 2 — Sysifos data entry** | *Moved in full to the [Sysifos arc](../sysifos/plan.md) (decision S1, 2026-06-13).* Retained below as the originating reference only. | — | — | **Relocated → Sysifos arc** |
| **Phase 3 — Q&A + reports + dashboards** | Golem-Investment serves chat-side Q&A via Iris/Themis/Golem template; Midas-core exposes complex-calc MCP tools; `services/report-renderer` generates XLSX/PPTX/PDF/HTML; Iris dashboard system live with v1 templates; Google Finance poller running on schedule. | 3.1 / 3.2 / 3.3 / 3.4 / 3.5 / 3.6 | ~4–5 weeks | **Gated** — M3 (routing) + Iris P4.2 (artifacts) + Arges/postgres worker (S3.2) |

**Status (2026-06-23).** **Phase 1 is DONE** (Stages 1.1–1.5; tag `midas-arc/phase-1-foundation-v1`). Phase 2 (the Sysifos data-entry surface) was **split to the [Sysifos arc](../sysifos/plan.md)** (decision S1) and is the unblocked next Stream-B work — every Midas pre-flight gate it needs is now met. **Phase 3 is the only remaining Midas work** and is gated (M3 routing + Iris P4.2 artifacts + the postgres worker / Arges for S3.2); it is independent of the Sysifos arc except that both write through Midas-core.

**Critical path** (post-split): Phase 1 ✓ → Sysifos arc → Midas Phase 3. Within Phase 3 some stage parallelisation is possible — see "Dependencies" per stage. The original "Phase 3 hard-depends on the ai-platform `workers/postgres`" framing is superseded: post-fork ai-platform is maintenance-only and **everything forks**, so the S3.2 dependency is the kantheon **Arges** worker — **forked now** (decided 2026-06-23), its own arc at [`../arges/plan.md`](../arges/plan.md) (`arges/v0.1.0`).

---

## 2. Pre-flight — what must be true before Phase 1 starts

| Pre-flight item | Source | Status (2026-06-02) |
|---|---|---|
| ai-platform Maven publishing live for `shared/proto`, `otel-config`, `ktor-configurator`, `logging-config`, `data-formatter` (no `mcp-server-base` — does not exist; corrected 2026-06-12) | `aip-v1-gap-closure-plan.md` Gap 1 | **closed** (carried over from Themis arc) |
| Kantheon repo bootstrap done (Themis Stage 1.1 closed) — `just init / build-kt / test-all / lint-all` green | Themis arc Phase 1 Stage 1.1 | **closed** |
| `tools/capabilities-mcp` running in local K3s | Themis arc Phase 1 Stages 1.2–1.4 | **closed** |
| Iris-BFF deployable skeleton (for Phase 3 Stage 3.5 dashboard extension) | Iris arc (to be scheduled in parallel) | flag — confirm before Phase 3 Stage 3.5 |
| Themis with routing layer live (for Phase 3 Stage 3.2 — Iris-routes-to-Golem-Investment) | Themis arc Phase 3 | flag — confirm before Phase 3 Stage 3.2 |
| `infra/whois` Keycloak realm + service accounts for `midas-core` / `sysifos-bff` / `iris-bff` / `report-renderer` | ai-platform `infra/whois` | configure before Phase 1 Stage 1.3 |
| `infra/sql-security` OPA bundle has a `midas/*` policy slot | ai-platform `infra/sql-security` | configure before Phase 1 Stage 1.3 |
| ai-platform `workers/postgres` plan exists, dispatcher + translator + validator PG paths confirmed | [`../aip-v1-pg-worker-plan.md`](../_archive/aip-v1-pg-worker-plan.md) | written alongside this plan; track in ai-platform |
| CloudNativePG operator (or equivalent) available in local K3s | local infra | install during Phase 1 Stage 1.1 |
| Bora's broker fixture XLSX files (at least 2 brokers) collected for Excel loader fixtures | brief | gather before Phase 1 Stage 1.5 |

**Cross-repo dependency on `workers/postgres`:** This work proceeds in parallel in ai-platform and is NOT on the Phase 1 / 2 critical path. It must land before Phase 3 Stage 3.2; if it slips, Phase 3 starts with Stages 3.1, 3.3, 3.4, 3.5 (which don't need the worker) and defers 3.2 to the end.

---

## 3. Phase 1 — Foundation

> **Revision 2026-06-21 (Bora, pre-Stage-1.1 repo-reconciliation).** The repo moved substantially since this plan was written (testing arc, fork complete, new shared libs). Four decisions, folded into the stages below:
> 1. **DB layer = Exposed, not jOOQ.** Match the in-repo precedent (iris-bff) and reuse `shared/libs/kotlin/db-common` (Exposed + HikariCP). No `nu.studer.jooq`, no build-time codegen-Postgres. Removes the only legitimate build-time-PG concern.
> 2. **Postgres topology = one shared Kantheon PG.** Per kantheon-architecture §7.1, Midas does **not** own a `midas-postgres` CloudNativePG cluster. Instead Midas introduces a shared `postgres` to `deployment/local/` (the single local-infra entry point, currently MSSQL-only) and owns the `midas` **database** within it.
> 3. **Testing = component tier now ships in-arc.** The testing arc landed `componentTest`/`integrationTest` source sets (auto-wired in root `build.gradle.kts`) + `shared/libs/kotlin/component-testkit` (`Containers.postgres()`, `WireMockAdmin`) + `integration-harness`, with `just test-component` gating CI on every PR. Stage gates still pass on **mocked unit tests** (`src/test`, planning-conventions §4 unchanged), but each stage's previously-"deferred" real-PG verification (RLS leakage, repository round-trip, MV NOTIFY) now ships as a concrete `src/componentTest` spec — no longer indefinitely deferred. `architecture.md` is stale the other way (says "Testcontainers everywhere") and is being aligned to this split.
> 4. **Sysifos scope = out.** Midas Phase 1 owns only Midas modules (`agents/midas/{core,loaders/excel,shem}`, `services/report-renderer`) and ships `midas/v1` + `report/v1`. `sysifos-bff`, `frontends/sysifos`, and `sysifos/v1` belong to the Sysifos arc (which already has its own task lists). Stage 1.6 below was already relocated; Stage 1.1 T1 and Stage 1.2 T2 are corrected accordingly.
>
> Also reconciled against repo reality: `cz.dfpartner` Maven coupling is gone (fork complete); the catalog already carries `postgresql`/`hikaricp`/`flyway-*`/`apache-poi-ooxml`/`jib`/`testcontainers-postgresql`; the `my.kotlin-ktor` convention plugin does not exist (root `build.gradle.kts` `subprojects{}` auto-wires shared config); proto codegen (Kotlin + Python) is global/automatic; `capabilities-client`, `ktor-configurator` (MCP-over-Ktor), `envelope-render`, `llm-gateway-client` all exist and are reused.

**Goal.** Bring Midas-core to life with the operational DB and Excel loader. Everything below the user-facing data-entry screens (the Sysifos surface is the Sysifos arc).

**Deployable at phase close.** Midas-core + Excel loader running in local K3s against the shared Kantheon Postgres (`midas` database); capabilities-mcp shows Midas-core's five tool capabilities registered; can hit `/api/v1/clients` from curl with a Keycloak JWT.

### Stage 1.1 — Arc bootstrap + Postgres infra

> **DONE (2026-06-21).** Task list: [`tasks-p1-s1.1-bootstrap.md`](./tasks-p1-s1.1-bootstrap.md). Module skeletons + shared local Postgres (`midas` database) + Exposed/`db-common` + CI wiring landed; tag `midas/bootstrap-v0.1.0`. Sysifos modules excluded per decision #4.

**Goal.** The directory skeleton exists for every **Midas-owned** module. A shared `postgres` runs in local K3s (added to `deployment/local/`) with a `midas` database. Build is green on empty stubs.

**Pre-flight.**
- Themis arc Phase 1 closed (capabilities-mcp running). *(Done — Themis is at v0.2.0.)*
- Bora confirms the chosen Keycloak realm name. *(Still open — gates Stage 1.3, not 1.1.)*

**Tasks (6).**
1. **Module skeletons** — Create empty `agents/midas/core/`, `agents/midas/loaders/excel/`, `services/report-renderer/`, `agents/midas/shem/`. Each Kotlin module: `build.gradle.kts`, `src/main/kotlin/...`, `src/test/kotlin/...`, `k8s/{base,overlays/local}/`. **`agents/sysifos-bff/` and `frontends/sysifos/` are out of scope (Sysifos arc, decision #4).**
2. **`gradle/libs.versions.toml` updates** — add only what's missing: `quartz` (Google-Finance poller, Phase 3) and `playwright-kotlin` (report PDF/HTML, Phase 3). Already in the catalog (no-op): `postgresql`, `hikaricp`, `flyway-core`/`flyway-pgsql`, `apache-poi-ooxml`, `jib`, `exposed-*`, `testcontainers-postgresql`. **No jOOQ** (decision #1 — Exposed via `shared/libs/kotlin/db-common`). *(Quartz/Playwright may be deferred to their Phase-3 stages if preferred; nothing in Phase 1 needs them.)*
3. **Settings + root build** — add the new modules to `settings.gradle.kts`. No convention plugin to apply — the root `build.gradle.kts` `subprojects { plugins.withId("org.jetbrains.kotlin.jvm") { … } }` block auto-wires kotest + the `componentTest`/`integrationTest` source sets; each module just declares the kotlin/ktor + jib plugins in its own `build.gradle.kts` (copy a recent service, e.g. `services/charon`).
4. **Justfile recipes** — `just deploy-kt midas-core / loaders/excel / report-renderer`, `just db-migrate` (Flyway from CLI). *(Sysifos FE recipes — `sysifos-dev`, `build-fe sysifos` — belong to the Sysifos arc.)*
5. **Shared local Postgres** — add a `postgres` resource to `deployment/local/` (the single local-infra entry point, alongside the existing `mssql`), wired into `deployment/local/kustomization.yaml`. Local overlay: `imagePullPolicy: Never`, persistent volume 10 GiB. Bootstrap job creates the `midas` database + `midas_app` role. This is the shared Kantheon PG (kantheon-architecture §7.1); later PG-backed agents (iris, golem) reuse the same instance with their own databases.
6. **CI extension** — `.github/workflows/ci.yml` learns the new modules (auto-detect via Gradle plugins; no hardcoded lists). PR-green smoke run on each new module, including `test-component` (the component tier is in CI on every PR).

**Stage 1.1 DONE.** `just init && just build-kt agents/midas/core && just local-infra-up` brings up the shared `postgres` with the `midas` database ready (`kubectl -n kantheon get pods` shows it). CI passes (unit + component). *(Recipes take the **path form** — `agents/midas/core`, `agents/midas/loaders/excel`, `services/report-renderer` — since the nested dirs have no unique bare basename for `_resolve`'s `find -name`.)*

**Deliverable.** Midas arc skeleton at `git tag midas/bootstrap-v0.1.0`.

**Dependencies.** None internal. Themis arc Phase 1 closed.

### Stage 1.2 — Proto packages

> **DONE (2026-06-21).** Task list: [`tasks-p1-s1.2-protos.md`](./tasks-p1-s1.2-protos.md). `midas/v1` + `report/v1` compile to Kotlin; cash-leg baseline in `midas.proto`; enum-string drift test green; tag `shared-proto/midas-v0.1.0`. `sysifos/v1` deferred to the Sysifos arc (decision #4).

**Goal.** `midas/v1` + `report/v1` proto files compile; Kotlin bindings generated; no Kotlin code consumes them yet. **`sysifos/v1` is the Sysifos arc's (decision #4) — not written here.**

**Pre-flight.** Stage 1.1 DONE.

**Proto package roots.** `org.tatrman.kantheon.midas.v1` + `org.tatrman.kantheon.report.v1` per [`contracts.md`](../../../architecture/midas/contracts.md) §1 (Midas is constellation/agent-side — lives under `agents/` and feeds the Golem-Investment Shem; the `kantheon.*` root is correct here, vs the `org.tatrman.<service>.v1` root used by pure platform services).

**Tasks (5).**
1. **Write `midas.proto`** — per [`contracts.md`](../../../architecture/midas/contracts.md) §1.1. All entity messages, all request/response wrappers, all MCP tool input/output types, all reconciliation types, all loader types. Includes the cash-leg baseline (§1.1.A): `track_cash`, `correlation_id`, `TX_CASH_CREDIT`/`TX_CASH_DEBIT`, `TX_SRC_DERIVATION`, `ASSET_CASH`.
2. **Write `report.proto`** — per `contracts.md` §1.3. `ReportTemplate`, `RenderReportRequest`, `RenderReportResponse`, `ParamDef` enums.
3. **Codegen — automatic.** Dropping the `.proto` files under `shared/proto/src/main/proto/org/tatrman/kantheon/{midas,report}/v1/` is sufficient: `shared/proto/build.gradle.kts` registers codegen globally (`generateProtoTasks { all() … }`) for Kotlin + Python + gRPC. Just confirm `just proto` regenerates cleanly and idempotently — no per-package build wiring.
4. **Proto-shape unit tests (Kotlin)** — Kotest spec verifying enum values match string forms used in DDL CHECK constraints (`ClientStatus.ACTIVE` ↔ `'ACTIVE'`, `TransactionKind.TX_CASH_DEBIT` ↔ `'CASH_DEBIT'`). Catches DDL/proto drift early.
5. **TS package (deferred to consumer).** No TS package is emitted here — there is no Phase-1 TS consumer (Sysifos FE is the Sysifos arc, which owns `sysifos/v1`). When a Midas-domain TS surface is needed (Phase 3 dashboards / report previews), emit it then per the `shared/libs/ts/envelope-ts` buf pattern.

**Stage 1.2 DONE.** `midas/v1` + `report/v1` compile to Kotlin. `just proto` is idempotent. Unit test catches enum-string drift.

**Deliverable.** Proto packages at `git tag shared-proto/midas-v0.1.0`.

**Dependencies.** Stage 1.1.

### Stage 1.3 — Midas-core: DB schema + write API + RLS

> **DONE (2026-06-21).** Task list: [`tasks-p1-s1.3-core-db-rls.md`](./tasks-p1-s1.3-core-db-rls.md). V0001 schema + RLS + append-only triggers; Exposed repos; all write/read routes (Clients/Portfolios/Assets/Transactions/BalanceEntry/FxRates) with JWKS verify + tenant 403 + 409 idempotency; reverse-and-replace + balance-entry derivation; **cash-leg derivation (§1.1.A) — the Sysifos cash-row baseline**; componentTest RLS-leakage proof; deployed to bp-dsk. This stage's write API + cash legs are the hard gates the Sysifos arc depends on (now met).

**Goal.** Midas-core compiles, the operational DB is fully migrated, all REST write/read endpoints answer at the unit level against mocked repository fakes, RLS wiring in place — **and the real-PG behaviour (RLS leakage, repository round-trip) ships as `src/componentTest` specs** (decision #3). Stage gate = mocked unit tests pass (planning-conventions §4); componentTest is an additional arc deliverable, in CI on every PR.

**Pre-flight.** Stage 1.2 DONE. Keycloak realm name confirmed + service account for `midas-core` exists.

**Tasks (8).**
1. **Tests first — RLS wiring (unit)** — Kotest spec `RlsPolicySpec` at the unit level: assert the connection-borrow path always issues `SET LOCAL app.tenant_id` from the request tenant (against a mocked/fake connection), and that the repository layer refuses to run a query when no tenant is set.
2. **Flyway migration V0001** — write `db/migration/V0001__schema.sql` per [`contracts.md`](../../../architecture/midas/contracts.md) §6.1: tables, indices, RLS policies, `app_current_tenant()` function, append-only triggers on `transactions`. Includes the cash-leg baseline (§1.1.A: `portfolios.track_cash`, `transactions.correlation_id`, extended `kind` CHECK, `idx_transactions_correlation`).
3. **Exposed schema mapping** — define the Exposed `Table` objects mirroring V0001 (no jOOQ; decision #1). Reuse `shared/libs/kotlin/db-common` (`DatabaseConnection`, `query{}` transaction helper, HikariCP + Postgres). Flyway owns the DDL; Exposed maps onto the migrated schema (Flyway is the source of truth, Exposed `Table`s are hand-written and verified against the migration by the componentTest in T4b).
4. **Repository tests first (unit)** — Kotest spec per repository (`ClientRepositorySpec`, `PortfolioRepositorySpec`, `AssetRepositorySpec`, `TransactionRepositorySpec`, `FxRateRepositorySpec`), unit-level against MockK / in-memory fakes exercising the query construction + the `SET LOCAL app.tenant_id` discipline.
   **4b. Repository + RLS componentTest** — `src/componentTest/kotlin/.../MidasRepositoryComponentSpec.kt` + `RlsLeakageComponentSpec.kt` using `component-testkit`: `Containers.postgres()`, Flyway-migrate V0001 against it, then prove (i) each repository round-trips real rows, and (ii) tenant A cannot read tenant B's rows (the real RLS-leakage proof — now a concrete CI spec, not deferred). `@Tags("component")`, wired via `"componentTestImplementation"(project(":shared:libs:kotlin:component-testkit"))`.
5. **Repository implementations** — make the unit + component specs pass. Exposed-typed selects/inserts; explicit transactions; `SET LOCAL app.tenant_id` on connection borrow via the Hikari pool initializer.
6. **Route tests first** — Ktor TestApplication specs per route (Clients, Portfolios, Assets, Transactions, BalanceEntry, FxRates) including JWT verification + tenant-mismatch 403 + 409 idempotency.
7. **Route implementations + derivation layer** — make route tests pass. Implement `derivation/BalanceToTransaction.kt` (preview + commit), `derivation/ReverseAndReplace.kt` (PATCH transaction path), `derivation/CashLegDerivation.kt` (S2 — derive the `TX_CASH_*` counter-leg on `POST /transactions` + `:batch` when `portfolio.track_cash`; auto-provision the per-`(portfolio,currency)` `ASSET_CASH`; share `correlation_id`; cascade on reversal), `tenant/TenantHeaderInterceptor.kt`. Tests-first cover: a BUY with `track_cash=true` produces a security leg + a `TX_CASH_DEBIT` sharing `correlation_id`; `track_cash=false` produces the security leg only; reversing the security leg reverses the cash leg. **Note:** JWT signature verification (JWKS) is genuinely new — there is no shared `keycloak-auth` lib and iris-bff's `BearerAuthenticator` is validate-only; build a real verifier here (factor it so the Sysifos arc can reuse it).
8. **Deploy + smoke** — Jib build, Kustomize apply to local K3s (against the shared `postgres`/`midas` database), hit `/api/v1/clients` from curl with a real Keycloak JWT. `/health` 200, `/ready` 200.

**Stage 1.3 DONE.** Midas-core deployable; all routes tested at unit level (mocked fakes); repository round-trip + RLS cross-tenant isolation proven by `src/componentTest` (real PG, in CI). `just deploy-kt midas-core && curl ... /api/v1/clients` returns `[]` for a fresh tenant.

**Deliverable.** Midas-core service running in local K3s.

**Dependencies.** Stage 1.2.

### Stage 1.4 — Materialized views + MCP tool stubs + capabilities registration

> **DONE (2026-06-21).** Task list + shipped-vs-plan deltas: [`tasks-p1-s1.4-mv-mcp-capabilities.md`](./tasks-p1-s1.4-mv-mcp-capabilities.md). Two deliberate deltas (both in `contracts.md` §6.3): refresh is **synchronous** (async NOTIFY/LISTEN deferred to v1.x), and the MCP surface is a **second listener on :7311** (CIO) alongside REST on :7310 (Netty), not the same Ktor app.

**Goal.** Materialized views in place; Midas-core's five MCP tools answer at least with stub computations (full TWR/MWR/FIFO come in Stage 3.3); Midas-core heartbeats into capabilities-mcp.

**Pre-flight.** Stage 1.3 DONE.

**Tasks (6).**
1. **V0002 + V0003 migrations** — `db/migration/V0002__materialized_views.sql` (mv_position_current + mv_portfolio_value_daily skeleton). `V0003__view_refresh_strategy.sql` setting up NOTIFY/LISTEN channel `mv_position_refresh` and a debounce contract document.
2. **MV refresh tests first (unit)** — Kotest spec at the unit level: drive the listener with a faked `PGNotificationListener` emitting `mv_position_refresh` notifications and assert it invokes `REFRESH MATERIALIZED VIEW CONCURRENTLY mv_position_current` (mocked DB call) with the 500ms debounce, and that a burst of notifications coalesces into one refresh.
   **2b. MV refresh componentTest** — `src/componentTest/.../MvRefreshComponentSpec.kt` via `component-testkit` (`Containers.postgres()`, V0001–V0003 applied): insert a transaction → trigger fires NOTIFY → listener refreshes `mv_position_current` within 5s; assert the new position is visible (the real NOTIFY-on-insert path — now a concrete CI spec, not deferred).
3. **MV refresh listener** — `MvRefreshListener.kt` using `PGNotificationListener`; debounce 500ms; logs + OTel span per refresh.
4. **MCP server bootstrap + tool stub tests** — Wiremock spec verifies `/mcp` returns the five tool descriptors; each tool responds to a sample `argsJson` with shape-valid output (stub: zeros, hard-coded performance). Per `contracts.md` §3. Reuse the MCP-over-Ktor layer in `shared/libs/kotlin/ktor-configurator` (`McpKtorServerBootstrap`, `SafeMcpTool`).
5. **MCP tool stub implementations** — `PortfolioPerformanceTool`, `PositionValuationTool` (real; reads `mv_position_current`), `CostBasisTool` (stub), `FeeAllocationTool` (stub), `ReconciliationTool` (real; reads `transactions` + `loader_runs`).
6. **Capabilities registration** — Midas-core uses `shared/libs/kotlin/capabilities-client` (`CapabilitiesClient.startupRegister`, 30s heartbeat + warn-and-continue are built in) to register five `ToolCapability` entries at startup, loaded from `src/main/resources/manifests/tools/*.yaml` via a small copied `ManifestLoader` (the `tools/theseus-mcp` pattern). Test against a Wiremock'd capabilities-mcp.

**Stage 1.4 DONE.** `capabilities-mcp.list()` shows `midas.*:v1` entries; `PositionValuationTool` returns correct quantities for a fixture-seeded portfolio (unit). The MV refresh-within-5s-of-insert behaviour is proven by `MvRefreshComponentSpec` (real PG, in CI).

**Deliverable.** Midas-core + materialized views + MCP stubs running and registered.

**Dependencies.** Stage 1.3.

### Stage 1.5 — Excel loader (upload → preview → commit)

> **DONE (2026-06-21).** Task list: [`tasks-p1-s1.5-excel-loader.md`](./tasks-p1-s1.5-excel-loader.md). Parser + mapper + full upload→preview→commit lifecycle + deploy wiring + blob-retention janitor, all tested (the live cluster smoke `k8s/smoke.sh` awaits one bp-dsk run). Two scoping notes: `loader_runs` is an in-memory store (DB-backed swap deferred); fixtures are synthetic (POI) until real broker exports land.

**Goal.** Excel loader service runs; one fixture broker template parses cleanly; uploaded statement previews + commits through Midas-core; idempotent re-upload works.

**Pre-flight.** Stage 1.4 DONE. Two broker fixture XLSX files in hand.

**Tasks (7).**
1. **Parser tests first** — Kotest spec per fixture broker template; assert that a known input XLSX produces N `Transaction` drafts with correct kind/quantity/price/fee/external_id.
2. **POI parser + broker registry** — `parser/ExcelParser.kt` (POI XSSFWorkbook); `parser/BrokerRegistry.kt` mapping `broker_id → template config` (sheet name, header row, column mapping); two broker templates as `.properties` or YAML in `src/main/resources/brokers/`.
3. **Mapper tests first** — Kotest spec mapping parsed rows → `midas.v1.Transaction` drafts. Verifies date parsing, currency mapping, kind mapping (broker-specific strings → `TransactionKind`), `external_id` construction.
4. **Mapper implementations** — `mapper/TransactionMapper.kt` per broker (default + per-broker overrides).
5. **Loader lifecycle tests first** — Wiremock-driven (Midas-core stubbed) spec covering POST /uploads → GET /runs/{id}/preview → POST /runs/{id}/commit. Idempotency: second upload of same file returns same loader_run_id + skip_existing on commit.
6. **Loader lifecycle implementation** — Ktor routes per `contracts.md` §4.1; `loader_runs` table managed by the loader (separate connection profile — same DB instance, different schema OR loader-local schema TBD in this stage).
7. **Deploy + smoke + cleanup** — Jib + Kustomize; smoke: upload a fixture XLSX → preview → commit → confirm transactions appear in Midas-core. Delete `loader_run.upload_blob_ref` files after 24h (cron in v1; S3 lifecycle in v1.x).

**Stage 1.5 DONE.** `just deploy-kt loaders/excel && upload fixture → preview → commit` succeeds; idempotent re-run skips zero new transactions.

**Deliverable.** Excel loader live in local K3s.

**Dependencies.** Stage 1.4. (Can start parser/mapper work in parallel after Stage 1.2.)

### Stage 1.6 — Sysifos-BFF + FE shell → **relocated to the Sysifos arc**

This stage no longer belongs to the Midas arc. It became **Sysifos Phase 1** (decision S1, 2026-06-13) and is fully specified at [`../sysifos/plan.md`](../sysifos/plan.md) §3 (Stages 1.1–1.3: arc bootstrap + `sysifos/v1` relocation, `bff-base` + Sysifos-BFF skeleton, hybrid write skeleton + FE shell). The `bff-base` extraction, the Sysifos draft/commit path, and the Vue FE shell all live there. Midas Phase 1 closes at Stage 1.5.

### Phase 1 closing

**Phase 1 DONE (2026-06-21).** All five stage DONE criteria met (Stages 1.1–1.5; Sysifos is its own arc). Demo verified: with a Keycloak JWT, create a client + portfolio via curl on Midas-core, then upload an Excel statement via the loader → preview → commit → confirm transactions visible in the `midas` database (incl. derived cash legs). Midas-core's five tool capabilities visible in capabilities-mcp.

**Phase 1 deliverable.** Git tag `midas-arc/phase-1-foundation-v1`. ✓

---

## 4. Phase 2 — Sysifos data entry → **relocated to the Sysifos arc**

The entire data-entry surface (Clients, Portfolios, Assets, Transactions, bulk grid, Balance entry, Statement import, Reconciliation, Loader status, Audit) moved to the **[Sysifos arc](../sysifos/plan.md)** as its Phase 2 (Stages 2.1–2.6) per decision **S1** (2026-06-13). That plan is the source of truth and is the unblocked next Stream-B work now that Midas Phase 1 is done.

The Midas arc continues at its **Phase 3** (Q&A + reports + dashboards, §5), which relates to the Sysifos arc only insofar as both write through Midas-core. **The derived cash legs the Sysifos screens render are Midas baseline** — `TX_CASH_CREDIT`/`TX_CASH_DEBIT`, `Portfolio.track_cash`, `Transaction.correlation_id` ship in V0001/V0002 + the proto, and `derivation/CashLegDerivation.kt` shipped in Stage 1.3 (T7). Per [`../../architecture/midas/contracts.md`](../../architecture/midas/contracts.md) §1.1.A. **This is live** (Midas S1.3 done), so Sysifos Stages 2.2/2.3 (cash sub-rows) are unblocked.

---

## 5. Phase 3 — Q&A + reports + dashboards

> **Task lists (written 2026-06-27).** Per-stage checkbox lists at [`tasks-p3-overview.md`](./tasks-p3-overview.md) (index + dependencies) and `tasks-p3-s3.1…s3.6-*.md`. **The overview §0 carries the post-fork reconciliations this §5 text predates** (query-mcp→theseus-mcp, metadata-mcp→Ariadne `ListQueries`/`ResolveArea`, workers/postgres→**Arges**, `DOMAIN_QA`→**`AREA_QA`**, Shem **assembled** like `golem-ucetnictvi` not mounted, PD-6 generic dashboards). When executing, follow the task lists; this §5 prose is the originating intent. *(Doc-sync of §5 + contracts §9.1 to those reconciliations is a tracked follow-up.)*

**Goal.** Bring user-facing analytics surfaces live. Iris can answer investment Q&A; reports render; dashboards refresh; FX/price data flows in.

**Critical dependencies arriving in Phase 3 (status 2026-06-23):**

- **Themis routing layer** active — **MET** (`themis/v0.2.0`, 2026-06-21). The remaining half of M3 is Iris reaching Phase 2 exit (`iris/v0.1.0`); Iris is mid-P2.
- **Iris artifact system** (Iris Phase 4 Stage 4.2) for dashboards (S3.5) — pending; Iris is mid-P2.
- **Postgres worker** for S3.2 onward (S3.1 doesn't need it) — the kantheon **`Arges`** worker (`workers/arges`). **Decided 2026-06-23: forked now**, its own Stream-B arc ([`../arges/plan.md`](../arges/plan.md), `arges/v0.1.0`), mirrors Brontes + adds the `SET LOCAL app.tenant_id` RLS contract. S3.2 gates on `arges/v0.1.0`; S3.1/3.3/3.4/3.5 can run ahead. Two Arges follow-ons that touch Midas: the **Proteus PostgreSQL unparse** gap (Arges S1.2) and the Midas-side **`midas_app_readonly`** role migration ([arges contracts §6](../../../architecture/arges/contracts.md)) for the live `pg-midas` path.

### Stage 3.1 — Golem-Investment ShemManifest + curated queries

**Goal.** ShemManifest fixture in capabilities-mcp; the curated queries it references are registered in metadata-mcp; Golem template image launches with the Shem mounted; pod is reachable but Q&A path not green yet.

**Pre-flight.** Themis routing layer live; Golem template image exists; metadata-mcp accepts new query catalog entries.

**Tasks (6).**
1. **Write `shem-investment.yaml`** — per [`contracts.md`](../../../architecture/midas/contracts.md) §9.1. Sourced from agreed terminology; reviewed against ShemManifest discipline rule (no correctness-affecting knowledge in `style_addendum`).
2. **Register Shem in capabilities-mcp** — drop YAML into `tools/capabilities-mcp/manifests/agents/`; bump capabilities-mcp config; verify `list_agents()` returns golem-investment.
3. **Curated queries in metadata-mcp** — register `q.midas.positions_current`, `q.midas.transactions_recent`, `q.midas.dividends_period`, `q.midas.fees_period`, `q.midas.realised_pnl_period` in ai-platform metadata-mcp's catalog. Each query: name, parameter slots, target connection (`pg-midas`), SQL template.
4. **Golem-Investment pod** — Kustomize `agents/golem/k8s/overlays/local/golem-investment.yaml`; mounts `shem-investment.yaml` via ConfigMap; new Deployment + Service.
5. **Health + registration** — pod boots, registers itself in capabilities-mcp; smoke: `capabilities-mcp.get(golem-investment)` returns the ShemManifest with live `last_heartbeat_at`.
6. **Themis routing smoke** — Themis with fixture LLM routes the question "what is the current AAPL position in Smith's portfolio" to `golem-investment` with confidence > 0.7 (Layer 1 rule-based on `description_for_router` + `example_questions`).

**Stage 3.1 DONE.** Golem-Investment pod live and registered; Themis routes correctly; pod itself does not answer (waits on Stage 3.2).

### Stage 3.2 — Q&A green path (depends on `workers/postgres`)

**Goal.** Iris sends a question; Themis routes; Golem-Investment executes; query-mcp + workers/postgres + Midas-core MCP tools serve data; user gets a `ConversationalResponse` with `envelope/v1` blocks.

**Pre-flight.** `workers/postgres` deployed and registered (ai-platform-side).

**Tasks (7).**
1. **`workers/postgres` smoke** — manually exercise: `query-mcp.compile(q.midas.positions_current)` against `pg-midas` connection profile; assert Arrow IPC stream returns rows from `mv_position_current`.
2. **Golem-Investment graph wiring tests first** — Wiremock-driven Q&A test against fixture: "current AAPL position in Smith portfolio" → expected Block sequence.
3. **Golem-Investment graph wiring** — Koog graph for the Golem template (existing) parameterised with the Shem. Verify `preferred_capabilities` resolution to live tool endpoints.
4. **MCP tool calls — happy path** — Golem-Investment calls `midas.position.valuation:v1` for valuation questions, `midas.portfolio.performance:v1` for return questions; results render via envelope blocks.
5. **MCP tool calls — implementations** — implement real TWR / FIFO in Midas-core's `calc/` (currently stub from Stage 1.4). `TwrSpec` + `FifoSpec` unit tests with hand-computed expected values from reference portfolios.
6. **Q&A component test (mocked constellation)** — component-level test of the Golem-Investment turn with Themis/Iris-BFF/query-mcp/Midas-core MCP edges Wiremock-stubbed: five canned questions each produce the expected `envelope/v1` Block sequence. Per the testing policy (planning-conventions.md §4): the real end-to-end smoke through a live Iris → Themis → Golem-Investment chain is deferred to the separate integration-test suite (the live Q&A path remains a Phase-3 capability, exercised in the closing demo).
7. **Error path: portfolio_id not found** — Themis-resolved portfolio_id missing from DB → Golem returns envelope with diagnostic block; user sees clean error.

**Stage 3.2 DONE.** Five canned Q&A questions produce the correct `envelope/v1` Block sequences at the mocked-component level (live end-to-end-through-Iris verification deferred to the separate integration-test suite; confirmed in the Phase-3 demo). Latency p95 < 5s for simple questions, measured at the component level.

### Stage 3.3 — Midas-core complex calc tools

**Goal.** `CostBasisTool` and `FeeAllocationTool` complete (Stage 1.4 left these as stubs). MWR (money-weighted return) implemented.

**Tasks (5).**
1. **MWR tests first** — `MwrSpec` with reference portfolios (manual cashflows, known IRR results). Newton-Raphson IRR solver with stability tests for near-zero returns.
2. **MWR implementation** — `calc/Mwr.kt`; integrate with `PortfolioPerformanceTool`.
3. **FIFO cost basis tests first** — `FifoSpec` with multi-buy / partial-sell scenarios; verifies lot ordering and remaining quantities.
4. **FIFO cost basis implementation** — `calc/Fifo.kt`; integrate with `CostBasisTool`.
5. **Fee allocation tests first + implementation** — pro-rata allocation across positions by market value at trade date; integrate with `FeeAllocationTool`.

**Stage 3.3 DONE.** All five MCP tools serve real data; reference-portfolio fixtures pass calc tests at 4-decimal precision.

**Dependencies.** Stage 3.2 (validates real flows before more calc work).

### Stage 3.4 — Report renderer service + v1 templates

**Goal.** `services/report-renderer` deployable; three v1 templates render against fixture data.

**Tasks (7).**
1. **Service skeleton + template-resolver tests first** — Wiremock-driven spec: `GET /templates` returns the three v1 templates; `GET /templates/{id}` resolves and reads bytes from classpath.
2. **`TemplateResolver` + service skeleton** — `services/report-renderer/src/main/kotlin/.../App.kt`; `RepoBundledResolver` reading from `src/main/resources/templates/`.
3. **XLSX engine tests first** — render `portfolio-statement:v1` against fixture data; assert named ranges populated, table region `tbl_positions` has expected row count.
4. **XLSX engine** — POI-based template substitution: named-range writes, table-region row insertion + style preservation.
5. **PDF/HTML pipeline tests first + implementation** — render XLSX → screenshot via Playwright Kotlin → PDF. Smoke: byte-size > 5 KB, content type correct.
6. **`/render` route + artifact storage** — request handler dispatches by template+format; stores in `/var/midas/artifacts/` (FS in v1) keyed by artifact_id; cleanup cron after 7 days.
7. **Three v1 templates as fixtures** — `portfolio-statement.v1.xlsx`, `performance-report.v1.xlsx`, `performance-report.v1.pptx`, `transaction-ledger.v1.xlsx`. Author manually in Excel/PPT, place in `src/main/resources/templates/`. Render + manually open in Excel/PPT to verify fidelity.

**Stage 3.4 DONE.** All three templates render in all four formats; `report-renderer` healthy in K3s.

**Dependencies.** Stage 3.3 (PPTX charts use real performance numbers).

### Stage 3.5 — Iris dashboards: Midas content on the generic artifact system

> **Reframed by PD-6 (2026-06-12, Bora-approved).** The dashboard *system* (pins, dashboards, `iris_artifacts`, refresh machinery) is built generically in the **Iris arc** (`iris/contracts.md` §2.8 + §3.3) — pane source is `common.v1.ViewProvenance`, not this arc's `agent_call_spec`. This stage is now a **consumer**: Midas-domain templates + content + the E2E proof.

**Goal.** Midas content live on the generic system: Golem-Investment blocks pinnable; `investment-overview:v1` template ships; dashboards render via the envelope pipeline.

**Pre-flight.** Iris arc artifact stage shipped (generic pins/dashboards live); Iris-BFF deployable.

**Tasks (5).**
1. **Template schema + loader (generic, contributed to Iris arc)** — `dashboard-templates/*.yaml` ParamDefs/pane model from `midas/architecture.md` §11.1, tests first; lands Iris-side, Midas supplies content.
2. **`investment-overview:v1` template** — Golem-Investment question panes + `midas.position.valuation` tool pane + report-preview pane; param fill (client/portfolio/period).
3. **Report-preview pane kind** — render-on-open via `/reports/render` proxy (report-renderer); the one genuinely Midas-specific pane mechanics item.
4. **Pin/refresh verification against Golem-Investment** — typed-action refresh path works for investment patterns; per-pane error states on model change.
5. **End-to-end smoke** — ask "YTD performance for Smith portfolio", pin chart to a new "Smith book" dashboard, reopen → refresh; create `investment-overview` from template.

**Stage 3.5 DONE.** Midas content pinnable + one v1 template ships on the generic system.

**Dependencies.** Iris arc artifact stage (system); Stages 3.2 + 3.4 (panes call Golem-Investment + report-renderer).

### Stage 3.6 — Google Finance poller + FX rates

**Goal.** Daily FX rate refresh + market-price refresh, running on Quartz schedule. Stale-data warnings visible in envelopes.

**Tasks (5).**
1. **Sheets-API client tests first** — Wiremock-driven spec against a fixture Google Sheet URL returning `GOOGLEFINANCE` formula outputs; assert parser extracts rate + date.
2. **Sheets-API client** — `loaders/google-finance/parser/SheetsClient.kt` using google-sheets-api Java client + service account auth.
3. **Poller — FX rates** — Quartz job: every day at 23:00 UTC fetches active currency pairs (read from `portfolios.base_currency` + asset currencies in DB) and upserts to `fx_rates` via Midas-core's `POST /fx-rates`.
4. **Poller — market prices** — Quartz job: every day at 23:30 UTC fetches active assets' close prices and writes to `mv_portfolio_value_daily`-feeding tables (decision in Stage 1.4 informs whether this lands in `transactions` as `MARK_TO_MARKET` or in a separate prices table — v1 picks separate `asset_prices` table for cleanliness).
5. **Stale-data warning surfacing** — if FX rate older than 24h, Midas-core's MCP tools surface a `ResponseMessage` with severity=WARN. Envelope rendering in Iris shows a "stale" badge on tables.

**Stage 3.6 DONE.** Daily pollers run for 7 days without incident; FX rates current; positions valued at current market prices.

### Phase 3 closing

**Phase 3 DONE.** Final demo: user logs in to Iris, asks "summarise Smith's portfolio for Q1", gets a chart + table + narrative, saves chart as a pane on the "Smith book" dashboard, exports a PDF performance report. Separately, user logs in to Sysifos, imports a fresh statement, reconciles. Daily pollers refresh FX/prices automatically.

**Phase 3 deliverable.** Git tag `midas-arc/phase-3-qa-reports-v1`. Arc complete.

---

## 6. Risks and known unknowns

- **Arges (PG worker) slippage** — the S3.2 query path needs the kantheon **Arges** worker (`arges/v0.1.0`; arc planned 2026-06-23, [`../arges/plan.md`](../arges/plan.md)). Its own gate is the **Proteus PostgreSQL unparse** gap (Arges S1.2). Mitigation: Phase 1 + the Sysifos arc don't need Arges; Phase 3 can start with Stages 3.1, 3.3, 3.4, 3.5 and defer 3.2 until `arges/v0.1.0` + the `midas_app_readonly` role land.
- **POI template fidelity** — Apache POI's PPTX support is famously hit-or-miss for complex slide layouts. Mitigation: keep v1 templates simple; defer fancy graphics to v1.x; consider Aspose.Slides as paid backup if POI breaks down.
- **Materialized-view refresh latency** — `mv_position_current` refresh + concurrent transactions may contend. Mitigation: benchmark in Stage 1.4 with 1M-row fixture; switch to scheduled refresh + read-your-writes-from-base-table fallback if needed.
- **Multi-currency edge cases** — assets denominated in non-portfolio-base currency with stale FX produce wrong NAV. Mitigation: stale-FX warning surfacing in Stage 3.6; explicit user-visible "as-of FX date" in valuations.
- **FIFO + reversal-entry interaction** — reversing a sale must "release" the lot it consumed. Mitigation: lot ledger as a derived table in Stage 3.3, not as denormalized fields on positions. Reference-portfolio tests cover round-trips.
- **`bff-base` extraction premature** — risk that Iris-BFF and Sysifos-BFF don't actually share enough to warrant the lib. *(Now owned by the Sysifos arc — its Stage 1.2 audit; if shared surface < 200 LOC, fold helpers directly into both BFFs and revisit in v1.x.)*
- **Google Sheets API rate limits** — 100 reads / 100s / user. Mitigation: poller batches all FX pairs into one sheet; service account quota is per-user (not per-sheet).

---

## 7. Out of scope for this arc

Reaffirmed from architecture.md §3.4, repeated for plan-doc completeness:

- Custom write-worker for Midas-core (deferred per D2).
- Yahoo Finance / SFTP / REST adapter loaders.
- Corporate actions handling.
- Cost basis methods beyond FIFO.
- User-editable report templates + S3-backed storage.
- Iris chat-side Excel upload flow.
- Dashboard sharing across users / read-only-share / team templates.
- Per-portfolio ACLs (v1 stops at tenant-level).
- LLM-based reconciliation auto-resolve.
- Full E2E integration testing — separate flow per planning convention. Per the testing policy (planning-conventions.md §4): plans develop against mocked unit tests only (Kotest + Wiremock + MockK, in-memory fakes); Testcontainers/real-Postgres, RLS-leakage, and end-to-end-through-Iris verification all live in the separate integration-test suite. "Deploy + smoke" tasks remain as deployment confirmations / demos, not as an automated e2e test gate.

---

## 8. Sequencing summary

```
Midas Phase 1 (DONE) ───────────►   [Sysifos arc] ───────────────►   Midas Phase 3
  1.1✓ 1.2✓ 1.3✓ 1.4✓ 1.5✓            P1 (1.1→1.3)                       3.1 ─┐
  (1.6 → Sysifos arc)                  P2 (2.1→2.6)                       3.2 ─┤  (needs Arges)
                                                                          3.3 ─┼─► closing
                                                                          3.4 ─┤
                                                                          3.5 ─┤  (needs Iris P4.2)
                                                                          3.6 ─┘

   Cross-stream gates into Midas Phase 3:
     M3 (routing: Themis✓ + Iris/v0.1.0 pending)  ──► S3.1 (Golem-Investment routing)
     Iris P4.2 (artifact system)                  ──► S3.5 (dashboards)
     Arges (kantheon PG worker, arges/v0.1.0 — arc planned) ──► S3.2 (Q&A green path)
```

Critical path (post-split): Midas P1 ✓ → Sysifos arc (P1 → P2) → Midas Phase 3 (3.1 → 3.2 → 3.3 → 3.4 → 3.5 → 3.6). Sysifos and Midas Phase 3 both write through Midas-core but are otherwise independent.

Some Phase 3 stages can parallelise (3.3 || 3.4 || 3.5 if developers split), but 3.2 gates the most-blocking dependency (the Arges PG worker). The conservative default is strict sequence; if Arges slips, run 3.1/3.3/3.4/3.5 and defer 3.2.

---

*Plan doc owner: Bora. Lives in `docs/implementation/v1/midas/`. Update on every scope, deliverable, or sequencing change. Revision history via git.*
