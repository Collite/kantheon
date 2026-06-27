-- Kleio/DocWH P1 Stage 1.3 — Pinakes's own small schema on the one Kantheon PG
-- (plan §8 leaning: assets now; pipelines / pipeline_runs / lineage join in
-- Phase 3). The catalogue: what raw asset was staged, where, and which feed it
-- belongs to. In-memory at P1 (the running profile); this is the deploy path.

CREATE TABLE assets (
    id            TEXT        PRIMARY KEY,
    asset_ref     TEXT        NOT NULL,   -- Seaweed stage key
    source_feed   TEXT        NOT NULL,   -- binds the pipeline (architecture §7)
    mime_type     TEXT        NOT NULL,
    original_name TEXT        NOT NULL,
    staged_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_assets_source_feed ON assets (source_feed);
