-- V2__memory.sql (Postgres variant; contracts §4.2)
--
-- Replaces the SQLite FTS5 + sqlite-vec projections with:
--   full-text -> tsvector GENERATED column + GIN index (ranked by ts_rank_cd)
--   vector    -> pgvector vector(1536) + HNSW cosine index
-- The text-search config is FIXED here ('simple') and is NEVER per-instance — the
-- RRF golden-fixture parity set (Stage 3.1 T1) defines the accepted behaviour at the
-- tokenizer margin (FTS5 porter/unicode61 vs PG simple). The SQLite V6 `category`
-- alter is folded into memory_docs here (fresh schema).

CREATE TABLE memory_docs (
  path        text PRIMARY KEY,
  content     text NOT NULL,
  scope       text NOT NULL DEFAULT 'Default',
  category    text NOT NULL DEFAULT 'Document',
  ts          timestamptz NOT NULL,
  byte_size   integer NOT NULL,
  hash_sha256 text NOT NULL
);

CREATE INDEX idx_memory_docs_category ON memory_docs(category);

CREATE TABLE memory_chunks (
  doc_path    text NOT NULL REFERENCES memory_docs(path) ON DELETE CASCADE,
  chunk_idx   integer NOT NULL,
  content     text NOT NULL,
  token_count integer NOT NULL,
  ts          timestamptz NOT NULL,
  tsv         tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
  embedding   vector(1536),            -- pgvector; null until indexed
  PRIMARY KEY (doc_path, chunk_idx)
);

CREATE INDEX idx_memory_chunks_tsv ON memory_chunks USING gin(tsv);
CREATE INDEX idx_memory_chunks_vec ON memory_chunks USING hnsw (embedding vector_cosine_ops);
