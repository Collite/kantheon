# Iris — Stage 1.2: iris-bff skeleton + session persistence

> Branch: `feat/iris-arc` (whole arc → one PR). Plan: [`plan.md`](./plan.md) §3 Stage 1.2. Contracts: [`../../../architecture/iris/contracts.md`](../../../architecture/iris/contracts.md) §2.1 (session REST), §3 (persistence shapes), §6 (config). Pre-flight: Stage 1.1 done (`envelope/v1` + `iris/v1` protos).
>
> **Goal (testable boundary).** `agents/iris-bff` compiles, migrations are defined, session CRUD is green: `just test-kt iris-bff` passes (unit + component). `/v1/session` CRUD against local PG is the deferred live-smoke (Stage 1.4 / integration suite).

## Decisions locked at stage start

- **Persistence = Exposed (v1) + HikariCP + Flyway**, not jOOQ. The plan text said "jOOQ" but the repo's convention (version catalog, `shared/libs/kotlin/db-common`, hebe CLAUDE.md "Exposed DSL not ORM; Flyway for migrations") is **Exposed**. Followed the repo.
- **Two store implementations behind a `SessionStore` interface.** `InMemorySessionStore` is the working v1 store + the unit/component-test fake (fully tested here). `ExposedSessionStore` is the Postgres binding (compile-verified; library-handled JSONB via `exposed-json`); its real-PG fidelity is **deferred to the integration-test suite** per the testing policy — shipping hand-written, un-run JSONB SQL as "tested" would be dishonest. Store selection is config-driven (`iris.db.enabled`).
- **JSONB columns via `exposed-json`** (`jsonb(name, { it }, { it })` — raw-string serialize). Added `exposed-json` to the catalog. Exposed 1.0 `uuid()` columns are `kotlin.uuid.Uuid`; the domain stays on `java.util.UUID` with conversion at the Exposed boundary. SQL comparison operators are the **top-level** `org.tatrman…exposed.v1.core.{eq,neq,greater,and}` (the `SqlExpressionBuilder` members are deprecation-errors in 1.0).
- **Auth = validate-only bearer.** The JWT payload is decoded for `sub` + tenant claims; full JWKS signature verification is a hardening follow-up (`iris.auth.verify-signature`, Stage 1.4). Missing/malformed bearer → 401. The user bearer is retained for downstream OBO forwarding (Phase 3).
- **Wire = proto-canonical camelCase JSON** for the session DTOs (consistent with the envelope/v1 wire decision, Stage 1.1).

## Tasks

- [x] **T1 — Module skeleton + build + `Application.kt`.**
  `agents/iris-bff/build.gradle.kts` (Ktor Netty, `shared.ktor`/`shared.otel`/`shared.logging`/`db-common`, `:shared:proto`, Exposed core/jdbc/java-time/json, Hikari, Postgres, Flyway, Jib); `settings.gradle.kts` include; `Application.kt` (≤45 lines) + `Wiring.kt` (store selection + StatusPages); `application.conf` + `logback.xml`. Port **7410**.

- [x] **T2 — Flyway migrations + Exposed tables.**
  `V1__iris_core.sql` — all seven tables day one (contracts §3/§3.1/§3.2/§3.3): `iris_sessions`, `iris_turns` (incl. `origin`/`origin_ref`), `iris_snapshots`, `iris_v2_threads`, `iris_audit`, `iris_feedback`, `iris_artifacts`. `infra/Tables.kt` maps the three the store touches at 1.2; `infra/IrisDatabase.kt` runs Flyway-on-boot (mirrors hebe `Db.migrate`).

- [x] **T3+T4 — `SessionStore` (interface + InMemory + Exposed) + spec.**
  `domain/SessionStore.kt` + records; `InMemorySessionStore` (thread-safe, snapshot-before-discard, monotone seq); `infra/ExposedSessionStore.kt`. `SessionStoreSpec` (Kotest, 8 cases): create/get/list, monotone seq (incl. no-reuse across discards), getTurn, summary title, reset (snapshot + discard-all + clear), discardTurnsAfter (anchor-relative + no-op-on-last).

- [x] **T5 — Session REST + health + bearer + component test.**
  `api/SessionRoutes.kt` (`/v1/session` POST, `/v1/sessions` GET, `/v1/session/{id}` GET, `/v1/session/{id}/reset` POST, `/v1/session/{id}/turn/{turnId}` GET; owner-only, 404-not-leak), `api/HealthRoutes.kt` (`/health` + `/ready`, `buildJsonObject` not `mapOf`), `api/Auth.kt` (validate-only bearer). `SessionRoutesSpec` (Ktor `testApplication`, 7 cases): health/ready, 401-no-bearer, create, owner-visibility + 404-intruder, list-scoping, 400-bad-uuid, reset.

- [x] **T6 — K8s manifests + task doc + verify.**
  `k8s/{base,overlays/local}` (Jib `iris-bff:dev`, `/ready` readiness, `imagePullPolicy: Never` local; `IRIS_DB_ENABLED=false` until PG provisioned). This doc. `just test-kt iris-bff` + `:agents:iris-bff:build` (ktlint incl.) green.

**DONE means:** `./gradlew :agents:iris-bff:build` green (compile + 15 tests + ktlint); `SessionStore` behavioural contract green against the in-memory fake; session REST + health + bearer component-tested. **Stage 1.2 DONE.**

## Carry-forward notes

- **`ExposedSessionStore` runtime validation.** Compile-verified + library-handled JSONB/UUID, but not run against a live PG in this environment. Validate in the integration-test suite (or Stage 1.3's component pass against a real/containerised PG). The audit/feedback/artifact/v2-thread tables exist in the migration but have **no Exposed mappings or write methods yet** — those land with their features (Stage 1.3 audit write; Phase 4 artifacts/feedback).
- **JWKS signature verification** (`iris.auth.verify-signature = true`) is unimplemented — validate-only at v1. Wire the Keycloak issuer/JWKS check in Stage 1.4 (live deploy) — the config keys + seam are in place.
- **`deployment/local` infra** must provision the Kantheon PG `iris` database (kantheon-architecture §7.1) before `IRIS_DB_ENABLED=true`.

## Up / across

- Up: [`./README.md`](./README.md). Plan: [`./plan.md`](./plan.md). Prev: [`tasks-p1-s1.1-envelope-proto.md`](./tasks-p1-s1.1-envelope-proto.md).
- Next: Stage 1.3 — dispatch + SSE multiplex (transitional `/v2`).
