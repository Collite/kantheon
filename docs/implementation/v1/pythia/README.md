# Pythia — v1 Implementation

The Pythia arc was **planned 2026-06-12** (superseding the earlier "parked until after Themis" status). Third arc in the locked execution order Iris → Golem → Pythia.

## Files

| File | What |
|---|---|
| [`plan.md`](./plan.md) | Phased implementation plan: 5 phases × 16 stages (~95 tasks). Full v1 design retained — incl. NATS event streaming, Polars Worker DataFrameNode, Seaweed evidence blobs, and the cross-repo Charon + Metis deliverables (Phase 4). |
| [`v1.5-backlog.md`](./v1.5-backlog.md) | Explicitly-deferred items with reasons and revisit triggers (cnc layer, `model.decompose.variance`, Temporal, sweeping simulations, …). |

## Task lists (all 5 phases written 2026-06-26)

Per-stage `tasks-p<n>-s<n.m>-*.md` task lists for the full arc. Phases 1–3 are the self-contained RCA investigator (SQL-only, no cross-repo dependency). **Phase 4 Stage 4.1 execution is blocked on the Charon arc** (`charon/v0.3.0` + `charon-mcp/v0.1.0` — not yet tagged as of 2026-06-26; only `charon/v0.1.0` exists); the task lists are written ahead per the parallel-planning allowance (plan §8). Metis is done (`metis/v0.3.0` + `metis-mcp/v0.1.0`), so Stage 4.2 is unblocked.

| Phase | Overview | Stages |
|---|---|---|
| **1** — contract + skeleton + lifecycle | [`tasks-p1-overview.md`](./tasks-p1-overview.md) | [`s1.1 proto`](./tasks-p1-s1.1-proto.md) · [`s1.2 persistence + checkpointer`](./tasks-p1-s1.2-persistence-checkpointer.md) · [`s1.3 lifecycle + events + API`](./tasks-p1-s1.3-lifecycle-events-api.md) |
| **2** — procedural investigations e2e | [`tasks-p2-overview.md`](./tasks-p2-overview.md) | [`s2.1 resolution + planner`](./tasks-p2-s2.1-resolution-planner.md) · [`s2.2 DAG executor`](./tasks-p2-s2.2-dag-executor.md) · [`s2.3 query + evaluate + budget`](./tasks-p2-s2.3-query-evaluate-budget.md) · [`s2.4 render + synthesize + e2e`](./tasks-p2-s2.4-render-synthesize-e2e.md) |
| **3** — hypotheses + HITL complete | [`tasks-p3-overview.md`](./tasks-p3-overview.md) | [`s3.1 eval fallback + suspicion`](./tasks-p3-s3.1-eval-fallback-suspicion.md) · [`s3.2 reviser + prioritisation`](./tasks-p3-s3.2-reviser-prioritisation.md) · [`s3.3 replay/reproduce + RCA e2e`](./tasks-p3-s3.3-replay-reproduce-rca-e2e.md) |
| **4** — data plane + models | [`tasks-p4-overview.md`](./tasks-p4-overview.md) | [`s4.1 charon + dataframe`](./tasks-p4-s4.1-charon-dataframe.md) ⚠️ _blocked on `charon/v0.3.0`_ · [`s4.2 metis + model + forecast/sim`](./tasks-p4-s4.2-metis-model-forecast-sim.md) |
| **5** — constellation integration | [`tasks-p5-overview.md`](./tasks-p5-overview.md) | [`s5.1 master-of-golems`](./tasks-p5-s5.1-master-of-golems.md) · [`s5.2 iris integration`](./tasks-p5-s5.2-iris-integration.md) · [`s5.3 eval + hardening + ship`](./tasks-p5-s5.3-eval-hardening-ship.md) |

## What's elsewhere

- **Architecture + contracts**: [`../../../architecture/pythia/`](../../../architecture/pythia/) — `architecture.md` (incl. the 2026-06-12 infrastructure reality check) + `contracts.md` (proto/REST/NATS/DDL projection of the design, with the divergence table in §9).
- **Design**: [`../../../design/pythia/`](../../../design/pythia/) — `Pythia-v1-Design.md` (the semantic authority), brainstorming records, framework evaluation, open questions (all resolved).
- **Sibling arcs**: [`../charon/`](../charon/) + [`../metis/`](../metis/) — both kantheon-side (migrated platform-grade services); `charon/v0.3.0` and `metis/v0.3.0` gate Phase 4. **Cross-repo**: `aip-v1-gateway-worker-plan.md` (to be written before Phase 4 task lists) — llm-gateway tier routing + Polars Worker workspace read-out.

## Up / across

- Up: [`../README.md`](../README.md) — v1 implementation entry point.
- Across: [`../../../design/pythia/`](../../../design/pythia/), [`../../../architecture/pythia/`](../../../architecture/pythia/).
