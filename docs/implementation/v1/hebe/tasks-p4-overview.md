# Phase 4 — Constellation client

> **Reads with.** [`plan.md`](./plan.md) §"Phase 4", [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §1 (Hebe is a client of the constellation) + §7 (the scheduled-investigation flow), [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §1.2 (`hebe.v1` proto) + §3 (headless-client contract), [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Phase deliverable (deployable)

"Every Monday 03:00, ask X, message me the answer" works **E2E on local K3s**: a scheduled routine fires → Hebe drives a chat turn through **iris-bff** with an OBO token → the conclusion is delivered to **Telegram** with a deep link into the Iris session. First target is a **Golem-answerable** question (O-3); Pythia investigations require no Hebe-side change when they land. The same client path runs from `personal`/`server` over the public ingress through the Phase 2 outbox. Tag **`hebe/v0.4.0`**.

> **Gated by iris-bff deployed (Iris arc ≥ Phase 2 — master-plan M3).** Phases 1–3 are unaffected and worth shipping regardless; if iris-bff slips, P4 stalls but nothing earlier does (plan risks note).

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **4.1** — iris-bff headless client | Manual routine run produces a turn visible in Iris session history; Wiremock'd iris-bff SSE consumption green | [`tasks-p4-s4.1-iris-bff-client.md`](./tasks-p4-s4.1-iris-bff-client.md) |
| **4.2** — Routine type + delivery loop | Cron-fired Golem question delivered to Telegram with an Iris deep link; failure never silent; tag `hebe/v0.4.0` | [`tasks-p4-s4.2-routine-delivery.md`](./tasks-p4-s4.2-routine-delivery.md) |

## Sequencing

```
Stage 4.1 ──► Stage 4.2
 headless client   routine + delivery loop + tag v0.4.0
```

## Pre-flight for the phase

- [ ] **Phase 3 DONE** (`hebe/v0.3.0`); a Hebe pod runs on K3s and is registered `non_routable`.
- [ ] **iris-bff deployed and reachable in K3s** (Iris arc ≥ Phase 2). This is the hard external gate.
- [ ] **`TurnOrigin` co-design landed 2026-06-12** (cohesion review) — `iris/contracts.md` §1.2 (`ChatTurnRequest.origin = 6` / `origin_ref = 7`) + `iris_turns.origin/origin_ref` columns already exist; remaining Iris-side work is machine-client OBO acceptance (standard bearer validation) + the "scheduled" badge (Iris Stage 4.1), tracked in the Iris arc, not here.
- [ ] OBO token service from Phase 2 Stage 2.3 available (`currentBearer()`); the Phase 2 outbox (Stage 2.5) wraps the H→I and H→C seams that this phase fills in.

## Cross-arc coordination

| With | What | When |
|---|---|---|
| Iris arc | machine-client OBO acceptance (standard bearer validation); "scheduled" badge | before Stage 4.1 (badge can trail) |
| Pythia arc | nothing required from Hebe; scheduled investigations work via iris-bff once Pythia is routable | post-P4 |
| PD-10 / PD-2 | close PD-10 layer 3 + PD-2 out-of-band path with Resolution pointers | Stage 4.2 T6 |

## Aggregate progress

- [ ] **Stage 4.1** — iris-bff headless client.
- [ ] **Stage 4.2** — Routine type + delivery loop.

When both are checked, push tag `hebe/v0.4.0`. **Hebe v1 arc complete.**

## Up / across

- Up: [`./README.md`](./README.md). Neighbours: [`tasks-p3-overview.md`](./tasks-p3-overview.md).
