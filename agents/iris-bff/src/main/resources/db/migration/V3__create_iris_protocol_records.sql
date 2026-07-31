-- PT arc, Phase 1 Stage 1.1 (T3): the /protocol write path (contracts §6.1).
--
-- One row per turn: pointers into the federated sources the assembler later
-- reads (trace, gateway turn ref, plan/call refs, the log window) plus the two
-- in-band captures that cannot be recovered from logs afterwards — F2 (Themis
-- ResolveResponse) and F7 (the applied security rules). PT-4: the record is the
-- backbone precisely because the sources it points at rot at their own
-- retention; the pointers outlive the bodies.
--
-- ON DELETE CASCADE, not a nullable FK: a protocol record has no meaning
-- without its turn. Turns are never hard-deleted on the ordinary path (reset
-- and edit-resend status-flip to 'discarded'), so this fires only when a turn
-- genuinely goes away.
CREATE TABLE iris_protocol_records (
    turn_id        UUID PRIMARY KEY REFERENCES iris_turns (turn_id) ON DELETE CASCADE,
    pointers       JSONB NOT NULL,                     -- RecordPointers JSON (camelCase, proto JsonFormat)
    captures       JSONB NOT NULL DEFAULT '{}'::jsonb, -- RecordCaptures — b64 proto bytes per field
    schema_version TEXT  NOT NULL,                     -- "protocol/v1.<minor>"; stamped on every write
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Retention/janitor scans sweep by age, not by session.
CREATE INDEX idx_protocol_records_created ON iris_protocol_records (created_at);
