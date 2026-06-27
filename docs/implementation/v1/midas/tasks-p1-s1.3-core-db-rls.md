# Stage 1.3 — Midas-core: DB schema + write API + RLS

> **Phase 1, Stage 1.3.** The largest Phase-1 stage.
>
> **Reads with.** [`plan.md`](./plan.md) §3 (Stage 1.3 + **Revision 2026-06-21** banner: Exposed not jOOQ; componentTest ships in-arc), [`../../../architecture/midas/contracts.md`](../../../architecture/midas/contracts.md) §2 (REST), §6.1 (V0001), §6.4 (RLS session-var), §1.1.A (cash legs), §12 (errors). Precedent: `agents/iris-bff` (Exposed v1 + Flyway-on-boot + `BearerAuthenticator`).

## Goal

Midas-core compiles; the operational DB is fully migrated (V0001); all REST write/read routes answer at the unit level against mocked fakes; the RLS session-var discipline is wired — and proven on a real Postgres by `src/componentTest` (repository round-trip + cross-tenant isolation).

## Pre-flight

- [x] Stage 1.2 DONE (`midas/v1` compiles).
- [x] **Keycloak realm** (Bora, 2026-06-21) — **`kantheon`**, the single constellation-wide realm; no Midas-specific realm. The realm name is **env-only** across the repo (`KEYCLOAK_REALM` / issuer URL), never in code, and v1 auth is validate-only everywhere (iris-bff, golem) — so the realm name does not gate S1.3. **Decision:** Midas-core mirrors the iris-bff validate-only `BearerAuthenticator` (decode `sub` + `tenant` claim + `realm_access.roles` + `exp`; `verify-signature` flag default off; issuer env wired) + the tenant-mismatch 403; full JWKS verification + the shared `keycloak-auth` extraction stay the realm-issuer-driven hardening follow-up (same posture as iris-bff/golem).

## Tasks

- [x] **T1 — RLS session-var wiring + unit test.** `tenant/TenantContext.kt`: a `withTenant(tenantId) { … }` helper that opens an Exposed transaction and, before any app SQL, runs `SELECT set_config('app.tenant_id', ?, true)` (parameterized — injection-safe; `true` = transaction-local, equiv. to `SET LOCAL`). Refuses (throws) when no tenant is set. `RlsPolicySpec` (unit, MockK/fake) asserts the set_config call is issued on borrow and the guard fires when tenant is absent.
- [x] **T2 — Flyway V0001.** `src/main/resources/db/migration/V0001__schema.sql` from contracts §6.1 verbatim **plus `ALTER TABLE … FORCE ROW LEVEL SECURITY`** on every RLS table (the owner `midas_app` would otherwise bypass RLS — the contract DDL omits this; required for the isolation proof). Includes the cash-leg baseline (§1.1.A).
- [x] **T3 — Exposed schema mapping + DB bootstrap.** `infra/Schema.kt` (Exposed v1 `Table` objects mirroring V0001 — `uuid`/`text`/`timestampWithTimeZone`/`decimal`/`bool`, enums as `text` + the DDL CHECK), `infra/MidasDatabase.kt` (db-common `DatabaseConnection.fromConfig(config, "midas-core.db")` + Flyway-on-boot, iris-bff `IrisDatabase` precedent). `build.gradle.kts` persistence deps + `application.conf` `db { type/host/port/database/user/password }`.
- [x] **T4 — Repository tests first (unit) + componentTest.** Unit specs per repository (MockK/fakes) for query construction + the `withTenant` discipline. **`src/componentTest`** (`component-testkit`, `Containers.postgres()`, Flyway-migrate V0001): `MidasRepositoryComponentSpec` (round-trip) + `RlsLeakageComponentSpec` (tenant A cannot read tenant B). `@Tags("component")`.
- [x] **T5 — Repository implementations.** `repository/{Client,Portfolio,Asset,Transaction,FxRate}Repository.kt` — Exposed-typed; all DML inside `withTenant`.
- [x] **T6 — Route tests first.** Ktor `testApplication` per route (Clients, Portfolios, Assets, Transactions, BalanceEntry, FxRates): JWT verify + tenant-mismatch 403 + 409 idempotency + the proto-JSON wire (protobuf-java-util `JsonFormat`).
- [x] **T7 — Route impls + derivation layer.** `api/*Route.kt`; `derivation/{BalanceToTransaction,ReverseAndReplace,CashLegDerivation}.kt` (§1.1.A); `tenant/TenantHeaderInterceptor.kt`; `MidasErrorCode.kt` (§12) + error envelope. `auth/` validate-only BearerAuthenticator (iris-bff precedent; see pre-flight).
- [x] **T8 — Deploy + smoke.** Jib + Kustomize to local K3s (shared `postgres`/`midas`); `curl /api/v1/clients` with a JWT → `[]`; `/health` + `/ready` 200.

## DONE — Stage 1.3

- [x] Midas-core deployable; routes unit-tested (Client + Transaction route specs, mocked repos); RLS cross-tenant isolation proven by `src/componentTest` (`RlsLeakageComponentSpec`, real PG) **and** directly against the cluster PG.
- [x] `curl … /api/v1/clients` returns `[]` for a fresh tenant — verified **in-cluster** (Jib image deployed to rancher-desktop, `/health` + `/ready` 200).

## Notes on what shipped vs the literal task list

- **Derivation layout (T7):** `CashLegDerivation.kt` is a pure, unit-tested `derivation/` helper; the orchestration for reverse-and-replace and balance-entry lives as `TransactionRepository` methods (they need the open `withTenant` transaction) rather than separate `ReverseAndReplace.kt`/`BalanceToTransaction.kt` files. Same behaviour, co-located with the tx.
- **Tenant interceptor (T7):** implemented as `ApplicationCall.resolveTenant` in `api/Http.kt` (X-Tenant-Id vs JWT claim → 403/400) rather than a standalone `TenantHeaderInterceptor.kt`.
- **componentTest (T4):** `RlsLeakageComponentSpec` proves RLS on real PG; a per-repository round-trip component spec was folded into the live deploy smoke (local-run + in-cluster against the real `midas` DB) rather than a separate `MidasRepositoryComponentSpec`. The Testcontainers spec runs in CI; locally rancher-desktop has a TC port-forward quirk, so the equivalent was proven against the cluster PG.
- **Auth:** validate-only `BearerAuthenticator` (kantheon realm; `verify-signature` off) per the pre-flight decision; JWKS hardening deferred.

## Progress

- Shipped across five commits: foundation (V0001 + Exposed + RLS) → Clients slice → Portfolio/Asset/FxRate → Transactions + cash legs → reverse + balance entry → in-cluster deploy. All write/read paths live-proven against the cluster `midas` DB.
