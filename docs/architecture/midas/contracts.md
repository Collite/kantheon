# Midas — Wire Contracts (kantheon arc, Phases 1–3)

> **Scope.** All cross-service contracts produced or extended by Phases 1, 2, and 3 of the Midas arc: protobuf packages, REST endpoints, MCP tool surfaces, manifest YAML schemas, Flyway DB schema, and Iris-BFF dashboard storage extensions.
>
> **Authority.** This document is the source of truth. Task lists implementing the work below must match these contracts byte-for-byte. Any deviation is a planning bug — fix the doc first, then the code.
>
> **Companions.** [`architecture.md`](./architecture.md), [`../../implementation/v1/midas/plan.md`](../../implementation/v1/midas/plan.md), [`midas-brief.md`](./midas-brief.md), [`../themis/contracts.md`](../themis/contracts.md) (reference: contract shape).

---

## Table of contents

1. [Proto packages](#1-proto-packages)
2. [Midas-core REST API](#2-midas-core-rest-api)
3. [Midas-core MCP tool surface](#3-midas-core-mcp-tool-surface)
4. [Loader REST API](#4-loader-rest-api)
5. [Sysifos-BFF API](#5-sysifos-bff-api)
6. [Database schema (Flyway)](#6-database-schema-flyway)
7. [Iris-BFF dashboard schema extensions](#7-iris-bff-dashboard-schema-extensions)
8. [Report-renderer API](#8-report-renderer-api)
9. [Manifest YAMLs](#9-manifest-yamls)
10. [Capabilities registration](#10-capabilities-registration)
11. [Audit log shape](#11-audit-log-shape)
12. [Error envelope](#12-error-envelope)

---

## 1. Proto packages

Three new packages under `org.tatrman.kantheon.*`. All import `org.tatrman.kantheon.common.v1.ResponseMessage` (Rule 6: every response carries `repeated ResponseMessage messages = 99;`).

### 1.1 `org.tatrman.kantheon.midas.v1` (new — Phase 1 Stage 1.2)

File: `shared/proto/src/main/proto/org/tatrman/kantheon/midas/v1/midas.proto`

```proto
syntax = "proto3";
package org.tatrman.kantheon.midas.v1;

import "google/protobuf/timestamp.proto";
import "org/tatrman/kantheon/common/v1/response_message.proto";  // kantheon Rule-6 stand-in (kantheon-architecture §4, D1 2026-06-12)

// =========================================================================
// Domain entities
// =========================================================================

message Money {
  string amount = 1;        // decimal string; persisted as NUMERIC(20,4) (Exposed `decimal`)
  string currency = 2;      // ISO 4217 (CHAR(3))
}

message Client {
  string client_id = 1;     // UUID
  string tenant_id = 2;     // UUID
  string name = 3;
  string contact_email = 4;
  string contact_phone = 5;
  ClientStatus status = 6;
  google.protobuf.Timestamp created_at = 7;
  google.protobuf.Timestamp updated_at = 8;
  string created_by_user_id = 9;
  string updated_by_user_id = 10;
}

enum ClientStatus { CLIENT_ACTIVE = 0; CLIENT_ARCHIVED = 1; }

message Portfolio {
  string portfolio_id = 1;
  string tenant_id = 2;
  string client_id = 3;
  string name = 4;
  string base_currency = 5;        // ISO 4217
  PortfolioType portfolio_type = 6;
  CostBasisMethod cost_basis_method = 7;   // FIFO only in v1
  google.protobuf.Timestamp inception_date = 8;
  PortfolioStatus status = 9;
  google.protobuf.Timestamp created_at = 10;
  google.protobuf.Timestamp updated_at = 11;
  bool track_cash = 12;            // S2: derive the cash counter-leg of each trade (default true)
}

enum PortfolioType { PORTFOLIO_BROKERAGE = 0; PORTFOLIO_RETIREMENT = 1; PORTFOLIO_OTHER = 2; }
enum PortfolioStatus { PORTFOLIO_ACTIVE = 0; PORTFOLIO_ARCHIVED = 1; }
enum CostBasisMethod { COST_BASIS_FIFO = 0; }  // LIFO / AVG in v1.x

message Asset {
  string asset_id = 1;             // UUID
  string tenant_id = 2;            // NULL for global assets (rare in v1; defer)
  string symbol = 3;               // e.g. "AAPL"
  string isin = 4;                 // optional
  string name = 5;                 // "Apple Inc."
  AssetKind kind = 6;
  string exchange = 7;             // "NASDAQ"
  string currency = 8;             // trading currency
  AssetStatus status = 9;
  google.protobuf.Timestamp created_at = 10;
  google.protobuf.Timestamp updated_at = 11;
}

enum AssetKind {
  ASSET_STOCK = 0;
  ASSET_ETF = 1;
  ASSET_BOND = 2;
  ASSET_FUND = 3;
  ASSET_CASH = 4;
}
enum AssetStatus { ASSET_ACTIVE = 0; ASSET_DELISTED = 1; }

message Transaction {
  string transaction_id = 1;       // UUID
  string tenant_id = 2;
  string portfolio_id = 3;
  string asset_id = 4;
  TransactionKind kind = 5;
  google.protobuf.Timestamp trade_date = 6;
  google.protobuf.Timestamp settle_date = 7;
  string quantity = 8;             // decimal string
  Money price = 9;                 // per-unit price; in transaction currency
  Money fee = 10;
  Money tax = 11;
  Money total = 12;                // pre-computed: quantity * price + fee + tax (sign per kind)
  string currency = 13;             // CHAR(3); transaction currency
  string external_id = 14;          // idempotency key: "{broker}:{stmt}:{line}"
  string reverses_transaction_id = 15;  // FK to the entry being reversed (NULL for primary inserts)
  string note = 16;
  TransactionSource source = 17;
  google.protobuf.Timestamp recorded_at = 18;
  string recorded_by_user_id = 19;
  string correlation_id = 20;      // S2: ties a security leg to its derived cash leg (reversal cascades)
}

enum TransactionKind {
  TX_BUY = 0;
  TX_SELL = 1;
  TX_DIVIDEND = 2;
  TX_INTEREST = 3;
  TX_FEE = 4;
  TX_TAX = 5;
  TX_TRANSFER_IN = 6;
  TX_TRANSFER_OUT = 7;
  TX_ADJUSTMENT = 8;   // generated from balance entry; manual correction
  TX_CASH_CREDIT = 9;  // derived cash leg: cash in (sell proceeds, dividend, interest, transfer-in cash) — S2
  TX_CASH_DEBIT = 10;  // derived cash leg: cash out (buy cost, fee, tax, transfer-out cash) — S2
}

enum TransactionSource {
  TX_SRC_MANUAL = 0;
  TX_SRC_LOADER_EXCEL = 1;
  TX_SRC_LOADER_GOOGLE_FINANCE = 2;
  TX_SRC_LOADER_API = 3;
  TX_SRC_DERIVATION = 4;   // from balance-entry derivation
  TX_SRC_REVERSAL = 5;
}

message Position {
  string portfolio_id = 1;
  string asset_id = 2;
  string quantity = 3;
  Money avg_cost = 4;
  Money current_value = 5;
  Money unrealised_pnl = 6;
  Money unrealised_pnl_base = 7;   // in portfolio base currency
  google.protobuf.Timestamp as_of = 8;
}

message FxRate {
  string from_ccy = 1;
  string to_ccy = 2;
  google.protobuf.Timestamp rate_date = 3;
  string rate = 4;                 // decimal string
  string source = 5;               // "google-finance" / "manual" / ...
}

message PerformanceMetric {
  string portfolio_id = 1;
  google.protobuf.Timestamp period_start = 2;
  google.protobuf.Timestamp period_end = 3;
  string twr = 4;                  // decimal string (% as fraction; 0.123 = 12.3%)
  string mwr = 5;
  string total_return_amount = 6;  // in base ccy
  Money starting_value = 7;
  Money ending_value = 8;
  Money realised_pnl = 9;
  Money unrealised_pnl = 10;
}

// =========================================================================
// REST request/response wrappers
// =========================================================================

message CreateClientRequest  { Client client = 1; }
message UpdateClientRequest  { Client client = 1; }
message ClientResponse       { Client client = 1; repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }
message ListClientsResponse  { repeated Client clients = 1; PageInfo page_info = 2; repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }

message CreatePortfolioRequest  { Portfolio portfolio = 1; }
message UpdatePortfolioRequest  { Portfolio portfolio = 1; }
message PortfolioResponse       { Portfolio portfolio = 1; repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }
message ListPortfoliosResponse  { repeated Portfolio portfolios = 1; PageInfo page_info = 2; repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }

message CreateAssetRequest      { Asset asset = 1; }
message UpdateAssetRequest      { Asset asset = 1; }
message AssetResponse           { Asset asset = 1; repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }
message ListAssetsResponse      { repeated Asset assets = 1; PageInfo page_info = 2; repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }

message InsertTransactionRequest    { Transaction transaction = 1; }
message BatchInsertTransactionsRequest { repeated Transaction transactions = 1; bool skip_existing = 2; }
message BatchInsertTransactionsResponse {
  int32 inserted_count = 1;
  int32 skipped_count = 2;          // already-exists hits (idempotent)
  int32 failed_count = 3;
  repeated TransactionInsertError errors = 4;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}
message TransactionInsertError { Transaction transaction = 1; string reason = 2; string code = 3; }
message EditTransactionRequest    { string transaction_id = 1; Transaction new_transaction = 2; string reason = 3; }
message EditTransactionResponse   { Transaction reversal = 1; Transaction replacement = 2; repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }
message TransactionResponse        { Transaction transaction = 1; repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }
message ListTransactionsResponse   { repeated Transaction transactions = 1; PageInfo page_info = 2; repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }

message BalanceEntryRequest {
  string portfolio_id = 1;
  string asset_id = 2;
  string target_quantity = 3;
  google.protobuf.Timestamp as_of = 4;
  string reason = 5;
}
message BalanceEntryPreview {
  string portfolio_id = 1;
  string asset_id = 2;
  string current_quantity = 3;
  string target_quantity = 4;
  string diff_quantity = 5;
  Transaction proposed_transaction = 6;       // not yet committed
}
message BalanceEntryCommitResponse {
  Transaction committed_transaction = 1;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}

message ListPositionsResponse      { repeated Position positions = 1; repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }

message FxRateUpsertRequest        { FxRate fx_rate = 1; }
message FxRateResponse             { FxRate fx_rate = 1; repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }
message ListFxRatesResponse        { repeated FxRate fx_rates = 1; repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99; }

message PageInfo { int32 page = 1; int32 size = 2; int32 total = 3; }

// =========================================================================
// Reconciliation
// =========================================================================

message ReconcileRequest {
  string portfolio_id = 1;
  google.protobuf.Timestamp period_start = 2;
  google.protobuf.Timestamp period_end = 3;
  string loader_run_id = 4;             // optional: scope to a specific import
}
message ReconcileResponse {
  repeated ReconcileDiff diffs = 1;
  ReconcileSummary summary = 2;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}
message ReconcileDiff {
  ReconcileDiffKind kind = 1;
  Transaction system_transaction = 2;     // present for SYSTEM_ONLY / VALUE_MISMATCH
  Transaction statement_transaction = 3;  // present for STATEMENT_ONLY / VALUE_MISMATCH
  repeated FieldDelta deltas = 4;         // for VALUE_MISMATCH
  ReconcileStatus status = 5;             // EXPECTED / INVESTIGATE / RESOLVED
}
enum ReconcileDiffKind { RECON_SYSTEM_ONLY = 0; RECON_STATEMENT_ONLY = 1; RECON_VALUE_MISMATCH = 2; }
enum ReconcileStatus  { RECON_OPEN = 0; RECON_EXPECTED = 1; RECON_INVESTIGATE = 2; RECON_RESOLVED = 3; }
message FieldDelta { string field = 1; string system_value = 2; string statement_value = 3; }
message ReconcileSummary { int32 total_diffs = 1; int32 system_only = 2; int32 statement_only = 3; int32 value_mismatch = 4; }

// =========================================================================
// MCP tool inputs/outputs
// =========================================================================

message PortfolioPerformanceToolInput {
  string portfolio_id = 1;
  google.protobuf.Timestamp period_start = 2;
  google.protobuf.Timestamp period_end = 3;
  bool include_breakdown_by_asset = 4;
}
message PortfolioPerformanceToolOutput {
  PerformanceMetric portfolio = 1;
  repeated PerformanceMetric per_asset_breakdown = 2;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}

message PositionValuationToolInput {
  string portfolio_id = 1;
  google.protobuf.Timestamp as_of = 2;
}
message PositionValuationToolOutput {
  repeated Position positions = 1;
  Money total_value = 2;                  // base ccy
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}

message CostBasisToolInput {
  string portfolio_id = 1;
  string asset_id = 2;                    // optional; if absent: all assets
  google.protobuf.Timestamp as_of = 3;
}
message CostBasisToolOutput {
  repeated CostBasisLot lots = 1;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}
message CostBasisLot {
  string asset_id = 1;
  google.protobuf.Timestamp acquired_at = 2;
  string remaining_quantity = 3;
  Money cost_per_unit = 4;
  Money total_cost = 5;
  string source_transaction_id = 6;
}

message FeeAllocationToolInput {
  string transaction_id = 1;
}
message FeeAllocationToolOutput {
  Money total_fee = 1;
  repeated FeeAllocation allocations = 2;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}
message FeeAllocation {
  string asset_id = 1;
  Money allocated_fee = 2;
  string allocation_basis = 3;            // "pro-rata-value" / "equal" / etc.
}

message ReconcileStatementToolInput {
  string loader_run_id = 1;
  string portfolio_id = 2;
}
// output: ReconcileResponse (reused)
```

### 1.1.A Derived cash legs (S2) — baseline behaviour

Folded into the Midas baseline (Midas is greenfield — no forward migration needed). The proto deltas above (`TransactionKind.TX_CASH_CREDIT`/`TX_CASH_DEBIT`, `Portfolio.track_cash`, `Transaction.correlation_id`) and the DDL in §6 (`portfolios.track_cash`, `transactions.correlation_id`, the extended `kind` CHECK, `idx_transactions_correlation`, the `mv_position_current` netting) are part of V0001/V0002 — **not** a separate migration.

**Behaviour (Midas-core).** `POST /transactions` and `POST /transactions:batch` derive the cash counter-leg inside the same DB transaction when `portfolio.track_cash` is true, against an auto-provisioned `ASSET_CASH` instance per `(portfolio_id, currency)`; both legs share a `correlation_id`. The response carries both legs. Module `midas-core/derivation/CashLegDerivation.kt` (built in Midas Stage 1.3 alongside the write path). Cash legs carry `source = TX_SRC_DERIVATION`. Edits/reversals on a security leg cascade to its cash leg via `correlation_id`.

**Origin.** Sysifos design decision S2 (2026-06-13); [`../sysifos/architecture.md`](../sysifos/architecture.md) §10. Owned by the Midas arc; Sysifos sends the security leg only and renders both. Must be live before Sysifos Stage 2.2.

### 1.2 `org.tatrman.kantheon.sysifos.v1` (relocated to the Sysifos arc — 2026-06-13)

> **Ownership moved.** `sysifos/v1` is now owned by the **Sysifos arc** (decision S1). The canonical, current definition — including the Sysifos-arc additions (`DRAFT_TRANSACTION_BATCH`, `DRAFT_ASSET`, `BatchRowResult`, `AssetForm`, `TransactionBatchForm`, `ReconciliationDecisionForm`, `PortfolioForm.track_cash`) — lives in [`../sysifos/contracts.md`](../sysifos/contracts.md) §1. The block below is the **pre-split snapshot, retained for history only — do not implement from it.**

The proto body is **not** restated here. The single physical source is `shared/proto/src/main/proto/org/tatrman/kantheon/sysifos/v1/sysifos.proto`, defined authoritatively in [`../sysifos/contracts.md`](../sysifos/contracts.md) §1 (Sysifos arc, Stage 1.1 relocation). `sysifos/v1` form payloads import `midas/v1` types; Sysifos-BFF translates `Draft.payload_json` into the corresponding `midas.v1.*` request and forwards.

### 1.3 `org.tatrman.kantheon.report.v1` (new — Phase 1 Stage 1.2)

File: `shared/proto/src/main/proto/org/tatrman/kantheon/report/v1/report.proto`

```proto
syntax = "proto3";
package org.tatrman.kantheon.report.v1;

import "google/protobuf/timestamp.proto";
import "org/tatrman/kantheon/common/v1/response_message.proto";  // kantheon Rule-6 stand-in (kantheon-architecture §4, D1 2026-06-12)

message ReportTemplate {
  string template_id = 1;          // e.g. "portfolio-statement:v1"
  string display_name = 2;
  ReportFormat format = 3;         // XLSX or PPTX (native render)
  string version = 4;              // semver
  repeated ParamDef params = 5;
  string description = 6;
  string storage_ref = 7;          // resolver-internal (filesystem path; later S3 URI)
  bool active = 8;
}

enum ReportFormat { REPORT_XLSX = 0; REPORT_PPTX = 1; }
enum OutputFormat { OUTPUT_XLSX = 0; OUTPUT_PPTX = 1; OUTPUT_PDF = 2; OUTPUT_HTML = 3; }

message ParamDef {
  string name = 1;                 // "portfolio_id"
  ParamKind kind = 2;
  bool required = 3;
  string default_value = 4;        // optional
  string description = 5;
}
enum ParamKind {
  PARAM_STRING = 0;
  PARAM_INT = 1;
  PARAM_DATE = 2;
  PARAM_PORTFOLIO_ID = 3;
  PARAM_CLIENT_ID = 4;
  PARAM_ASSET_ID = 5;
  PARAM_PERIOD = 6;                // "ytd" / "mtd" / "qtd" / "all" / explicit range
}

message RenderReportRequest {
  string template_id = 1;
  string args_json = 2;            // Rule 7: function-call args as JSON string
  OutputFormat output_format = 3;
  string tenant_id = 4;
  string user_id = 5;
  string correlation_id = 6;
}

message RenderReportResponse {
  string artifact_id = 1;
  string artifact_url = 2;         // signed download URL (or local path in v1)
  string mime_type = 3;
  int64 size_bytes = 4;
  google.protobuf.Timestamp generated_at = 5;
  google.protobuf.Timestamp expires_at = 6;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}

message ListTemplatesResponse {
  repeated ReportTemplate templates = 1;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}
```

### 1.4 Existing packages — no changes in this arc

`envelope/v1`, `capabilities/v1`, `themis/v1` — touched only at Iris's dashboard work (Phase 3 Stage 3.5), which adds nothing new to the proto. Iris-BFF persists dashboard records in its own DB; no over-the-wire dashboard type is needed in v1.

---

## 2. Midas-core REST API

Base path: `/api/v1`. All requests carry `Authorization: Bearer <jwt>` and `X-Tenant-Id: <uuid>`. Tenant-ID consistency between JWT claim and header is verified at the route layer; mismatch returns 403.

All bodies are JSON, conforming to the proto types in §1.1 (canonical JSON, snake_case keys, decimal-as-string for `Money.amount` and `Transaction.quantity`).

### 2.1 Clients

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/clients` | `CreateClientRequest` | `ClientResponse` (201) |
| GET | `/clients` | — (query: `page`, `size`, `status`, `name_prefix`) | `ListClientsResponse` |
| GET | `/clients/{id}` | — | `ClientResponse` |
| PATCH | `/clients/{id}` | `UpdateClientRequest` | `ClientResponse` |
| POST | `/clients/{id}/archive` | — | `ClientResponse` (status flipped) |

### 2.2 Portfolios

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/portfolios` | `CreatePortfolioRequest` | `PortfolioResponse` |
| GET | `/portfolios` | — (query: `client_id`, `status`, `page`, `size`) | `ListPortfoliosResponse` |
| GET | `/portfolios/{id}` | — | `PortfolioResponse` |
| PATCH | `/portfolios/{id}` | `UpdatePortfolioRequest` | `PortfolioResponse` |
| POST | `/portfolios/{id}/archive` | — | `PortfolioResponse` |

### 2.3 Assets

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/assets` | `CreateAssetRequest` | `AssetResponse` |
| GET | `/assets` | — (query: `symbol`, `kind`, `exchange`, `page`, `size`) | `ListAssetsResponse` |
| GET | `/assets/{id}` | — | `AssetResponse` |
| PATCH | `/assets/{id}` | `UpdateAssetRequest` | `AssetResponse` |

### 2.4 Transactions

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/transactions` | `InsertTransactionRequest` | `TransactionResponse` |
| POST | `/transactions:batch` | `BatchInsertTransactionsRequest` | `BatchInsertTransactionsResponse` |
| GET | `/transactions` | — (query: `portfolio_id`, `asset_id`, `from`, `to`, `kind`, `page`, `size`) | `ListTransactionsResponse` |
| GET | `/transactions/{id}` | — | `TransactionResponse` |
| PATCH | `/transactions/{id}` | `EditTransactionRequest` | `EditTransactionResponse` (reversal + replacement) |
| DELETE | `/transactions/{id}` | — (query: `reason`) | `TransactionResponse` (the reversing entry) |

**Invariants.**
- PATCH never UPDATEs the source row. The endpoint inserts a reversing entry (**same `kind`** as the original, **`quantity` negated**, `reverses_transaction_id = original.id`, `source = TX_SRC_REVERSAL`) and a new entry. Response carries both. *(Net position is read with a kind→sign rule shared by `mv_position_current` and balance-entry — see §6.2 / `CashLegDerivation.positionSign`; stored `quantity` is a positive magnitude on primary inserts and a negative one on reversals, so `sign(kind) × quantity` cancels the original in both readers.)*
- DELETE inserts a reversal only.
- The reversal cascades to the original's derived cash leg via `correlation_id` (a second reversing entry on the cash leg); a PATCH's replacement entry derives its own fresh cash leg.
- Idempotency on POST `/transactions`: server checks `external_id` UNIQUE constraint within tenant; duplicate returns 409 with the existing transaction in body.
- POST `/transactions:batch` honours `skip_existing`: if `true`, duplicates by `external_id` are skipped silently (counted in `skipped_count`); if `false`, the batch fails on first conflict.

### 2.5 Balance entry

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/balance-entries:preview` | `BalanceEntryRequest` | `BalanceEntryPreview` |
| POST | `/balance-entries:commit` | `BalanceEntryRequest` | `BalanceEntryCommitResponse` |

Preview is read-only — it computes the diff and proposes the `ADJUSTMENT` transaction without inserting. Commit re-runs the diff (race-safe) and inserts.

### 2.6 Positions (read-only)

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/portfolios/{id}/positions` | — (query: `as_of`) | `ListPositionsResponse` |

### 2.7 FX rates

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/fx-rates` | `FxRateUpsertRequest` | `FxRateResponse` |
| GET | `/fx-rates` | — (query: `from_ccy`, `to_ccy`, `from_date`, `to_date`) | `ListFxRatesResponse` |

POST upserts on `(from_ccy, to_ccy, rate_date)`. Last write wins (Google Finance loader re-runs are idempotent).

### 2.8 Reconciliation

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/reconcile` | `ReconcileRequest` | `ReconcileResponse` |
| POST | `/reconcile/{diff_id}/decision` | `{ status: ReconcileStatus, note?: string }` | `ReconcileDiff` |

`POST /reconcile/{diff_id}/decision` persists the user's decision on a diff (mark expected / investigate / resolved) without changing the underlying data.

### 2.9 Standard endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/health` | Returns 200 if process alive |
| GET | `/ready` | Returns 200 only if DB pool + Flyway-migrated + capabilities-mcp registration successful |
| GET | `/metrics` | Prometheus metrics scrape |

---

## 3. Midas-core MCP tool surface

Midas-core exposes an MCP server at `/mcp` (Kotlin MCP SDK, HTTP streaming transport per EXAMPLES.md §3c). Five tools, all `kind=TOOL` registrations in `capabilities-mcp`.

### 3.1 Tool: `midas.portfolio.performance:v1`

**Input** (`argsJson` per Rule 7):
```json
{
  "portfolio_id": "uuid",
  "period_start": "2026-01-01T00:00:00Z",
  "period_end":   "2026-06-30T23:59:59Z",
  "include_breakdown_by_asset": false
}
```
**Output** (`structuredContent`): `PortfolioPerformanceToolOutput` (§1.1).

### 3.2 Tool: `midas.position.valuation:v1`

**Input:**
```json
{ "portfolio_id": "uuid", "as_of": "2026-06-30T23:59:59Z" }
```
**Output:** `PositionValuationToolOutput`.

### 3.3 Tool: `midas.position.cost_basis:v1`

**Input:**
```json
{ "portfolio_id": "uuid", "asset_id": "uuid?", "as_of": "2026-06-30T23:59:59Z" }
```
**Output:** `CostBasisToolOutput`.

### 3.4 Tool: `midas.transaction.fee_allocation:v1`

**Input:** `{ "transaction_id": "uuid" }`
**Output:** `FeeAllocationToolOutput`.

### 3.5 Tool: `midas.reconcile.statement:v1`

**Input:** `ReconcileStatementToolInput` (§1.1).
**Output:** `ReconcileResponse`.

### 3.6 Search tags (for `capabilities-mcp.search`)

Each tool registers with `search_tags` to make Themis Layer 1 routing and Pythia planner discovery work without LLM fan-out:

| Tool | Tags |
|---|---|
| `midas.portfolio.performance:v1` | `portfolio, performance, twr, mwr, return, ytd, investment` |
| `midas.position.valuation:v1` | `position, valuation, holdings, market_value, nav` |
| `midas.position.cost_basis:v1` | `cost_basis, fifo, lots, tax, gain` |
| `midas.transaction.fee_allocation:v1` | `fees, allocation, transaction` |
| `midas.reconcile.statement:v1` | `reconcile, statement, diff, audit` |

---

## 4. Loader REST API

Each loader exposes a small lifecycle API. Excel loader v1 surface:

### 4.1 Excel loader

Base path: `/api/v1` on the loader service. Same JWT + X-Tenant-Id pattern.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/uploads` | multipart: `file=<xlsx>`, `broker_id=<id>`, `portfolio_id=<uuid>` | `{ loader_run_id, status_url }` |
| GET | `/runs/{id}` | — | `LoaderRun` |
| GET | `/runs/{id}/preview` | — | `LoaderPreview` |
| POST | `/runs/{id}/commit` | `{ skip_existing: bool, confirm: true }` | `BatchInsertTransactionsResponse` (proxied through Midas-core) |
| GET | `/runs` | — (query: `portfolio_id`, `from`, `to`, `page`, `size`) | `ListLoaderRunsResponse` |

```proto
message LoaderRun {
  string loader_run_id = 1;
  string source_kind = 2;              // "EXCEL" / "GOOGLE_FINANCE" / ...
  string broker_id = 3;                // for excel
  string portfolio_id = 4;
  string tenant_id = 5;
  string user_id = 6;
  LoaderRunStatus status = 7;
  google.protobuf.Timestamp uploaded_at = 8;
  google.protobuf.Timestamp completed_at = 9;
  int32 row_count_total = 10;
  int32 row_count_committed = 11;
  int32 row_count_skipped = 12;
  int32 row_count_failed = 13;
  string error_summary = 14;
}
enum LoaderRunStatus {
  LR_UPLOADED = 0;
  LR_PARSING = 1;
  LR_MAPPING = 2;
  LR_PREVIEW_READY = 3;
  LR_COMMITTING = 4;
  LR_COMPLETED = 5;
  LR_FAILED = 6;
}

message LoaderPreview {
  string loader_run_id = 1;
  repeated PreviewRow rows = 2;
  PreviewSummary summary = 3;
}
message PreviewRow {
  int32 source_row_index = 1;
  Transaction draft = 2;               // midas.v1.Transaction (no transaction_id yet)
  PreviewDecision decision = 3;
  string note = 4;                     // e.g. "duplicate of {existing_id}"
}
enum PreviewDecision { PV_NEW = 0; PV_DUPLICATE = 1; PV_ERROR = 2; }
message PreviewSummary { int32 new_count = 1; int32 duplicate_count = 2; int32 error_count = 3; }
```

These messages live in `midas.v1` per §1.1 (loader contracts fold into the Midas package).

### 4.2 Google Finance loader (Phase 3)

Pollers don't expose `/uploads` / `/runs/{id}/preview` / `/runs/{id}/commit` — they're scheduled. Surface:

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/runs` | — (query: `from`, `to`, `page`, `size`) | `ListLoaderRunsResponse` |
| GET | `/runs/{id}` | — | `LoaderRun` |
| POST | `/runs:trigger` | `{ kind: "fx_rates" or "market_prices", date: optional }` | `LoaderRun` (manual trigger) |

---

## 5. Sysifos-BFF API

> **Relocated 2026-06-13 (S1).** The authoritative Sysifos-BFF API now lives in [`../sysifos/contracts.md`](../sysifos/contracts.md) §3, where it gained the bulk-grid draft path, the `/screens/*` fan-out, and the asset quick-create route. The table below is the pre-split snapshot — retained for history; do not implement from it.

Base path: `/api/v1` on the BFF service. Authoritative schema for the FE↔BFF wire.

### 5.1 Session

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/sessions` | — | `{ session_id }` |
| GET | `/sessions/current` | — | `SysifosSession` |

### 5.2 Drafts (optimistic-write surface)

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/drafts` | `Draft` (with `payload_json`) | `{ draft_id, status: PENDING }` |
| GET | `/drafts/{id}` | — | `Draft` |

Drafts commit asynchronously; clients subscribe to `/stream` (§5.5) for `DraftAck`/`DraftCommitted`/`DraftRejected`.

### 5.3 Direct CRUD proxies (synchronous reads)

Same paths as Midas-core REST (§2), prefixed `/midas`. The BFF forwards with `X-Tenant-Id` injection from JWT. Used for reads (lists, gets). Writes go through `/drafts` for optimistic UX.

| Method | Path | Forwards to |
|---|---|---|
| GET | `/midas/clients` | Midas-core `GET /api/v1/clients` |
| GET | `/midas/portfolios` | Midas-core `GET /api/v1/portfolios` |
| GET | `/midas/assets` | Midas-core `GET /api/v1/assets` |
| GET | `/midas/transactions` | Midas-core `GET /api/v1/transactions` |
| GET | `/midas/portfolios/{id}/positions` | Midas-core `GET /api/v1/portfolios/{id}/positions` |
| POST | `/midas/balance-entries:preview` | Midas-core (preview) |
| POST | `/midas/reconcile` | Midas-core |

### 5.4 Loader proxy

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/loaders/excel/uploads` | multipart | proxied + `LoaderRun` |
| GET | `/loaders/excel/runs/{id}` | — | proxied |
| GET | `/loaders/excel/runs/{id}/preview` | — | proxied |
| POST | `/loaders/excel/runs/{id}/commit` | — | proxied |
| GET | `/loaders/*/runs` | — | proxied |

### 5.5 Stream

| Method | Path | Notes |
|---|---|---|
| GET | `/stream` | SSE; emits `SysifosStreamEvent`s for the calling session |

### 5.6 Dictionaries (cached at BFF)

| Method | Path | Notes |
|---|---|---|
| GET | `/dictionaries/brokers` | for upload UI |
| GET | `/dictionaries/currencies` | ISO 4217 |
| GET | `/dictionaries/transaction-kinds` | enum values + display labels |

### 5.7 Standard endpoints

`/health`, `/ready`, `/metrics` per §2.9.

---

## 6. Database schema (Flyway)

All under PostgreSQL 16. Migration files under `agents/midas/core/src/main/resources/db/migration/`.

### 6.1 V0001__schema.sql — baseline tables

> **Persistence layer (locked Stage 1.3, 2026-06-21).** The repository layer is **Exposed v1** (`Table` mappings over this Flyway-owned DDL), not jOOQ — the earlier jOOQ-codegen plan was dropped (no build-time codegen DB needed). Decimal columns map to Exposed `decimal(p, s)`.
>
> **RLS hardening applied in the shipped migration (deltas from the DDL below):**
> - **`FORCE ROW LEVEL SECURITY`** is added to *every* tenant table (clients, portfolios, assets, transactions, reconciliation_decisions, loader_runs, audit_log). `midas_app` *owns* these tables (it runs the migration) and a table owner bypasses RLS by default — `FORCE` is required for the tenant policies to bind to the app's own connections. Without it, cross-tenant isolation does not hold.
> - **`assets`** carries an explicit `WITH CHECK (tenant_id = app_current_tenant())` in addition to its `USING` clause: `USING` lets callers *read* global (`tenant_id IS NULL`) assets, but the explicit `WITH CHECK` forbids *writing* a global or cross-tenant asset (otherwise `WITH CHECK` defaults to the `USING` expression and any tenant could insert a globally-visible row).

```sql
-- ===================================================================
-- Extensions
-- ===================================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;     -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ===================================================================
-- Helper for RLS: read tenant from session var; throw if unset
-- ===================================================================
CREATE OR REPLACE FUNCTION app_current_tenant() RETURNS UUID AS $$
BEGIN
  RETURN current_setting('app.tenant_id')::uuid;
EXCEPTION WHEN OTHERS THEN
  RAISE EXCEPTION 'app.tenant_id session var not set';
END;
$$ LANGUAGE plpgsql STABLE;

-- ===================================================================
-- clients
-- ===================================================================
CREATE TABLE clients (
  client_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          UUID NOT NULL,
  name               TEXT NOT NULL,
  contact_email      TEXT,
  contact_phone      TEXT,
  status             TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by_user_id TEXT NOT NULL,
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_by_user_id TEXT NOT NULL
);
CREATE INDEX idx_clients_tenant_name ON clients (tenant_id, name);
ALTER TABLE clients ENABLE ROW LEVEL SECURITY;
CREATE POLICY clients_tenant ON clients USING (tenant_id = app_current_tenant());

-- ===================================================================
-- portfolios
-- ===================================================================
CREATE TABLE portfolios (
  portfolio_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           UUID NOT NULL,
  client_id           UUID NOT NULL REFERENCES clients(client_id),
  name                TEXT NOT NULL,
  base_currency       CHAR(3) NOT NULL,
  portfolio_type      TEXT NOT NULL DEFAULT 'BROKERAGE'
                      CHECK (portfolio_type IN ('BROKERAGE','RETIREMENT','OTHER')),
  cost_basis_method   TEXT NOT NULL DEFAULT 'FIFO' CHECK (cost_basis_method IN ('FIFO')),
  inception_date      DATE,
  status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  track_cash          BOOLEAN NOT NULL DEFAULT true,   -- S2: derive the cash counter-leg of each trade
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by_user_id  TEXT NOT NULL,
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_by_user_id  TEXT NOT NULL
);
CREATE INDEX idx_portfolios_tenant_client ON portfolios (tenant_id, client_id);
ALTER TABLE portfolios ENABLE ROW LEVEL SECURITY;
CREATE POLICY portfolios_tenant ON portfolios USING (tenant_id = app_current_tenant());

-- ===================================================================
-- assets
-- ===================================================================
CREATE TABLE assets (
  asset_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           UUID,                                   -- NULL for global assets (rare in v1)
  symbol              TEXT NOT NULL,
  isin                TEXT,
  name                TEXT NOT NULL,
  kind                TEXT NOT NULL
                      CHECK (kind IN ('STOCK','ETF','BOND','FUND','CASH')),
  exchange            TEXT,
  currency            CHAR(3) NOT NULL,
  status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DELISTED')),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by_user_id  TEXT NOT NULL,
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_by_user_id  TEXT NOT NULL,
  UNIQUE (tenant_id, symbol, exchange)
);
CREATE INDEX idx_assets_symbol ON assets (symbol);
ALTER TABLE assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE assets FORCE ROW LEVEL SECURITY;
CREATE POLICY assets_tenant ON assets
  USING (tenant_id IS NULL OR tenant_id = app_current_tenant())
  WITH CHECK (tenant_id = app_current_tenant());

-- ===================================================================
-- transactions (event log; append-only)
-- ===================================================================
CREATE TABLE transactions (
  transaction_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id               UUID NOT NULL,
  portfolio_id            UUID NOT NULL REFERENCES portfolios(portfolio_id),
  asset_id                UUID NOT NULL REFERENCES assets(asset_id),
  kind                    TEXT NOT NULL
                          CHECK (kind IN ('BUY','SELL','DIVIDEND','INTEREST','FEE','TAX',
                                          'TRANSFER_IN','TRANSFER_OUT','ADJUSTMENT',
                                          'CASH_CREDIT','CASH_DEBIT')),    -- S2 derived cash legs
  trade_date              TIMESTAMPTZ NOT NULL,
  settle_date             TIMESTAMPTZ,
  quantity                NUMERIC(28,8) NOT NULL,
  price_amount            NUMERIC(20,4) NOT NULL DEFAULT 0,
  price_currency          CHAR(3),
  fee_amount              NUMERIC(20,4) NOT NULL DEFAULT 0,
  fee_currency            CHAR(3),
  tax_amount              NUMERIC(20,4) NOT NULL DEFAULT 0,
  tax_currency            CHAR(3),
  total_amount            NUMERIC(20,4) NOT NULL,
  total_currency          CHAR(3) NOT NULL,
  currency                CHAR(3) NOT NULL,
  external_id             TEXT,
  reverses_transaction_id UUID REFERENCES transactions(transaction_id),
  correlation_id          UUID,                          -- S2: links a security leg to its derived cash leg
  note                    TEXT,
  source                  TEXT NOT NULL
                          CHECK (source IN ('MANUAL','LOADER_EXCEL','LOADER_GOOGLE_FINANCE',
                                            'LOADER_API','DERIVATION','REVERSAL')),
  recorded_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  recorded_by_user_id     TEXT NOT NULL
);
CREATE UNIQUE INDEX uq_transactions_tenant_extid
  ON transactions (tenant_id, external_id)
  WHERE external_id IS NOT NULL;
CREATE INDEX idx_transactions_portfolio_trade ON transactions (portfolio_id, trade_date DESC);
CREATE INDEX idx_transactions_asset_trade     ON transactions (asset_id, trade_date DESC);
CREATE INDEX idx_transactions_reverses        ON transactions (reverses_transaction_id) WHERE reverses_transaction_id IS NOT NULL;
CREATE INDEX idx_transactions_correlation     ON transactions (correlation_id) WHERE correlation_id IS NOT NULL;
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
CREATE POLICY transactions_tenant ON transactions USING (tenant_id = app_current_tenant());

-- Append-only: forbid UPDATE/DELETE on transactions
CREATE OR REPLACE FUNCTION transactions_no_mutate() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'transactions table is append-only; use reversal entries'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_transactions_no_update BEFORE UPDATE ON transactions
  FOR EACH ROW EXECUTE FUNCTION transactions_no_mutate();
CREATE TRIGGER trg_transactions_no_delete BEFORE DELETE ON transactions
  FOR EACH ROW EXECUTE FUNCTION transactions_no_mutate();

-- ===================================================================
-- fx_rates
-- ===================================================================
CREATE TABLE fx_rates (
  from_ccy    CHAR(3) NOT NULL,
  to_ccy      CHAR(3) NOT NULL,
  rate_date   DATE NOT NULL,
  rate        NUMERIC(20,10) NOT NULL,
  source      TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (from_ccy, to_ccy, rate_date)
);
-- fx_rates is globally shared; no tenant_id, no RLS.

-- ===================================================================
-- reconciliation_decisions
-- ===================================================================
CREATE TABLE reconciliation_decisions (
  decision_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL,
  diff_key        TEXT NOT NULL,             -- hash of (loader_run_id, source_row_index, transaction_id)
  loader_run_id   UUID,
  transaction_id  UUID,
  status          TEXT NOT NULL CHECK (status IN ('OPEN','EXPECTED','INVESTIGATE','RESOLVED')),
  note            TEXT,
  decided_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  decided_by_user_id TEXT NOT NULL,
  UNIQUE (tenant_id, diff_key)
);
ALTER TABLE reconciliation_decisions ENABLE ROW LEVEL SECURITY;
CREATE POLICY recon_tenant ON reconciliation_decisions USING (tenant_id = app_current_tenant());

-- ===================================================================
-- loader_runs (status table; loader-owned)
-- ===================================================================
CREATE TABLE loader_runs (
  loader_run_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            UUID NOT NULL,
  user_id              TEXT NOT NULL,
  source_kind          TEXT NOT NULL,
  broker_id            TEXT,
  portfolio_id         UUID,
  status               TEXT NOT NULL CHECK (status IN
                       ('UPLOADED','PARSING','MAPPING','PREVIEW_READY','COMMITTING','COMPLETED','FAILED')),
  uploaded_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at         TIMESTAMPTZ,
  row_count_total      INT NOT NULL DEFAULT 0,
  row_count_committed  INT NOT NULL DEFAULT 0,
  row_count_skipped    INT NOT NULL DEFAULT 0,
  row_count_failed     INT NOT NULL DEFAULT 0,
  error_summary        TEXT,
  upload_blob_ref      TEXT          -- FS path in v1; S3 URI later
);
CREATE INDEX idx_loader_runs_tenant_status ON loader_runs (tenant_id, status, uploaded_at DESC);
ALTER TABLE loader_runs ENABLE ROW LEVEL SECURITY;
CREATE POLICY loader_runs_tenant ON loader_runs USING (tenant_id = app_current_tenant());

-- ===================================================================
-- audit_log
-- ===================================================================
CREATE TABLE audit_log (
  audit_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL,
  actor_user_id    TEXT NOT NULL,
  entity_type      TEXT NOT NULL,
  entity_id        UUID NOT NULL,
  operation        TEXT NOT NULL CHECK (operation IN ('CREATE','UPDATE','ARCHIVE','REVERSE','DELETE')),
  before_jsonb     JSONB,
  after_jsonb      JSONB,
  occurred_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  trace_id         TEXT
);
CREATE INDEX idx_audit_log_tenant_occurred ON audit_log (tenant_id, occurred_at DESC);
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_log_tenant ON audit_log USING (tenant_id = app_current_tenant());
```

### 6.2 V0002__materialized_views.sql — derived state

```sql
-- ===================================================================
-- mv_position_current — quantity, FIFO avg cost, unrealised P&L per holding
-- ===================================================================
CREATE MATERIALIZED VIEW mv_position_current AS
WITH net AS (
  SELECT
    portfolio_id,
    asset_id,
    tenant_id,
    SUM(
      CASE kind
        WHEN 'BUY' THEN quantity
        WHEN 'SELL' THEN -quantity
        WHEN 'TRANSFER_IN' THEN quantity
        WHEN 'TRANSFER_OUT' THEN -quantity
        WHEN 'ADJUSTMENT' THEN quantity
        WHEN 'CASH_CREDIT' THEN quantity      -- S2: cash legs net onto the CASH asset's position
        WHEN 'CASH_DEBIT' THEN -quantity
        ELSE 0
      END
    ) AS quantity
  FROM transactions
  GROUP BY portfolio_id, asset_id, tenant_id
)
SELECT
  net.portfolio_id,
  net.asset_id,
  net.tenant_id,
  net.quantity,
  -- FIFO avg-cost computation handled in midas-core's calc module; this view
  -- exposes raw aggregates only.
  NOW() AS as_of
FROM net
WHERE net.quantity <> 0;

CREATE UNIQUE INDEX idx_mv_position_current
  ON mv_position_current (tenant_id, portfolio_id, asset_id);

-- ===================================================================
-- mv_portfolio_value_daily — daily portfolio value at base ccy
--   (skeleton; full implementation joins fx_rates + latest asset price)
-- ===================================================================
CREATE MATERIALIZED VIEW mv_portfolio_value_daily AS
SELECT
  p.portfolio_id,
  p.tenant_id,
  d::date AS as_of,
  0::numeric(20,4) AS total_value_base    -- populated in V0003 once price-source is wired
FROM portfolios p
CROSS JOIN generate_series(
  COALESCE(p.inception_date, CURRENT_DATE - INTERVAL '1 year'),
  CURRENT_DATE,
  '1 day'::interval
) d;

CREATE UNIQUE INDEX idx_mv_portfolio_value_daily
  ON mv_portfolio_value_daily (tenant_id, portfolio_id, as_of);

-- mv_realised_pnl_ytd — to be defined in V0004 alongside calc spec.
```

### 6.3 V0003__view_refresh_strategy.sql

> **Shipped mechanism (Stage 1.4, 2026-06-21) — synchronous refresh via a BYPASSRLS MV owner.**
>
> - **Ownership.** The materialized views are created **`WITH NO DATA`** and their ownership is transferred to **`midas_mv_owner`**, a `BYPASSRLS NOLOGIN` role provisioned out-of-band by the Postgres **init job** (`deployment/local/postgres/init-sql-configmap.yaml`) — *not* by Flyway, because creating a `BYPASSRLS` role needs a superuser and Flyway runs as `midas_app`. `midas_app` is granted membership in `midas_mv_owner`. A `REFRESH` runs the view query in the **owner's** RLS context, so it reads across every tenant; `midas_app` alone is `FORCE`-RLS-bound and could not. **Consequence:** any environment that runs these migrations must first provision `midas_mv_owner` (the init job in prod/local; the test harness mirrors it in `RlsLeakageComponentSpec`), or V0002's `ALTER … OWNER` fails.
> - **Trigger.** `mv_position_current` refreshes **synchronously** — `midas-core` issues a plain (non-`CONCURRENTLY`) `REFRESH MATERIALIZED VIEW mv_position_current` once per write request (batch inserts coalesce naturally), via `infra/MvRefresher`. It **fails open**: a missing role/MV is logged and swallowed so writes still succeed (the MV is merely stale).
> - **Deferred to v1.x.** The async path — a `NOTIFY` (`mv_position_refresh`) trigger feeding a debounced `PGNotificationListener` that issues `REFRESH … CONCURRENTLY` — is **installed but unused** (the `NOTIFY` trigger ships in V0003; no listener is attached). `CONCURRENTLY` is deferred because it cannot run inside a transaction and needs the MV populated once first; the synchronous refresh already meets the "fresh within 5s of insert" target. Daily snapshots (`mv_portfolio_value_daily`, `mv_realised_pnl_ytd`) refresh on a cron schedule (Phase 3).
>
> ⚠ The non-`CONCURRENTLY` refresh takes an `ACCESS EXCLUSIVE` lock that briefly blocks MV reads on every write — acceptable at v1 fixture scale; the `CONCURRENTLY` switch (the unique index is already in place) is the planned mitigation under load.

### 6.4 RLS session-var contract

Every JDBC connection borrowed by Midas-core executes:

```sql
SET LOCAL app.tenant_id = '<uuid-from-X-Tenant-Id-header>';
```

after `BEGIN`, before any application SQL. The `infra/sql-security` OPA bundle (ai-platform) defines the policy; Midas-core enforces only the session-var set. `workers/postgres` does the same when reading via `query-mcp` in Phase 3.

---

## 7. Iris-BFF dashboard schema extensions

> **Superseded by PD-6 (2026-06-12).** Replaced by the generic `iris_artifacts` schema + artifact endpoints in [`../iris/contracts.md`](../iris/contracts.md) §2.8 + §3.3 (pane source = `common.v1.ViewProvenance`; refresh = typed-action/replay re-execution). The DDL below is retained as the requirements record only — do not implement. The template YAML model (ParamDefs/panes) carries over into the generic system; `user_preference` survives as-is if still needed by Stage 3.5.

Additions to Iris-BFF's existing Postgres (Phase 3 Stage 3.5).

```sql
CREATE TABLE dashboard (
  dashboard_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL,
  owner_user_id    TEXT NOT NULL,
  template_id      TEXT,                          -- nullable for free-form
  name             TEXT NOT NULL,
  resolved_params  JSONB NOT NULL DEFAULT '{}',
  layout           JSONB NOT NULL DEFAULT '{}',   -- dockview-shaped
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_dashboard_owner ON dashboard (tenant_id, owner_user_id);

CREATE TABLE dashboard_pane (
  pane_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  dashboard_id     UUID NOT NULL REFERENCES dashboard(dashboard_id) ON DELETE CASCADE,
  kind             TEXT NOT NULL CHECK (kind IN ('CHART','TABLE','REPORT_PREVIEW','TEXT')),
  source           JSONB NOT NULL,    -- agent_call_spec: { agent_id, method, args_json }
  position         JSONB NOT NULL,    -- dockview position descriptor
  cache_ttl_seconds INT NOT NULL DEFAULT 300,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE user_preference (
  user_id          TEXT NOT NULL,
  tenant_id        UUID NOT NULL,
  key              TEXT NOT NULL,
  value_jsonb      JSONB NOT NULL,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (tenant_id, user_id, key)
);
```

Iris-BFF new endpoints (Phase 3 Stage 3.5):

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/dashboards` | — | `{ dashboards: [Dashboard] }` |
| POST | `/dashboards` | `{ name, template_id?, resolved_params?, layout? }` | `{ dashboard }` |
| GET | `/dashboards/{id}` | — | `{ dashboard, panes }` |
| PATCH | `/dashboards/{id}` | partial | `{ dashboard }` |
| DELETE | `/dashboards/{id}` | — | 204 |
| POST | `/dashboards/{id}/panes` | `{ kind, source, position }` | `{ pane }` |
| PATCH | `/dashboards/{id}/panes/{pid}` | partial | `{ pane }` |
| DELETE | `/dashboards/{id}/panes/{pid}` | — | 204 |
| GET | `/dashboards/{id}/render` | (SSE) | stream of per-pane envelope events |
| GET | `/preferences` | — | `{ preferences: { [key]: value } }` |
| PUT | `/preferences/{key}` | `{ value }` | `{ key, value }` |
| GET | `/dashboard-templates` | — | `{ templates: [DashboardTemplate] }` |

Dashboard templates are repo-bundled YAML, schema:

```yaml
template_id: investment-overview:v1
display_name: Investment overview
params:
  - { name: client_id,    kind: CLIENT_ID,    required: true }
  - { name: portfolio_id, kind: PORTFOLIO_ID, required: true }
  - { name: period,       kind: PERIOD,       required: true, default: ytd }
panes:
  - kind: CHART
    source:
      agent_id: golem-investment
      method: answer
      args_json: '{"question":"YTD performance for portfolio {portfolio_id}"}'
    position: { group: main, weight: 2 }
  - kind: TABLE
    source:
      agent_id: midas.core.mcp
      method: midas.position.valuation:v1
      args_json: '{"portfolio_id":"{portfolio_id}","as_of":"{period.end}"}'
    position: { group: main, weight: 1 }
  - kind: REPORT_PREVIEW
    source:
      agent_id: report-renderer
      method: render
      args_json: '{"template_id":"portfolio-statement:v1","args_json":"{\"portfolio_id\":\"{portfolio_id}\",\"as_of_date\":\"{period.end}\"}","output_format":"OUTPUT_HTML"}'
    position: { group: side, weight: 1 }
```

---

## 8. Report-renderer API

Base path: `/api/v1`.

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/templates` | — | `ListTemplatesResponse` |
| GET | `/templates/{id}` | — | `ReportTemplate` |
| POST | `/render` | `RenderReportRequest` | `RenderReportResponse` (synchronous; ≤ 30s) |
| GET | `/artifacts/{id}` | — | streams the rendered file (signed token in v1.x) |
| DELETE | `/artifacts/{id}` | — | 204 (manual cleanup before TTL expiry) |

`POST /render` is synchronous in v1. v1.x splits into job + poll if 30s ever proves insufficient.

---

## 9. Manifest YAMLs

### 9.1 Golem-Investment ShemManifest

File: `agents/midas/shem/shem-investment.yaml`. Loaded by the Golem template image at boot when `SHEM_PATH=/shems/shem-investment.yaml` env var is set.

```yaml
schema_version: capabilities/v1
kind: AGENT
agent_kind: DOMAIN_QA
agent_id: golem-investment
display_name: Investment Q&A
intent_kinds_supported: [PROCEDURAL]
description_for_router: >
  Answers questions about a client's investment portfolios — positions, current value,
  performance (returns, P&L), transaction history, fees, dividends, FX exposure.
  Reads the kantheon-owned operational Postgres via query-mcp; uses Midas-core's
  MCP calc tools for time-weighted and money-weighted returns.

example_questions:
  - "What's the YTD return on the Smith portfolio?"
  - "Show me current positions for client X."
  - "What were the dividends paid in Q1?"
  - "What's the unrealised P&L on my AAPL holdings?"
  - "How much did I pay in fees last year?"

counter_examples:
  - "Show me HR headcount."                  # → Golem-HR
  - "What's our pipeline this quarter?"      # → Golem-Sales
  - "Why did the system latency spike?"      # → Pythia

capability_refs:
  - midas.portfolio.performance:v1
  - midas.position.valuation:v1
  - midas.position.cost_basis:v1
  - midas.transaction.fee_allocation:v1
  - midas.reconcile.statement:v1
  - query.run:v1                             # query-mcp
  - query.compile:v1

service_endpoint: http://golem-investment.kantheon.svc.cluster.local:8080
health_check_path: /health
typical_latency_ms: 2500
typical_cost_usd: 0.012
hitl_default: NEVER

# ShemManifest-specific (correctness-affecting)
domain_name: Investment
domain_entities:
  - { type: Client,    table: clients,    proto: midas.v1.Client }
  - { type: Portfolio, table: portfolios, proto: midas.v1.Portfolio }
  - { type: Asset,     table: assets,     proto: midas.v1.Asset }
  - { type: Transaction, table: transactions, proto: midas.v1.Transaction }
  - { type: Position,  view:  mv_position_current, proto: midas.v1.Position }

domain_terminology:
  - { term: "ROI",  definition: "Return on Investment; ratio of profit to cost basis. Time-period dependent." }
  - { term: "TWR",  definition: "Time-weighted return; geometric mean of sub-period returns; eliminates cash-flow timing effects." }
  - { term: "MWR",  definition: "Money-weighted (dollar-weighted) return; equivalent to IRR; sensitive to timing of contributions/withdrawals." }
  - { term: "NAV",  definition: "Net Asset Value; market value of a portfolio's holdings." }
  - { term: "FIFO", definition: "First-In, First-Out cost basis method; lots sold in acquisition order." }
  - { term: "Realised P&L",   definition: "Gain or loss on sold positions." }
  - { term: "Unrealised P&L", definition: "Gain or loss on held positions (mark-to-market vs cost basis)." }
  - { term: "Base currency",  definition: "The portfolio's report currency; all valuations roll up to it." }

preferred_queries:
  - { id: q.midas.positions_current, description: "Current positions for portfolio" }
  - { id: q.midas.transactions_recent, description: "Recent transactions for portfolio" }
  - { id: q.midas.dividends_period, description: "Dividends paid in a period" }
  - { id: q.midas.fees_period, description: "Fees paid in a period" }
  # Queries registered into metadata-mcp; see ai-platform's metadata catalog.

preferred_capabilities:
  - midas.portfolio.performance:v1
  - midas.position.valuation:v1
  - midas.position.cost_basis:v1

style_addendum: >
  Tone: precise, numerical, professional. Always quote figures with currency.
  When uncertainty exists (intraday prices, FX rates older than 1 day), say so explicitly.

locale_defaults:
  - { locale: en, date_format: "YYYY-MM-DD", thousands_sep: ",", decimal_sep: "." }
  - { locale: cs, date_format: "DD.MM.YYYY", thousands_sep: " ", decimal_sep: "," }
```

### 9.2 Midas-core tool manifests (heartbeat payloads)

Midas-core registers five `ToolCapability` entries at startup (see §3). The heartbeat client library (`shared/libs/kotlin/capabilities-client`) sends a `RegisterRequest` per tool with:

```yaml
kind: TOOL
capability_id: midas.portfolio.performance:v1
category: midas.calc.*
version: "1.0.0"
preconditions:
  - { field: portfolio_id, exists: true }
cost_hints:
  typical_latency_ms: 800
  typical_cost_usd: 0.0
search_tags: [portfolio, performance, twr, mwr, return, ytd, investment]
service_endpoint: http://midas-core.kantheon.svc.cluster.local:8080/mcp
description: Compute TWR/MWR/total return for a portfolio over a period.
```

Re-emitted every 30s as heartbeat. Warn-and-continue if `capabilities-mcp` is unreachable.

### 9.3 Report templates (v1)

Repo paths under `services/report-renderer/src/main/resources/templates/`:

- `portfolio-statement.v1.xlsx` — named ranges: `{{portfolio.name}}`, `{{portfolio.base_currency}}`, `{{as_of_date}}`; table region `tbl_positions` for the positions list; table region `tbl_transactions` for the ledger.
- `performance-report.v1.xlsx` — same naming convention; performance table by period.
- `performance-report.v1.pptx` — slides: cover (params), performance summary, asset breakdown chart, transaction summary.
- `transaction-ledger.v1.xlsx` — single sheet, one table region `tbl_transactions`.

`TemplateResolver` in v1 loads from classpath at `templates/{template_id_with_dots_to_slashes}.{ext}`.

---

## 10. Capabilities registration

Reuses the existing `capabilities-mcp` heartbeat protocol from the Themis arc (see [`../themis/contracts.md`](../themis/contracts.md) §3).

Phase 1 Stage 1.4 wires Midas-core to register its five tool capabilities at startup. Phase 3 Stage 3.1 places `agents/midas/shem/shem-investment.yaml` into `tools/capabilities-mcp`'s fixture path so it's loaded on registry boot. The Golem template, when launched with the investment Shem, also registers itself via heartbeat after boot — same path Golem-ERP / Golem-HR follow.

Sysifos-BFF and `report-renderer` do **not** register agent capabilities — they're not user-facing agents, they're infrastructure used by Iris/Sysifos. Themis does not route to them.

---

## 11. Audit log shape

Every write through Midas-core emits an `audit_log` row. Shape covered in §6.1's DDL; payload conventions:

- `before_jsonb` / `after_jsonb` carry the proto-JSON of the entity. For `transactions`, `before` is NULL on insert (event log).
- `operation` values: `CREATE` (insert), `UPDATE` (mutable entity updates), `ARCHIVE` (client/portfolio archive), `REVERSE` (transaction reversal entry), `DELETE` (reserved for v1.x).
- `trace_id` = OTel trace id of the request that triggered the write. Lets a support engineer reconstruct the full call chain from an audit row.

Retention: 7 years. Implemented as a partitioned table by month in v1.x; flat in v1.

---

## 12. Error envelope

All endpoints follow the shared error contract:

```json
{
  "error": {
    "code":    "TRANSACTION_DUPLICATE_EXTERNAL_ID",
    "message": "A transaction with external_id 'IBKR:STMT-2026-04:line-37' already exists",
    "field":   "external_id",
    "details": { "existing_transaction_id": "..." }
  },
  "messages": [ ... ]   // Rule 6: repeated ResponseMessage messages = 99
}
```

HTTP status codes:

| Status | When |
|---|---|
| 200 | Successful read |
| 201 | Successful create |
| 204 | Successful delete / no-content reply |
| 400 | Validation error (FieldValidationError list in details) |
| 401 | Missing / invalid JWT |
| 403 | Tenant mismatch or RLS denial |
| 404 | Entity not found in tenant scope |
| 409 | Conflict (duplicate external_id, version mismatch) |
| 422 | Domain rule violated (e.g. portfolio archive when open positions exist — v1.x rule) |
| 500 | Unexpected server error |

Error codes are stable strings (see `MidasErrorCode.kt`). Initial set:

```
CLIENT_NOT_FOUND
PORTFOLIO_NOT_FOUND
ASSET_NOT_FOUND
TRANSACTION_NOT_FOUND
TRANSACTION_DUPLICATE_EXTERNAL_ID
TRANSACTION_VALIDATION_FAILED
BALANCE_ENTRY_PORTFOLIO_OR_ASSET_NOT_FOUND
BALANCE_ENTRY_NO_DIFF
TENANT_HEADER_MISSING
TENANT_HEADER_JWT_MISMATCH
RLS_VIOLATION
LOADER_RUN_NOT_FOUND
LOADER_RUN_INVALID_STATE
RECONCILE_DIFF_NOT_FOUND
REPORT_TEMPLATE_NOT_FOUND
REPORT_PARAM_INVALID
FX_RATE_NOT_FOUND
```

---

## 13. Version, observability, and stability

- **Proto evolution.** Proto fields are append-only after first publish; never renumber. Major version bumps create a new package (e.g. `midas/v2`). Breaking changes during this arc (Phases 1–3, pre-cutover) are allowed up until Phase 2 close.
- **OTel attribute names.** `kantheon.tenant_id`, `kantheon.user_id`, `kantheon.portfolio_id`, `kantheon.transaction_id`, `kantheon.loader_run_id`, `kantheon.external_id`, `kantheon.template_id`, `kantheon.dashboard_id`, `kantheon.pane_id`.
- **Stability commitment.** Endpoints under `/api/v1` are stable from Phase 2 close onward. MCP tool IDs are stable from Phase 3 close. Manifest YAMLs follow the same versioning rules as the proto packages.

---

*Contracts doc owner: Bora. Lives in `docs/architecture/midas/`. Update before every change to a wire surface. Revision history via git.*
