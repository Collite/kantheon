# Golem Phase 2 · Stage 2.1 — golem/v1 proto + module skeleton + persistence

> **Arc.** Golem Phase 2 (template core). **Branch.** `feat/golem-p2-s2.1-proto-skeleton`.
> **Companions.** [`plan.md`](./plan.md) §4 Stage 2.1, [`../../../architecture/golem/contracts.md`](../../../architecture/golem/contracts.md) §1/§4, [`../../../architecture/golem/architecture.md`](../../../architecture/golem/architecture.md) §3.
> **Goal.** Module compiles; persistence green.

## Persistence-stack note

Contracts §4 says "jOOQ", but the established kantheon precedent (iris-bff, the most recent persistence
arc) is **Exposed v1 + `db-common` `DatabaseConnection` + Flyway**. Stage 2.1 follows that precedent
for one idiom across the repo. (The `golem_turns` shape is unchanged.)

## Tasks

- [x] **T1 — `golem.proto`.** `shared/proto/.../golem/v1/golem.proto` per contracts §1 (`java_multiple_files`,
  imports envelope/v1 + themis/v1 + common/v1). Bindings generate + compile.
  **Contract fix:** `Status` enum values prefixed `STATUS_*` — proto3 enum values are package-scoped
  siblings, so a bare `CLARIFICATION` collided with `PlanSource.CLARIFICATION`. contracts.md updated.
- [x] **T2 — module skeleton.** `agents/golem` (`org.tatrman.kantheon.golem`): `Application.kt` (programmatic
  Ktor bootstrap via `shared.ktor`), `Wiring.kt` (repo selection + fail-fast migration), `api/HealthRoutes`
  (`/health` + `/ready`), `application.conf`, `k8s/{base,overlays/local}` (port 7420, `imagePullPolicy: Never`).
  `settings.gradle.kts` include added (auto-picked-up by CI `just test-all` / `lint-all`).
- [x] **T3 — Flyway `golem_turns` + Exposed.** `V1__golem_core.sql` per contracts §4 (status CHECK,
  `request_id` index); `GolemTurnsTable` (Exposed; JSONB as opaque strings, `uuid()` ↔ `java.util.UUID`
  at the boundary).
- [x] **T4 — `TurnsRepositorySpec` (tests first).** Against the in-memory fake: insert→findById round-trip,
  unknown→null, duplicate-id reject, `findByRequestId` latest-wins (clarification→resume), status wire
  round-trip + drift throw. Real-PG fidelity deferred to the integration suite (planning-conventions §4).
- [x] **T5 — repositories.** `TurnsRepository` interface; `InMemoryTurnsRepository`; `ExposedTurnsRepository`
  (insert / findById / findByRequestId).
- [x] **T6 — CI wiring + lint.** Module included in `settings.gradle.kts` → covered by `./gradlew test` /
  `ktlintCheck`. ktlint clean.

## DONE

Module compiles; **persistence green (6 tests)**, ktlint clean, `:agents:golem:build` passes.
The REST/SSE answer surface, Shem/PackageContext, Koog graph and platform clients land in Stages 2.2–2.4.
