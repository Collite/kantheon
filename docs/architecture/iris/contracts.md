# Iris — Wire Contracts (kantheon arc, Phases 1–3)

> **Companions.** [`architecture.md`](./architecture.md), [`../../implementation/v1/iris/plan.md`](../../implementation/v1/iris/plan.md), [`../themis/contracts.md`](../themis/contracts.md) (themis/v1 + RoutingPickChip origin).
>
> **Authority.** This document is the source of truth for `org.tatrman.kantheon.envelope.v1` (constellation-wide — Golem and Pythia contracts reference it) and `org.tatrman.kantheon.iris.v1`. Task lists referencing these contracts must match them exactly.
>
> **Compatibility rule.** `envelope/v1` is derived field-for-field from the proven **FormatEnvelope v2 (Stage 07-B)** contract in `ai-platform/frontends/agents-fe/src/types/envelope.ts` and `ai-platform/agents/golem/src/api/v2/models.py`. Recorded v2 JSON envelopes MUST parse through the generated `envelope-ts` bindings unchanged (golden-sample CI gate). Additions are allowed; renames are not.

## 1. Proto packages

### 1.1 `org.tatrman.kantheon.envelope.v1` (full definition — Phase 1 Stage 1.1)

File: `shared/proto/src/main/proto/org/tatrman/kantheon/envelope/v1/envelope.proto`

