-- Kleio/DocWH P5 Stage 5.1 — the `kleio` DB (contracts §3). One internal Kantheon
-- Postgres, `kleio` database (topology §7.1). Conversation memory is Iris's job
-- (the Golem rule) — Kleio persists one turn row + generated artifacts.

CREATE TABLE kleio_turns (
    turn_id        TEXT        PRIMARY KEY,
    session_id     TEXT        NOT NULL,
    notebook_id    TEXT        NOT NULL,
    question       TEXT        NOT NULL,
    status         TEXT        NOT NULL,
    envelopes      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    sources_used   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    resource_usage JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notebook_artifacts (
    artifact_id        TEXT        PRIMARY KEY,
    notebook_id        TEXT        NOT NULL,
    kind               TEXT        NOT NULL,   -- SUMMARY | FAQ | TIMELINE | BRIEFING
    envelope           JSONB       NOT NULL,
    sources_used       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_by_user_id TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_kleio_turns_session ON kleio_turns (session_id);
CREATE INDEX idx_notebook_artifacts_notebook ON notebook_artifacts (notebook_id);
