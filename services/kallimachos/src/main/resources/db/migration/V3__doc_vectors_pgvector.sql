-- Kleio/DocWH P2 Stage 2.1 — the VECTOR plane (contracts §3, §1 PartVector). One
-- multilingual embedding model for the whole corpus (a CONFORMED dimension,
-- architecture §11); N is the pipeline `EmbedConfig.dimensions`. Changing the
-- model is a dual-write re-embed (the `(part, model_id, model_version)` key),
-- not a per-instance change — so N is pinned here.
--
-- N defaults to 1024 (BGE-M3 / multilingual-e5-large class). If the conformed
-- model's dimension differs, this migration is the single place it changes.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE doc_vectors (
    part_id       BIGINT NOT NULL REFERENCES parts(id) ON DELETE CASCADE,
    model_id      TEXT   NOT NULL,
    model_version TEXT   NOT NULL,
    embedding     vector(1024) NOT NULL,
    PRIMARY KEY (part_id, model_id, model_version)
);

-- ANN index — ivfflat with cosine distance (doc-store precedent; hnsw is the
-- scale-out upgrade). Cosine matches the normalised multilingual space.
CREATE INDEX idx_doc_vectors_ann
    ON doc_vectors USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
