# Charon — Wire Contracts (kantheon arc, Phases 1–3)

> **Companions.** [`architecture.md`](./architecture.md), [`../../implementation/v1/charon/plan.md`](../../implementation/v1/charon/plan.md).
>
> **Authority.** Source of truth for `org.tatrman.charon.v1`, the `charon-mcp` tool surface, the connection-registry schema, conventions (buckets, keys, type mapping), and configuration. [`../pythia/contracts.md`](../pythia/contracts.md) §6 defers to this document; Pythia's `Handle` kinds map 1:1 onto `Location` kinds (§7 below).

## 1. Proto package `org.tatrman.charon.v1`

File: `shared/proto/src/main/proto/org/tatrman/charon/v1/charon.proto`. Package root deliberately `org.tatrman.charon` (migrated-service convention — not `org.tatrman.kantheon.*`).

```proto
syntax = "proto3";
package org.tatrman.charon.v1;

import "org/tatrman/kantheon/common/v1/response_message.proto";  // kantheon Rule-6 stand-in (kantheon-architecture §4, D1 2026-06-12)

service CharonService {
  rpc Materialize (MaterializeRequest) returns (MoveResult);
  rpc Stage       (StageRequest)       returns (MoveResult);
  rpc Copy        (CopyRequest)        returns (MoveResult);
  rpc Evict       (EvictRequest)       returns (EvictResult);
  rpc Describe    (DescribeRequest)    returns (DescribeResult);
}

// =========================================================================
// Locations
// =========================================================================
message Location {
  oneof kind {
    SeaweedBlob seaweed       = 1;
    RedisEntry redis          = 2;
    WorkerSessionDf worker_df = 3;
    DbTable db_table          = 4;
  }
}

message SeaweedBlob {
  string bucket = 1;
  string key    = 2;
  optional string retention_tag = 3;       // "production" | "shallow" — drives lifecycle rules
}

message RedisEntry {
  string key = 1;
  optional int64 ttl_seconds = 2;          // default from config; 0 = no expiry (discouraged)
}

message WorkerSessionDf {
  WorkerKind worker_kind = 1;
  string session_id = 2;
  string df_name    = 3;                   // matches workers/steropes keying (session_id, df_name)
}

enum WorkerKind { WORKER_KIND_UNSPECIFIED = 0; POLARS = 1; METIS = 2; }

message DbTable {
  string connection_id = 1;                // resolved in Charon's connection registry; never credentials
  string schema = 2;
  string table  = 3;
}

// =========================================================================
// Requests
// =========================================================================
message MoveOptions {
  optional string expected_schema_fingerprint = 1;  // SHA-256 of canonical Arrow IPC schema bytes;
                                                    //   verified when set; always computed + returned
  optional DbWriteMode db_write_mode = 2;           // required when target is DbTable
  optional int64 max_bytes = 3;                     // per-move cap override (≤ server cap)
  optional int32 chunk_rows = 4;                    // streaming chunk override
}

enum DbWriteMode { DB_WRITE_MODE_UNSPECIFIED = 0; CREATE = 1; REPLACE = 2; APPEND = 3; }

message MaterializeRequest { Location source = 1; Location target = 2; MoveOptions options = 3; }
  // target MUST be seaweed | redis | db_table
message StageRequest       { Location source = 1; WorkerSessionDf target = 2; MoveOptions options = 3; }
message CopyRequest        { Location source = 1; Location target = 2; MoveOptions options = 3; }
  // any legal pair (legality matrix §2)
message EvictRequest       { Location location = 1; }
  // db_table NOT allowed (DB cleanup is the owner's job) → INVALID_ARGUMENT
message DescribeRequest    { Location location = 1; }

// =========================================================================
// Results
// =========================================================================
message MoveResult {
  Location target = 1;                     // resolved (e.g. generated key)
  string schema_fingerprint = 2;
  string schema_json = 3;                  // Arrow schema, JSON rendering
  int64 row_count = 4;
  int64 size_bytes = 5;                    // bytes written (Arrow IPC for blob tiers; n/a→0 for DB)
  int64 duration_ms = 6;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}

message EvictResult {
  bool existed = 1;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}

message DescribeResult {
  bool exists = 1;
  string schema_fingerprint = 2;
  string schema_json = 3;
  int64 row_count = 4;                     // -1 when unknown without a full scan
  bool row_count_exact = 5;
  int64 size_bytes = 6;                    // -1 when not applicable
  optional string expires_at = 7;          // Redis TTL / worker session TTL / S3 lifecycle, when known
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}
```

**PD-5 note (2026-06-12):** `Describe` (`exists` + `schema_fingerprint`) is Pythia's resume-time liveness probe — Pythia never holds leases on Charon-staged data; dead handles re-materialize lazily from checkpointed move specs, with fingerprint drift surfaced as a warning + `LooseEnd` (see `pythia/contracts.md` §3a).

