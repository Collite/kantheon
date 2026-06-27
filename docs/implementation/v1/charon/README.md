# Charon — v1 Implementation

Arc planned 2026-06-12. Independent of Iris/Golem; **`charon/v0.3.0` + `charon-mcp/v0.1.0` gate Pythia Phase 4.** Scheduling lean: run during the Golem arc.

| File | What |
|---|---|
| [`plan.md`](./plan.md) | 3 phases × 8 stages (~48 tasks): object-store movers (Seaweed/Redis + integrity core) → database edges (ADBC, named connections, allow-lists) → Polars Worker integration + `charon-mcp` + capability registration + benchmark. |

## Task lists

| Phase | Stages | Status |
|---|---|---|
| **1** — proto + integrity + object-store | [`s1.1 skeleton`](./tasks-p1-s1.1-skeleton.md) · [`s1.2 arrow + seaweed`](./tasks-p1-s1.2-arrow-seaweed.md) · [`s1.3 redis`](./tasks-p1-s1.3-redis.md) · [`s1.4 closeout`](./tasks-p1-s1.4-closeout.md) | 1.1–1.3 done (mocked); R1–R8 done in code; **1.4 closeout open** — CI fingerprint guard + IntegritySpec shared-pin alignment + re-tag (`charon/v0.1.0`) |
| **2** — database edges (ADBC) | [`s2.1 connections + extract`](./tasks-p2-s2.1-connections-extract.md) · [`s2.2 ingest`](./tasks-p2-s2.2-ingest.md) · [`s2.3 deploy + provisioned`](./tasks-p2-s2.3-deploy-provisioned.md) | written 2026-06-26 → `charon/v0.2.0` |
| **3** — worker + MCP + ship | [`s3.1 worker endpoint (+METIS)`](./tasks-p3-s3.1-worker-endpoint.md) · [`s3.2 mcp + bench + ship`](./tasks-p3-s3.2-mcp-bench-ship.md) | written 2026-06-26 → **`charon/v0.3.0` + `charon-mcp/v0.1.0` (Pythia P4 gate)** |

Stage 3.2 carries the **Pythia Phase 4.1 readiness checklist** — the sign-off that everything Pythia's data plane consumes (5 RPCs, 4 Location kinds, `Describe` liveness for all kinds, full legality matrix, evidence bucket, cross-engine `Stage`, fingerprint determinism, capability registration) is green.

Architecture + contracts: [`../../../architecture/charon/`](../../../architecture/charon/). Up: [`../README.md`](../README.md).
