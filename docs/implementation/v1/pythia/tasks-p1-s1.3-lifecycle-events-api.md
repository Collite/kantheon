# Stage 1.3 — Lifecycle + events + control surface

> **Phase 1, Stage 1.3.** Closes Phase 1 — tag `pythia/v0.1.0`.
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3.3, [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §2 (REST surface — authority), §3 (NATS subjects), [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §5 (orchestrator/events) + §7 (deployment gates), [`../../../architecture/kantheon-security.md`](../../../architecture/kantheon-security.md) §3.3 (PD-8 request admission), [`../../../design/pythia/Pythia-v1-Design.md`](../../../design/pythia/Pythia-v1-Design.md) §3.4 (transition table — authority), [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

The orchestrator drives the **12-status** lifecycle state machine over **scripted stage stubs** (no real planner/executor); events flow to the PG log + NATS publisher + SSE bridge incl. the coarse `pythia.lifecycle.{user_id}` subject; the full REST control surface answers; `GET /v1/investigations` lists per-user; the AWAITING_* TTL sweeper expires stale pauses; the pod deploys to local K3s and a fixture investigation walks `SUBMITTED → … → DONE`. **End state:** transition-table spec exhaustive-green; control-surface component tests green against `testApplication`; fixture e2e (on stubs) green.

## Pre-flight

- [ ] Stage 1.2 DONE — repositories + `Checkpointer` green.
- [ ] Branch `feat/pythia-p1-s1.3-lifecycle-events-api`.
- [ ] NATS JetStream reachability: add a boot-time smoke that connects to the configured NATS URL and degrades to PG-log-only with a warn if unreachable (architecture §7). The smoke is a real connection at boot; **tests mock the NATS publisher** (planning-conventions §4).

## Tasks (TDD-shaped: T1 writes the transition table spec first; the rest implement against it + their own component specs)

- [ ] **T1 — Exhaustive transition-table spec (tests first).**

  Create `src/test/kotlin/.../orchestrator/TransitionTableSpec.kt`. Encode the design §3.4 legal/illegal transition matrix **plus PD-11's `AWAITING_BUDGET_DECISION`** — 12 statuses, five AWAITING_*. Property-style: for every `(from, to)` pair, assert legal pairs are accepted and illegal pairs throw `IllegalTransition`. Pin specifically: each AWAITING_* is reachable only from its owning active phase; each resumes to a defined next status; terminal statuses (`DONE`/`FAILED`/`HALTED`/`INCONCLUSIVE`) accept no outbound transition. Cross-check the five AWAITING_* → control-endpoint mapping (contracts §2: resolution-input→`/answer`, plan-approval→`/approve-plan`, revision→`/approve-revision`, user-input→`/answer`, budget→`/budget-decision`).

  Acceptance: spec compiles and fails (no state machine yet); table has an assertion for every status pair (12×12 minus self).

- [ ] **T2 — `InvestigationOrchestrator` state machine (scripted stubs).**

  Implement `orchestrator/InvestigationOrchestrator.kt`: one coroutine per investigation in a supervised scope (architecture §5). Stage handlers are **scripted stubs** at this phase — `resolveStub`, `planStub`, `executeStub`, `synthesizeStub` advance status and emit the right events without real subsystems. On entering any AWAITING_*: checkpoint (Stage 1.2 `Checkpointer`) + emit `scheduler_drained` + record `awaiting_since`/`awaiting_ttl_until`. Resume via `Checkpointer.tryResume` (idempotent; second signal → 409 at the API layer).

  Acceptance: `TransitionTableSpec` green; a unit test drives one investigation `SUBMITTED→RESOLVING→PLANNING→EXECUTING→SYNTHESIZING→DONE` on stubs.

- [ ] **T3 — `EventEmitter` (PG log + NATS + lifecycle subject + degrade mode).**

  Implement `events/EventEmitter.kt`: every event → `pythia_events` (authoritative, sequence-assigned via the Stage 1.2 `EventRepository`) **then** NATS publish to `pythia.investigation.{id}.events` (stream `PYTHIA_EVENTS`, dedupe `(investigation_id, sequence)` — contracts §3). On **every status transition** also publish `InvestigationLifecycleEvent` to `pythia.lifecycle.{user_id}` (PD-2 — coarse, status-only). **Degrade-to-log-only:** if NATS is down, log + continue (events are never lost, only not-live — architecture §5/§7). Specs use a **mocked/in-memory NATS publisher** (real-NATS = integration suite, §4): assert PG-append-before-publish ordering, sequence monotonicity, lifecycle subject fired once per transition, degrade path logs without throwing.

  Acceptance: `EventEmitterSpec` green.

- [ ] **T4 — SSE bridge `GET /v1/investigations/{id}/events?from_seq=N`.**

  Implement the SSE endpoint (architecture §5, contracts §2): replay `pythia_events` from `from_seq` (PG), then tail live (NATS subscription, or PG-poll fallback in degrade mode). One `InvestigationEvent` per SSE `data:` frame (proto-JSON). iris-bff consumes this rather than NATS directly (divergence 4). Component test via `testApplication`: submit a fixture, request `?from_seq=0`, assert the replayed event sequence matches the emitted trace; assert `from_seq=N` skips the first N.

  Acceptance: SSE component spec green; frames are valid `InvestigationEvent` JSON.

- [ ] **T5 — REST control surface + PD-8 admission.**

  Implement all endpoints in `api/` per contracts §2: `POST /v1/investigations` (→ `{id, status}` 202, async), `GET /v1/investigations/{id}`, the SSE bridge (T4), `POST …/approve-plan`, `…/approve-revision`, `…/answer`, `…/budget-decision`, `…/halt` (cancel-with-partials — drain, synth over findings-so-far, `Conclusion.partial = true`, STOP_USER — contracts §2 halt semantics). **PD-8 request admission (kantheon-security §3.3):** every endpoint validates the inbound bearer and re-checks `visibility_roles`; reject → 403 + Rule-6 message. Resume idempotency: second call to an already-resumed AWAITING_* → **409** with current status. `X-Correlation-Id` propagated.

  Component tests (`testApplication`, mocked auth + orchestrator): each endpoint's happy path + the 403 (bad bearer/role) + the 409 (double-resume). `replay`/`reproduce` endpoints are **stubbed to 501** here (real impl Phase 3 Stage 3.3) — assert the 501 + a Rule-6 "available from v0.3.0" note so the surface is complete-shaped.

  Acceptance: control-surface component specs green; all five AWAITING_* resumable each via exactly one endpoint.

- [ ] **T6 — `GET /v1/investigations` per-user list (PD-2 inbox source).**

  Implement the list endpoint (contracts §2): `?user_id=&statuses=&page=&page_size=` → `{ investigations: [InvestigationSummary], next_page? }`. `InvestigationSummary` = id, question, status, created_at, updated_at, resource_usage totals, caller.kind. Role-filtered per the caller's bearer (PD-8). This is the source iris-bff aggregates for the inbox.

  Component test: seed several investigations across users/statuses; assert filtering by `user_id` + `statuses`, paging (`next_page` present when more), and that another user's rows are excluded.

  Acceptance: list spec green.

- [ ] **T7 — AWAITING_* TTL expiry sweeper.**

  Implement a scheduled sweeper (24 h default from `pythia.awaiting.ttl-hours`) that finds investigations where `awaiting_ttl_until < now()` and transitions them to a terminal `HALTED` (STOP_USER / TTL) — emitting the lifecycle event + a Rule-6 "expired awaiting input after 24h" message. Unit test with an injected clock: an investigation parked past TTL is swept; one within TTL is left.

  Acceptance: sweeper spec green; clock is injectable (no real wall-clock waits).

- [ ] **T8 — Deploy to K3s + fixture e2e on stubs.**

  Add `k8s/{base,overlays/local}/` Kustomize manifests (`imagePullPolicy: Never` in the local overlay — kantheon idiom), Jib build, `just deploy-kt pythia`. Readiness gates per architecture §7 (DB migrated; NATS reachable-or-degrade; Themis reachable-but-boot-proceeds; capabilities-mcp warn-and-continue). Run a fixture investigation through the live pod (stubs) and assert it reaches `DONE` with a complete event trace over the SSE bridge.

  **Note:** the live-pod walk is a *capability* demonstration, not a gated integration test (planning-conventions §4 — "test type ≠ system capability"). The DONE criteria are satisfied by the mocked component specs above; record the live walk result in the PR.

  Acceptance: pod Ready in local K3s; fixture investigation reaches DONE on stubs.

## DONE — Stage 1.3 → Phase 1

- [ ] All tasks checked; `just test-kt pythia` green; transition table exhaustive-green.
- [ ] Pod Ready in local K3s; fixture investigation `SUBMITTED → … → DONE` on stubs.
- [ ] Integration-suite carry-overs recorded (real-NATS publish/subscribe, live SSE under load, real K3s readiness-gate behaviour).
- [ ] CI green on `[pythia-p1-s1.3] lifecycle + events + API`.
- [ ] **Tag `pythia/v0.1.0`.** Update [`tasks-p1-overview.md`](./tasks-p1-overview.md) aggregate-progress + [`plan.md`](./plan.md) §11 checklist. **Phase 1 DONE.**

## Library / pattern references

- **contracts §2** (REST + halt + admission), **§3** (NATS subjects + lifecycle subject), **design §3.4** (transition table), **kantheon-security §3.3** (PD-8).
- **ai-platform `EXAMPLES.md` §1** (Ktor routing, SSE), **§2a** (`buildJsonObject`).
- **architecture §5** (orchestrator coroutine model, drain, resume idempotency), **§7** (readiness gates + degrade).

## Out of scope for Stage 1.3

- Real Themis resolution / planner / executor — Phase 2.
- Real `replay`/`reproduce` — Phase 3 Stage 3.3 (stubbed to 501 here).
- Budget threshold parking logic (the `/budget-decision` endpoint exists; the tracker that *parks* into AWAITING_BUDGET_DECISION is Phase 2 Stage 2.3).
