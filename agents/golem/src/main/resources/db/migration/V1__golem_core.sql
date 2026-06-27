-- golem_turns — one row per finished Golem turn (contracts §4).
-- One turn = one row; conversation memory is Iris's job. AMEND/DRILL read a
-- prior turn's plan + current_view back (resolved in Stage 2.4). No checkpoint
-- table, no event log — Golems don't pause; a clarification is terminal-and-resume.
CREATE TABLE golem_turns (
    id                   UUID PRIMARY KEY,          -- == ConversationalResponse.id
    request_id           UUID NOT NULL,             -- == GolemRequest.id == Iris turn_id
    golem_id             TEXT NOT NULL,
    user_id              TEXT NOT NULL,
    tenant_id            TEXT NOT NULL,
    question             TEXT NOT NULL,
    resolved_intent      JSONB NOT NULL,            -- themis Resolution snapshot
    plan                 JSONB NOT NULL,            -- MiniPlan (AMEND/DRILL read this back)
    envelopes            JSONB NOT NULL,            -- [FormatEnvelope]
    current_view         JSONB,                     -- the view this turn produced (AMEND/DRILL target)
    step_records         JSONB NOT NULL DEFAULT '[]',
    resource_usage       JSONB NOT NULL DEFAULT '{}',
    pending_resume_token TEXT,                      -- set while status = clarification
    status               TEXT NOT NULL CHECK (status IN ('done', 'failed', 'clarification')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    finalised_at         TIMESTAMPTZ
);

CREATE INDEX golem_turns_request ON golem_turns (request_id);
