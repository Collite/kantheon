-- V7__messages_summary_id.sql

ALTER TABLE messages ADD COLUMN summary_id TEXT;
CREATE INDEX idx_messages_summary ON messages(summary_id) WHERE summary_id IS NOT NULL;