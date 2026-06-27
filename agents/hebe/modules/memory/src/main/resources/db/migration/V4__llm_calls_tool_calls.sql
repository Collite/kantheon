-- V4__llm_calls_tool_calls.sql

CREATE TABLE llm_calls (
  id              TEXT PRIMARY KEY,
  conversation_id TEXT,
  turn_id         TEXT,
  model           TEXT NOT NULL,
  tokens_in       INTEGER NOT NULL,
  tokens_out      INTEGER NOT NULL,
  tokens_cached   INTEGER NOT NULL DEFAULT 0,
  cost_micros_usd INTEGER,
  ms              INTEGER NOT NULL,
  ts              INTEGER NOT NULL
);

CREATE TABLE tool_calls (
  id            TEXT PRIMARY KEY,
  turn_id       TEXT,
  tool          TEXT NOT NULL,
  risk          TEXT NOT NULL,
  args_redacted TEXT NOT NULL,
  result_json   TEXT,
  ok            INTEGER NOT NULL,
  ms            INTEGER NOT NULL,
  ts            INTEGER NOT NULL,
  receipt_seq   INTEGER NOT NULL
);

CREATE INDEX idx_tool_calls_turn ON tool_calls(turn_id);
CREATE INDEX idx_tool_calls_ts ON tool_calls(ts);