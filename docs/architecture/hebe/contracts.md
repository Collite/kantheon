# Hebe — Kantheon Integration Contracts

> **Status:** draft v0.1 — 2026-06-12. Companion to [`architecture.md`](./architecture.md).
>
> Source of truth for everything that crosses a service boundary in the Hebe arc: proto packages, registration manifest, the iris/v1 additions Hebe needs, the per-instance PG schema, and the profile config schema. Hebe-internal interfaces (kernel ABI, plugin ABI, tool dispatcher) are unchanged and stay documented in [`standalone-v1-architecture.md`](./standalone-v1-architecture.md) §3–§4.

---

## 1. Proto surface

### 1.1 What Hebe consumes (no new protos needed)

| Contract | Package | Use |
|---|---|---|
| Agent registration + heartbeat | `org.tatrman.kantheon.capabilities.v1` | `RegisterRequest`/`HeartbeatRequest`; `AgentCapability` with `agent_kind = PERSONAL_ASSISTANT`, **`non_routable = true`** (field 16, added 2026-06-12) |
| Chat turns into the constellation | `org.tatrman.kantheon.iris.v1` | `ChatTurnRequest` + `IrisStreamEvent` consumption — Hebe is a headless Iris client (§3 below) |
| LLM calls (k8s profile) | llm-gateway's OpenAI-compat HTTP surface | Same client as BYOK; base URL + auth header swap |

### 1.2 `org.tatrman.kantheon.hebe.v1` (lands Phase 4)

File: `shared/proto/src/main/proto/org/tatrman/kantheon/hebe/v1/hebe.proto`. Minimal in v1 — only the types that cross a boundary (routine semantics shared between scheduler, console, and receipts; rendered in the web console FE). Full console-API proto alignment is deferred to v1.x.

```proto
syntax = "proto3";
package org.tatrman.kantheon.hebe.v1;

import "google/protobuf/timestamp.proto";
import "org/tatrman/kantheon/common/v1/response_message.proto";

// A scheduled routine. Mirrors the `routines` table row.
message Routine {
  string routine_id                       = 1;
  string name                             = 2;
  string cron                             = 3;   // 5-field cron, instance-local TZ
  RoutineBody body                        = 4;
  bool enabled                            = 5;
  google.protobuf.Timestamp last_run_at   = 6;
  google.protobuf.Timestamp next_run_at   = 7;
}

message RoutineBody {
  oneof kind {
    SkillBody skill                  = 1;  // standalone-era: local skill
    ToolBody tool                    = 2;  // standalone-era: direct tool call
    KantheonQuestionBody kantheon    = 3;  // NEW: a turn dispatched via iris-bff
  }
}

message SkillBody  { string skill_name = 1; string args_json = 2; }   // Rule 7: argsJson
message ToolBody   { string tool_name  = 1; string args_json = 2; }

// "Run this question through the constellation and deliver the result."
message KantheonQuestionBody {
  string question                  = 1;   // natural-language; routed by Themis like any turn
  string session_ref               = 2;   // stable per-routine Iris session (created on first run)
  repeated string delivery_channels = 3;  // "telegram" | "web" — where Hebe pushes the conclusion
  string routing_hint              = 4;   // optional; honoured by Themis Layer 0
}

// One execution of a routine. Mirrors a `jobs` row of kind=routine + delivery outcome.
message RoutineRun {
  string run_id                           = 1;
  string routine_id                       = 2;
  RunStatus status                        = 3;
  google.protobuf.Timestamp started_at    = 4;
  google.protobuf.Timestamp ended_at      = 5;
  string turn_ref                         = 6;   // iris-bff turn id (kantheon bodies only)
  string error                            = 7;
  repeated DeliveryRecord deliveries      = 8;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;  // Rule 6
}

enum RunStatus {
  RUN_STATUS_UNSPECIFIED = 0;
  RUNNING                = 1;
  AWAITING_AGENT         = 2;   // constellation turn paused (e.g. Pythia AWAITING_*)
  DELIVERED              = 3;
  FAILED                 = 4;
  CANCELLED              = 5;
}

message DeliveryRecord {
  string channel                          = 1;
  google.protobuf.Timestamp delivered_at  = 2;
  bool ok                                 = 3;
}
```

