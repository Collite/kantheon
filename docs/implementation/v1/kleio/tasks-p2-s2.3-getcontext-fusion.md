# Stage 2.3 — graph-primary getContext + fusion

> **Phase 2, Stage 2.3.** Branch `feat/docwh-p2-s2.3-getcontext-fusion`.
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4 Stage 2.3, [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §8 (the four-step graph-primary retrieval), [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §1 (`ContextRequest`/`ContextChunk`/`Citation`/`RetrievalLead`) + §5 (citation ↔ envelope mapping).

## Goal

`getContext` — the graph-primary, citation-bearing RAG primitive. `HybridFusion` leads with the graph walk (S2.2) and boosts with vector (S2.1) + keyword (S1.2) recall; returns top-k `ContextChunk`s each with a `Citation` and a `RetrievalLead` (`GRAPH`/`VECTOR`/`KEYWORD`) label. `NO_GROUNDING` semantics + `findSimilar`. DONE = cited graph-primary retrieval over source parts; tag **`kallimachos/v0.2.0`**.

## Tasks (6)

- [ ] **T1 — Tests first: `HybridFusionSpec`.**

  Spec the fusion: **graph-led** ranking (graph proximity weighted first, `graph-weight` config) with vector/keyword recall **boost**; cross-plane score normalisation; each returned chunk carries the right `lead` label (`GRAPH` when it surfaced via the walk, `VECTOR`/`KEYWORD` when only a booster reached it). Early-corpus degradation: with an empty graph, fusion falls back to recall-only and still returns cited chunks (architecture §8, the "thin wiki" risk).

  Acceptance: spec written and failing. Commit `[docwh-p2-s2.3] failing hybrid fusion spec`.

- [ ] **T2 — `HybridFusion` + `retrieval/` assembly; `POST /getContext`.**

  Implement `HybridFusion` (architecture §8 step 4) and assemble the `retrieval/` package (seed → `GraphWalk` → `VectorRecall`/`KeywordRecall` boost → fuse). Implement `POST /getContext` returning `ContextChunk[]` with `Citation` (contracts §1). Mart scope mandatory (`ContextRequest.notebook_id`).

  Acceptance: T1 fusion spec green; `getContext` returns ranked cited chunks.

- [ ] **T3 — Tests first: `CitationMappingSpec`.**

  Spec the citation → envelope mapping (contracts §5): `Citation.source_ref` → `Block.provenance.source_tables[]`; ids → `Drilldown.arg_mapping {sourceId, partId, pageId}` (`scope="point"`, `source="citation"`); `title`+`locator` → `Drilldown.display`. This is the **grounding contract** Kleio (P5) relies on.

  Acceptance: spec written and failing. Commit.

- [ ] **T4 — `min-score` NO_GROUNDING semantics + empty result.**

  Implement the `min-score` threshold (config, contracts §11): below threshold → an explicit no-grounding signal (no fabricated chunks); empty mart → empty result, not an error. This is what Kleio's `STATUS_NO_GROUNDING` (P5) renders as a CALLOUT.

  Acceptance: `CitationMappingSpec` green; below-threshold returns the no-grounding signal; empty result handled.

- [ ] **T5 — `POST /findSimilar` (vector + graph boost) route.**

  Implement `findSimilar` (contracts §4 surface; vector recall + a graph-proximity boost). Mart-scoped. Spec: returns vector-led similar chunks with `lead = VECTOR`.

  Acceptance: `findSimilar` route + spec green.

- [ ] **T6 — Retrieval benchmark harness (`bench/`) + tag.**

  Build `bench/` — a harness measuring latency + candidate counts per plane on a reference mart (architecture §13 metrics: `kallimachos_retrieval_candidates{plane}`, `graph_walk_depth`). This feeds the P4 `cost_hints` on the `library.*` manifests. Tag **`kallimachos/v0.2.0`**; bump catalog.

  Acceptance: benchmark runs + emits per-plane numbers; tag pushed. PR `[docwh-p2-s2.3] graph-primary getContext + fusion`.

## DONE — Stage 2.3

- [ ] All six tasks checked.
- [ ] `getContext` returns graph-led, recall-boosted, **cited** `ContextChunk`s with correct `RetrievalLead` labels.
- [ ] Citation → envelope mapping (the grounding contract) spec-green.
- [ ] `min-score` NO_GROUNDING + empty handled; `findSimilar` route live.
- [ ] Benchmark harness feeds P4 `cost_hints`.
- [ ] Tag `kallimachos/v0.2.0` pushed. **Phase 2 DONE — graph-primary cited retrieval over source parts.**
- [ ] PR merged.

## Library / pattern references

- **architecture.md §8** — the four-step graph-primary retrieval (seed → walk → boost → fuse).
- **contracts.md §1** — `ContextRequest`/`ContextChunk`/`Citation`/`RetrievalLead`. **§5** — the citation ↔ envelope mapping (authority for T3).
- **envelope/v1** `Block.provenance` (PD-9) + `Drilldown` — the targets of the citation mapping.

## Out of scope for Stage 2.3

- Wiki pages leading the seed (Phase 3 S3.2 T7 reweights fusion to prefer ENTITY/CONCEPT pages) — here the graph is source-parts only.
- The `library.*` MCP surface + RLS (Phase 4) — `getContext` is the in-process route here.
- Real pgvector/AGE verification (integration suite).
