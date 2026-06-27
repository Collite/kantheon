# Phase 3 — Pinakes pipelines + LLM wiki-compile

> **Reads with.** [`plan.md`](./plan.md) §5 (Phase 3), [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §6 (the wiki) + §7 (pipelines & stage library) + §14 (compile cost/quality + fragmentation risks), [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §2 (`Pipeline`/`Stage`/`StageKind`/`EmbedConfig`/`Lineage`) + §1 (`Page`/`PageKind`/`ConceptRef`/`LoadPagesRequest`) + §6 (the `concept_ref` Ariadne seam).

## Phase deliverable (deployable) — the DocWH differentiator

The stage library + a handful of per-source named pipeline DAGs + run/lineage; the **LLM compile** stage (entity/concept pages, synthesis, links, global entity resolution, contradiction flags). The corpus becomes a **wiki**; `getContext` now leads with concept/entity pages. The `concept_ref` Ariadne seam is reserved. Tags **`pinakes/v0.2.0`** + **`kallimachos/v0.3.0`**.

> **The compile stage is LLM-driven (Prometheus) and batch/offline — never on the query path.** It is the valuable, expensive part; cost/quality is the headline risk (§14). Prompts live in `prompts/`, eval'd on a fixture corpus; degrade to mechanical-links-only on compile failure (corpus stays queryable).

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **3.1** — stage library + pipeline runner + per-source binding | Named per-source pipelines run mechanical stages with lineage; embed conformance enforced | [`tasks-p3-s3.1-stage-library.md`](./tasks-p3-s3.1-stage-library.md) |
| **3.2** — the LLM compile + linking + global resolution | A run compiles sources into linked wiki pages with globally-resolved entities | [`tasks-p3-s3.2-llm-compile.md`](./tasks-p3-s3.2-llm-compile.md) |
| **3.3** — compile hardening + deploy | Contradiction flags, cost budgets, re-ingest compounding; tags `pinakes/v0.2.0` + `kallimachos/v0.3.0` | [`tasks-p3-s3.3-hardening-deploy.md`](./tasks-p3-s3.3-hardening-deploy.md) |

## Sequencing

```
Stage 3.1 ──► 3.2 ──► 3.3
 stage library   LLM compile + link + resolve   hardening + deploy + tags
```

## Pre-flight for the phase

- [ ] **Phase 2 DONE** (`kallimachos/v0.2.0`) — the four planes + `getContext` exist; compile writes pages into them.
- [ ] Prometheus chat/`CreateResponse` reachable (the compile LLM egress) — Wiremock'd in unit tests.
- [ ] **Bora-owned content (plan §9):** the handful of v1 pipelines (which source feeds, per-feed chunk + compile config) and the compile prompt set (entity/concept/summary synthesis) — the main content task of the phase; eval'd on a fixture corpus.
- [ ] Pinakes state decision confirmed (own small schema on the one PG: `assets`/`pipelines`/`pipeline_runs`/`lineage` — plan §8 leaning).

## Aggregate progress (plan §11)

- [ ] **3.1** stage library + pipeline runner + per-source binding.
- [ ] **3.2** LLM compile + linking + global entity resolution (+ concept_ref seam).
- [ ] **3.3** compile hardening + deploy. **P3 — `pinakes/v0.2.0` + `kallimachos/v0.3.0`.**

When all three are checked, push both tags and move to Phase 4.

## Up / across

- Up: [`./README.md`](./README.md). Neighbours: [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`tasks-p4-overview.md`](./tasks-p4-overview.md).
