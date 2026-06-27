# Metis — Wire Contracts (kantheon arc, Phases 1–3)

> **Companions.** [`architecture.md`](./architecture.md), [`../../implementation/v1/metis/plan.md`](../../implementation/v1/metis/plan.md).
>
> **Authority.** Source of truth for `org.tatrman.metis.v1`, the `metis-mcp` tool surface, workspace semantics, the numerical-fidelity contract, and configuration. [`../pythia/contracts.md`](../pythia/contracts.md) §7 defers here; [`../charon/contracts.md`](../charon/contracts.md) `WorkerKind.METIS` staging lands on the workspace RPCs in §1.3.

## 1. Proto package `org.tatrman.metis.v1`

File: `shared/proto/src/main/proto/org/tatrman/metis/v1/metis.proto`. Package root `org.tatrman.metis` (migrated-service convention).

### 1.1 Service

```proto
syntax = "proto3";
package org.tatrman.metis.v1;

import "org/tatrman/kantheon/common/v1/response_message.proto";  // kantheon Rule-6 stand-in (kantheon-architecture §4, D1 2026-06-12)

service MetisService {
  // Estimation lifecycle
  rpc Fit              (FitRequest)              returns (FitResult);
  rpc Diagnose         (DiagnoseRequest)         returns (DiagnoseResult);
  rpc Project          (ProjectRequest)          returns (ProjectResult);
  rpc SimulateScenario (SimulateScenarioRequest) returns (ProjectResult);

  // Workspace surface (Charon staging + Pythia housekeeping)
  rpc ImportDataFrame  (stream ArrowChunk)       returns (ImportResult);
  rpc ExportDataFrame  (ExportRequest)           returns (stream ArrowChunk);
  rpc DropWorkspaceEntry (DropRequest)           returns (DropResult);
  rpc GetStatus        (GetStatusRequest)        returns (GetStatusResponse);
}
```

### 1.2 Estimation messages

```proto
enum ModelKind { MODEL_KIND_UNSPECIFIED = 0; LINEAR = 1; ARIMA = 2; PROPHET = 3; }

message FitRequest {
  string session_id = 1;
  ModelKind model_kind = 2;
  oneof input {
    string input_df = 3;                       // workspace DF name (Charon-staged) — the normal path
    bytes inline_arrow_ipc = 4;                // small series convenience; ≤ metis.inline-max-bytes
  }
  string model_name = 5;                       // workspace key for the fitted model
  // Per-kind params (set the one matching model_kind):
  optional ArimaParams arima = 6;
  optional ProphetParams prophet = 7;
  optional LinearParams linear = 8;
}

message ArimaParams {
  optional int32 seasonality = 1;              // e.g. 12; absent → auto-detect candidate set
  optional string order = 2;                   // "(p,d,q)(P,D,Q,s)" explicit; absent → bounded auto-order
  optional int32 max_order = 3;                // auto-search bound override
}
message ProphetParams {
  optional bool yearly = 1; optional bool weekly = 2; optional bool daily = 3;
  // regressors: v1.x (design lists them; deferred until a consumer needs them)
}
message LinearParams { repeated string x_cols = 1; string y_col = 2; }

message FitResult {
  string model_name = 1;
  ModelKind model_kind = 2;
  string chosen_order = 3;                     // ARIMA: the selected order (auto or explicit); else ""
  double aic = 4;                              // NaN-encoded as omitted where N/A
  double log_likelihood = 5;
  int64 input_rows = 6;
  int64 fit_duration_ms = 7;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}

message DiagnoseRequest { string session_id = 1; string model_name = 2; }
message DiagnoseResult {
  bool pass = 1;                               // conjunction of individual checks at default α
  repeated DiagnosticCheck checks = 2;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}
message DiagnosticCheck {
  string name = 1;                             // "ljung_box" | "adf" | "residual_normality" | ...
  bool pass = 2;
  double statistic = 3;
  double p_value = 4;
  string detail = 5;
}

message ProjectRequest {
  string session_id = 1;
  string model_name = 2;
  string horizon = 3;                          // ISO date ("2026-12-31") or "+N" periods
  optional double confidence_level = 4;        // default 0.90
  string output_df = 5;                        // workspace DF name for the forecast frame
}

message SimulateScenarioRequest {
  string session_id = 1;
  string forecast_df = 2;                      // a Project output in the same session
  string deltas_json = 3;                      // Rule 7; multiplicative-then-additive over yhat/lower/upper: {"scaleFactor":1.2,"yhatDelta":1000.0} (unknown keys ignored w/ warning)
  string output_df = 4;
}

message ProjectResult {
  string output_df = 1;                        // forecast frame columns: ds, yhat, yhat_lower, yhat_upper, kind(actual|forecast)
  string schema_fingerprint = 2;
  int64 rows = 3;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}
```

