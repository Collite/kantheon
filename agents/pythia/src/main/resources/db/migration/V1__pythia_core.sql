-- Pythia core schema (contracts §4). Six tables: investigations + the four
-- per-investigation aggregates (hypotheses / steps / handles) + the diff-based
-- checkpoint log + the authoritative event log. JSONB columns carry proto3-JSON
-- payloads opaquely (the repository (de)serialises by identity, golem idiom).
-- artifact_ref handed to Iris = pythia_investigations.id.

CREATE TABLE pythia_investigations (
    id                  UUID PRIMARY KEY,
    parent_id           UUID,
    caller              JSONB NOT NULL,
    question            TEXT NOT NULL,
    request             JSONB NOT NULL,                  -- full Investigation snapshot
    status              TEXT NOT NULL,
    resolution          JSONB,
    plan                JSONB,
    conclusion          JSONB,
    resource_usage      JSONB NOT NULL DEFAULT '{}',
    warnings            JSONB NOT NULL DEFAULT '[]',
    awaiting_since      TIMESTAMPTZ,                     -- set on entering any AWAITING_*
    awaiting_ttl_until  TIMESTAMPTZ,                     -- 24h default expiry (Stage 1.3 sweeper)
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    finalised_at        TIMESTAMPTZ
);

-- The PD-2 inbox source filters by caller user_id + status; index the JSONB key.
CREATE INDEX pythia_investigations_user
    ON pythia_investigations ((caller ->> 'userId'));
CREATE INDEX pythia_investigations_status ON pythia_investigations (status);

CREATE TABLE pythia_hypotheses (
    investigation_id    UUID NOT NULL REFERENCES pythia_investigations(id),
    hyp_id              TEXT NOT NULL,
    parent_hyp_id       TEXT,
    body                JSONB NOT NULL,                  -- full Hypothesis
    status              TEXT NOT NULL,
    confidence          DOUBLE PRECISION,
    PRIMARY KEY (investigation_id, hyp_id)
);

CREATE TABLE pythia_steps (
    investigation_id    UUID NOT NULL REFERENCES pythia_investigations(id),
    step_id             TEXT NOT NULL,
    node_id             TEXT NOT NULL,
    body                JSONB NOT NULL,                  -- full StepRecord
    status              TEXT NOT NULL,
    output_handle       JSONB,
    PRIMARY KEY (investigation_id, step_id)
);

CREATE TABLE pythia_handles (
    investigation_id    UUID NOT NULL REFERENCES pythia_investigations(id),
    handle_id           TEXT NOT NULL,
    kind                TEXT NOT NULL,
    body                JSONB NOT NULL,                  -- full Handle (minus inline payload)
    inline_data         BYTEA,                           -- PgResultSnapshot Arrow IPC (capped)
    PRIMARY KEY (investigation_id, handle_id)
);

CREATE TABLE pythia_checkpoints (
    investigation_id    UUID NOT NULL REFERENCES pythia_investigations(id),
    seq                 INT NOT NULL,
    taken_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason              TEXT NOT NULL,                   -- awaiting_* | plan_revised | batch_completed
    scheduler_state     JSONB NOT NULL,                  -- full snapshot at the baseline (seq 0); '{}' after
    diff                JSONB NOT NULL,                  -- field-level delta vs the prior checkpoint
    PRIMARY KEY (investigation_id, seq)
);

CREATE TABLE pythia_events (
    investigation_id    UUID NOT NULL REFERENCES pythia_investigations(id),
    sequence            BIGINT NOT NULL,                 -- per-investigation monotone, gap-free
    emitted_at          TIMESTAMPTZ NOT NULL,
    kind                TEXT NOT NULL,
    payload             JSONB NOT NULL,
    PRIMARY KEY (investigation_id, sequence)
);