```proto
syntax = "proto3";
package org.tatrman.kantheon.envelope.v1;

// Cohesion review 2026-06-12: envelope/v1 imports ONLY common/v1 (the declared
// bottom layer). AgentId moved themis/v1 → common/v1 (D2) so RoutingPickChip
// does not drag themis/v1 (and transitively cz.dfpartner.nlp/metadata) into
// every envelope consumer — incl. the FE's envelope-ts bundle.
import "org/tatrman/kantheon/common/v1/handoff.proto";          // AgentId, HandoffContext, BlockProvenance
import "org/tatrman/kantheon/common/v1/response_message.proto"; // kantheon Rule-6 stand-in (kantheon-architecture §4)

// =========================================================================
// FormatEnvelope — the FE wire unit. One envelope = one chat bubble.
// Field-compatible with FormatEnvelope v2 (agents-fe Stage 07-B).
// =========================================================================
message FormatEnvelope {
  string bubble_id  = 1;
  string turn_id    = 2;
  string thread_id  = 3;                    // == Iris session_id post-transition

  optional string text         = 4;
  optional string content_json = 5;         // opaque row/payload JSON (Rule-7 style string)
  FormatSpec format            = 6;
  repeated string warnings     = 7;

  repeated Chip chips                 = 8;
  repeated Drilldown drilldowns       = 9;
  optional PendingClarification pending_clarification = 10;

  repeated EntityContextSnapshot entity_context = 11;
  optional CurrentView current_view   = 12;
  optional string update_tab_id       = 13;

  PlanSource plan_source              = 14;
  double plan_score                   = 15;
  optional string losing_plan_summary = 16;

  optional string error_code          = 17;
  string created_at                   = 18;  // ISO-8601
  string agent_version                = 19;
  optional string agent_id            = 20;  // NEW vs v2: which agent produced this (BFF-enriched)

  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}

enum PlanSource {
  PLAN_SOURCE_UNSPECIFIED = 0;
  PATTERN       = 1;
  FREE_SQL      = 2;
  AMEND         = 3;
  DRILL         = 4;
  CLARIFICATION = 5;
}

// =========================================================================
// FormatSpec + per-kind details (verbatim from v2)
// =========================================================================
message FormatSpec {
  FormatKind kind = 1;
  optional TableDetails table          = 2;
  optional ChartIntentDetails chart    = 3;
  optional MarkdownDetails markdown    = 4;
}

enum FormatKind {
  FORMAT_KIND_UNSPECIFIED = 0;
  PLAINTEXT = 1;
  MARKDOWN  = 2;
  TABLE     = 3;
  CHART     = 4;
}

message TableDetails {
  optional string alternate_colors = 1;       // None | Cols | Rows | Both
  repeated TableHeader headers     = 2;
  map<string, TableColumnSpec> columns = 3;
  optional TablePagingInfo paging  = 4;
  repeated TableSortSpec sort      = 5;
  repeated TableFilterSpec filters = 6;
}

message TableHeader        { string name = 1; string title = 2; }
message TableColumnSpec    { optional string alignment = 1; optional int32 width = 2;
                             optional bool hidden = 3; optional string format = 4; }
message TablePagingInfo    { int32 page = 1; int32 page_size = 2; optional int64 total_rows = 3; }
message TableSortSpec      { string column = 1; string direction = 2; }     // asc | desc
message TableFilterSpec    { string column = 1; string operator = 2;        // eq|neq|lt|lte|gt|gte|contains|in
                             string value_json = 3; }

message ChartIntentDetails {
  optional ChartIntent intent       = 1;
  optional string vega_lite_spec_json = 2;   // compiled spec; FE embeds directly
  // Stage-06-era loose fields kept for v2 golden-sample compatibility:
  optional string series_field  = 3;
  optional string series_column = 4;
  repeated string series        = 5;
  optional string rows_json     = 6;
}

message ChartIntent {
  string kind = 1;                            // line | bar | pie | scatter | area
  optional string title = 2;
  string x = 3;
  repeated string y = 4;
  optional string series_field = 5;
  optional bool stacked = 6;
  optional bool show_legend = 7;
  repeated string hide_series = 8;
}

message MarkdownDetails { optional bool allow_mermaid = 1; optional bool allow_images = 2; }

// =========================================================================
// Chip — oneof of conversational chip kinds
// =========================================================================
message Chip {
  oneof kind {
    PromptChip prompt        = 1;             // == v2 Chip (static/heuristic/pattern_derived/llm_topup)
    RoutingPickChip routing  = 2;             // Themis Phase 3 Stage 3.6 type, hosted here
    InvestigateChip investigate = 3;          // PD-1 (2026-06-12): escalation to Pythia
  }
}

// PD-1 (2026-06-12): escalation affordance. Click → BFF re-issues the turn with
// routing_hint = "pythia" + the embedded HandoffContext. Two emitters:
//  - Golem: on confidence-gate failure with analytical intent (may also accompany
//    a partial answer — metadata, not control flow);
//  - Iris-BFF itself: always-on "Investigate this" drilldown action on any
//    table/chart block (BFF builds the handoff from the turn it owns).
message InvestigateChip {
  org.tatrman.kantheon.common.v1.HandoffContext handoff = 1;
  string proposed_question = 2;               // prefill for the re-issued turn
  string label = 3;
}
// Size guard (cohesion review 2026-06-12, handover §4.10b): the handoff rides
// inline in the envelope — emitters cap handoff.entities at 50 bindings (most
// relevant first) and truncate suggested_focus at 1 KiB. ViewProvenance.sql is
// one statement by construction. Keeps the chip payload bounded for big
// applied_context lists without a peer artifact-read API (v1.1 ledger §1).

message PromptChip {
  string display = 1;
  string prompt  = 2;
  string source  = 3;                         // static | heuristic | pattern_derived | llm_topup
  optional string pattern_id = 4;
  optional string prefilled_args_json = 5;    // Rule-7 style
}

message RoutingPickChip {
  org.tatrman.kantheon.common.v1.AgentId agent_id = 1;  // moved from themis/v1 (D2, 2026-06-12)
  string label = 2;                           // display name from capabilities-mcp
  string why   = 3;                           // matches AgentAlternate.why verbatim
}

// =========================================================================
// Drilldown / clarification / context (verbatim from v2)
// =========================================================================
message Drilldown {
  string id = 1;
  string display = 2;
  string target_pattern_id = 3;
  map<string, string> arg_mapping = 4;
  string scope = 5;                           // row | point
  string source = 6;                          // explicit_ttr | auto_overlap
}

message PendingClarification {
  string kind = 1;                            // entity_choice | intent_choice | missing_arg
  string resume_token = 2;
  repeated ClarificationOption options = 3;
  optional string context_text = 4;
  optional string issued_by_agent_id = 5;     // NEW vs v2: BFF resume-routing (see iris/v1)
}

message ClarificationOption { string id = 1; string display = 2; optional string description = 3; }

message EntityContextSnapshot {
  string entity_type = 1;
  optional string entity_id = 2;
  string display_label = 3;
  optional int32 span_start = 4;
  optional int32 span_end = 5;
}

message CurrentView {
  optional string pattern_id = 1;
  optional string args_json = 2;
  optional string sql = 3;
  optional string bubble_id = 4;
  optional int64 total_rows = 5;
}

// =========================================================================
// Block — the agent-side renderable unit (Pythia / Golem artifacts).
// FormatEnvelope ⊇ Block: the BFF lifts one Block into one FormatEnvelope
// by adding conversation metadata. Golem/Pythia contracts reference Block.
// =========================================================================
message Block {
  string block_id = 1;
  BlockRole role  = 2;
  optional string caption = 3;
  optional string text    = 4;                // for PLAINTEXT / MARKDOWN kinds
  optional string content_json = 5;           // rows / data payload
  FormatSpec format = 6;
  // PD-9 (2026-06-12): "how was this computed". Stamped at format time by
  // envelope-render (view + agent id); Pythia adds step/hypothesis/model refs.
  // Absence renders "provenance unavailable" — never an error. Powers the ⓘ
  // popover (SQL in a collapsed expander, visible to all in v1), the iris_audit
  // payload, pin tiles (PD-6), and conclusion→hypothesis-tree links (PD-2).
  // NOTE: adds the envelope/v1 → common/v1 import (deliberate bottom-layering).
  optional org.tatrman.kantheon.common.v1.BlockProvenance provenance = 7;
}

enum BlockRole {
  BLOCK_ROLE_UNSPECIFIED = 0;
  PRIMARY  = 1;
  EVIDENCE = 2;
  SUMMARY  = 3;
  HEADING  = 4;
  CALLOUT  = 5;
  LOOSE_ENDS_SECTION = 6;
}
```

