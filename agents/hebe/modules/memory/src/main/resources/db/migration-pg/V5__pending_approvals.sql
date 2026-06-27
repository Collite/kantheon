-- V5__pending_approvals.sql (Postgres variant; contracts §4.1)

CREATE TABLE pending_approvals (
  id            text PRIMARY KEY,
  turn_id       text NOT NULL,
  tool          text NOT NULL,
  args_redacted text NOT NULL,
  prompt        text NOT NULL,
  channel       text NOT NULL,
  thread_ext_id text,
  created_at    timestamptz NOT NULL,
  expires_at    timestamptz NOT NULL,
  resolved_at   timestamptz,
  approved      boolean
);
