# Stage 1.2 — First real-dependency component specs

> **Phase 1, Stage 1.2.** First real-dep coverage against a real database engine.
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §"Phase 1", [`../../../architecture/testing/architecture.md`](../../../architecture/testing/architecture.md) §2.2 + §9 (Mac/MSSQL caveat), [`../../../../EXAMPLES.md`](../../../../EXAMPLES.md) §9, [`../../../../AGENTS.md`](../../../../AGENTS.md) §1.4.
>
> **Status.** ✅ Done 2026-06-20 (Bora / Claude). **Scope change:** the planned Charon↔Postgres spec is **deferred** — Charon's DB extract/ingest edge isn't built yet (see below). Shipped the Brontes↔MSSQL spec + the shared fixtures + the WireMock-container helper.

## Goal

A Proteus-emitted SQL string is verified against a **real MSSQL** via Brontes's actual worker pipeline, in Testcontainers, in `componentTest`. A shared `component-testkit` module means later specs (and the integration tier) don't re-declare containers or the WireMock admin protocol.

## Scope change — Charon Postgres spec deferred (decided with Bora, 2026-06-20)

The plan named two specs: `CharonPostgresComponentSpec` and `BrontesMssqlComponentSpec`. On inspection, **Charon has no DB edge yet** — `AdbcReader`/`AdbcWriter`/`ConnectionRegistry` are explicitly deferred to *Charon's own* Phase 2 / Stage 2.1, and `CharonMoveExecutor` returns `NotYetImplemented` for every DB source/target (only S3/SeaweedFS + Redis are built). A round-trip spec would be testing code that doesn't exist.

**Decision (Bora):** defer Charon, ship Brontes now. The `CharonPostgresComponentSpec` lands when Charon's DB edges do (Charon Phase 2 Stage 2.1) — at which point the `Containers.postgres()` factory + the `SqlScripts` seed runner are already here for it. The Postgres factory is proven now by the testkit self-test, so the lane is ready.

## Tasks

- [x] **T1/T3 — Shared container fixtures + `component-testkit` module.**
  New `shared/libs/kotlin/component-testkit` (consumed via `componentTestImplementation`):
  - `Containers` — pinned factories: `postgres()` (`postgres:16-alpine`, native multi-arch), `mssql()` (`mcr.microsoft.com/mssql/server:2022-latest`, **amd64-only**, EULA accepted, platform pinned `linux/amd64`, 5-min startup headroom), `wiremock()` (`wiremock/wiremock:3.13.2`).
  - `CiOnly` — Kotest `Condition` gating the MSSQL spec to CI / `-DmssqlLocal`.
  - `wiremock.WireMockAdmin` — the `/__admin` load protocol (T4).
  - `sql.SqlScripts` — GO-batched seed runner (driver-agnostic).
  Catalog: added `testcontainers-mssqlserver` + `testcontainers-postgresql` (2.x `testcontainers-` artifact prefix, `org.testcontainers.<db>` package).

- [x] **T2/T3 — `BrontesMssqlComponentSpec` (CI-only).**
  `@Tags("component") @EnabledIf(CiOnly::class)`. Boots real MSSQL, creates `kantheon_local`, seeds via `SqlScripts.runResource`, then drives the **real `ExecutePipeline`** (faked `TranslatorClient` returning the SQL = the "Proteus-emitted" string) for a JOIN+filter over `dbo.sample_orders ⋈ dbo.sample_regions`. Asserts: no error message, row count = 2, schema fingerprint present, and — by deserializing the Arrow IPC — the column list + a sampled JOIN-resolved value (`region_name[0] == "Europe"`). This is the real `pool → JDBC → ResultSet → Arrow IPC → ResultBatch` path; only the translator is faked.

- [x] **T4 — WireMock-container helper.**
  `WireMockAdmin` (JDK `HttpClient`, no extra deps) implements the exact runtime fixture-load the integration tier reuses in-cluster (contracts §3.2): `POST /__admin/mappings/import`, `POST /__admin/reset`, `GET /__admin/requests`. Proven by `WireMockContainerHelperSpec` (testkit's own componentTest): load fixture → stubbed `GET /hello` returns 200 `world` → journal shows the call → reset clears it.

- [x] **T5 — Seed fixtures under componentTest resources.**
  `workers/brontes/src/componentTest/resources/seed/mssql-sample.sql` (reuses the `deployment/local/mssql` shape + adds `sample_regions` for the JOIN). The WireMock fixture lives at `…/component-testkit/src/componentTest/resources/wiremock/testkit/hello/mappings.json`.

- [x] **T6 — Wired into `componentTest` + cluster-independence.**
  `just test-component` (all modules) runs: `SmokeComponentSpec`, the testkit's `WireMockContainerHelperSpec` (WireMock + Postgres), and Brontes (skipped locally). No Kubernetes / `infra-up` touched. Local wall-clock ≈ 30 s (smoke + WireMock + Postgres-pull on first run).

## DONE criteria

- [x] `BrontesMssqlComponentSpec` is **CI-gated** — *skipped* on the arm64 laptop under `just test-component` (no results emitted, no failure), runs on the native-amd64 CI runner.
- [x] Shared `containers/` fixtures used by both Brontes (now) and the testkit self-test; all image tags pinned.
- [x] WireMock-container helper proven (the integration tier will reuse `WireMockAdmin` verbatim).
- [x] No cluster involvement; `test-all` (154 tasks) stays green and mocked-only at the `test` rung.
- [ ] **CharonPostgresComponentSpec** — deferred to Charon Phase 2 Stage 2.1 (DB edges).

## Validation status (faithful) — the MSSQL spec was NOT green-run locally

The Brontes spec **compiles** and is **correctly CI-gated** (clean skip locally). It could **not** be green-validated on the dev machine: SQL Server 2022 under amd64 **qemu emulation on Apple Silicon segfaults** — empirically confirmed (`qemu: uncaught target signal 11 (Segmentation fault)`, container exit 139), even with the 5-min startup window. The harness drove the real `ExecutePipeline` up to the container-boot wall; the failure is the emulated SQL Server process, not the spec. **This is exactly why the spec is `CiOnly`.**

A fully green run requires the native-amd64 CI runner. Note `ci.yml` triggers only on `push: main` / `pull_request → main`, so the `test-component` step (and this spec) will first run when the `testing` branch is **opened as a PR to `main`** (or run on any amd64 host). Until then the spec is verified by compilation + skip-behavior + code review of the (real) pipeline path it drives.

## Notes for the executor / follow-ups

- When Charon's DB edge lands: add `CharonPostgresComponentSpec` using `Containers.postgres()` + `SqlScripts`, plus `org.postgresql:postgresql` on Charon's `componentTestImplementation` for the JDBC driver. The factory + seed runner are already in place.
- The local fast component loop is **Postgres + WireMock** only; MSSQL coverage is delegated entirely to CI (no Rosetta/qemu needed for a green `just test-component`).
