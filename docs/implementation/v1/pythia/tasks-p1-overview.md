# Phase 1 — Contract + skeleton + lifecycle

> **Reads with.** [`plan.md`](./plan.md) §3 (Phase 1 description), [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §3 (module map) + §5 (execution model) + §7 (deployment gates), [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §1–§4, [`../../planning-conventions.md`](../../planning-conventions.md).
>
> **Phase deliverable.** `agents/pythia` pod in local K3s answering its REST control surface on **fixtures/stubs** (no real planner/executor yet): `pythia/v1` proto generated; Postgres state + checkpointer; lifecycle state machine over all **12** statuses (PD-11 `AWAITING_BUDGET_DECISION` included); typed event stream (PG log + NATS publisher + SSE bridge incl. the `pythia.lifecycle.{user_id}` subject); `GET /v1/investigations` per-user list; AWAITING_* TTL sweeper. A fixture investigation walks `SUBMITTED → … → DONE` on scripted stage stubs. Tag `pythia/v0.1.0`.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **1.1** — `pythia/v1` proto | `just proto` regenerates; KT bindings compile; serialisation round-trip specs green incl. unknown-enum tolerance; three golden-JSON artifact fixtures present | [`tasks-p1-s1.1-proto.md`](./tasks-p1-s1.1-proto.md) |
| **1.2** — Module + persistence + checkpointer | `just test-kt pythia` green; repository specs + `CheckpointerSpec` pass against an in-memory/mocked DB fake; module compiles with `/health` 200 | [`tasks-p1-s1.2-persistence-checkpointer.md`](./tasks-p1-s1.2-persistence-checkpointer.md) |
| **1.3** — Lifecycle + events + control surface | Exhaustive transition-table spec green; REST control surface answers (submit/get/approve/answer/budget-decision/halt + list + SSE) on stubs; fixture investigation reaches DONE; deploys to K3s | [`tasks-p1-s1.3-lifecycle-events-api.md`](./tasks-p1-s1.3-lifecycle-events-api.md) |

## Sequencing

Strictly sequential. Each stage closes before the next starts.

```
Stage 1.1 ──► Stage 1.2 ──────────► Stage 1.3
  proto        persistence+ckpt       lifecycle + events + API
```

## Pre-flight for the phase (plan §2)

- [ ] **Themis** `themis/v0.2.0` — INVESTIGATION_DEEP profile honoured + routing live. *(Pythia Phase 1 does not call Themis at runtime — `ThemisClient` lands in Stage 2.1 — but the proto imports `themis/v1`, so the themis proto package must be generatable in `shared/proto`.)*
- [ ] **envelope/v1** locked (Iris Stage 1.1); **envelope-render** `v0.1.0` (Golem Phase 1). *(Stage 1.1's proto imports `envelope/v1`; the render library itself is only consumed from Phase 2 Stage 2.4.)*
- [ ] **common/v1** present in `shared/proto`: `ResponseMessage` (Rule 6), `HandoffContext` (PD-1), `AgentId`. These predate this arc (cohesion review 2026-06-12).
- [ ] **Postgres for pythia** provisioned (true per plan §2). One internal Kantheon PG instance, database `pythia` (persistence topology, kantheon-architecture §7.1).
- [ ] **NATS JetStream** reachable from the kantheon namespace (verified deployed; reachability smoke is a Stage 1.3 task).
- [ ] **Branch** `feat/pythia-p1-s1.1-proto` from `main` (branch convention: `feat/pythia-p<n>-s<n.m>-<short>`, contracts §10).

## Aggregate progress

Mark each stage when DONE:

- [ ] **Stage 1.1** — `pythia/v1` proto.
- [ ] **Stage 1.2** — Module + persistence + checkpointer.
- [ ] **Stage 1.3** — Lifecycle + events + control surface.

When all three are checked: tag `pythia/v0.1.0`. **Phase 1 DONE** — move to [`tasks-p2-overview.md`](./tasks-p2-overview.md).

## Up / across

- Up: [`./README.md`](./README.md) — Pythia implementation index.
- Phase neighbours: [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`tasks-p3-overview.md`](./tasks-p3-overview.md).
- Reference arc (format template): [`../themis/tasks-p1-overview.md`](../themis/tasks-p1-overview.md).