**Error model.** gRPC status codes: `INVALID_ARGUMENT` (illegal pair, missing db_write_mode, allow-list violation), `NOT_FOUND` (source absent), `FAILED_PRECONDITION` (CREATE on existing table; fingerprint mismatch), `RESOURCE_EXHAUSTED` (byte cap), `DEADLINE_EXCEEDED`, `UNAVAILABLE` (endpoint down). Non-fatal detail always in `messages = 99` (Rule 6). **No partial writes on any failure** (architecture §5 invariants).

## 2. Legality matrix

| source ↓ / target → | seaweed | redis | worker_df | db_table |
|---|---|---|---|---|
| **seaweed** | Copy | Materialize/Copy | Stage | Materialize/Copy (write-allowed conn) |
| **redis** | Materialize/Copy | Copy | Stage | Materialize/Copy |
| **worker_df** | Materialize/Copy | Materialize/Copy | Stage (cross-session/engine) | Materialize/Copy |
| **db_table** (read-allowed conn) | Materialize/Copy | Materialize/Copy | Stage | Copy (cross-connection) |

Same-location no-op → `MoveResult` with `messages` note, no I/O.

## 3. charon-mcp tool surface (thin wrapper)

MCP tools, streamable-HTTP at `POST /mcp`; JSON mirrors of the proto messages (camelCase, Rule 7 spirit — locations as structured JSON, not stringified):

| Tool | → gRPC |
|---|---|
| `move.materialize` | `Materialize` |
| `move.stage` | `Stage` |
| `move.copy` | `Copy` |
| `move.evict` | `Evict` |
| `move.describe` | `Describe` |

Zero logic: validate JSON → proto, one call, proto → JSON (incl. `messages`). The wrapper also owns the five `ToolCapability` manifests (`manifests/move.*.yaml`) and the capabilities-mcp heartbeat (`capabilities-client`). `cost_hints` filled from the Phase 3 benchmark.

## 4. Connection registry schema

`/etc/charon/connections.yaml` (ConfigMap + sealed-secret env substitution):

```yaml
connections:
  - id: erp-replica                # the connection_id used in DbTable
    kind: mssql                    # postgres | mssql
    jdbc_url: ${ERP_REPLICA_URL}   # secrets via env substitution; never inline
    username: ${ERP_REPLICA_USER}
    password: ${ERP_REPLICA_PASSWORD}
    allow:
      read: true
      write: false
      schemas: ["dbo"]             # glob-free exact list at v1
    pool: { max: 4 }
  - id: analytics-staging
    kind: postgres
    jdbc_url: ${ANALYTICS_PG_URL}
    username: ${ANALYTICS_PG_USER}
    password: ${ANALYTICS_PG_PASSWORD}
    allow: { read: true, write: true, schemas: ["staging"] }
```

Rules: unknown `connection_id` → `INVALID_ARGUMENT`; write to `allow.write: false` or schema outside the list → `INVALID_ARGUMENT` (never attempted); registry reload via `POST /refresh` (cluster-internal). **Pythia's internal Postgres is never listed.** Which connections exist at v1 is Bora-owned content (plan §8).

## 5. DB type mapping (Arrow → DDL, deterministic)

| Arrow type | Postgres | MSSQL |
|---|---|---|
| Int8/16/32/64 | SMALLINT/SMALLINT/INTEGER/BIGINT | SMALLINT/SMALLINT/INT/BIGINT |
| Float32/64 | REAL/DOUBLE PRECISION | REAL/FLOAT |
| Decimal128(p,s) | NUMERIC(p,s) | DECIMAL(p,s) |
| Utf8/LargeUtf8 | TEXT | NVARCHAR(MAX) |
| Bool | BOOLEAN | BIT |
| Date32 | DATE | DATE |
| Timestamp(µs, tz?) | TIMESTAMPTZ / TIMESTAMP | DATETIMEOFFSET / DATETIME2 |
| Binary | BYTEA | VARBINARY(MAX) |
| List/Struct/Map | **unsupported at v1 → FAILED_PRECONDITION** | same |

No silent coercion; unmappable column names the column in the error. Reads map inversely; driver-specific types outside the table fail with a named column.

## 6. Conventions

