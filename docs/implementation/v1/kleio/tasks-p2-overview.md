# Phase 2 — Retrieval planes + graph-primary getContext

> **Reads with.** [`plan.md`](./plan.md) §4 (Phase 2), [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §8 (graph-primary retrieval) + §11 (embeddings as a conformed dimension) + §14 (the AGE + embeddings risks), [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §1 (`ContextRequest`/`ContextChunk`/`Citation`) + §3 (vector + AGE planes) + §10 (`EmbedText`), [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Phase deliverable (deployable)

pgvector plane + the Prometheus embeddings client; the **Apache AGE** plane (sources/parts structure + `CONTAINS`); `getContext` as a **graph-led** fusion (graph walk first, vector + keyword recall-boost) over source parts. Internally usable RAG (before the wiki-compile of P3 thickens the graph). Tag **`kallimachos/v0.2.0`**.

> **The inversion that defines this arc:** the wiki-graph **leads**; vector + keyword are **recall boosters** (the inverse of doc-store). Early in the corpus the graph is thin, so `getContext` degrades gracefully to recall-only and gets richer as the compile (P3) adds pages/links.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **2.1** — vector plane + Prometheus embeddings | EMBED stage embeds via Prometheus; vector recall green on fakes | [`tasks-p2-s2.1-vector-embeddings.md`](./tasks-p2-s2.1-vector-embeddings.md) |
| **2.2** — Apache AGE plane (spike-gated) | Four-plane ingest atomic; graph walk green | [`tasks-p2-s2.2-age-plane.md`](./tasks-p2-s2.2-age-plane.md) |
| **2.3** — graph-primary getContext + fusion | `getContext` returns cited chunks with `RetrievalLead` labelling; tag `kallimachos/v0.2.0` | [`tasks-p2-s2.3-getcontext-fusion.md`](./tasks-p2-s2.3-getcontext-fusion.md) |

## Sequencing

```
Stage 2.1 ──► 2.2 ──► 2.3
 vector+embed   AGE plane   graph-primary getContext + tag v0.2.0
```

## Pre-flight for the phase (plan §4)

- [ ] **Phase 1 DONE** (`kallimachos/v0.1.0` + `pinakes/v0.1.0`).
- [ ] **Prometheus `EmbedText` RPC** available (additive, contracts §10) — the **one hard external pre-flight** of the whole arc. Owner coordination (plan §9). **Fallback:** the doc-store `RemoteHttpEmbeddingsClient` behind `EmbeddingsPort` if `EmbedText` slips (risks §14). Pick the multilingual model + pin `dimensions` = the `doc_vectors` column N = the pipeline `EmbedConfig.dimensions` (conformed, architecture §11).
- [ ] **AGE extension confirmed** in the `kallimachos` DB (cluster-side; confirmed available 2026-06-20).

## Testing policy

Mocked unit/component (architecture §13): Wiremock Prometheus, in-memory `Port` fakes for vector/graph. The AGE adapter has a **spike gate** (S2.2 T1) deciding AGE-over-JDBC vs an adjacency-table fallback behind `GraphPort`. Real pgvector recall + AGE openCypher round-trips are the integration suite.

## Aggregate progress (plan §11)

- [ ] **2.1** pgvector + Prometheus embeddings.
- [ ] **2.2** AGE plane (spike-gated) + graph walk.
- [ ] **2.3** graph-primary getContext + fusion. **P2 — `kallimachos/v0.2.0`.**

When all three are checked, push the tag and move to Phase 3.

## Up / across

- Up: [`./README.md`](./README.md). Neighbours: [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`tasks-p3-overview.md`](./tasks-p3-overview.md).
