# Fork — Stage 5.0: `infra/` tree + auth/identity shared libs

> Branch: `feat/fork-p5-s5.0-infra-auth-libs`. Pre-flight: Phase 1 (shared-lib idiom; `db-common` already in-repo). Plan: [`plan.md`](./plan.md) Stage 5.0. Tracker: [`tasks.md`](./tasks.md).
>
> Sources: `shared/libs/kotlin/whois-common` (3 domain records: `UserRecord`, `UserIdRecord`, `UserSource`; only `kotlinx-serialization`), `shared/libs/kotlin/erp-sql-common/.../auth/` (4 files: `CachingTokenProvider`, `KeycloakTokenProvider`, `TokenProvider`, `TokenResponse` — verified **zero** imports from the rest of erp-sql-common). Introduces the top-level **`infra/`** directory (architecture §2.1). Contracts §1 (package roots), §6 (libs map).

- [x] **T1 — Introduce `infra/` in the build.**
  Added the top-level `infra/` tree to `settings.gradle.kts` (with a layout comment mirroring the Phase-1 `workers/` introduction). `infra/_smoke` placeholder module (`org.tatrman.infra.smoke`, one stub + a kotest spec + README) proves the build accepts the new tree; retired when the real modules land (whois 5.1, health 5.2, backstage 5.5). Settings evaluate green (`:infra:_smoke:help`).

- [x] **T2 — Fork `whois-common` (test first).**
  Copied `shared/libs/kotlin/whois-common` → kantheon; package `infra.whois.domain` → `org.tatrman.whois.domain`; provenance header in README. `UserRecordTest` exercises `keycloakId`/`erpId`/`allRoles` extensions + a JSON serialization round-trip (replaces the upstream `DummyTest`). Green.

- [x] **T3 — Extract `keycloak-auth` (test first).**
  New lib `shared/libs/kotlin/keycloak-auth`. Copied the 4 `erp-sql-common/.../auth/*.kt` files; package `infra.erp.sql.common.auth` → `org.tatrman.keycloak.auth`. **Guard test** `NoErpSqlCommonImportSpec` walks `src/main/kotlin` and fails on any `erp.sql.common` / `infra.erp` / `cz.dfpartner` reference — the extraction's whole justification. The two forked unit specs (`CachingTokenProviderTest`, `KeycloakTokenProviderTest`) come along. Provenance header cites the original `erp-sql-common` auth path.

- [x] **T4 — Register + build.**
  `include(":shared:libs:kotlin:whois-common")` and `:keycloak-auth` in `settings.gradle.kts`; deps blocks (whois-common: kotlinx-ser-json; keycloak-auth: ktor-client core/apache/content-negotiation + kotlinx-ser + caffeine + typesafe-config + slf4j + coroutines per its imports). Both compile + test green.

- [x] **T5 — Provenance + libs-map cross-check.**
  Both libs' READMEs carry the `forked-from` provenance line; contracts §6 rows match the actual package roots (`org.tatrman.whois.domain`, `org.tatrman.keycloak.auth`) and consumers (whois service 5.1, Argos `WhoisRoleSource` 5.3).

- [x] **T6 — Stage exit.**
  `gradlew test ktlintCheck` green across the build (full sweep); `rg "infra\.erp\.sql\.common" shared/libs/kotlin/keycloak-auth` empty (source + README). Stage 5.0 checked in [`tasks.md`](./tasks.md).

**DONE means:** `infra/` is accepted by the build; `whois-common` + `keycloak-auth` are in-repo with green suites and **no** erp-sql-common dependency. **✅ Met 2026-06-24.**
