-- V5__pending_approvals.sql

CREATE TABLE pending_approvals (
  id           TEXT PRIMARY KEY,
  turn_id      TEXT NOT NULL,
  tool         TEXT NOT NULL,
  args_redacted TEXT NOT NULL,
  prompt       TEXT NOT NULL,
  channel      TEXT NOT NULL,
  thread_ext_id TEXT,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  resolved_at  INTEGER,
  approved     INTEGER
);