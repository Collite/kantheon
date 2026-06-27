-- V4__llm_calls_tool_calls.sql (Postgres variant; contracts §4.1)
--
-- Append-only (standalone invariant): app role gets no UPDATE/DELETE on llm_calls /
-- tool_calls (granted at provisioning, Stage 3.3 / contracts §4.4).

CREATE TABLE llm_calls (
  id              text PRIMARY KEY,
  conversation_id text,
  turn_id         text,
  model           text NOT NULL,
  tokens_in       integer NOT NULL,
  tokens_out      integer NOT NULL,
  tokens_cached   integer NOT NULL DEFAULT 0,
  cost_micros_usd bigint,
  ms              bigint NOT NULL,
  ts              timestamptz NOT NULL
);

CREATE TABLE tool_calls (
  id            text PRIMARY KEY,
  turn_id       text,
  tool          text NOT NULL,
  risk          text NOT NULL,
  args_redacted text NOT NULL,
  result_json   jsonb,
  ok            boolean NOT NULL,
  ms            bigint NOT NULL,
  ts            timestamptz NOT NULL,
  receipt_seq   bigint NOT NULL
);

CREATE INDEX idx_tool_calls_turn ON tool_calls(turn_id);
CREATE INDEX idx_tool_calls_ts ON tool_calls(ts);
