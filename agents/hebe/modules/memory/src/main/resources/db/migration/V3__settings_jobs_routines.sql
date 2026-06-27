-- V3__settings_jobs_routines.sql

CREATE TABLE settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  ts    INTEGER NOT NULL
);

CREATE TABLE jobs (
  id           TEXT PRIMARY KEY,
  kind         TEXT NOT NULL,
  status       TEXT NOT NULL,
  started_at   INTEGER,
  ended_at     INTEGER,
  trigger_at   INTEGER,
  payload_json TEXT,
  result_json  TEXT,
  attempt      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_jobs_kind_status ON jobs(kind, status);
CREATE INDEX idx_jobs_trigger ON jobs(trigger_at) WHERE status = 'pending';

CREATE TABLE routines (
  id            TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  cron          TEXT NOT NULL,
  body_kind     TEXT NOT NULL,
  body_ref      TEXT NOT NULL,
  body_json     TEXT,
  enabled       INTEGER NOT NULL DEFAULT 1,
  created_at    INTEGER NOT NULL,
  last_run_at   INTEGER,
  next_run_at   INTEGER
);