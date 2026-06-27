# Stage 1.2 — Module + persistence + checkpointer

> **Phase 1, Stage 1.2.**
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3.2, [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §4 (DDL — authority) + §3a (PD-5 checkpoint shape), [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §3 (module map) + §5 ("Handle table", "Events"), [`../../planning-conventions.md`](../../planning-conventions.md) §4 (testing policy — **mocked unit tests only**).

## Goal

The `agents/pythia` Kotlin module exists, compiles, serves `/health`; Flyway DDL per contracts §4 applied; jOOQ-generated records; repositories + `Checkpointer` implemented and **specced against an in-memory / mocked DB fake** (real-PG fidelity is deferred to the separate integration-test suite — planning-conventions §4). **End state:** `just test-kt pythia` green; `/health` returns `{"status":"ok"}`.

## Pre-flight

- [ ] Stage 1.1 DONE — `pythia/v1` bindings compile (this module depends on `:shared:proto`).
- [ ] Branch `feat/pythia-p1-s1.2-persistence-checkpointer`.
- [ ] Confirm the kantheon Flyway+jOOQ idiom by reading an existing service that uses it (Themis or Charon module `build.gradle.kts` + `db/migration/` layout). Mirror its plugin + config wiring exactly.

## Tasks (TDD-shaped: T3 + T5 write tests first; T4 + T6 make them pass)

- [ ] **T1 — Module skeleton.**

  Create `agents/pythia/` per architecture §3 module map: `build.gradle.kts` (kotlin.jvm + ktor + `:shared:proto` + Flyway + jOOQ + Kotest + MockK + kotlin-logging; **no Testcontainers** — testing policy §4), `src/main/kotlin/org/tatrman/kantheon/pythia/App.kt`, `src/main/resources/application.conf` (HOCON; port 7090; `pythia.handles.inline-max-bytes`, `pythia.awaiting.ttl-hours = 24`, DB + NATS config blocks), `/health` + `/ready` routes (architecture §7 gates — `/ready` 503 until DB migrated). Add `include(":agents:pythia")` to `settings.gradle.kts`. **Kotlin source root `org.tatrman.kantheon.pythia`** (CLAUDE.md §4 — the constellation reservation governs proto contracts; agent Kotlin roots use `kantheon.<agent>`).

  Follow **ai-platform `EXAMPLES.md` §1a/§1b** for the Ktor `Application.kt` shape (under ~45 lines; `buildJsonObject` for `/health`, never `mapOf` — §2a).

  Acceptance: `just build-kt pythia` green; `just test-kt pythia` (no tests yet) green; `/health` 200 via a `testApplication` smoke spec.

- [ ] **T2 — Flyway DDL + jOOQ codegen.**

  Create `src/main/resources/db/migration/V1__pythia_core.sql` with the **six tables verbatim from contracts §4**: `pythia_investigations`, `pythia_hypotheses`, `pythia_steps`, `pythia_handles` (incl. `inline_data BYTEA` for `PgResultSnapshot`), `pythia_checkpoints` (`scheduler_state JSONB`, `diff JSONB`, `reason` in `awaiting_* | plan_revised | batch_completed`), `pythia_events` (`PRIMARY KEY (investigation_id, sequence)`). Include the `awaiting_since` / `awaiting_ttl_until` columns on `pythia_investigations` (used by the Stage 1.3 TTL sweeper). Wire jOOQ generation against the migrated schema.

  Acceptance: migration applies cleanly to a throwaway DB during jOOQ codegen; generated records appear for all six tables.

- [ ] **T3 — Repository specs (tests first).**

  Create `src/test/kotlin/.../persistence/InvestigationRepositorySpec.kt` etc. against an **in-memory / mocked DB fake** (a hand-rolled in-memory map-backed implementation of the repository interfaces, or jOOQ over an in-memory H2-in-PG-mode fake — pick one and document the choice; **real-PG fidelity is the integration suite's job**, §4). Cover: insert+load `Investigation` round-trip (JSONB `request`/`resolution`/`plan` survive); status update; hypothesis upsert keyed `(investigation_id, hyp_id)`; step upsert with `output_handle`; handle insert incl. an inline `PgResultSnapshot` (BYTEA round-trip); event append is gap-free and ordered by `sequence`.

  Acceptance: specs compile and **fail** (no impl yet) — red bar confirms the tests exercise real behaviour.

- [ ] **T4 — Repositories.**

  Define repository interfaces in `persistence/` + the production jOOQ-backed implementations + the in-memory fake used by T3. One repo per aggregate: `InvestigationRepository`, `HypothesisRepository`, `StepRepository`, `HandleRepository`, `EventRepository` (sequence-assigning append — `SELECT max(sequence)+1 … FOR UPDATE` or a per-investigation sequence; document the concurrency contract), `CheckpointRepository`. JSONB columns (de)serialise via the kantheon proto-JSON helper (EXAMPLES.md §2).

  Acceptance: T3 specs green.

- [ ] **T5 — `CheckpointerSpec` (tests first).**

  Create `src/test/kotlin/.../persistence/CheckpointerSpec.kt`. Pin the design §5 + contracts §3a behaviour:
  - Snapshot taken on each of the three `reason`s (`awaiting_*` on entering any AWAITING_* / `plan_revised` on revision / `batch_completed`).
  - **Diff-based** storage: a checkpoint stores the delta against the prior `scheduler_state`, not a full copy each time; assert two sequential checkpoints store a small diff.
  - **Restore round-trip:** `restore(investigation_id)` reconstructs the latest scheduler state by folding diffs; assert equality with the pre-checkpoint state.
  - **Resume idempotency:** a status-conditional `UPDATE … WHERE status = :awaiting` returns rows-affected once; a second resume attempt affects 0 rows (first-signal-wins — architecture §5). Spec both paths.
  - **PD-5 checkpoint shape (contracts §3a):** per-handle, the checkpoint records the *recipe* (Charon move spec / Metis fit spec placeholder structs — typed now, consumed in Phase 4) **and the Arrow-fingerprint** at materialisation. Assert these fields persist + restore. (No live Charon/Metis here — the structs are recorded, the probes that consume them are Phase 4.)

  Acceptance: specs compile and fail (no `Checkpointer` yet).

- [ ] **T6 — Checkpointer.**

  Implement `persistence/Checkpointer.kt`: `checkpoint(investigationId, reason, schedulerState)` (diff vs latest, append to `pythia_checkpoints`), `restore(investigationId): SchedulerState` (fold diffs), `tryResume(investigationId, fromStatus): Boolean` (status-conditional UPDATE, returns whether this caller won). Diffs over a stable JSON representation of `SchedulerState` (a Kotlin data class capturing frontier, in-flight step ids, per-handle recipe+fingerprint, budget counters). Use a simple structural JSON-patch (no external lib needed — a field-level diff is sufficient and easier to reason about than RFC-6902; document the format).

  Acceptance: `CheckpointerSpec` green; `just test-kt pythia` all green.

## DONE — Stage 1.2

- [ ] All tasks checked; `just test-kt pythia` green on clean checkout.
- [ ] `/health` 200 via smoke spec; module compiles.
- [ ] Integration-suite carry-overs **recorded** (not blocking): real-PG repository fidelity, real-PG checkpoint diff/restore under concurrency, jOOQ-vs-live-schema drift check. List them in the PR description under "Deferred to integration suite".
- [ ] CI green on `[pythia-p1-s1.2] persistence + checkpointer`.

## Library / pattern references

- **contracts §4** — DDL (verbatim); **contracts §3a** — checkpoint recipe+fingerprint shape; **architecture §5** — checkpointer behaviour + resume idempotency.
- **ai-platform `EXAMPLES.md` §1a/§1b** (Ktor), **§2** (proto-JSON serialization).
- **Existing kantheon service** (Themis or Charon module) — copy the Flyway + jOOQ Gradle wiring; do not invent a new idiom.
- **planning-conventions §4** — mocked-unit-tests-only; Testcontainers/real-PG → integration suite.

## Out of scope for Stage 1.2

- Orchestrator / state machine / event emitter / REST — Stage 1.3.
- Live PG / NATS verification — integration suite.
- The actual probes that read PD-5 recipes (liveness `Describe`/`GetStatus`) — Phase 4 Stage 4.1/4.2.