**Field-number contract.** `FormatEnvelope` 1–19 mirror v2 field order; 20+ are kantheon additions. `messages = 99` per Rule 6. Do not renumber.

**v2 → v1 lossless-mapping notes.** v2's `format.details` flattened-or-nested duality collapses to the typed `oneof`-style optionals above; the transitional adapter (§6) performs the normalisation. v2 `content` (arbitrary JSON) maps to `content_json` (string) — Rule-7 style; `envelope-ts` re-exposes it parsed.

### 1.2 `org.tatrman.kantheon.iris.v1` (new — Phase 1 Stage 1.1)

File: `shared/proto/src/main/proto/org/tatrman/kantheon/iris/v1/iris.proto`

```proto
syntax = "proto3";
package org.tatrman.kantheon.iris.v1;

import "org/tatrman/kantheon/envelope/v1/envelope.proto";
import "org/tatrman/kantheon/common/v1/handoff.proto";          // ViewProvenance, EntityBinding (TurnPointer)
import "org/tatrman/kantheon/common/v1/response_message.proto"; // kantheon Rule-6 stand-in
// Deliberately does NOT import themis/v1, golem/v1, pythia/v1 (kantheon-architecture §4).

// ---- Session surface ----
message Session {
  string session_id = 1;
  string user_id    = 2;
  string tenant_id  = 3;
  repeated org.tatrman.kantheon.envelope.v1.EntityContextSnapshot entity_context = 4;
  repeated TurnPointer turns = 5;
  string created_at = 6;
  string updated_at = 7;
}

message TurnPointer {
  string turn_id      = 1;
  string agent_id     = 2;              // "pythia" | "golem-erp" | "golem-v2" (transitional) | ...
  string artifact_ref = 3;              // agent-side per-turn record pointer
  repeated string displayed_block_ids = 4;
  optional string question = 5;         // verbatim user text (for excerpt building)
  string created_at = 6;
  // PD-1/PD-4 (2026-06-12): per-turn snapshot of what the agent echoed.
  // current_view is agent-owned; the BFF only snapshots and forwards.
  optional org.tatrman.kantheon.common.v1.ViewProvenance current_view = 7;
  repeated org.tatrman.kantheon.common.v1.EntityBinding applied_context = 8;
}

// PD-1 assembly rule (2026-06-12): on EVERY dispatch the BFF builds a
// common.v1.HandoffContext from the previous TurnPointer (source_agent_id,
// source_turn_ref=artifact_ref, user_question, current_view, applied_context)
// + the session EntityContext, and sends it (a) to Themis inside ResolveRequest
// (this IS themis_prior_context) and (b) to the routed agent's request.
// PD-4: the BFF compares each response's applied_context against what it sent —
// scope indicator in the FE; warning bubble on mismatch.

// ---- Chat surface ----
message ChatTurnRequest {
  string session_id = 1;
  string question   = 2;
  optional string routing_hint_agent_id = 3;   // RoutingPickChip click reissue
  optional string desired_format = 4;          // /format slash arming
  optional bool dry_run = 5;                   // /sql slash arming
  // Hebe co-design, landed 2026-06-12 (cohesion review; hebe/contracts.md §3.1):
  // scheduled turns are persisted/routed/rendered exactly like user turns;
  // origin is metadata for the session log, inbox badge, audit, analytics.
  // iris-bff must NOT gate on it.
  TurnOrigin origin       = 6;                 // default USER
  optional string origin_ref = 7;              // routine_id for SCHEDULED; empty otherwise
}

enum TurnOrigin {
  TURN_ORIGIN_UNSPECIFIED = 0;
  USER                    = 1;
  SCHEDULED               = 2;                 // Hebe routine
}

message ChatResumeRequest {
  string session_id   = 1;
  string resume_token = 2;
  oneof answer {
    string selected_option_id = 3;
    string free_text_answer   = 4;
  }
}

message TypedActionRequest {
  string session_id = 1;
  string bubble_id  = 2;                       // which envelope the action targets
  TypedAction action = 3;
}

message TypedAction {
  string kind = 1;          // sort | filter | paginate | select_row | chip_invocation | edit_resend
  string payload_json = 2;  // Rule-7 style; schema per kind documented in §2.4
}

// ---- Stream events (SSE payloads; one JSON-serialised IrisStreamEvent per SSE event) ----
message IrisStreamEvent {
  string turn_id = 1;
  int64 sequence = 2;                          // monotone per turn
  oneof event {
    org.tatrman.kantheon.envelope.v1.FormatEnvelope envelope = 3;
    StepEvent step          = 4;
    ToolCallEvent tool_call = 5;
    ThinkingEvent thinking  = 6;
    ErrorEvent error        = 7;
    DoneEvent done          = 8;
  }
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}

message StepEvent     { string node = 1; string phase = 2;        // started | completed | failed
                        optional string summary = 3; optional string detail_json = 4;
                        optional int64 latency_ms = 5; }
message ToolCallEvent { string tool = 1; string phase = 2; optional string summary = 3; }
message ThinkingEvent { string text_delta = 1; }
message ErrorEvent    { string code = 1; string message = 2; bool recoverable = 3; }
message DoneEvent     { string outcome = 1;                       // done | failed | clarification
                        optional string turn_pointer_json = 2; }
```

