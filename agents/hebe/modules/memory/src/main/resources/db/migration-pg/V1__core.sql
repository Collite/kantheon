-- V1__core.sql (Postgres variant of the SQLite db/migration/V1__core.sql)
--
-- Type mapping (contracts §4.1): TEXT pk/refs -> text, epoch INTEGER -> timestamptz,
-- *_json TEXT -> jsonb, INTEGER bool -> boolean. The SQLite V7 `messages.summary_id`
-- alter is folded into the base table here (fresh schema, no alter history to mirror).
-- Applied per-instance: flyway.schemas=hebe_<id>, search_path pinned.

CREATE TABLE conversations (
  id            text PRIMARY KEY,
  channel       text NOT NULL,
  user_id       text NOT NULL,
  external_id   text,
  started_at    timestamptz NOT NULL,
  ended_at      timestamptz,
  metadata_json jsonb
);

CREATE INDEX idx_conversations_channel ON conversations(channel);
CREATE INDEX idx_conversations_external ON conversations(external_id);

-- Append-only (standalone invariant): the app role is granted no UPDATE/DELETE on
-- messages at provisioning (Stage 3.3, contracts §4.4).
CREATE TABLE messages (
  id              text PRIMARY KEY,
  conversation_id text NOT NULL REFERENCES conversations(id),
  role            text NOT NULL,
  content         text NOT NULL,
  tool_calls_json jsonb,
  tool_call_id    text,
  ts              timestamptz NOT NULL,
  redaction_json  jsonb,
  meta_json       jsonb,
  summary_id      text
);

CREATE INDEX idx_messages_conv_ts ON messages(conversation_id, ts);
CREATE INDEX idx_messages_summary ON messages(summary_id) WHERE summary_id IS NOT NULL;