Notes: Rule 6 (`messages = 99`) and Rule 7 (`args_json`, camelCase keys on the wire) inherited as everywhere in kantheon.

## 2. Capabilities registration manifest

Fixture at `tools/capabilities-mcp/src/main/resources/manifests/agents/hebe.yaml` (bootstrap); runtime registration from the instance supersedes it. One registration **per instance** — `agent_id` carries the instance id.

```yaml
kind: AGENT
agent_kind: PERSONAL_ASSISTANT
agent_id: hebe-bora                 # hebe-<instance_id>
display_name: "Hebe (Bora)"
non_routable: true                  # NEVER a routing candidate — all four Themis layers skip it
description_for_router: ""          # deliberately empty; never enters Layer 2 prompts
intent_kinds_supported: []          # none — Hebe answers no analytical intents
capability_refs: []
service_endpoint: http://hebe-bora.kantheon.svc:8765
health_check_path: /healthz
hitl_default: SPECULATIVE
```

**Registry behaviour contract:** entries with `non_routable: true` are excluded from Themis's `list_agents()` routing view (Layer 1 scoring, Layer 2 prompt assembly, Layer 3 alternates) but returned by plain `list`/`get`/`search` for discovery. A Themis regression test asserts Hebe never appears in a `RoutingDecision` (plan Stage 3.4).

## 3. Hebe → iris-bff: the headless-client contract

Hebe drives the same surface as the Vue FE: `POST` chat turn, consume the SSE stream (`step` / `envelope` / `done` / `error` events). Two additions are required on the **Iris side** — co-design items for the Iris arc, tracked in its contracts doc once agreed:

### 3.1 `TurnOrigin` on `ChatTurnRequest` (iris/v1 addition)

> **Landed 2026-06-12 (cohesion review):** the fields below are now part of `iris/contracts.md` §1.2 (`origin = 6`, `origin_ref = 7`) and the `iris_turns` DDL. This section remains as the co-design rationale.

```proto
// addition to org.tatrman.kantheon.iris.v1.ChatTurnRequest
TurnOrigin origin       = N;   // default USER
string origin_ref       = N+1; // routine_id for SCHEDULED; empty otherwise

enum TurnOrigin {
  TURN_ORIGIN_UNSPECIFIED = 0;
  USER                    = 1;
  SCHEDULED               = 2;   // Hebe routine
}
```

Semantics: the turn is persisted, routed, and rendered exactly like a user turn; `origin` is metadata for the session log, the PD-2 inbox ("scheduled" badge), audit, and analytics. iris-bff must not gate on it.

### 3.2 Machine-client auth

Hebe authenticates with a Keycloak **on-behalf-of** token for the instance's bound user. iris-bff sees a valid user bearer token — no service-account path, no special casing. (Consequence: PD-8 authorization decisions automatically apply to scheduled turns.) This holds for **every** platform-reaching profile (`personal`, `server`, `k8s`), not just k8s: `personal`/`server` mint the OBO grant via device-code + cached refresh token against the public Keycloak; `k8s` via the in-cluster client-credentials → OBO exchange. Only `local` (`platform.reach = none`) skips this entirely.

### 3.3 Session naming

First run of a routine creates a session titled after the routine (`"⏰ <routine name>"`); `session_ref` is stored on the routine; subsequent runs append turns to the same session. Session creation goes through the ordinary iris-bff session API.

### 3.4 Pause semantics

If the stream reports an agent pause (`AWAITING_*`), Hebe marks the run `AWAITING_AGENT`, delivers a channel message with a deep link into the Iris session, and does **not** attempt to answer clarifications itself in v1. Resume happens in Iris by the human.

## 4. Per-instance Postgres schema (`storage.backend = postgres`: `server` + `k8s`)

Applies to both PG-backed profiles. `server` points at an **external** PG (TLS, pooled, creds from file/keychain); `k8s` at the in-cluster Kantheon PG (creds from K8s Secret). Database `hebe`; schema `hebe_<instance_id>` per instance; `search_path` pinned; **no cross-schema access**. Flyway migration set shared by all instances (`flyway.schemas=hebe_<id>`), maintained at `modules/memory/src/main/resources/db/migration-pg/` alongside the SQLite set (`db/migration/`).