## 2. iris-bff REST + SSE surface

All endpoints bearer-authenticated (Keycloak). Headers: `Authorization`, `X-Correlation-Id` (generated if absent), `X-Session-Id` where noted. Proto-shape JSON per the wire policy.

### 2.1 Session endpoints

| Endpoint | Method | Body → Response |
|---|---|---|
| `/v1/session` | POST | `{}` → `Session` (fresh; static chips included in first `GET`) |
| `/v1/session/{id}` | GET | → `Session` (with turns; envelopes hydrated from `iris_turns` on demand) |
| `/v1/sessions` | GET | → `[SessionSummary]` for the authenticated user |
| `/v1/session/{id}/reset` | POST | → `Session` (snapshot taken, turns cleared) |
| `/v1/session/{id}/turn/{turnId}` | GET | → stored envelope(s) for one turn (history hydration) |

### 2.2 Chat endpoints

| Endpoint | Method | Notes |
|---|---|---|
| `/v1/chat/turn` | POST | `ChatTurnRequest` → terminal `IrisStreamEvent.envelope` JSON (sync convenience; not used by SPA) |
| `/v1/chat/stream` | POST | `ChatTurnRequest` → `text/event-stream` of `IrisStreamEvent` (SSE event name = oneof case name) |
| `/v1/chat/resume` | POST | `ChatResumeRequest` → `text/event-stream` (routed to the clarification issuer) |
| `/v1/action` | POST | `TypedActionRequest` → `text/event-stream` (no LLM; re-issued query against producing agent) |

### 2.3 SSE framing

```
event: step
data: {"turnId":"...","sequence":3,"step":{"node":"execute","phase":"started"}}

event: envelope
data: {"turnId":"...","sequence":9,"envelope":{...FormatEnvelope JSON...}}

event: done
data: {"turnId":"...","sequence":10,"done":{"outcome":"done"}}

: heartbeat          ← comment frame every 15s on idle
```

### 2.4 TypedAction payload schemas (`payload_json`)

| kind | payload |
|---|---|
| `sort` | `{ "column": str, "direction": "asc"\|"desc" }` |
| `filter` | `{ "column": str, "operator": str, "value": any }` |
| `paginate` | `{ "page": int, "pageSize": int }` |
| `select_row` | `{ "rowIndex": int, "rowKey": any }` |
| `chip_invocation` | `{ "patternId": str?, "prompt": str, "prefilledArgs": obj? }` |
| `edit_resend` | `{ "editedQuestion": str, "fromTurnId": str }` |
| `reask_agent` | **PD-14 (2026-06-12):** `{ "turnId": str, "targetAgentId": str }` — re-issues the turn with `routing_hint = targetAgentId` (Layer 0) AND upserts `iris_feedback.corrected_agent_id` (the strongest misroute label, PD-3). FE: persistent **agent badge** on every answer bubble (display name from the BFF's capabilities cache); badge menu "re-ask with a different agent" opens a picker of the role-filtered routable agents, **pre-sorted by the original `RoutingDecision.alternates` with their `why` strings** |

`sort`/`filter`/`paginate` re-issue against the producing agent's typed-action surface (transitionally new-golem; natively Golem's `/v1/action`) and emit a replacing envelope with the same `bubble_id`. `edit_resend` snapshots, discards turns after `fromTurnId`, and re-enters the normal turn flow (Phase 3: re-routes through Themis).

### 2.5 Multi-question decomposition rule (PD-13, 2026-06-12)

