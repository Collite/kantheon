-- V3__settings_jobs_routines.sql (Postgres variant; contracts §4.1)
--
-- Two content changes vs the SQLite set, folded into the base tables (contracts §4.1):
--   routines: body_kind gains value 'kantheon_question' (free text, no CHECK), plus
--             new columns session_ref / last_turn_ref (per-routine Iris session).
--   jobs:     kind='routine' rows gain turn_ref (the iris-bff turn id) for
--             cross-linking receipts <-> Iris session.

CREATE TABLE settings (
  key   text PRIMARY KEY,
  value text NOT NULL,
  ts    timestamptz NOT NULL
);

CREATE TABLE jobs (
  id           text PRIMARY KEY,
  kind         text NOT NULL,
  status       text NOT NULL,
  started_at   timestamptz,
  ended_at     timestamptz,
  trigger_at   timestamptz,
  payload_json jsonb,
  result_json  jsonb,
  attempt      integer NOT NULL DEFAULT 0,
  turn_ref     text
);

CREATE INDEX idx_jobs_kind_status ON jobs(kind, status);
CREATE INDEX idx_jobs_trigger ON jobs(trigger_at) WHERE status = 'pending';

CREATE TABLE routines (
  id            text PRIMARY KEY,
  name          text NOT NULL,
  cron          text NOT NULL,
  body_kind     text NOT NULL,
  body_ref      text NOT NULL,
  body_json     jsonb,
  enabled       boolean NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL,
  last_run_at   timestamptz,
  next_run_at   timestamptz,
  session_ref   text,
  last_turn_ref text
);
