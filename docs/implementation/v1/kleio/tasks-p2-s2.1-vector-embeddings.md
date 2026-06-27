# Stage 2.1 — vector plane + Prometheus embeddings

> **Phase 2, Stage 2.1.** Branch `feat/docwh-p2-s2.1-vector-embeddings`.
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4 Stage 2.1, [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §1 (`PartVector`/`LoadVectorsRequest`) + §3 (`doc_vectors`) + §10 (`EmbedText` proto), [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §11 (conformed embedding dimension).

## Goal

The pgvector plane (`doc_vectors`) + the `PrometheusEmbeddingsClient`; the `EMBED` stage embeds parts via Prometheus and writes vectors keyed by `(part, model_id, model_version)`; vector KNN recall works. The non-atomic embedding edge (`embedding_status = PENDING` + backfill) is implemented.

## Tasks (6)

- [ ] **T1 — Flyway `V3__doc_vectors_pgvector.sql`.**

  `doc_vectors(part_id fk, embedding vector(N), model_id, model_version)` + the ANN index (ivfflat or hnsw — pick per the doc-store precedent + architecture §11). **N = the pipeline `EmbedConfig.dimensions`** (conformed corpus dimension — the migration pins it; changing the model is a dual-write re-embed, not a per-instance change).

  Acceptance: migration parses + Flyway validate clean.

- [ ] **T2 — Tests first: `PrometheusEmbeddingsClientSpec` (Wiremock).**

  Spec the embeddings client against a Wiremock'd Prometheus `EmbedText` (contracts §10): **batched** inputs, the response `dimensions` is asserted equal to the configured N (a mismatch is a config error), and an embed error marks the part `embedding_status = PENDING` (not a hard failure — the ingest tx already committed).

  Acceptance: spec written and failing. Commit `[docwh-p2-s2.1] failing embeddings client spec`.

- [ ] **T3 — `PrometheusEmbeddingsClient` (`EmbeddingsPort`); wire into EMBED; backfill hook.**

  Implement `PrometheusEmbeddingsClient` behind the `EmbeddingsPort` (architecture §4) calling `EmbedText` (contracts §10). If `EmbedText` is not yet available, wire the doc-store `RemoteHttpEmbeddingsClient` behind the same Port (risks §14 fallback). Wire it into the `EMBED` stage; set `embedding_status` + add the backfill hook for `PENDING` parts.

  Acceptance: T2 spec green; EMBED produces vectors via Prometheus (or the fallback).

- [ ] **T4 — Tests first: `PgVectorAdapterSpec` (fake).**

  Spec the vector adapter: upsert keyed by `(part, model_id, model_version)` (idempotent re-embed), KNN order correct, metadata filter narrows candidates. Against a fake (planning-conventions §4 — real pgvector recall is the integration suite).

  Acceptance: spec written and failing. Commit.

- [ ] **T5 — Port `VectorPort` + `PgVectorAdapter`; `VectorRecall`.**

  Port doc-store's `VectorPort` + `PgVectorAdapter` onto the single-PG profile; implement `VectorRecall` (the recall-booster primitive that `getContext` calls in S2.3). `LoadVectorsRequest`/`PartVector` (contracts §1) is the write path from the EMBED stage.

  Acceptance: T4 vector adapter spec green; `VectorRecall` returns KNN candidates.

- [ ] **T6 — `backfillEmbeddings` for PENDING parts; spec.**

  Implement `backfillEmbeddings` — a batch pass that embeds `embedding_status = PENDING` parts and flips them to `OK`. Spec: a corpus with PENDING parts is backfilled idempotently.

  Acceptance: backfill spec green. PR `[docwh-p2-s2.1] vector plane + prometheus embeddings`.

## DONE — Stage 2.1

- [ ] All six tasks checked.
- [ ] `V3__doc_vectors_pgvector.sql` (vector(N) + ANN index, N = conformed `EmbedConfig.dimensions`).
- [ ] EMBED embeds via Prometheus (`EmbedText`) or the documented fallback; vectors keyed by `(part, model_id, model_version)`.
- [ ] `embedding_status = PENDING` + `backfillEmbeddings` for the non-atomic embedding edge.
- [ ] Vector recall green on fakes.
- [ ] PR merged.

## Library / pattern references

- **contracts.md §10** — `EmbedText` proto (batched). **§1/§3** — `PartVector`/`doc_vectors`.
- **architecture.md §11** — one conformed multilingual model; `(part, model_id, model_version)` keying; dual-write re-embed.
- doc-store `VectorPort` / `PgVectorAdapter` / `RemoteHttpEmbeddingsClient` (the fallback).
- **EXAMPLES.md §9** — Wiremock stub for the Prometheus `EmbedText` endpoint.

## Out of scope for Stage 2.1

- The AGE graph plane (Stage 2.2).
- `getContext` fusion (Stage 2.3) — `VectorRecall` is built here, fused there.
- Real pgvector recall verification (integration suite).
