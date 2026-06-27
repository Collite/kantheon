# Arges — the Postgres worker (architecture docs)

> **Arges** ("brightness") is the third Kyklops — the **PostgreSQL read worker**. It executes validated query plans against kantheon-owned Postgres sources (the Midas operational DB first) and streams results back as Arrow IPC, exactly like its siblings **Brontes** (MSSQL) and **Steropes** (Polars). It implements `org.tatrman.worker.v1.WorkerService` and is dispatched by **Kyklop**.

This folder is the authoritative architecture for the Arges arc, per [`../../implementation/planning-conventions.md`](../../implementation/planning-conventions.md) §6.2.

| Doc | What it covers |
|---|---|
| [`architecture.md`](./architecture.md) | Shape of the worker (mirror of Brontes), the new RLS `SET LOCAL app.tenant_id` contract, module map, deployment topology, observability. |
| [`contracts.md`](./contracts.md) | Wire contracts: `worker/v1` surface (reused), HOCON connection config, the Postgres→Arrow type table, Kyklop worker-slot registration, ports, the read-only role + RLS grants. |

**Plan:** [`../../implementation/v1/arges/plan.md`](../../implementation/v1/arges/plan.md).

**Why Arges exists.** Midas-core stores the operational portfolio data in the shared Kantheon Postgres. The chat-side Q&A path (Midas Phase 3 Stage 3.2 → Golem-Investment → Theseus/query pipeline) needs to *read* that DB through the standard worker-dispatch pipeline. Brontes reads MSSQL; Steropes runs Polars; neither speaks Postgres. Arges is that worker. It is the kantheon **fork** of the never-built ai-platform `workers/postgres` plan ([`../../implementation/v1/_archive/aip-v1-pg-worker-plan.md`](../../implementation/v1/_archive/aip-v1-pg-worker-plan.md)) — but, post-fork, it is built in-repo by mirroring Brontes, with **zero ai-platform coupling**.

**Decision (2026-06-23, Bora).** Fork now (do not keep a cross-repo dependency on ai-platform's planned `workers/postgres`; do not defer). Resolves the master-plan §7 open item.
