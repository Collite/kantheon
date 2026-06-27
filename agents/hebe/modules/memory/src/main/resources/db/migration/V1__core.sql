-- V1__core.sql

CREATE TABLE conversations (
  id            TEXT PRIMARY KEY,
  channel       TEXT NOT NULL,
  user_id       TEXT NOT NULL,
  external_id   TEXT,
  started_at    INTEGER NOT NULL,
  ended_at      INTEGER,
  metadata_json TEXT
);

CREATE INDEX idx_conversations_channel ON conversations(channel);
CREATE INDEX idx_conversations_external ON conversations(external_id);

CREATE TABLE messages (
  id              TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL REFERENCES conversations(id),
  role            TEXT NOT NULL,
  content         TEXT NOT NULL,
  tool_calls_json TEXT,
  tool_call_id    TEXT,
  ts              INTEGER NOT NULL,
  redaction_json  TEXT,
  meta_json       TEXT
);

CREATE INDEX idx_messages_conv_ts ON messages(conversation_id, ts);