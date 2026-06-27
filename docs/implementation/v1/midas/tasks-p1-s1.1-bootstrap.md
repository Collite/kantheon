# Stage 1.1 — Arc bootstrap + shared Postgres infra

> **Phase 1, Stage 1.1.** First task list of the Midas arc.
>
> **Reads with.** [`plan.md`](./plan.md) §3 (Stage 1.1 + the **Revision 2026-06-21** banner — the four decisions this list implements), [`../../../architecture/midas/architecture.md`](../../../architecture/midas/architecture.md) §3 (module map), [`../../../architecture/midas/contracts.md`](../../../architecture/midas/contracts.md) §6 (DB schema, for the `midas` database bootstrap).
>
> **Sibling.** The Sysifos arc's [`tasks-p1-s1.1-bootstrap-proto.md`](../sysifos/tasks-p1-s1.1-bootstrap-proto.md) owns `agents/sysifos-bff`, `frontends/sysifos`, and `sysifos/v1` (decision #4). This stage does **not** touch them; its pre-flight `midas/v1` (Stage 1.2) is what the Sysifos bootstrap imports.

## Goal

The four **Midas-owned** modules exist and compile on stubs; a shared `postgres` runs in local K3s (`deployment/local/`) with the `midas` database + `midas_app` role provisioned; the build is green (unit + component) on empty stubs; CI knows the new modules.

## Pre-flight

- [x] **Themis arc Phase 1 closed** — capabilities-mcp running. *(Done — Themis at v0.2.0.)*
- [x] **Branch**: `feat/midas-p1-s1.1-bootstrap` from `main`. *(Done.)*
- [x] Keycloak realm name — **not needed for 1.1** (gates Stage 1.3).

## Tasks

- [x] **T1 — Module skeletons.** Create, each with `build.gradle.kts`, `src/main/kotlin/...`, `src/test/kotlin/`, `k8s/{base,overlays/local}/`:
  - `agents/midas/core/` — pkg `org.tatrman.kantheon.midas.core`; `App.kt` (empty Ktor `main` + `module`), `src/main/resources/application.conf`, `src/main/resources/db/migration/` (empty, V0001 lands in 1.3).
  - `agents/midas/loaders/excel/` — pkg `org.tatrman.kantheon.midas.loaders.excel`; `App.kt`, `application.conf`.
  - `services/report-renderer/` — pkg `org.tatrman.kantheon.report`; `App.kt`, `application.conf`.
  - `agents/midas/shem/` — **not a Gradle module** (holds `shem-investment.yaml`, Phase 3); create the directory with a `.gitkeep` + one-line `README.md`.

  Copy the `build.gradle.kts` shape from a recent service (`services/charon`) — kotlin/ktor + jib plugins, `shared/libs/kotlin/{otel-config,logging-config,ktor-configurator,db-common}` deps (db-common only for midas-core).

  Acceptance: all three Kotlin modules compile empty (`just build-kt agents/midas/core`, `.../loaders/excel`, `services/report-renderer` — **path form**, since `_resolve`'s `find -name` has no unique bare basename for the nested dirs).

- [x] **T2 — `gradle/libs.versions.toml`.** Add only what's missing and Phase-1-relevant — in practice **nothing is required for Phase 1**. Audit confirms present already: `postgresql`, `hikaricp`, `flyway-core`/`flyway-pgsql`, `apache-poi-ooxml`, `jib`, `exposed-*`, `testcontainers-postgresql`, `component-testkit`/`integration-harness` projects. **Do not add jOOQ** (decision #1). Defer `quartz` (Google-Finance poller) + `playwright-kotlin` (report PDF/HTML) to their Phase-3 stages — note this here rather than adding unused entries now.

  Acceptance: no catalog change needed; if any version bump is required for the skeletons it is centralised (no hardcoded versions).

- [x] **T3 — Settings + root build.** Add `include(":agents:midas:core")`, `include(":agents:midas:loaders:excel")`, `include(":services:report-renderer")` to `settings.gradle.kts`. **No convention plugin** — the root `build.gradle.kts` `subprojects { plugins.withId("org.jetbrains.kotlin.jvm") { … } }` block auto-wires kotest + the `componentTest`/`integrationTest` source sets; each module declares its own kotlin/ktor/jib plugins.

  Acceptance: `./gradlew projects` lists the three new modules; the unit-test isolation guard (root build) is satisfied.

- [x] **T4 — Justfile recipes.** Confirm the generic `build-kt`/`test-kt`/`deploy-kt`/`test-component` recipes resolve the new modules via the path form (they auto-detect). Make `local-infra-up` wait for Postgres + the `postgres-init` job. **No standalone `db-migrate` recipe** — migrations run **on app boot** via programmatic Flyway (the iris-bff precedent, `IrisDatabase.kt`); Midas-core does the same in Stage 1.3.

  Acceptance: `just build-kt agents/midas/core`, `just test-kt agents/midas/core`, `just test-component agents/midas/core` all resolve and run (green on empty stubs).

- [x] **T5 — Shared local Postgres.** Add a `postgres/` resource tree under `deployment/local/` (alongside the existing `mssql/`): `deployment.yaml` (postgres:16-alpine, single replica, 10 GiB PVC), `service.yaml`, `init-job.yaml` + `init-sql-configmap.yaml` creating the `midas` database + `midas_app` role (least-priv: CONNECT + schema usage; RLS-bypass off). Wire `postgres` into `deployment/local/kustomization.yaml`. Local overlay `imagePullPolicy: Never`. This is the **shared Kantheon PG** (kantheon-architecture §7.1) — later agents add their own databases to the same instance.

  Acceptance: `just local-infra-up` brings up `postgres` (`kubectl -n kantheon get pods` shows it ready); `psql ... -l` lists `midas`; connecting as `midas_app` to `midas` succeeds.

- [x] **T6 — CI extension.** Confirm `.github/workflows/ci.yml` picks the new modules up via Gradle auto-detect (no hardcoded module list to edit). Confirm `test-component` runs (the component tier is gated in CI). Add the `deployment/local/postgres` path to any local-infra smoke step if one exists.

  Acceptance: CI green on the branch (init → lint → test-all → test-component).

## DONE — Stage 1.1

- [x] All six tasks checked.
- [x] Build green (unit + component) on empty stubs: `:agents:midas:core`, `:agents:midas:loaders:excel`, `:services:report-renderer` all pass `test` + `componentTest` + `ktlintCheck`; `./gradlew projects` lists them.
- [x] All kustomize manifests build (`kubectl kustomize` on `deployment/local` + the three overlays).
- [x] **Live `local-infra-up` bring-up verified** on rancher-desktop: postgres pod 1/1 Ready, `postgres-init` job complete (`init.sql applied`), `\l` shows the `midas` database (owner `midas_app`), and `midas_app` connects to `midas`. *(Surfaced + fixed during bring-up: emptyDir vs PVC, init-pod Service-selector collision, glibc vs musl DNS — see commit `845abdc`. A pre-existing rancher-desktop CoreDNS/API-cert staleness was resolved by a rancher-desktop restart.)*
- [ ] CI green on the branch (pushed; awaiting Actions run).
- [ ] Tag `midas/bootstrap-v0.1.0`.