When Themis returns `MultiQuestionDetected`, Iris decomposes UI-side into N follow-up turns **only when `decomposition = SPLIT`**. On `KEEP_TOGETHER` (relating intent: compare / correlate / explain-by / rank-across) the original turn proceeds whole — Themis routes it as one cross-domain question (typically → Pythia). The FE may show `decomposition_rationale` as a hint bubble.

### 2.6 Discovery — "what can I ask?" (PD-7, 2026-06-12)

`GET /v1/discover` → `{ domains: [DomainCard] }` — per-domain cards assembled from the BFF's capabilities cache, **filtered by the caller's `visibility_roles`** (PD-8: you only discover what you may ask; `non_routable` entries excluded). `DomainCard` = `{ agent_id, display_name, blurb (from description_for_router), example_questions: [str] }` (`example_questions` is the v1 source; `preferred_queries` lack display text — v1.1). Two FE surfaces: the first-run / empty-session panel ("Here's what I can answer about ERP, HR, Investment…") and suggested-question chips on an empty input box. Clicking a question chip submits it as a normal turn.

### 2.7 Investigation inbox (PD-2 resolution, 2026-06-12)

The inbox is a **view over Pythia's persisted state** — no Iris-side store. iris-bff aggregates `pythia GET /v1/investigations?user_id=…` and joins session names / turn refs / `TurnOrigin` badges from its own `iris_turns`. Investigations only in v1 (`agent_kind = INVESTIGATOR`); the surface does not preclude widening later.

| Endpoint | Method | Body → Response |
|---|---|---|
| `/v1/inbox` | GET | → `{ items: [InboxItem], counts: { running, needs_input } }` — `InboxItem` = investigation summary ⋈ {session_id, session_title, turn_id, origin (USER\|SCHEDULED)} |
| `/v1/inbox/stream` | GET (SSE) | user-scoped `inbox_event` push; BFF subscribes `pythia.lifecycle.{user_id}` on NATS and fans out (polling `/v1/inbox` is the degraded fallback if NATS is down) |