### 1.3 Workspace messages

```proto
message ArrowChunk {
  // First chunk carries header; subsequent chunks payload only (worker ResultBatch convention).
  optional ImportHeader header = 1;
  bytes ipc_payload = 2;
}
message ImportHeader { string session_id = 1; string df_name = 2; optional string expected_schema_fingerprint = 3; }
message ImportResult { string df_name = 1; string schema_fingerprint = 2; int64 rows = 3;
                       repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }
message ExportRequest { string session_id = 1; string df_name = 2; optional int32 chunk_rows = 3; }
message DropRequest   { string session_id = 1; string name = 2; }   // DF or model
message DropResult    { bool existed = 1; repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }

message GetStatusRequest {}
message GetStatusResponse {
  int32 sessions = 1; int32 dataframes = 2; int32 models = 3; int64 workspace_bytes = 4;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}
```

**Workspace semantics.** One store keyed `(session_id, name)` holding **DataFrames and fitted models** in a single namespace (name collision → `ALREADY_EXISTS`); idle-TTL (default 60 min, matching worker/Charon session conventions) + caps (`max_dfs_per_session`, `max_models_per_session`, `max_bytes_total`) → `RESOURCE_EXHAUSTED`. Schema fingerprint = SHA-256 over the **canonical logical-schema string** (platform-wide cross-engine algorithm; **not** raw Arrow IPC schema bytes — review-006 R3, 2026-06-15, established IPC bytes are not byte-stable across Arrow implementations nor self-consistent across a pyarrow re-serialise). Byte-identical to Charon `Integrity.canonicalSchemaString` / `Integrity.fingerprint` (`services/charon/.../core/Integrity.kt`) and the Steropes worker; the reference impl is `charon/.../fixtures/integrity/regenerate.py`. Metis's `metis.arrow_io.schema_fingerprint` cross-checks against the shared fixture set `shared/testdata/fingerprints/` in `services/metis/tests/test_fingerprint_cross_engine.py` — parametrized over every fixture (incl. **list and map**, the nested-type cases: a map is the entries-wrapped `{key,value}` struct, the form Arrow Java's `Field.children` exposes, and `is_map` is checked before `is_list` since `MapType` subclasses `ListType`).

**Error model.** `INVALID_ARGUMENT` (bad params, wrong model_kind/params pairing), `NOT_FOUND` (session/DF/model — Pythia treats a NOT_FOUND model as re-fittable), `ALREADY_EXISTS`, `RESOURCE_EXHAUSTED` (caps), `DEADLINE_EXCEEDED` (per-kind fit timeouts), `FAILED_PRECONDITION` (e.g. Project horizon before series end; SimulateScenario on a non-forecast frame).

## 2. metis-mcp tool surface (thin Kotlin wrapper)

**Single-tool surface (locked 2026-06-15, Bora).** One tool per RPC, with the model kind carried as a `modelKind` argument rather than split into per-kind tools. This was chosen over the original per-kind split (`model.fit.arima` / `…prophet` / `…linear`) for LLM ergonomics — one tool to discover and call, kind picked by argument — and reconciles with the Pythia design vocabulary in §4. Capability ids are `metis.<tool>:v1` (the `<service>.<tool>:v1` repo convention, as `theseus.query:v1`); `category: "metis"`.

| Tool (capability id) | → gRPC | Args (camelCase) |
|---|---|---|
| `model.fit` (`metis.model.fit:v1`) | `Fit` | `sessionId`, `modelKind` (`LINEAR`\|`ARIMA`\|`PROPHET`), `modelName`, `inputDf` \| `inlineArrowIpc` (base64); per-kind: `xCols`/`yCol` (LINEAR), `arimaSeasonality`/`arimaOrder`/`arimaMaxOrder` (ARIMA) |
| `model.diagnose` (`metis.model.diagnose:v1`) | `Diagnose` | `sessionId`, `modelName` (LINEAR/ARIMA; Prophet residuals → `UNIMPLEMENTED` at v1) |
| `model.project` (`metis.model.project:v1`) | `Project` | `sessionId`, `modelName`, `horizon` (`"+N"` or ISO date), `outputDf`, `confidenceLevel?` (default 0.90) |
| `model.simulate` (`metis.model.simulate:v1`) | `SimulateScenario` | `sessionId`, `forecastDf`, `deltasJson`, `outputDf` |
| `data.import` (`metis.data.import:v1`) | `ImportDataFrame` | `sessionId`, `dfName`, `inlineArrowIpc` (base64; single-chunk over MCP) |
| `data.export` (`metis.data.export:v1`) | `ExportDataFrame` | `sessionId`, `dfName` → base64 Arrow IPC |
| `data.drop` (`metis.data.drop:v1`) | `DropWorkspaceEntry` | `sessionId`, `name` (DF or model) |