- **Seaweed:** endpoint `data-seaweedfs:8333` (S3); Pythia evidence in bucket `pythia-evidence`, keys `{investigation_id}/{handle_id}.arrow`; lifecycle by `retention_tag` (production = 90 d, shallow = 7 d — resolved Pythia Q5). Other consumers bring their own bucket conventions.
- **Fingerprint:** a deterministic SHA-256 schema identity, **identical across Charon (Kotlin) and `workers/polars`/Steropes (Python)** so a schema staged by one engine verifies against the other. **Algorithm (decided 2026-06-15, Bora — review-006 R3):** a **canonical, implementation-independent** digest computed from the *logical* schema, **not** raw Arrow IPC bytes (those are not byte-stable across Arrow implementations — Arrow Java 18.3.0 ≠ pyarrow 18.0.0 for the same schema, and pyarrow is not self-consistent across a round-trip). Canonical form: top-level fields joined by `\n` in declaration order; each field rendered as `name|type|nullability[<child;child;…>]` where `nullability ∈ {null, nonnull}` and `<…>` holds recursively-encoded child fields (struct/list/map); `type` spells out every parameter (int width+sign, float bits, `decimal{128|256}_{precision}_{scale}`, `timestamp_{s|ms|us|ns}_{tz}`, `date_{day|ms}`, …) using **shared unit tokens** rather than either library's native enum names; field/schema **metadata is excluded**; SHA-256 of the UTF-8 bytes, lowercase hex. Kotlin impl: `core/Integrity.kt` (Charon) + `ArrowIpcSerializer.fingerprintFor` (Brontes, the MSSQL worker — migrated off raw IPC bytes to this canonical form in fork Stage 3.4); Python impl: `workers/steropes/.../fingerprint.py` (Steropes). **Shared cross-engine pin (fork Stage 3.4 T2):** `shared/testdata/fingerprints/` holds reference Arrow IPC fixtures + `fingerprints.json`; Charon's `IntegritySpec`, Brontes's `SchemaFingerprintCrossEngineSpec`, and Steropes's `test_fingerprint.py` all recompute against it and must agree (the `reference.arrow` digest `69779ea6…` is the anchor all three match). **Nested types:** list value field → `item`; **map → the entries-wrapped struct `{key, value}`** (the form Arrow Java exposes; the Python side synthesizes it from pyarrow's key_field/item_field so the bytes-identical cross-check holds). *Stream B follow-up — RESOLVED (Charon P1 S1.4 closeout, 2026-06-26):* Charon's private `fixtures/integrity/` copy (whose `regenerate.py` used the flat `[key_field, item_field]` map form) is **deleted**; `IntegritySpec` now recomputes directly against the shared `shared/testdata/fingerprints/` anchor incl. `map.arrow`, exercising the entries-wrapped form in Kotlin. The shared `generate.py` is the single Python reference. A CI regen+diff guard (`.github/workflows/ci.yml`) locks the fixtures against algorithm drift.
- **Chunking:** default `chunk_rows` aligned with worker `ResultBatch` conventions (config `charon.move.chunk-rows`, default 65536).
- **Worker paths (revised at Charon Stage 3.1 closeout, 2026-06-26).** The original assumption — stage-in via `WorkerService.Execute(plan, assign_to_workspace=df_name)` — was wrong: `Execute` only stashes the *result of a plan*, and no plan node carries external Arrow bytes, so it can't ingest a pre-computed DataFrame. **Stage-in is now a dedicated client-streaming RPC** on both engines: `worker.v1 WorkerService.ImportDataFrame(stream ImportChunk) → ImportDataFrameResult` (POLARS/Steropes) and `metis.v1 MetisService.ImportDataFrame(stream ArrowChunk) → ImportResult` (METIS); the first chunk carries `ImportHeader{session_id, df_name, expected_schema_fingerprint?}`, each `ipc_payload` is a self-contained Arrow IPC stream. **Read-out** is `Execute` over a `WorkspaceRef` plan node (POLARS) / `ExportDataFrame` (METIS). **Evict** is `DropWorkspaceEntry` on both. Charon's `WorkerEndpoint` keys exactly `(session_id, df_name)`.

## 7. Pythia Handle ↔ Charon Location mapping

| pythia/v1 `Handle` kind | charon/v1 `Location` kind |
|---|---|
| `SeaweedArrowBlob` | `SeaweedBlob` |
| `RedisArrowEntry` | `RedisEntry` |
| `WorkerSessionDF` | `WorkerSessionDf` |
| `DbTableRef` | `DbTable` |
| `LiveQueryRef`, `PgResultSnapshot` | **no mapping** — Pythia-internal; Pythia executes/inlines itself |

## 8. Configuration (application.conf keys)

```
charon.grpc.port                  (7251)
charon.http.port                  (7250)         # probes + /refresh
charon.s3.{endpoint, region, access-key, secret-key}
charon.redis.{url, default-ttl-s, max-value-bytes}
charon.connections.path           (/etc/charon/connections.yaml)
charon.worker.steropes.{host, port}   # in-repo Polars worker, default steropes:7301
charon.move.{chunk-rows=65536, max-bytes-default, deadline-default-ms=120000}
charon-mcp.{port=7252, charon-grpc.{host,port}}
```

## 9. Build & version contracts

Tags: `charon/v0.1.0` (Phase 1 — object-store mover), `charon/v0.2.0` (Phase 2 — DB edges), `charon/v0.3.0` + `charon-mcp/v0.1.0` (Phase 3 — worker + wrapper + registration; **Pythia Phase 4 pre-flight**). Branches `feat/charon-p<n>-s<n.m>-<short>`. Proto consumed in-repo; published with kantheon's proto artifact if/when ai-platform-side consumers appear.

---

*Contracts owner: Bora. Locked structure 2026-06-12 (Charon arc planning). Field-level changes update this doc first.*
