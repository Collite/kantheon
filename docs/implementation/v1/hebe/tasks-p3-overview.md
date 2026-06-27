# Phase 3 — Postgres & instances

> **Reads with.** [`plan.md`](./plan.md) §"Phase 3", [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §5 (storage architecture) + §5.2 (dual memory backend) + §5.3 (workspace/receipts follow `fs.durability`), [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §4 (per-instance PG schema, V1–V7, provisioning runbook), [`../../planning-conventions.md`](../../planning-conventions.md) §4 (mocked-unit testing policy).

## Phase deliverable (deployable)

A Hebe instance runs on local **K3s** as a pod: schema-per-instance Postgres (`hebe_<instance_id>`), workspace + receipts in PG (`fs.durability = ephemeral`), Jib image + Kustomize deploy, and the instance registered in capabilities-mcp as **`non_routable`**. The **`server` shape** (external PG + file workspace/receipts) is validated as a side-effect of the axis split. Tags **`hebe/v0.3.0`** + **`capabilities-mcp/v0.2.0`**.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **3.1** — PG MemoryStore backend | RRF parity test green at the unit level on fixtures; `storage.backend=postgres` wiring asserted with a mocked driver | [`tasks-p3-s3.1-pg-memory.md`](./tasks-p3-s3.1-pg-memory.md) |
| **3.2** — Workspace + receipts in PG | Full Hebe loop runs on PG with zero filesystem state besides logs; receipts chain-verify with tamper detection | [`tasks-p3-s3.2-workspace-receipts.md`](./tasks-p3-s3.2-workspace-receipts.md) |
| **3.3** — Instance provisioning + K8s deploy | Documented instance bring-up reproducible from clean K3s (provision → deploy → converse → routine fires → receipts verify) | [`tasks-p3-s3.3-instance-deploy.md`](./tasks-p3-s3.3-instance-deploy.md) |
| **3.4** — capabilities-mcp v0.2.0 + Hebe registration | K3s Hebe registered, heartbeating, provably unroutable; registry serves `non_routable` + `visibility_roles`; tags `hebe/v0.3.0` + `capabilities-mcp/v0.2.0` | [`tasks-p3-s3.4-capabilities-registration.md`](./tasks-p3-s3.4-capabilities-registration.md) |

## Sequencing

```
Stage 3.1 ──► 3.2 ──► 3.3 ──► 3.4
 PG memory    ws+receipts  provision+deploy  register non_routable + cap-mcp v0.2.0
```

## Pre-flight for the phase

- [ ] **Phase 2 DONE** (`hebe/v0.2.0`).
- [ ] **Kantheon PG in `deployment/local` with pgvector** + the `hebe` database created (phase pre-flight, plan §"Phase 3").
- [ ] Kantheon's Exposed-DSL + Flyway convention confirmed (the PG backend code follows it; see any existing kantheon `services/*` PG module).
- [ ] **Testing-policy reminder (critical for this phase):** PG/pgvector behaviour (HNSW recall, `ts_rank_cd`, RLS) **cannot** be H2-faked, so it is **not** covered by the in-repo unit suite. Stages develop against **mocked drivers / in-memory fakes** exercising the Exposed-DSL query construction + the RRF parity harness on fixtures. Real-PG verification (incl. RRF parity against a live instance, HNSW recall, the K3s round-trip) lives in the **separate integration-test suite** and does **not** gate stage DONE. This supersedes the old "Testcontainers vs Hebe's no-testcontainers" tension — **no Testcontainers in the unit suite**.

## The migration sets

Two Flyway sets, maintained together in `:agents:hebe:modules:memory`:

- SQLite: `db/migration/` (existing).
- Postgres: `db/migration-pg/` (this phase) — `V1`–`V5` ported tables (§3.1), `V6__workspace.sql` + `V7__receipts.sql` (§3.2), applied per-schema (`flyway.schemas=hebe_<id>`).

## Aggregate progress

- [ ] **Stage 3.1** — PG MemoryStore backend.
- [ ] **Stage 3.2** — Workspace + receipts in PG.
- [ ] **Stage 3.3** — Instance provisioning + K8s deploy.
- [ ] **Stage 3.4** — capabilities-mcp v0.2.0 + Hebe registration.

When all four are checked, push tags `hebe/v0.3.0` + `capabilities-mcp/v0.2.0` and move to Phase 4.

## Up / across

- Up: [`./README.md`](./README.md). Neighbours: [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`tasks-p4-overview.md`](./tasks-p4-overview.md).
- Cross-arc: Stage 3.4 touches **Themis** (routing-view exclusion regression) and **capabilities-mcp** (the `non_routable`/`visibility_roles` registry contract).