JSON mirrors of the proto (camelCase). **Workspace tools are exposed over MCP** (revised 2026-06-15, Bora — they were built and tested, and a direct staging/retrieval/cleanup surface is useful for ad-hoc and agent-driven use). Charon and Pythia still call the gRPC surface directly (streaming import/export, no base64 round-trip); the MCP `data.*` tools are the convenience edge, not their path. The wrapper owns the seven `ToolCapability` manifests + heartbeat; `cost_hints` filled from the Phase 3 benchmark (fit duration percentiles per kind on reference sizes).

## 3. Numerical-fidelity contract

Golden fixtures pin: ARIMA chosen order + AIC (`rtol 1e-6`), forecast points + CI bands (`rtol 1e-4`), prophet yhat at horizon marks (`rtol 1e-3` — Stan sampling variance bounded by fixed seed), OLS coefficients (`rtol 1e-8`). Fixed seeds everywhere (`metis.seed` config; recorded in `FitResult.messages`). Goldens regenerate only in dedicated PRs with library-upgrade rationale. This contract is what makes `replay()`/`reproduce()` meaningful for forecast investigations.

## 4. Pythia ModelNode mapping

The single-tool surface (§2) means the model kind travels as an **argument**, not as part of the capability id. The Pythia design's per-kind vocabulary (`model.fit.arima`, `model.project.prophet`, `model.simulate.scenario` — `Pythia-v1-Design.md` §6.2) maps onto one bound capability each, with `modelKind`/scenario carried in the node's args:

| pythia/v1 `ModelNode` (design vocabulary) | Bound capability + Metis call |
|---|---|
| `model.fit.<kind>(input=Handle)` | `metis.model.fit:v1` — Charon stages handle → session DF; `Fit(input_df, model_name = node_id, model_kind = <kind>)` |
| `model.project.<kind>(model=Handle, horizon)` | `metis.model.project:v1` — `Project(model_name, horizon, confidence_level)` → output DF → Handle (`WorkerSessionDF` kind, worker_kind METIS) |
| `model.simulate.scenario(forecast=Handle, deltas)` | `metis.model.simulate:v1` — `SimulateScenario(forecast_df, deltas_json)` |
| diagnostics step (design §4.3 N3) | `metis.model.diagnose:v1` — `Diagnose(model_name)`; LLM interprets `DiagnoseResult` in a ReasoningNode — data deterministic, prose not |

`session_id` = investigation-derived (sticky affinity). Forecast evidence: Charon `Materialize(worker_df → pythia-evidence)` at finalisation per Pythia policies.

## 5. Configuration

```
metis.grpc.port                   (7261)
metis.http.port                   (7260)
metis.workspace.{idle-ttl-s=3600, max-dfs-per-session=50, max-models-per-session=20, max-bytes-total}
   # PD-5 (2026-06-12): TTL stays simple — Pythia never extends it. GetStatus is Pythia's
   # resume-time liveness probe; expired models/DFs re-fit/re-stage lazily from checkpointed
   # specs (see pythia/contracts.md §3a).
metis.inline-max-bytes            (4194304)
metis.fit.timeouts.{linear-ms=10000, arima-ms=120000, prophet-ms=300000}
metis.fit.arima.{max-order=5, seasonality-candidates=[4,7,12,52]}
metis.seed                        (42)
metis-mcp.{port=7262, metis-grpc.{host,port}}
```

## 6. Build & version contracts

Tags: `metis/v0.1.0` (Phase 1 — workspace + skeleton), `metis/v0.2.0` (Phase 2 — models + goldens), `metis/v0.3.0` + `metis-mcp/v0.1.0` (Phase 3 — wiring + registration; **gates Pythia Phase 4 Stage 4.2**). Branches `feat/metis-p<n>-s<n.m>-<short>`. Python module versioned in `pyproject.toml`, tag-driven like the rest of the repo; `metis-mcp` follows kantheon Kotlin conventions.

---

*Contracts owner: Bora. Locked structure 2026-06-12 (Metis arc planning). Numerical goldens are part of the contract (§3).*
