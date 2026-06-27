-- Iris-BFF core schema (contracts §3). All tables land day one (cohesion review
-- D6); the audit/feedback/artifact write paths are wired in Stage 1.3 / Phase 4.

CREATE TABLE iris_sessions (
    session_id      UUID PRIMARY KEY,
    user_id         TEXT NOT NULL,
    tenant_id       TEXT NOT NULL,
    entity_context  JSONB NOT NULL DEFAULT '[]',
    current_display JSONB NOT NULL DEFAULT '{}',     -- BFF-owned rendering choices
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX iris_sessions_user_idx ON iris_sessions (user_id, updated_at DESC);

CREATE TABLE iris_turns (
    turn_id        UUID PRIMARY KEY,
    session_id     UUID NOT NULL REFERENCES iris_sessions (session_id),
    seq            INT  NOT NULL,                     -- order within session
    agent_id       TEXT NOT NULL,
    artifact_ref   TEXT,                              -- agent-side pointer (null for /v2 transitional)
    question       TEXT NOT NULL,
    envelope_json  JSONB,                             -- snapshot/cache of terminal envelope(s)
    displayed_block_ids TEXT[] NOT NULL DEFAULT '{}',
    pending_resume_token TEXT,                        -- set while a clarification is open
    resume_issuer_agent_id TEXT,
    status         TEXT NOT NULL CHECK (status IN ('done', 'failed', 'clarification', 'discarded')),
    origin         TEXT NOT NULL DEFAULT 'user' CHECK (origin IN ('user', 'scheduled')),  -- TurnOrigin; inbox badge join
    origin_ref     TEXT,                              -- Hebe routine_id when origin = scheduled
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, seq)
);

CREATE TABLE iris_snapshots (
    snapshot_id    UUID PRIMARY KEY,
    session_id     UUID NOT NULL REFERENCES iris_sessions (session_id),
    reason         TEXT NOT NULL,                     -- reset | edit_resend
    entity_context JSONB NOT NULL,
    turn_ids       UUID[] NOT NULL,                   -- turns visible at snapshot time
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Transitional 1:1 session ↔ new-golem-thread binding; dropped at Golem cutover.
CREATE TABLE iris_v2_threads (
    session_id    UUID PRIMARY KEY REFERENCES iris_sessions (session_id),
    v2_thread_id  TEXT NOT NULL
);

-- Hash-chained append-only audit (PD-8, §3.1). app role: INSERT + SELECT only.
CREATE TABLE iris_audit (
    seq        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ts         TIMESTAMPTZ NOT NULL,
    user_id    TEXT NOT NULL,
    event_kind TEXT NOT NULL,        -- turn | typed_action | export | resume | escalation | artifact_refresh
    payload    JSONB NOT NULL,
    segment    TEXT NOT NULL,        -- "YYYY-MM" — retention + verification unit
    prev_hash  TEXT NOT NULL,
    self_hash  TEXT NOT NULL,        -- sha256(canonical(payload) + prev_hash)
    sig        TEXT NOT NULL         -- Ed25519 over self_hash (iris-bff signing key)
);

CREATE INDEX iris_audit_segment_idx ON iris_audit (segment, seq);

-- Turn feedback (PD-3, §3.2 — telemetry, not audit: plain table, no hash chain).
CREATE TABLE iris_feedback (
    feedback_id  UUID PRIMARY KEY,
    turn_id      UUID NOT NULL,
    user_id      TEXT NOT NULL,
    agent_id     TEXT NOT NULL,
    verdict      TEXT NOT NULL CHECK (verdict IN ('up', 'down')),
    reason       TEXT,                 -- wrong_data | wrong_agent | wrong_format | too_slow | other
    comment      TEXT,
    corrected_agent_id TEXT,           -- PD-14: filled by the re-ask action
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (turn_id, user_id)
);

-- Pins & dashboards (PD-6, §3.3).
CREATE TABLE iris_artifacts (
    artifact_id     UUID PRIMARY KEY,
    user_id         TEXT NOT NULL,
    tenant_id       TEXT NOT NULL,
    kind            TEXT NOT NULL,        -- pin | dashboard
    name            TEXT NOT NULL,
    agent_id        TEXT,
    envelope_json   JSONB,
    provenance      JSONB,
    applied_context JSONB,
    display_state   JSONB,
    params_json     JSONB,
    refresh_mode    TEXT NOT NULL DEFAULT 'manual',   -- manual | on_open
    param_mode      TEXT,                 -- pythia pins: moving | frozen
    template_id     TEXT,
    member_ids      UUID[],               -- dashboards: ordered pin refs
    layout_json     JSONB,
    refreshed_at    TIMESTAMPTZ,
    refresh_error   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX iris_artifacts_user_idx ON iris_artifacts (user_id, kind);
