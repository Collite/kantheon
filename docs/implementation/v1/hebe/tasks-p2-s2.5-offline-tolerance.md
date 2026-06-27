# Stage 2.5 — Offline tolerance (`personal` profile)

> **Phase 2, Stage 2.5.** **The one piece of genuinely new engineering in the four-profile expansion** (architecture §7.1) — not axis plumbing. Budget accordingly.
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §"Stage 2.5", [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §7.1 (the three mechanisms + the LLM-fallback split), [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §5.2 (`[platform.catchup]` + `[llm.byok_fallback]`).

## Goal

Gated by `platform.availability = intermittent` (harmless + enabled-but-idle on always-on profiles), implement the three offline-tolerance mechanisms:

1. **Durable scheduler with missed-trigger catch-up** — owed fires while the process was down are detected on boot and run per a per-routine policy (`run_once_on_wake` | `run_all_missed` | `skip`), with coalescing after a long sleep.
2. **Outbox (store-and-forward)** — iris-bff turn dispatch (H→I) and channel deliveries (H→C) become idempotent queue rows draining on connectivity (in-memory store in P2 behind a backend-agnostic seam; on-disk durability is Phase 3).
3. **Runtime circuit-breaker / connectivity probe** — tracks gateway + iris-bff reachability *now* (distinct from the static axis); open ⇒ outbox holds + doctor reports DEGRADED; half-open probe restores.

Plus the **LLM byok-fallback split**: Hebe's own reasoning routines fall back to BYOK when the breaker is open; constellation (`kantheon_question`) turns **never** fall back — they defer via the outbox and the user gets a "queued, will run when reconnected" note.

## Pre-flight

- [x] **Stage 2.2 DONE** (gateway client) **and Stage 2.3 DONE** (OBO) — the outbox wraps both seams; pre-flight is a hard gate (plan §"Stage 2.5").
- [x] **Branch**: `feat/hebe-p2-s2.5-offline-tolerance`.
- [x] Re-read architecture §7.1 — the sharp edges are **idempotency of enqueue/drain** and the **owed-fire coalescer** (risks note). Tests target exactly these.
- [x] Note: the `jobs` queue already persists (standalone). This stage **adds** owed-fire detection + outbox semantics on top; it does not replace the scheduler.

## Tasks

- [x] **T1 — Tests first: missed-trigger catch-up per policy + coalescing.**

  Create `agents/hebe/modules/scheduler/src/test/kotlin/.../CatchupSpec.kt`. Simulate a process that was down across one or more due cron ticks (control the clock; set `next_run_at` in the past). Assert per policy:

  - `run_once_on_wake` (default for `kantheon_question`) — exactly **one** owed fire on boot regardless of how many were missed.
  - `run_all_missed` — one fire per missed tick.
  - `skip` — no owed fire; `next_run_at` advances.
  - **Coalescing** (`coalesce = true`) — a long sleep does not produce a thundering herd; identical owed fires collapse.

  Acceptance: specs written and failing. Commit `[hebe-p2-s2.5] failing catch-up specs`.

- [x] **T2 — Durable scheduler catch-up.**

  On boot, evaluate every routine's `next_run_at` against `now` and the per-routine catch-up policy (`[platform.catchup].default_policy` + per-routine override). Add owed-fire detection + the coalescer to the existing `jobs`-backed loop. Only active when `platform.availability = intermittent` (idle otherwise).

  Acceptance: T1 specs pass.

- [x] **T3 — Outbox seam (tests first, then impl).**

  Tests first (`OutboxSpec`): enqueue/drain is **idempotent** (re-enqueueing the same logical turn/delivery does not double-send; draining a row twice is a no-op); ordering preserved per destination; a drain failure leaves the row for retry. Then implement the outbox as an **in-memory** idempotent queue behind a backend-agnostic `OutboxStore` seam, wrapping the two seams — the iris-bff turn dispatch (H→I) and channel delivery (H→C) become enqueue operations that drain when connectivity returns. The §7 flow becomes asynchronous at those two seams **without changing its shape**. (The durable SQLite/PG row store — i.e. restart survival — plugs in behind the same seam in **Phase 3**; P2 locks the contract, not on-disk persistence.)

  Acceptance: `OutboxSpec` green; idempotency proven on both enqueue and drain.

- [x] **T4 — Runtime circuit-breaker / connectivity probe.**

  Implement a breaker (distinct from the static `platform.availability` axis) tracking llm-gateway + iris-bff reachability *now*: closed → calls flow; open → outbox holds + `doctor` reports DEGRADED (not FAILED); half-open → a probe restores flow on success. Tests: forced-open holds the outbox; half-open probe success closes it; doctor reflects each state.

  Acceptance: breaker state machine spec green; doctor DEGRADED on open.

- [x] **T5 — LLM byok-fallback wiring (the split).**

  Implement `llm.source = gateway_with_byok_fallback` (contracts §5.2 `[llm.byok_fallback]`):

  - **Hebe's own reasoning routines** (heartbeat, summariser, fact-extract, ad-hoc local chat) → when the breaker is open, fall back to the configured BYOK model so Hebe stays useful offline.
  - **Constellation turns** (`kantheon_question` via iris-bff) → **never** fall back; they **defer** via the outbox and the user gets a never-silent "queued, will run when reconnected" channel note.

  Tests: with the breaker open, an own-routine turn is served by the fallback model; a `kantheon_question` is deferred + the queued-note is emitted (not silently dropped, not answered by fallback).

  Acceptance: the split is enforced; both branches spec-green.

- [x] **T6 — Component test: the full offline loop + tag.**

  A component test with a **faked connectivity probe / breaker**: start `personal`, lose connectivity, fire routines (one own-routine, one `kantheon_question`), then restore. Assert: owed fires catch up per policy, the outbox drains in order, the own-routine was served by byok-fallback, the deferred constellation turn runs on reconnect, and the user received the queued-note then the result. Per planning-conventions §4 this is mocked at the component level; the live simulated-host run is **deferred to the integration suite**.

  Acceptance: component test green; always-on profiles unaffected (a `server`/`k8s` run shows the machinery idle). Tag `hebe/v0.2.0`. PR `[hebe-p2-s2.5] offline tolerance`.

## DONE — Stage 2.5

- [x] All six tasks checked.
- [x] Component tests prove a `personal` Hebe survives a connectivity gap across a scheduled fire and reconciles on resume (in-process; **restart**-durable persistence of the queue is Phase 3).
- [x] Idempotent enqueue/drain + the owed-fire coalescer proven (the two sharp edges).
- [x] byok-fallback serves own-routines; `kantheon_question` defers with a never-silent note.
- [x] Always-on profiles unaffected (machinery idle).
- [x] Tag `hebe/v0.2.0` pushed. **Phase 2 DONE.** PR merged.

## Library / pattern references

- **architecture.md §7.1** — the three mechanisms + the LLM-fallback-by-what-is-reasoning rule. The single most important reference for this stage.
- **contracts.md §5.2** — `[platform.catchup]` policies + `[llm.byok_fallback]`.
- **plan.md risks note** — "offline tolerance is real engineering, not plumbing"; idempotency + coalescer are the sharp edges.

## Out of scope for Stage 2.5

- The actual iris-bff turn payload + SSE consumption (Phase 4 Stage 4.1) — here the outbox wraps a **stubbed** H→I seam; Phase 4 fills it in. The outbox contract (enqueue/drain/idempotency) is what this stage locks.
- **Durable (on-disk) persistence of the queue (Phase 3)** — P2 ships an in-memory `OutboxStore`; the SQLite-then-PG row store (the only thing that makes the queue survive a process **restart**) plugs in behind the same backend-agnostic seam in Phase 3. P2 locks the enqueue/drain/idempotency contract only.
- Live intermittent-host verification (integration suite).
