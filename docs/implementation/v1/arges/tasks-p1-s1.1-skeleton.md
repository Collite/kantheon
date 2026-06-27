# Stage 1.1 — Module skeleton + build wiring + capabilities/status

> **Phase 1, Stage 1.1.** Mirror Brontes; boot an empty-pipeline Postgres worker that advertises itself.
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3 (Stage 1.1), [`../../../architecture/arges/architecture.md`](../../../architecture/arges/architecture.md) §2 (mirror table), [`../../../architecture/arges/contracts.md`](../../../architecture/arges/contracts.md) §1–2, §5. **Template:** `workers/brontes/` (read it side-by-side).

## Goal

`workers/arges` compiles + boots; `GetCapabilities`/`GetStatus` answer; Kyklop can discover it; `execute` is a `not_implemented` stub. Package root `org.tatrman.kantheon.arges`.

## Pre-flight

- [ ] `workers/brontes` readable as the template; `org.tatrman.worker.v1` + `plan/v1` + `proteus/v1` compiled (**met**).
- [ ] Branch `feat/p1-s1.1-arges-skeleton` from `main`.

## Tasks

- [ ] **T1 — Module skeleton.** Copy Brontes's tree into `workers/arges/`: `build.gradle.kts`, `src/{main,test,componentTest,bench}/kotlin`, `src/main/resources/{application.conf,logback.xml}`, `k8s/` (Chart.yaml + templates). Strip Brontes logic to stubs. Acceptance: directories present; module compiles empty.
- [ ] **T2 — `build.gradle.kts`.** Adapt Brontes's: replace `libs.mssql.jdbc` → `libs.postgresql`; keep grpc/ktor/arrow/typesafe-config/logging deps + `componentTest` deps (`component-testkit`, proto, arrow, coroutines); `application { mainClass = "org.tatrman.kantheon.arges.ApplicationKt" }`; jib image `arges:dev`, ports `7302`/`7303`, Arrow `--add-opens` on `test` + `componentTest`. (Drop the MSSQL-only `mssqlLocal` forwarding.)
- [ ] **T3 — `PipelineContext.tenant_id` verification.** Confirm `org.tatrman.plan.v1.PipelineContext` carries the caller `tenant_id`. If absent at HEAD, add it as an additive field + `just proto` (archived brief §3.1 contingency). Record the exact field name `Execute` will read. Acceptance: a unit test reads `tenant_id` off a built `PipelineContext`.
- [ ] **T4 — Settings + CI.** Add `include("workers:arges")` to `settings.gradle.kts`. Confirm the root `subprojects {}` auto-wires kotest + `componentTest`/`integrationTest`. `just build-kt workers/arges` green; CI auto-detects.
- [ ] **T5 — `Application.kt` + gRPC + config.** Copy Brontes's `Application.kt`: Ktor health on 7302, gRPC `WorkerService` on 7303. `application.conf` with the `pg-midas` `connections` block (contracts §2), `requires-tenant-id=true`, `read-only=true`, telemetry off by default.
- [ ] **T6 — `ConnectionPoolManager` + `ConnectionConfig` (PG) + `WorkerServiceImpl` (caps/status).** Adapt the pool manager: `jdbc:postgresql://host:port/database` URL build, `default-schema="public"`, `read-only`, `requires-tenant-id` parse, `SELECT 1` probe. `WorkerServiceImpl`: `engine_name="postgres"`, `supported_dialects=["POSTGRESQL"]`, `supports_stateful_sessions=false`, status readiness/probe/dep shape from Brontes; `execute` → single `not_implemented` error batch. **Tests first:** URL build + config parse; capability/status shape.

## DONE

- [ ] `just build-kt workers/arges` + unit tests green; CI passes.
- [ ] Pod boots; `GetCapabilities` advertises `postgres`/`POSTGRESQL`; `GetStatus` shows the configured connection.
- [ ] Kyklop with `KYKLOP_WORKER_ARGES_ENDPOINT` set lists Arges as a candidate.