Note the axis split: `server` runs this PG schema for the db **but keeps file workspace + file receipts** (its FS persists), so it applies §4.1–§4.2 but **not** the §4.3 tables. Only `fs.durability = ephemeral` (`k8s`) adds §4.3.

### 4.1 Ported tables (logical schema identical to standalone §5)

`conversations`, `messages`, `settings`, `jobs`, `routines`, `llm_calls`, `tool_calls`, `pending_approvals` port 1:1 with type mapping: `TEXT` pk/refs → `text`, epoch `INTEGER` timestamps → `timestamptz`, `*_json TEXT` → `jsonb`, booleans `INTEGER` → `boolean`. Indexes carry over. Two content changes:

- `routines.body_kind` gains value `kantheon_question` (alongside `skill | tool | sop_v2`); `body_json` then holds `KantheonQuestionBody` fields. New columns: `session_ref text`, `last_turn_ref text`.
- `jobs` rows of `kind=routine` gain `turn_ref text` (the iris-bff turn id) for cross-linking receipts ↔ Iris session.

Standalone invariants carry over unchanged: `messages` / `llm_calls` / `tool_calls` are append-only; FTS/vector projections are derived and rebuildable.

### 4.2 Memory search (replaces FTS5 + sqlite-vec)

```sql
-- V2__memory.sql (PG variant)
CREATE TABLE memory_docs (
  path        text PRIMARY KEY,
  content     text NOT NULL,
  scope       text NOT NULL DEFAULT 'Default',
  ts          timestamptz NOT NULL,
  byte_size   integer NOT NULL,
  hash_sha256 text NOT NULL
);

CREATE TABLE memory_chunks (
  doc_path    text NOT NULL REFERENCES memory_docs(path) ON DELETE CASCADE,
  chunk_idx   integer NOT NULL,
  content     text NOT NULL,
  token_count integer NOT NULL,
  ts          timestamptz NOT NULL,
  tsv         tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
  embedding   vector(1536),            -- pgvector; null until indexed
  PRIMARY KEY (doc_path, chunk_idx)
);

CREATE INDEX idx_memory_chunks_tsv ON memory_chunks USING gin(tsv);
CREATE INDEX idx_memory_chunks_vec ON memory_chunks USING hnsw (embedding vector_cosine_ops);
```

- Hybrid retrieval: top-k by `ts_rank_cd` + top-k by cosine distance → **Reciprocal Rank Fusion, k₀ = 60** — identical fusion code as the SQLite backend; only the two candidate queries differ.
- **Parity contract:** a golden fixture corpus (queries → expected ranked doc/chunk ids) runs against both backends in CI; divergence fails the build. Tokenizer note: FTS5 `porter unicode61` vs PG `simple`/`english` config will differ at the margin — the golden set defines the accepted behaviour; the PG text-search config is fixed in the migration and never per-instance.
- Embedding dimension (1536) stays config-bound to the embedding model; changing models requires a migration + re-embed, same rule as standalone.

### 4.3 New tables (`fs.durability = ephemeral` only — i.e. `k8s`)

```sql
-- V6__workspace.sql — workspace files move from ~/.hebe/workspace/ into the schema
CREATE TABLE workspace_files (
  path        text PRIMARY KEY,            -- "MEMORY.md", "daily/2026-06-12.md"
  content     text NOT NULL,
  revision    integer NOT NULL DEFAULT 1,  -- bumped on every write; optimistic concurrency
  updated_at  timestamptz NOT NULL,
  updated_by  text NOT NULL                -- "agent" | "console:<user>"
);

-- V7__receipts.sql — port of the NDJSON hash-chained receipts log
CREATE TABLE receipts (
  seq         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ts          timestamptz NOT NULL,
  payload     jsonb NOT NULL,              -- the receipt document, unchanged shape
  prev_hash   text NOT NULL,               -- self_hash of seq-1 ('genesis' for seq 1)
  self_hash   text NOT NULL,               -- sha256 over canonical(payload) + prev_hash
  sig         text NOT NULL                -- Ed25519 over self_hash (signing key per instance)
);
```

- `receipts` is append-only (no UPDATE/DELETE grants for the app role). Chain verification (`hebe memory show receipts --verify`) walks `seq` order — same algorithm as the file log; the signing key moves from `secrets.db` to the instance's K8s Secret.
- `workspace_files` is the PG implementation of the new `WorkspaceStore` seam; the local profile keeps the filesystem implementation.

