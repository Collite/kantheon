-- Kleio/DocWH P1 Stage 1.2 — the relational plane of the `kallimachos` corpus
-- (contracts §3). Single internal Kantheon Postgres, `kallimachos` database
-- (topology §7.1). The `vector` + `age` extensions are installed cluster-side
-- and used from P2 (this migration touches only the relational + full-text
-- planes; V2 adds the GIN tsv index).
--
-- IDs are DB-generated and GLOBALLY UNIQUE across sources + parts + pages — one
-- shared sequence backs all three id columns, so a node id is unambiguous
-- wherever it surfaces (citations, graph edges, notebook membership).

CREATE SEQUENCE IF NOT EXISTS corpus_node_id;

-- Notebooks (marts) — m:n curated views over the corpus. owner + visibility_roles
-- (PD-8; stored at v1, enforced at P4 via OBO + Argos).
CREATE TABLE notebooks (
    id               TEXT PRIMARY KEY,
    display_name     TEXT        NOT NULL,
    owner_user_id    TEXT        NOT NULL,
    visibility_roles TEXT[]      NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Sources — ingested documents (faithful, citeable).
CREATE TABLE sources (
    id               BIGINT      PRIMARY KEY DEFAULT nextval('corpus_node_id'),
    asset_ref        TEXT        NOT NULL DEFAULT '',
    mime_type        TEXT        NOT NULL DEFAULT '',
    title            TEXT        NOT NULL DEFAULT '',
    metadata         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    embedding_status TEXT        NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Parts — paragraph chunks of a source. `content_tsv` is the FULL-TEXT plane
-- column (maintained by the V2 trigger; GIN-indexed in V2).
CREATE TABLE parts (
    id           BIGINT   PRIMARY KEY DEFAULT nextval('corpus_node_id'),
    source_id    BIGINT   NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    idx          INT      NOT NULL,
    kind         TEXT     NOT NULL DEFAULT 'paragraph',
    content_text TEXT     NOT NULL,
    content_tsv  TSVECTOR,
    metadata     JSONB    NOT NULL DEFAULT '{}'::jsonb
);

-- Pages — LLM-authored wiki pages (Pinakes compile, P3). `concept_ref` is the
-- §6 Ariadne bridge seam: the column is PRESENT and NULL at v1.
CREATE TABLE pages (
    id          BIGINT      PRIMARY KEY DEFAULT nextval('corpus_node_id'),
    kind        TEXT        NOT NULL,
    title       TEXT        NOT NULL,
    content_md  TEXT        NOT NULL,
    concept_ref JSONB,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- m:n mart membership — a source or page belongs to many notebooks.
CREATE TABLE notebook_members (
    notebook_id TEXT   NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
    node_kind   TEXT   NOT NULL,  -- 'source' | 'page'
    node_id     BIGINT NOT NULL,
    PRIMARY KEY (notebook_id, node_kind, node_id)
);

-- Audit (security §4 receipts) — one row per ingest/retrieval.
CREATE TABLE request_log (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    node_kind     TEXT,
    node_id       BIGINT,
    action        TEXT        NOT NULL,
    actor_user_id TEXT,
    notebook_id   TEXT,
    at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_parts_source ON parts (source_id);
CREATE INDEX idx_notebook_members_node ON notebook_members (node_kind, node_id);
