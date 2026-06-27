-- V6__workspace.sql (contracts §4.3) — the markdown workspace (MEMORY.md,
-- IDENTITY.md, HEARTBEAT.md, daily/*.md) moves from ~/.hebe/workspace/ into the
-- instance schema. Selected by fs.durability=ephemeral (k8s only); persistent-FS
-- profiles (local/personal/server) keep the file workspace.

CREATE TABLE workspace_files (
  path        text PRIMARY KEY,            -- "MEMORY.md", "daily/2026-06-12.md"
  content     text NOT NULL,
  revision    integer NOT NULL DEFAULT 1,  -- bumped on every write; optimistic concurrency
  updated_at  timestamptz NOT NULL,
  updated_by  text NOT NULL                -- "agent" | "console:<user>"
);