### 4.4 Provisioning a new instance (runbook contract, v1)

1. `CREATE SCHEMA hebe_<id>;` + dedicated role with usage limited to that schema (no UPDATE/DELETE on `receipts`).
2. `flyway -schemas=hebe_<id> migrate` (shared PG migration set).
3. Create K8s Secret `hebe-<id>`: PG creds, Keycloak client credentials + bound user, llm-gateway key, Telegram bot token, receipts signing key.
4. Apply Kustomize overlay instantiated with `<id>` (pod, service, config).
5. Register/verify manifest in capabilities-mcp (`agent_id: hebe-<id>`, `non_routable: true`).

Automation of this runbook is open question O-2 in [`architecture.md`](./architecture.md) §10.

## 5. Config schema — axes + presets in `config.toml`

The four profiles are **named presets over orthogonal axes** (architecture §2). `profile` resolves a bundle of axis defaults at boot; **every axis remains individually overridable**, so the file below shows each axis with its resolved default per profile in a comment. Subsystems read axes, never the profile name. `HEBE_PROFILE` env var wins over file; an explicit axis key wins over the profile default.

### 5.1 Profile → axis default matrix

| Axis key | `local` | `personal` | `server` | `k8s` |
|---|---|---|---|---|
| `storage.backend` | `sqlite` | `sqlite` | `postgres` | `postgres` |
| `fs.durability` | `persistent` | `persistent` | `persistent` | `ephemeral` |
| `workspace.backend` | `files` | `files` | `files` | `postgres` |
| `receipts.backend` | `file` | `file` | `file` | `postgres` |
| `platform.reach` | `none` | `remote` | `remote` | `in_cluster` |
| `platform.availability` | — | `intermittent` | `always` | `always` |
| `llm.source` | `byok` | `gateway_with_byok_fallback` | `gateway` | `gateway` |
| `security.platform_identity` | `none` | `keycloak` | `keycloak` | `keycloak` |
| `security.console_auth` | `password` | `password` | `oidc` | `oidc` |
| `security.secrets_backend` | `keychain` | `keychain` | `file`/`keychain` | `k8s` |
| `otel.enabled` | `false` | `false` | `true` | `true` |
| `capabilities.enabled` | `false` | `optional` | `true` | `true` |
| `tools.posture` | `full` | `full` | `full`/`restricted` | `restricted` |

### 5.2 Annotated `config.toml`

