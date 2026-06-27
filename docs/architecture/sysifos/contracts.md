# Sysifos — Wire Contracts (kantheon arc)

> **Scope.** All cross-service contracts owned by the Sysifos arc: the `sysifos/v1` proto package (relocated here from the Midas arc per S1), the Sysifos-BFF FE↔BFF API, the SSE stream protocol, the dictionary surface, and the shared validation rule manifest. Midas-core contracts are **consumed, not restated** — cited by section into [`../midas/contracts.md`](../midas/contracts.md).
>
> **Authority.** This document is the source of truth for `sysifos/v1` and the Sysifos-BFF API. Task lists implementing them must match byte-for-byte. The cash-leg amendment to `midas/v1` is authoritative in [`../midas/contracts.md`](../midas/contracts.md) (amendment block) — §6 here is a pointer only.
>
> **Companions.** [`architecture.md`](./architecture.md), [`../../implementation/v1/sysifos/plan.md`](../../implementation/v1/sysifos/plan.md), [`../../design/sysifos/sysifos-design.md`](../../design/sysifos/sysifos-design.md), [`../midas/contracts.md`](../midas/contracts.md).

---

## Table of contents

1. [Proto package `sysifos/v1`](#1-proto-package-sysifosv1)
2. [Consumed Midas-core contracts](#2-consumed-midas-core-contracts)
3. [Sysifos-BFF API](#3-sysifos-bff-api)
4. [Validation rule manifest](#4-validation-rule-manifest)
5. [Dictionaries](#5-dictionaries)
6. [Midas-arc cash-leg amendment (pointer)](#6-midas-arc-cash-leg-amendment-pointer)
7. [Error envelope](#7-error-envelope)

---

## 1. Proto package `sysifos/v1`

Relocated verbatim from the Midas arc (formerly `midas/contracts.md` §1.2). No type changes from the move. `sysifos/v1` imports `midas/v1` (form payloads reference domain enums + `Money`), `envelope/v1`, and `common/v1` (Rule 6).

File: `shared/proto/src/main/proto/org/tatrman/kantheon/sysifos/v1/sysifos.proto`

```proto
syntax = "proto3";
package org.tatrman.kantheon.sysifos.v1;

import "google/protobuf/timestamp.proto";
import "org/tatrman/kantheon/common/v1/response_message.proto";   // Rule 6 stand-in
import "org/tatrman/kantheon/envelope/v1/envelope.proto";
import "org/tatrman/kantheon/midas/v1/midas.proto";               // form payloads reference midas types

// =========================================================================
// Session + draft state
// =========================================================================

message SysifosSession {
  string session_id = 1;
  string user_id = 2;
  string tenant_id = 3;
  google.protobuf.Timestamp created_at = 4;
  google.protobuf.Timestamp last_active_at = 5;
}

message Draft {
  string draft_id = 1;
  string session_id = 2;
  DraftKind kind = 3;
  string payload_json = 4;           // form-specific JSON; validated against Zod schema FE-side
  DraftStatus status = 5;
  google.protobuf.Timestamp created_at = 6;
  google.protobuf.Timestamp committed_at = 7;
  string commit_artifact_ref = 8;    // populated on commit
}
enum DraftKind {
  DRAFT_CLIENT = 0;
  DRAFT_PORTFOLIO = 1;
  DRAFT_TRANSACTION = 2;
  DRAFT_BALANCE_ENTRY = 3;
  DRAFT_LOADER_RUN_COMMIT = 4;
  DRAFT_RECONCILIATION_DECISION = 5;
  DRAFT_TRANSACTION_BATCH = 6;       // bulk-grid commit (S3) — added in the Sysifos arc
  DRAFT_ASSET = 7;                   // quick-create modal (S6) — added in the Sysifos arc
}
enum DraftStatus { DRAFT_PENDING = 0; DRAFT_VALIDATING = 1; DRAFT_COMMITTED = 2; DRAFT_REJECTED = 3; DRAFT_COMMITTING = 4; }

// =========================================================================
// Stream events
// =========================================================================

message SysifosStreamEvent {
  oneof event {
    DraftAck draft_ack = 1;
    DraftCommitted draft_committed = 2;
    DraftRejected draft_rejected = 3;
    LoaderProgress loader_progress = 4;
    LoaderPreviewReady loader_preview_ready = 5;
    org.tatrman.kantheon.envelope.v1.Block envelope_block = 6;
    SysifosError error = 7;
    BatchRowResult batch_row_result = 8;     // per-row outcome streaming for the bulk grid (S3)
    SessionHeartbeat heartbeat = 9;
  }
}

message DraftAck         { string draft_id = 1; google.protobuf.Timestamp at = 2; }
message DraftCommitted   { string draft_id = 1; string artifact_ref = 2; int32 committed_count = 3; int32 skipped_count = 4; }
message DraftRejected    { string draft_id = 1; string reason = 2; repeated FieldValidationError errors = 3; }
message FieldValidationError { string field = 1; string code = 2; string message = 3; int32 row_index = 4; }   // row_index for grid/import (-1 = single)

message LoaderProgress      { string loader_run_id = 1; LoaderProgressPhase phase = 2; int32 rows_processed = 3; int32 rows_total = 4; }
enum   LoaderProgressPhase  { LP_PARSING = 0; LP_MAPPING = 1; LP_DIFFING = 2; LP_PREVIEW_READY = 3; LP_COMMITTING = 4; LP_DONE = 5; }
message LoaderPreviewReady  { string loader_run_id = 1; int32 new_count = 2; int32 duplicate_count = 3; int32 error_count = 4; string preview_url = 5; }

message BatchRowResult { string draft_id = 1; int32 row_index = 2; BatchRowOutcome outcome = 3; string transaction_id = 4; string message = 5; }
enum BatchRowOutcome { BR_COMMITTED = 0; BR_SKIPPED = 1; BR_FAILED = 2; }

message SessionHeartbeat { string session_id = 1; google.protobuf.Timestamp at = 2; }
message SysifosError { string code = 1; string message = 2; }

// =========================================================================
// Form payload schemas (JSON-encoded into Draft.payload_json)
// =========================================================================
// Source of truth for FE Zod schemas. Each *Form maps 1:1 to a Draft.

message ClientForm {
  string name = 1;
  string contact_email = 2;
  string contact_phone = 3;
}

message PortfolioForm {
  string client_id = 1;
  string name = 2;
  string base_currency = 3;
  org.tatrman.kantheon.midas.v1.PortfolioType portfolio_type = 4;
  google.protobuf.Timestamp inception_date = 5;
  bool track_cash = 6;                // S2 — drives cash-leg derivation; default true
}

message AssetForm {                   // S6 quick-create modal
  string symbol = 1;
  string name = 2;
  org.tatrman.kantheon.midas.v1.AssetKind kind = 3;
  string currency = 4;
  string isin = 5;                    // optional
  string exchange = 6;                // optional
}

message TransactionForm {
  string portfolio_id = 1;
  string asset_id = 2;
  org.tatrman.kantheon.midas.v1.TransactionKind kind = 3;
  google.protobuf.Timestamp trade_date = 4;
  google.protobuf.Timestamp settle_date = 5;
  string quantity = 6;
  org.tatrman.kantheon.midas.v1.Money price = 7;
  org.tatrman.kantheon.midas.v1.Money fee = 8;
  org.tatrman.kantheon.midas.v1.Money tax = 9;
  string currency = 10;
  string note = 11;
  // NB: the security leg only. The cash counter-leg is derived in midas-core (S2).
}

message TransactionBatchForm {        // S3 bulk grid
  string portfolio_id = 1;            // grid is scoped to one portfolio
  repeated TransactionForm rows = 2;
  bool skip_existing = 3;
}

message BalanceEntryForm {
  string portfolio_id = 1;
  string asset_id = 2;
  string target_quantity = 3;
  google.protobuf.Timestamp as_of = 4;
  string reason = 5;
}

message ReconciliationDecisionForm {
  string diff_key = 1;
  org.tatrman.kantheon.midas.v1.ReconcileStatus status = 2;
  string note = 3;
}
```

> **Drift from the Midas-arc original:** four additions, all Sysifos-driven — `DRAFT_TRANSACTION_BATCH` + `DRAFT_ASSET` (DraftKind), `DRAFT_COMMITTING` (DraftStatus), `BatchRowResult`/`SessionHeartbeat` (stream events), `FieldValidationError.row_index`, `AssetForm`, `TransactionBatchForm`, `ReconciliationDecisionForm`, and `PortfolioForm.track_cash`. These were implied by S3/S5/S6 and are made explicit here as the owning arc.

---

## 2. Consumed Midas-core contracts

Sysifos calls these — defined authoritatively in [`../midas/contracts.md`](../midas/contracts.md). Listed here so the BFF proxy table (§3) is unambiguous. **Do not restate; cite.**

| Concern | Midas-core surface | Section |
|---|---|---|
| Clients CRUD | `/api/v1/clients*` | §2.1 |
| Portfolios CRUD | `/api/v1/portfolios*` | §2.2 |
| Assets CRUD | `/api/v1/assets*` | §2.3 |
| Transactions (insert / batch / edit / list) | `/api/v1/transactions*` | §2.4 |
| Balance entry preview + commit | `/api/v1/balance-entries:*` | §2.5 |
| Positions (read) | `/api/v1/portfolios/{id}/positions` | §2.6 |
| Reconciliation | `/api/v1/reconcile*` | §2.8 |
| Excel loader lifecycle | loader `/api/v1/uploads`, `/runs/*` | §4.1 |
| Domain types | `midas/v1.*` | §1.1 |

---

## 3. Sysifos-BFF API

Base path `/api/v1` on the BFF. All requests carry `Authorization: Bearer <jwt>`; the BFF derives `X-Tenant-Id` from the JWT claim and forwards it to Midas-core. FE↔BFF wire is canonical JSON of the `sysifos/v1` types.

### 3.1 Session

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/sessions` | — | `{ session_id }` |
| GET | `/sessions/current` | — | `SysifosSession` |

### 3.2 Drafts (async write surface — bulk + import + quick paths that opt in)

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/drafts` | `Draft` (with `payload_json`) | `202 { draft_id, status: PENDING }` |
| GET | `/drafts/{id}` | — | `Draft` |

Drafts commit asynchronously; clients subscribe to `/stream` (§3.6) for `DraftAck` / `DraftCommitted` / `DraftRejected` / `BatchRowResult`. `DRAFT_TRANSACTION_BATCH` rows stream `BatchRowResult` per row.

### 3.3 Sync CRUD proxies (reads + single-record writes)

Mirror Midas-core REST (§2), prefixed `/midas`; the BFF injects `X-Tenant-Id`. Reads always sync. Single-record writes go here too (hybrid model S5 — sync path); only bulk/import use `/drafts`.

| Method | Path | Forwards to (Midas-core) |
|---|---|---|
| GET / POST / PATCH | `/midas/clients*` | `/api/v1/clients*` |
| GET / POST / PATCH | `/midas/portfolios*` | `/api/v1/portfolios*` |
| GET / POST / PATCH | `/midas/assets*` | `/api/v1/assets*` (POST = quick-create; write gated `midas:admin` for the Assets screen, ungated for quick-create within an entry flow) |
| GET / POST / PATCH / DELETE | `/midas/transactions*` | `/api/v1/transactions*` |
| GET | `/midas/portfolios/{id}/positions` | `/api/v1/portfolios/{id}/positions` |
| POST | `/midas/balance-entries:preview` | `/api/v1/balance-entries:preview` |
| POST | `/midas/balance-entries:commit` | `/api/v1/balance-entries:commit` |
| POST | `/midas/reconcile` | `/api/v1/reconcile` |
| POST | `/midas/reconcile/{diff_id}/decision` | `/api/v1/reconcile/{diff_id}/decision` |

> **Server-side role gate.** Midas-core applies no RBAC (v1), so the BFF enforces it: `GET /midas/audit*` and asset *edits* (`PATCH`/`DELETE /midas/assets*`) require `midas:admin` (403 otherwise) — not FE-only. `POST /midas/assets` stays ungated because the admin Assets screen and the ungated in-flow quick-create share that endpoint and can't be told apart by path; it remains FE-gated by context.

### 3.4 Read fan-out (screen assembly)

Some screens need several Midas-core calls; the BFF fans out + assembles to cut FE round-trips.

| Method | Path | Assembles |
|---|---|---|
| GET | `/screens/transactions?portfolio_id=&page=&...` | portfolio list + asset dictionary + transactions page (with linked cash sub-rows grouped) |
| GET | `/screens/import/{loader_run_id}` | loader run + preview rows + per-row diff vs existing |

### 3.5 Loader proxy

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/loaders/excel/uploads` | multipart (`file`, `broker_id`, `portfolio_id`) | proxied `{ loader_run_id, status_url }` |
| GET | `/loaders/excel/runs/{id}` | — | proxied `LoaderRun` |
| GET | `/loaders/excel/runs/{id}/preview` | — | proxied `LoaderPreview` |
| POST | `/loaders/excel/runs/{id}/commit` | `{ skip_existing, confirm }` | proxied `BatchInsertTransactionsResponse` |
| GET | `/loaders/*/runs` | query: `portfolio_id, from, to, page, size` | proxied `ListLoaderRunsResponse` |

> **The upload response is ad-hoc (non-proto) snake_case** `{ loader_run_id, status_url }`, forwarded verbatim — the FE maps it to camelCase at the edge (`uploadStatement`), unlike the proto-JSON screens.
>
> **Commit is whole-run.** The commit contract has no per-row selection: NEW rows are imported and duplicates/errors are skipped (`skip_existing`). The preview is therefore read-only (no per-row include toggle); an ERROR-row symbol correction (quick-create) re-diffs on the next preview fetch.

### 3.6 Stream

| Method | Path | Notes |
|---|---|---|
| GET | `/stream` | SSE; emits `SysifosStreamEvent`s for the calling session. `SessionHeartbeat` every 30s. |

> **Import progress is polled in v1.** The Excel loader does not push `LoaderProgress` / `LoaderPreviewReady` onto the session bus yet, so the Import screen drives its progress bar + preview-ready transition by polling `GET /loaders/excel/runs/{id}` (status → phase). The two stream events + their FE handlers stay reserved for when a loader push channel lands.

### 3.7 Dictionaries (cached at BFF — §5)

| Method | Path | Notes |
|---|---|---|
| GET | `/dictionaries/brokers` | upload UI; from loader registry |
| GET | `/dictionaries/currencies` | ISO 4217 |
| GET | `/dictionaries/transaction-kinds` | enum values + cs/en labels |
| GET | `/dictionaries/asset-kinds` | enum values + cs/en labels |

### 3.8 Standard endpoints

`/health` (200 if alive), `/ready` (200 if Midas-core reachable + JWKS loaded), `/metrics` (Prometheus).

---

## 4. Validation rule manifest

`shared` declarative rules, the single source generating FE Zod (layer 1) + BFF pre-flight (layer 2). Authoritative semantics still live in Midas-core (layer 3). File: `agents/sysifos-bff/src/main/resources/validation-rules.yaml`, copied to the FE build via codegen.

```yaml
# validation-rules.yaml — generated into Zod (FE) + a Ktor pre-flight validator (BFF).
TransactionForm:
  portfolio_id: { required: true, type: uuid }
  asset_id:     { required: true, type: uuid }
  kind:         { required: true, enum: midas.v1.TransactionKind }
  trade_date:   { required: true, type: date, max: today }
  settle_date:  { required: false, type: date, gte_field: trade_date }
  quantity:     { required: true, type: decimal, ne: 0 }
  price.amount: { required: true, type: decimal, gte: 0 }
  fee.amount:   { required: false, type: decimal, gte: 0 }
  currency:     { required: true, type: iso4217 }
ClientForm:
  name:          { required: true, max_len: 200 }
  contact_email: { required: false, type: email }
PortfolioForm:
  client_id:     { required: true, type: uuid }
  name:          { required: true, max_len: 200 }
  base_currency: { required: true, type: iso4217 }
  track_cash:    { required: false, type: bool, default: true }
BalanceEntryForm:
  portfolio_id:    { required: true, type: uuid }
  asset_id:        { required: true, type: uuid }
  target_quantity: { required: true, type: decimal, gte: 0 }
  as_of:           { required: true, type: date, max: today }
AssetForm:
  symbol:   { required: true, max_len: 32 }
  name:     { required: true, max_len: 200 }
  kind:     { required: true, enum: midas.v1.AssetKind }
  currency: { required: true, type: iso4217 }
```

Cross-field rules needing data (asset exists, portfolio active) are BFF-only (layer 2); they cannot be Zod-expressed and are not duplicated FE-side.

---

## 5. Dictionaries

Cached in the BFF (TTL 10 min) and in Pinia FE-side:

- **brokers** — `[{ broker_id, display_name }]` from the Excel loader's `BrokerRegistry`.
- **currencies** — ISO 4217 list (static resource).
- **transaction-kinds** — `midas.v1.TransactionKind` values + `{ cs, en }` labels (incl. the new `TX_CASH_*` shown read-only — cash legs are derived, not user-entered).
- **asset-kinds** — `midas.v1.AssetKind` values + `{ cs, en }` labels.

---

## 6. Midas-core cash-leg behaviour (pointer)

Defined authoritatively in [`../midas/contracts.md`](../midas/contracts.md) §1.1.A — **folded into the Midas baseline** (Midas is greenfield; not a forward migration). Summary of what Sysifos relies on:

- `midas/v1.TransactionKind` includes `TX_CASH_CREDIT = 9`, `TX_CASH_DEBIT = 10`.
- `midas/v1.Portfolio.track_cash = 12` (default true); DDL `portfolios.track_cash BOOLEAN NOT NULL DEFAULT true` in V0001.
- `midas/v1.Transaction.correlation_id = 20`; derived cash legs carry `source = TX_SRC_DERIVATION` and the `correlation_id` of their security leg.
- Midas-core `POST /transactions` and `:batch` derive the cash leg server-side when `portfolio.track_cash` is true; the response includes both legs.
- Auto-provisioned `ASSET_CASH` per `(portfolio, currency)`.

Sysifos sends the security leg only and renders both. **This behaviour must be live in Midas-core (Midas Stage 1.3) before Sysifos Stage 2.2.**

---

## 7. Error envelope

Reuses the constellation error model. BFF surfaces Midas-core errors transparently; its own errors use `SysifosError { code, message }` (sync) or `DraftRejected { reason, errors[] }` (async). Standard codes:

| Code | Meaning | Surface |
|---|---|---|
| `AUTH_INVALID_JWT` | bad / expired token | 401 |
| `TENANT_MISMATCH` | JWT tenant ≠ requested | 403 |
| `VALIDATION_FAILED` | pre-flight or core rejection | `DraftRejected` / 400 |
| `MIDAS_UNAVAILABLE` | Midas-core unreachable | 502 |
| `DUPLICATE_EXTERNAL_ID` | idempotency hit on import | preview `DUPLICATE` / 409 |
| `UNKNOWN_ASSET` | symbol not in dictionary | triggers quick-create (not an error to the user) |

---

*Contracts doc owner: Bora. Lives in `docs/architecture/sysifos/`. Source of truth for `sysifos/v1` + the Sysifos-BFF API. Update before the code; revision history via git.*