Status mapping (Pythia's 12 → 5 user-facing): `SUBMITTED`/`RESOLVING`/`PLANNING`/`EXECUTING` → **Running**; all five `AWAITING_*` (incl. `AWAITING_BUDGET_DECISION`, PD-11) → **Needs your input**; `DONE` → **Done**; `FAILED` → **Failed**; `HALTED` → **Cancelled** (partial conclusion rendered — Pythia halt = cancel-with-partials). *(Enum names per `Pythia-v1-Design.md` §3.4, the semantic authority — cohesion review 2026-06-12 fixed the earlier "CONCLUDED" drift.)*

FE: header badge (Running + Needs-input count) + dockview panel. Rows: question, status, elapsed, cost-so-far (`resource_usage`), session link. Row click → open session + reattach via Pythia `/events?from_seq=N` (replay-then-live). Needs-input rows land on the pending clarification (existing chat clarification UI; control calls go to Pythia's per-state endpoints via BFF proxy).

**Hypothesis-tree pane (debug-grade, in scope per Bora 2026-06-12):** a collapsible tree rendered from `InvestigationArtifact.hypotheses` (`parent_id` nesting; per node: statement, `HypStatus`, confidence, rationale, `display_priority`; expandable evidence links + flat `test_step_ids` step list with status/cost). Live-updates from the hypothesis/execution events on the investigation stream. Deliberately *not* product-polished: it is the debugging window into the investigator. The plan-DAG pane stays out of scope — steps appear as flat lists per hypothesis.

### 2.8 Artifacts — pins & dashboards (PD-6 resolution, 2026-06-12)

A **pin** is a saved, refreshable view of any envelope: captured as {envelope snapshot, `common.v1.ViewProvenance`, producing `agent_id`, `applied_context` (EntityBindings), BFF display state (`current_display` slice)}. A **dashboard** is a named collection of pins + `layout_json` (+ optional `template_id` — templates are domain-supplied content, e.g. Midas's `investment-overview:v1`). Per-user in v1; refs stable + unguessable (PD-15 constraint).

| Endpoint | Method | Notes |
|---|---|---|
| `/v1/artifacts` | POST | pin from a turn: `{ turn_id, bubble_id, name }` — BFF assembles the capture |
| `/v1/artifacts` | GET | list (kind filter) |
| `/v1/artifacts/{id}` | GET / PATCH / DELETE | PATCH: rename, edit `params_json` ("same chart, Q3" — PD-10 layer 1), layout |
| `/v1/artifacts/{id}/refresh` | POST | deterministic re-execution, **never an LLM call**: Golem-kind pins → producing agent's typed-action surface, then re-apply display state; Pythia-kind pins → `replay` (param_mode MOVING) or `reproduce` (FROZEN). Failure → explicit stale/error state on the pin — never silently wrong |
| `/v1/dashboards/{id}/open` | GET (SSE) | parallel per-pin refresh per `refresh_mode` (`on_open`) with per-pin envelope/error events |

`refresh_mode: manual | on_open` in v1 (`scheduled` deferred to v1.1 as a Hebe routine kind `artifact_refresh` — Hebe owns scheduling + bound-user OBO). Refresh runs under the owner's OBO token. Pin tiles render refreshed-at + PD-9 provenance ⓘ + PD-4 scope indicator. Audited as `event_kind: artifact_refresh` when PD-8's audit sees data access.

**Midas reframe (Bora-approved 2026-06-12):** this generic system supersedes the Midas-arc dashboard design (`midas/architecture.md` §11, `midas/contracts.md` §7 — `agent_call_spec` panes are replaced by `ViewProvenance` capture). Midas Phase 3 Stage 3.5 becomes a *consumer*: domain templates + Golem-Investment content.

### 2.9 Turn feedback (PD-3 resolution, 2026-06-12)

`POST /v1/turns/{turn_id}/feedback` → `{ verdict: "up"|"down", reason?: "wrong_data"|"wrong_agent"|"wrong_format"|"too_slow"|"other", comment? }`. Upsert per `(turn_id, user_id)` — users may change their mind. FE: 👍/👎 on every answer bubble; one-tap reason picker on 👎. PD-14's "re-ask with different agent" action additionally records `corrected_agent_id` (the strongest misroute label — the user's actual correction). Capture is synchronous; **no agent sees feedback at runtime** — it exports offline to per-agent `eval/candidates/` directories via `just feedback-export` (per-agent schema adapters; human curation before promotion into gate corpora). Metric: `feedback_total{agent_id, verdict, reason}`.

## 3. Persistence shapes (Postgres, Flyway-managed)

```sql
CREATE TABLE iris_sessions (
  session_id      UUID PRIMARY KEY,
  user_id         TEXT NOT NULL,
  tenant_id       TEXT NOT NULL,
  entity_context  JSONB NOT NULL DEFAULT '[]',
  current_display JSONB NOT NULL DEFAULT '{}',     -- BFF-owned rendering choices
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE iris_turns (
  turn_id        UUID PRIMARY KEY,
  session_id     UUID NOT NULL REFERENCES iris_sessions(session_id),
  seq            INT  NOT NULL,                     -- order within session
  agent_id       TEXT NOT NULL,
  artifact_ref   TEXT,                              -- agent-side pointer (null for /v2 transitional)
  question       TEXT NOT NULL,
  envelope_json  JSONB,                             -- snapshot/cache of terminal envelope(s)
  displayed_block_ids TEXT[] NOT NULL DEFAULT '{}',
  pending_resume_token TEXT,                        -- set while a clarification is open
  resume_issuer_agent_id TEXT,
  status         TEXT NOT NULL,                     -- done | failed | clarification | discarded
  origin         TEXT NOT NULL DEFAULT 'user',      -- user | scheduled (TurnOrigin; inbox badge join)
  origin_ref     TEXT,                              -- Hebe routine_id when origin = scheduled
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (session_id, seq)
);

CREATE TABLE iris_snapshots (
  snapshot_id    UUID PRIMARY KEY,
  session_id     UUID NOT NULL REFERENCES iris_sessions(session_id),
  reason         TEXT NOT NULL,                     -- reset | edit_resend
  entity_context JSONB NOT NULL,
  turn_ids       UUID[] NOT NULL,                   -- turns visible at snapshot time
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Discarded turns are status-flipped, never deleted (undo restores from snapshot). Transitional mapping table `iris_v2_threads(session_id UUID PK, v2_thread_id TEXT)` keeps the 1:1 session↔new-golem-thread binding; dropped at Golem cutover.

### 3.1 Audit log (PD-8, 2026-06-12)

Hash-chained append-only audit, reusing the Hebe receipts shape verbatim (one chained-log format constellation-wide — see [`kantheon-security.md`](../kantheon-security.md) §4):

```sql
CREATE TABLE iris_audit (
  seq        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ts         TIMESTAMPTZ NOT NULL,
  user_id    TEXT NOT NULL,
  event_kind TEXT NOT NULL,        -- turn | typed_action | export | resume | escalation | artifact_refresh
                                   --   (artifact_refresh added 2026-06-12 cohesion review, per §2.8;
                                   --    reask_agent audits as typed_action — not a distinct kind)
  payload    JSONB NOT NULL,       -- question, RoutingDecision, agent_id, applied_context,
                                   -- ViewProvenance (pattern_id/sql/args/total_rows), refs
  segment    TEXT NOT NULL,        -- "YYYY-MM" — retention + verification unit
  prev_hash  TEXT NOT NULL,        -- self_hash of seq-1; segment header anchors to previous
                                   -- segment's terminal hash
  self_hash  TEXT NOT NULL,        -- sha256(canonical(payload) + prev_hash)
  sig        TEXT NOT NULL         -- Ed25519 over self_hash (iris-bff signing key)
);
-- app role: INSERT + SELECT only
```

Written at turn finalization and on typed actions / exports / resumes / `InvestigateChip` escalations. Retention: `iris.audit.retention_months` (default unlimited); expiry archives + drops **whole monthly segments**, keeping later segments verifiable. Verify endpoint: `GET /v1/audit/verify?segment=YYYY-MM` (admin role).

**Signing key + writer (Stage 1.4 A1/A2).** The live writer is `ExposedAuditStore` (Postgres); `append` takes a `pg_advisory_xact_lock` so the `prev_hash` read + insert is atomic against the chain tail (race-free under REPEATABLE READ), and `seq` is read back from the DB IDENTITY. The Ed25519 keypair is loaded from `iris.audit.signing-key-ref` (`Ed25519Signer.fromKeyRef`): a **mounted K8s Secret file path** (preferred) or inline PEM, holding **both** a PKCS#8 `PRIVATE KEY` and the matching X.509 `PUBLIC KEY` block (the public is required to verify the chain and is not derivable from an Ed25519 private via the standard JCA API). A ref that is set but unreadable/malformed is a **hard boot error** — never a silent fall-back to an ephemeral key, which would orphan the existing chain. Unset ⇒ ephemeral dev keypair (warning logged); production MUST set the ref. **Runbook (stub):** rotate by issuing a new Secret + restarting; the chain stays verifiable per segment because each segment is anchored independently — verify with `GET /v1/audit/verify?segment=` before archiving a rotated-out segment.

**Audit-field derivability against the forked data edge (paper-check, fork Stage 3.6 T5).** Confirmed every `iris_audit.payload` field is derivable at turn finalization with the surfaces as forked, with one bounded caveat:

| Payload field | Source | Status |
|---|---|---|
| `question` | inbound `ChatTurnRequest` (BFF-owned) | ✓ |
| `RoutingDecision`, `agent_id` | Themis response (BFF-owned) | ✓ |
| `applied_context` | response envelope `TurnPointer.applied_context`; PD-4 echo-compare | ✓ |
| ViewProvenance `pattern_id`, `args_json` | agent-side — the pattern/args the agent chose and sent to theseus-mcp | ✓ |
| ViewProvenance `sql` | theseus-mcp **`compile`** returns `compiledSql`; the **`query`** tool does **not** echo the executed/translated DB SQL (only the caller's `source`, which equals the SQL only when `source_language = sql`) | ⚠ gap on the direct-query path |
| ViewProvenance `total_rows` | `query` returns `rowCount` (post-row-limit) + `truncated`; the exact full count when truncated is not surfaced | ⚠ approximate when truncated |

The `sql`/`total_rows` caveats are **not blocking** — an agent that compiles-then-runs (the plan-cache path) gets `compiledSql` from `compile`, and v1 provenance tolerates a truncation-bounded `total_rows`. If Iris needs exact provenance SQL on the *direct* `query` path, add an additive `executedSql` (and an exact `totalRows`) echo to the `query` response — small, backward-compatible. Filed: [`../../implementation/kantheon-v1.1.md`](../../implementation/kantheon-v1.1.md) §11.

### 3.2 Feedback (PD-3, 2026-06-12 — telemetry, not audit: plain table, no hash chain)

```sql
CREATE TABLE iris_feedback (
  feedback_id  UUID PRIMARY KEY,
  turn_id      UUID NOT NULL,        -- joins iris_turns → question, agent_id; RoutingDecision via audit payload
  user_id      TEXT NOT NULL,
  agent_id     TEXT NOT NULL,
  verdict      TEXT NOT NULL,        -- up | down
  reason       TEXT,                 -- wrong_data | wrong_agent | wrong_format | too_slow | other
  comment      TEXT,
  corrected_agent_id TEXT,           -- PD-14: filled by the re-ask action
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (turn_id, user_id)
);
```

### 3.3 Artifacts (PD-6, 2026-06-12)

```sql
CREATE TABLE iris_artifacts (
  artifact_id    UUID PRIMARY KEY,
  user_id        TEXT NOT NULL,
  tenant_id      TEXT NOT NULL,
  kind           TEXT NOT NULL,        -- pin | dashboard
  name           TEXT NOT NULL,
  agent_id       TEXT,                 -- pins: producing agent
  envelope_json  JSONB,                -- pins: last rendered envelope snapshot
  provenance     JSONB,                -- pins: common.v1.ViewProvenance
  applied_context JSONB,               -- pins: EntityBindings at capture
  display_state  JSONB,                -- pins: BFF current_display slice (sort/filter)
  params_json    JSONB,                -- pins: editable bindings; dashboards: template params
  refresh_mode   TEXT NOT NULL DEFAULT 'manual',   -- manual | on_open  (scheduled = v1.1/Hebe)
  param_mode     TEXT,                 -- pythia pins: moving | frozen (replay vs reproduce)
  template_id    TEXT,                 -- dashboards: nullable (domain-supplied templates)
  member_ids     UUID[],               -- dashboards: ordered pin refs
  layout_json    JSONB,                -- dashboards
  refreshed_at   TIMESTAMPTZ,
  refresh_error  TEXT,                 -- explicit stale/error state
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```


## 4. `envelope-ts` library surface

Generated bindings (via `just proto`, ts-proto or protobuf-es — pinned in Stage 1.1) plus hand-written helpers:

```
shared/libs/ts/envelope-ts/
├── src/generated/…                  # envelope/v1 + iris/v1 TS types
├── src/parseEnvelope.ts             # defensive parser (port of agents-fe parseEnvelope; throws on missing required)
├── src/formatDirectives.ts          # %d/%f/%e/%g/%x/%s interpreter (carried from agents-fe)
└── src/index.ts
```

Renderer SFCs and `formatCatalog` stay in `frontends/iris` (UI, not contract).

## 5. Transitional `/v2` adapter mapping (GolemV2Client)

| new-golem `/v2` | iris-bff | Mapping |
|---|---|---|
| `POST /v2/session` | session create | `v2_thread_id` stored in `iris_v2_threads`; static chips forwarded |
| `POST /v2/chat/stream` (SSE: `node_start`, `node_done`, `plan_pick`, `exec_done`, `envelope`, `error`) | `/v1/chat/stream` | `node_* → step{node,phase}`; `plan_pick/exec_done → step` with `detail_json`; `envelope → envelope` (v2 JSON normalised into envelope/v1: `content→content_json`, flattened format details → typed spec, chips → `Chip.prompt`); `error → error`; stream close → `done` |
| `POST /v2/chat/resume` | `/v1/chat/resume` | pass-through `resume_token` + answer; issuer recorded as `golem-v2` |
| `POST /v2/refresh` | not exposed | ops-only; reachable via port-forward |
| Headers `X-User-ID`, `X-Correlation-Id` | from bearer + correlation | BFF injects |

The adapter is quarantined in `dispatch/golemv2/` with its own DTOs — nothing outside the package may import them. Deleted at Golem-rewrite cutover.

## 6. Configuration (application.conf keys)

```
# DB keys follow the shared db-common `DatabaseConnection` shape (host/port/database,
# not a single JDBC url) — see shared/libs/kotlin/db-common. `enabled=false` (default)
# selects the in-memory store for local boot / tests.
iris.db.{enabled, type, host, port, database, user, password}
iris.dispatch.golem-v2.{base-url, timeout-ms}            # transitional
iris.dispatch.agents.{<agent-id>.base-url, ...}          # native clients, later arcs
iris.themis.{base-url, timeout-ms}                       # Phase 3
iris.capabilities.{base-url, cache-ttl-s}
iris.stream.heartbeat-s         (default 15)             # SSE idle :heartbeat comment frame
iris.auth.{keycloak-issuer, audience, tenant-claim, default-tenant, verify-signature}

# Added 2026-06-12 (cohesion review — PD-2 / PD-8 / PD-6 surfaces):
iris.nats.{url, reconnect-max}                           # lifecycle subscriber (pythia.lifecycle.{user_id});
                                                         #   absent/down → inbox degrades to polling
iris.audit.retention-months     (default unlimited)      # whole-segment retention (kantheon-security §4.3)
iris.audit.signing-key-ref                               # Ed25519 key (K8s Secret); audit chain sig
iris.inbox.poll-fallback-s      (default 30)             # polling cadence when NATS is down
iris.pythia.{base-url, timeout-ms}                       # inbox aggregation + control-endpoint proxy (Phase 4)
```

## 7. Build & version contracts

- Tags: `iris-bff/v0.1.0` (Phase 1), `iris/v0.1.0` + `iris-bff/v0.2.0` (Phase 2), `iris-bff/v0.3.0` + `iris/v0.2.0` (Phase 3).
- `envelope/v1` + `iris/v1` ship inside kantheon `shared/proto`; no separate Maven publication at v1 (consumers are in-repo). `envelope-ts` is consumed by path dependency from `frontends/iris`.
- Branches per planning-conventions: `feat/p<n>-s<n.m>-<short>` within the `iris` arc namespace, e.g. `feat/iris-p1-s1.1-envelope-proto`.

---

*Contracts owner: Bora. Locked structure 2026-06-12 (Iris arc planning). Field-level changes require updating this doc first.*