```toml
profile = "personal"               # "local" | "personal" | "server" | "k8s" — resolves the defaults below
instance_id = "bora"               # required for postgres backends; defaults to "local"

[storage]
backend = "sqlite"                 # axis storage.backend — see matrix
sqlite_path = "~/.hebe/hebe.db"    # sqlite backends
pg_url_ref = "secret:pg"           # postgres backends; schema derived as hebe_<instance_id>
                                   #   server: EXTERNAL pg (TLS, pooled, creds from file/keychain)
                                   #   k8s:    in-cluster pg, creds from k8s secret

[fs]
durability = "persistent"          # axis fs.durability — "persistent" | "ephemeral"
                                   #   "ephemeral" (k8s) FORCES workspace+receipts into postgres

[workspace]
backend = "files"                  # axis workspace.backend; must be "postgres" when fs.durability="ephemeral"
path = "~/.hebe/workspace"

[receipts]
backend = "file"                   # axis receipts.backend; must be "postgres" when fs.durability="ephemeral"
                                   #   file = NDJSON hash-chained log (standalone §13)

[platform]
reach = "remote"                   # axis platform.reach — "none" | "remote" | "in_cluster"
availability = "intermittent"      # axis platform.availability — "always" | "intermittent"
                                   #   "intermittent" enables outbox + missed-trigger catch-up + circuit-breaker (arch §7.1)

[platform.catchup]                 # only meaningful when availability="intermittent"
default_policy = "run_once_on_wake"  # per-routine override: "run_once_on_wake" | "run_all_missed" | "skip"
coalesce = true                    # collapse a backlog of identical owed fires after a long sleep

[llm]
source = "gateway_with_byok_fallback"  # axis llm.source — "byok" | "gateway" | "gateway_with_byok_fallback"
base_url = "https://llm-gateway.kantheon.example.com/v1"   # gateway sources
api_key_ref = "secret:llm-gateway-key"                     # local/personal: "keychain:llm"
default_model = "..."
# cost attribution headers on gateway calls (PD-11; degrade gracefully if ignored):
#   X-Cost-Center: "hebe/<instance_id>", X-Turn-Ref: <turn/job id>
[llm.byok_fallback]                # only when source = "gateway_with_byok_fallback" (personal)
base_url = "http://localhost:11434/v1"
api_key_ref = "keychain:llm_fallback"
default_model = "..."              # used by HEBE'S OWN routines when the gateway breaker is open;
                                   # constellation (kantheon_question) turns never fall back — they defer

[security]
console_auth = "password"          # axis security.console_auth — "password" | "oidc"
platform_identity = "keycloak"     # axis security.platform_identity — "none" | "keycloak"
                                   #   required for ANY platform.reach != "none" (personal/server/k8s)
keycloak_url = "https://keycloak.example.com"
keycloak_realm = "kantheon"
bound_user = "bora"                # Keycloak user this instance acts for (OBO)
# personal/server obtain OBO via device-code + cached refresh token; k8s via client-credentials → OBO
secrets_backend = "keychain"       # axis security.secrets_backend — "keychain" | "file" | "k8s"

[tools]
posture = "full"                   # axis tools.posture — "full" | "restricted"
# restricted = shell/kubectl/git/filesystem off; memory/http/web-search/scheduling/kantheon on
enable = []                        # per-instance opt-ins, e.g. ["git"]
disable = []

[otel]
enabled = false                    # axis otel.enabled
otlp_endpoint = ""                 # personal may set a local endpoint without changing posture

[capabilities]
enabled = false                    # axis capabilities.enabled ("optional" on personal = enabled, warn-and-continue)
url = ""                           # capabilities-mcp endpoint
heartbeat_seconds = 60

[kantheon]
iris_bff_url = ""                  # remote: public ingress URL; in_cluster: svc URL; none: empty

[channels.telegram]
bot_token_ref = "keychain:telegram"  # k8s: "secret:telegram"
chat_user_map = { }                  # platform profiles: chat_id -> keycloak user (must include bound_user)
```

### 5.3 `hebe doctor` validation per resolved axes

- `platform.reach = none` (`local`): LLM endpoint, keychain, SQLite writable, workspace dir writable.
- `platform.availability = intermittent` (`personal`): the above + gateway/Keycloak/iris-bff **probed, not required** — unreachable ⇒ *degraded*, not *failed*; byok-fallback model reachable; outbox/queue writable.
- `platform.availability = always` (`server`/`k8s`): PG + schema reachable, Keycloak token mint, llm-gateway, capabilities-mcp, iris-bff health, Telegram webhook — all **required**.
- `fs.durability = ephemeral`: assert `workspace.backend = postgres` **and** `receipts.backend = postgres` (fail fast on a persistent-FS misconfig that would lose state in a pod).

## 6. Cross-arc dependencies recorded elsewhere

| Item | Where it must land |
|---|---|
| `non_routable` field 16 on `AgentCapability` | done 2026-06-12 — `capabilities.proto` + `themis/contracts.md` §1.1 |
| Themis routing layers skip `non_routable` entries | Themis Phase 3 Stage 3.3 (routing-view derivation; regression test in this arc's Stage 3.4) |
| `TurnOrigin` + `origin_ref` on `ChatTurnRequest`; "scheduled" badge in inbox | **landed 2026-06-12 (cohesion review)** — `iris/contracts.md` §1.2 (`ChatTurnRequest.origin/origin_ref`) + `iris_turns.origin/origin_ref` columns |
| Machine-client OBO acceptance at iris-bff | Iris arc (no new contract — standard bearer validation) |
| PD-2 investigation inbox shows scheduled sessions | Iris arc / PD-2 resolution |
| llm-gateway cost-attribution headers | ai-platform llm-gateway (PD-11; degrade gracefully if absent) |

---

*Doc owner: Bora. Wire policy applies: protobuf is the source of truth even where the wire is REST; hand-rolled JSON that bypasses the proto is not permitted.*
