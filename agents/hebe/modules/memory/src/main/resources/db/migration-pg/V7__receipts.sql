-- V7__receipts.sql (contracts §4.3) — the NDJSON hash-chained receipts log ported to
-- Postgres. Append-only: the app role gets no UPDATE/DELETE (granted at provisioning,
-- Stage 3.3 / contracts §4.4). Chain verification (`hebe memory show receipts
-- --verify`) walks seq order with the same algorithm as the file log; the Ed25519
-- signing key moves from secrets.db to the instance's K8s Secret. Selected by
-- receipts.backend=postgres (fs.durability=ephemeral, k8s only).

CREATE TABLE receipts (
  seq         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ts          timestamptz NOT NULL,
  payload     jsonb NOT NULL,              -- the receipt document, unchanged shape
  prev_hash   text NOT NULL,               -- self_hash of seq-1 ('genesis' for seq 1)
  self_hash   text NOT NULL,               -- sha256 over canonical(payload) + prev_hash
  sig         text NOT NULL                -- Ed25519 over self_hash (signing key per instance)
);
