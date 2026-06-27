# Arges — Phase 1 (Arges live) — task-list overview

> Entry point for Phase 1 executors. Phase goal, stage map, aggregate progress. Per `planning-conventions.md` §4.
>
> **Status (2026-06-23): READY TO START.** Brontes is the template (live); Midas DB is up. Begin at Stage 1.1. No stages done yet.
>
> **Reads with.** [`plan.md`](./plan.md) §3, [`../../../architecture/arges/architecture.md`](../../../architecture/arges/architecture.md), [`../../../architecture/arges/contracts.md`](../../../architecture/arges/contracts.md).

## Phase 1 goal

`workers/arges` deployable on bp-dsk; Kyklop dispatches a Postgres `PlanNode` to it; Arges unparses via Proteus → PostgreSQL, executes read-only under `SET LOCAL app.tenant_id`, streams Arrow IPC; component tests prove a real round-trip + RLS cross-tenant isolation through the worker. Tag `arges/v0.1.0`.

## Stage map

| Stage | Title | File | DONE |
|---|---|---|---|
| 1.1 | Module skeleton + build wiring + capabilities/status | [`tasks-p1-s1.1-skeleton.md`](./tasks-p1-s1.1-skeleton.md) | ☐ |
| 1.2 | PG execution pipeline + Arrow type mapper | [`tasks-p1-s1.2-execution-arrow.md`](./tasks-p1-s1.2-execution-arrow.md) | ☐ |
| 1.3 | RLS contract + component test + Kyklop reg + ship | [`tasks-p1-s1.3-rls-ship.md`](./tasks-p1-s1.3-rls-ship.md) | ☐ |

## Dependencies into Phase 1

- Brontes template + worker/plan/proteus protos — **MET**.
- **Proteus PostgreSQL unparse** — **gating gap** for Stage 1.2 (enum value exists; no PG code yet). Audit + close in S1.2 T1; may spin a sibling Proteus task.
- `Containers.postgres()` (testing arc) — **MET**, for S1.3.
- `midas_app_readonly` role — Midas-side coordination; needed only for the **live** `pg-midas` path (not the arc gate; component tests seed their own role).

## Phase 1 DONE

- [ ] All three stage DONE criteria checked.
- [ ] `just build-kt workers/arges` + unit + component suites green; CI passes.
- [ ] Demo: Kyklop → Arges → Postgres returns tenant-scoped Arrow rows; RLS leakage spec green.
- [ ] Tag `arges/v0.1.0`. Midas P3 S3.2 unblocked (pending the live `midas_app_readonly` role).
