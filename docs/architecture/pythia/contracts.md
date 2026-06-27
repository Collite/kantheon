# Pythia — Wire Contracts (kantheon arc, Phases 1–5)

> **Companions.** [`architecture.md`](./architecture.md), [`../../implementation/v1/pythia/plan.md`](../../implementation/v1/pythia/plan.md), [`../../design/pythia/Pythia-v1-Design.md`](../../design/pythia/Pythia-v1-Design.md) §3 (the semantic authority — this doc is its proto/REST/DDL projection), [`../iris/contracts.md`](../iris/contracts.md) §1.1 (envelope/v1 Block).
>
> **Authority.** Source of truth for `org.tatrman.kantheon.pythia.v1`, the REST control surface, NATS subjects, persistence DDL, and the Charon/Metis ToolCapability schemas. Where this doc compresses a type the design specifies in full (e.g. event payload fields), the design's §3 prevails; divergences are listed in §9.

## 1. Proto package `org.tatrman.kantheon.pythia.v1`

File: `shared/proto/src/main/proto/org/tatrman/kantheon/pythia/v1/pythia.proto`. Faithful proto projection of design §3.1–3.2; abbreviated here to the structural skeleton — field-complete in the proto file itself, written in Phase 1 Stage 1.1.

```proto
syntax = "proto3";
package org.tatrman.kantheon.pythia.v1;

import "org/tatrman/kantheon/envelope/v1/envelope.proto";
import "org/tatrman/kantheon/themis/v1/themis.proto";
import "org/tatrman/kantheon/common/v1/handoff.proto";          // HandoffContext (InvestigationContext.handoff)
import "org/tatrman/kantheon/common/v1/response_message.proto"; // kantheon Rule-6 stand-in (kantheon-architecture §4)

// ---- Request (design §3.1) ----
message Investigation {
  string id = 1;                              // assigned by Pythia on submit
  optional string parent_id = 2;
  Caller caller = 3;                          // kind IRIS|HEBE|API|SCHEDULED, user_id, tenant_id, correlation_id
  string question = 4;
  InvestigationContext context = 5;           // entity_context, conversation_excerpt, locale,
                                              //   themis_prior_context (ResolutionContinuation),
                                              //   handoff (common.v1.HandoffContext — PD-1 2026-06-12:
                                              //   typed anchor from the originating turn; Pythia seeds
                                              //   the investigation from handoff.view (pattern/sql/args
                                              //   behind what the user was looking at) + handoff.entities;
                                              //   Conclusion links back to handoff.source_turn_ref)
  StyleHint style_hint = 6;                   // LIST|NARRATIVE|FORECAST|SIMULATION|AUTO
  optional ScenarioSpec scenario_params = 7;  // horizon, confidence_level, deltas_json, ...
  Constraints constraints = 8;                // max_llm_cost_usd, max_llm_tokens, latency_budget_ms,
                                              //   max_step_count, depth_budget SHALLOW|NORMAL|DEEP
  HitlPolicy hitl_policy = 9;                 // plan_approval, on_suspicious_result, on_plan_revision,
                                              //   on_budget_threshold, disambiguation
  optional LlmOverrides llm_overrides = 10;
}

// ---- Artifact (design §3.2) ----
message InvestigationArtifact {
  string id = 1;
  optional string parent_id = 2;
  Status status = 3;                          // 12 values incl. five AWAITING_* — PD-11 (2026-06-12)
                                              //   added AWAITING_BUDGET_DECISION: the on_budget_threshold=ASK
                                              //   gate parks here (was unrepresentable; /budget-decision had
                                              //   no parking status). Maps to Needs-your-input in the PD-2 inbox.
  ResolutionResult resolution = 4;            // wraps themis Resolution + assumptions + clarification log
  PlanDag plan = 5;
  repeated StepRecord steps = 6;
  repeated Hypothesis hypotheses = 7;
  repeated Finding findings = 8;
  repeated LooseEnd loose_ends = 9;
  optional Conclusion conclusion = 10;
  ResourceUsage resource_usage = 11;
  repeated string warnings = 12;
  string created_at = 13;
  optional string finalised_at = 14;
  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}

// ---- Plan (design §3.2 PlanDag) ----
message PlanDag { repeated Hypothesis hypotheses = 1; repeated PlanNode nodes = 2;
                  repeated DataDep edges = 3; string rationale = 4; int32 revision = 5; }

message PlanNode {
  string node_id = 1;
  repeated string tests_hyp_ids = 2;
  oneof kind {
    QueryNode query          = 3;   // queryRef + params_json + stack (TransDSL ops) — query-mcp
    DataFrameNode dataframe  = 4;   // dfdsl + source handle — Polars Worker (Phase 4)
    ModelNode model          = 5;   // capability_id (model.*) + input handles — Metis (Phase 4)
    ReasoningNode reasoning  = 6;   // prompt_ref + handles + STRUCTURED|TEXT + tier_hint
    RenderNode render        = 7;   // TABLE|CHART|NARRATIVE_FRAGMENT + handles + block_role + caption
  }
}

message DataDep { string from_node_id = 1; string to_node_id = 2; string binding = 3; }

// ---- Hypothesis / evidence / predicate — verbatim design fields ----
message Hypothesis { string id = 1; optional string parent_id = 2; string statement = 3;
  optional Predicate predicate = 4; HypStatus status = 5; repeated string test_step_ids = 6;
  repeated EvidenceLink evidence = 7; double confidence = 8; string rationale = 9;
  double estimated_explanatory_power = 10; double diagnostic_power = 11;
  DisplayPriority display_priority = 12; }

// ---- Step / handle / conclusion / loose end / usage — verbatim design fields ----
message StepRecord { /* design §3.2 StepRecord, incl. cost{tier_used, cached} + error{recoverable} */ }
message Handle {
  oneof kind {
    LiveQueryRef live_query     = 1;
    PgResultSnapshot pg_snapshot = 2;        // ADDED 2026-06-12 (see §9 divergences); Pythia-private
    WorkerSessionDF worker_df   = 3;         // (worker_pod, session_id, df_name) — matches workers/polars keying
    SeaweedArrowBlob seaweed    = 4;
    RedisArrowEntry redis       = 5;
    DbTableRef db_table         = 6;         // ADDED 2026-06-12: named-connection DB table (Charon §6.2)
  }
}
message Conclusion { RenderableArtifact primary = 1; repeated RenderableArtifact alternates = 2;
  repeated string evidence_step_ids = 3; optional ConfidenceInfo confidence = 4;
  StopReason stop_reason = 5; bool budget_truncated = 6; }
message RenderableArtifact { repeated org.tatrman.kantheon.envelope.v1.Block blocks = 1; }

// ---- Event stream (design §3.3; one message, oneof per event kind) ----
message InvestigationEvent {
  string investigation_id = 1;
  int64 sequence = 2;                         // per-investigation monotone; PG-assigned
  string emitted_at = 3;
  oneof event { /* the full design §3.3 vocabulary: lifecycle (3), resolution (4),
                   planning (4), hypotheses (6), prioritisation (2), execution (6),
                   suspicion/findings (2), loose ends (2), budget (3),
                   synthesis (4), conclusion (1) — one message type each */ }
}
```

**Field-number policy.** `Handle.kind` slots 1–5 fixed as above; new handle kinds append. `Status`, `HypStatus`, `StopReason`, `GapKind` enums carry `_UNSPECIFIED = 0`. `messages = 99` on `InvestigationArtifact` and every REST response (Rule 6).

## 2. REST control surface

Bearer-authenticated; `X-Correlation-Id` propagated. Callers at v1: iris-bff (+ tests/scripts).

**Request admission (PD-8, normative in [`kantheon-security.md`](../kantheon-security.md) §3.3 — note added 2026-06-12 cohesion review):** every endpoint validates the inbound bearer and re-checks `visibility_roles`; reject = 403 + Rule-6 message. Downstream data calls (query-mcp, Charon, Metis where user data flows) carry the **user's OBO token, never service identity**; cross-domain planning constrains to the caller's role-filtered registry view and discloses exclusions in the `Conclusion` (constrain-and-disclose, security §3.5).

| Endpoint | Method | Body → Response |
|---|---|---|
| `/v1/investigations` | POST | `Investigation` → `{ id, status }` (202; async from here) |
| `/v1/investigations/{id}` | GET | → `InvestigationArtifact` (current state, any status) |
| `/v1/investigations/{id}/events` | GET (SSE) | `?from_seq=N` → `InvestigationEvent` stream (PG replay then NATS live tail) |
| `/v1/investigations/{id}/approve-plan` | POST | `{ verdict: APPROVE \| REJECT_WITH_COMMENT, comment? }` |
| `/v1/investigations/{id}/approve-revision` | POST | `{ verdict: APPROVE \| REJECT }` |
| `/v1/investigations/{id}/answer` | POST | `{ resume_token?, answers: [...] }` (resolution or mid-flight input) |
| `/v1/investigations/{id}/budget-decision` | POST | `{ decision: CONTINUE \| HALT_GRACEFULLY \| ABANDON }` |
| `/v1/investigations/{id}/halt` | POST | `{}` → drains + HALTED (STOP_USER) |
| `/v1/investigations/{id}/replay` | POST | `{ overrides? }` → new id (re-resolve relative params) |
| `/v1/investigations/{id}/reproduce` | POST | `{}` → new id (frozen resolved_params; reuse blobs if retained) |
| `/v1/investigations` | GET | **PD-2 (2026-06-12):** `?user_id=&statuses=&page=&page_size=` → `{ investigations: [InvestigationSummary], next_page? }` — the per-user list iris-bff aggregates for the inbox. `InvestigationSummary` = id, question, status, created_at, updated_at, resource_usage totals, caller.kind |
| `/health`, `/ready` | GET | per architecture §7 gates |

All five `AWAITING_*` states map to exactly one control endpoint each (`AWAITING_BUDGET_DECISION` → `/budget-decision`; added by PD-11 2026-06-12); resume is idempotent (second call → 409 with current status).

**Halt semantics (PD-2, locked):** `halt` is cancel-with-partials — drain in-flight steps, then run synthesis over findings-so-far and emit a `Conclusion` marked `partial: true` (STOP_USER). Paid-for findings are never discarded.

## 3. NATS subjects & retention

- Subject: `pythia.investigation.{id}.events`; stream `PYTHIA_EVENTS`, JetStream domain `local`, retention 24 h / 2 GiB (matches deployed middleware limits), at-least-once, dedupe by `(investigation_id, sequence)`.
- **PD-2 (2026-06-12) — lifecycle subject:** `pythia.lifecycle.{user_id}` carrying `InvestigationLifecycleEvent { investigation_id, user_id, old_status, new_status, ts }` on every status transition. Coarse-grained by design (status only, no payload). Consumers: iris-bff (inbox badge/panel fan-out); Hebe v1.x (out-of-band "investigation finished" pushes for interactive runs — no Pythia change needed then).
- Postgres `pythia_events` is authoritative and indefinite (archival to Seaweed after 1 year — resolved Q5 defaults).
- Consumers at v1: pythia's own SSE bridge + iris-bff lifecycle subscriber. Hebe/scheduled consumers later subscribe directly.

## 3a. Resume semantics across service TTLs (PD-5 resolution, 2026-06-12)

Metis workspaces and Charon-staged data stay TTL-simple — **Pythia never holds leases**. Per step handle, the checkpoint records the *recipe* (Charon move spec / Metis fit spec — deterministic given params + `model_version`) **and the data fingerprint** (Charon Arrow fingerprint) at original materialization. On resume from any `AWAITING_*` state, the executor probes handle liveness (Charon `Describe`, Metis `GetStatus`) and **lazily re-materializes only dead handles the resumed plan still needs**. If re-materialization yields a different fingerprint (source data changed mid-pause), the investigation continues with a Rule-6 warning + a `LooseEnd` ("inputs changed during pause: <handle>") — never a hard fail, never silent epoch-mixing. Cost accepted: recomputation after long pauses (checkpointed work, not lost work).

## 4. Persistence shapes (Postgres, Flyway)

```sql
CREATE TABLE pythia_investigations (
  id UUID PRIMARY KEY, parent_id UUID, caller JSONB NOT NULL, question TEXT NOT NULL,
  request JSONB NOT NULL,                 -- full Investigation snapshot
  status TEXT NOT NULL, resolution JSONB, plan JSONB, conclusion JSONB,
  resource_usage JSONB NOT NULL DEFAULT '{}', warnings JSONB NOT NULL DEFAULT '[]',
  awaiting_since TIMESTAMPTZ, awaiting_ttl_until TIMESTAMPTZ,        -- 24h default expiry
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), finalised_at TIMESTAMPTZ);

CREATE TABLE pythia_hypotheses (
  investigation_id UUID REFERENCES pythia_investigations(id), hyp_id TEXT,
  parent_hyp_id TEXT, body JSONB NOT NULL,                          -- full Hypothesis
  status TEXT NOT NULL, confidence DOUBLE PRECISION, PRIMARY KEY (investigation_id, hyp_id));

CREATE TABLE pythia_steps (
  investigation_id UUID, step_id TEXT, node_id TEXT, body JSONB NOT NULL,
  status TEXT NOT NULL, output_handle JSONB, PRIMARY KEY (investigation_id, step_id));

CREATE TABLE pythia_handles (
  investigation_id UUID, handle_id TEXT, kind TEXT NOT NULL, body JSONB NOT NULL,
  inline_data BYTEA,                       -- PgResultSnapshot Arrow IPC payload (capped)
  PRIMARY KEY (investigation_id, handle_id));

CREATE TABLE pythia_checkpoints (
  investigation_id UUID, seq INT, taken_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reason TEXT NOT NULL,                    -- awaiting_* | plan_revised | batch_completed
  scheduler_state JSONB NOT NULL, diff JSONB NOT NULL,
  PRIMARY KEY (investigation_id, seq));

CREATE TABLE pythia_events (
  investigation_id UUID, sequence BIGINT, emitted_at TIMESTAMPTZ NOT NULL,
  kind TEXT NOT NULL, payload JSONB NOT NULL, PRIMARY KEY (investigation_id, sequence));
```

`artifact_ref` handed to Iris = `pythia_investigations.id`.

## 5. GatewayClient tier mapping (until gateway-native tier routing)

```
pythia.llm.map.{CHAT.STRONG}    = tag:strong       # config; ops-tunable
pythia.llm.map.{CHAT.CHEAP}     = tag:cheap
pythia.llm.map.{EMBEDDING.*}    = tag:embedding
```

Every call carries `task_kind` as a request header/metadata for gateway telemetry, so the cutover to native `(modality, tier, task_kind)` routing is a client no-op. Per-call-site defaults per design pressure-point 8; `llm_overrides` escalates per investigation.

## 6. Charon (kantheon — own arc; contracts deferred)

**Authoritative contract:** [`../charon/contracts.md`](../charon/contracts.md) (`org.tatrman.charon.v1.CharonService` — Materialize / Stage / Copy / Evict / Describe; `Location` union seaweed | redis | worker_df | db_table; legality matrix; connection-registry schema; DB type mapping). Charon is the first migrated platform-grade service (`services/charon` + thin `tools/charon-mcp`); arc plan at [`../../implementation/v1/charon/plan.md`](../../implementation/v1/charon/plan.md).

What this document keeps, because it is Pythia-side:

- **Handle ↔ Location mapping** — Charon contracts §7: `SeaweedArrowBlob→SeaweedBlob`, `RedisArrowEntry→RedisEntry`, `WorkerSessionDF→WorkerSessionDf`, `DbTableRef→DbTable`; `LiveQueryRef` and `PgResultSnapshot` have **no mapping** (Pythia-internal — Pythia's PG is never a Charon connection).
- **Evidence conventions** — bucket `pythia-evidence`, keys `{investigation_id}/{handle_id}.arrow`, `retention_tag` production (90 d) / shallow (7 d) per resolved Q5.
- **When Pythia calls Charon** — the materialisation policies in [`architecture.md`](./architecture.md) §5 (evidence-persist, cross-engine staging, TTL-approach); Charon never decides on its own.
- **Transport** — Pythia's `CharonClient` is gRPC-direct; the MCP wrapper is not on Pythia's path.

`charon/v0.3.0` + `charon-mcp/v0.1.0` gate Pythia Phase 4 (plan §6 pre-flight).

## 7. Metis (kantheon — own arc; contracts deferred)

**Authoritative contract:** [`../metis/contracts.md`](../metis/contracts.md) (`org.tatrman.metis.v1.MetisService` — Fit / Diagnose / Project / SimulateScenario + workspace RPCs; `model.*` MCP tools; numerical-fidelity goldens). Metis is the second migrated platform-grade service (`services/metis`, **Python** — library-moat decision 2026-06-12) + thin Kotlin `tools/metis-mcp`; arc plan at [`../../implementation/v1/metis/plan.md`](../../implementation/v1/metis/plan.md).

Pythia-side notes kept here: ModelNode → Metis call mapping lives in Metis contracts §4 (`session_id` = investigation-derived; fitted models in-session, `NOT_FOUND` model = re-fittable; forecast frames materialised to evidence by Charon per Pythia policies). `model.decompose.variance` stays v1.5 (backlog). `metis/v0.3.0` + `metis-mcp/v0.1.0` gate Pythia Phase 4 Stage 4.2.

## 8. Eval-corpus schema

`eval/corpus/{procedural,rca,forecast,simulation}.jsonl`:

```json
{ "id": "rca-007", "question": "Proč je naše tržba YoY nižší pro kanál Private?",
  "locale": "cs", "depth_budget": "NORMAL",
  "fixture_llm_script": "scripts/rca-007.yaml",
  "expected": { "intent_kind": "RCA", "plan_valid": true,
                "min_hypotheses": 4, "terminal_status": "DONE",
                "stop_reason_in": ["STOP_GOAL_REACHED","STOP_HARD_CAP"],
                "budget_max_usd": 2.0 } }
```

Scripted-LLM fixtures make verdict accuracy deterministic in CI; a small live-LLM bucket runs nightly only.

## 9. Divergences from `Pythia-v1-Design.md` (locked 2026-06-12)

| # | Divergence | Why |
|---|---|---|
| 1 | `PgResultSnapshot` added as a fifth Handle kind | lets Phases 2–3 ship procedural + RCA before Charon exists; capped inline Arrow in Pythia's PG |
| 1b | Charon is a full-spec gRPC service (**`kantheon/services/charon`**, pkg `org.tatrman.charon.v1`) with `kantheon/tools/charon-mcp` as a thin wrapper; DB tables are first-class sources AND targets via named connections (`DbTableRef` handle kind); Pythia's internal PG never provisioned as a connection | locked 2026-06-12; service-vs-MCP rule + the Mover brief's "S3, central DB, cloud DB" scope; first migrated platform-grade service in kantheon |
| 2 | `chart-formatter` is not a separate library — envelope-render owns ChartIntent→Vega-Lite | one chart pipeline constellation-wide (Golem arc Phase 1 builds it) |
| 3 | Report Renderer is the Midas-arc `services/report-renderer` (`report/v1`) | one renderer; Pythia is a consumer in v1.x, not a v1 dependency |
| 4 | Iris consumes events via Pythia's SSE bridge, not NATS directly | one streaming protocol in the BFF; NATS remains for non-Iris consumers |
| 5 | `WorkerSessionDF.df_id` → `df_name` | matches the deployed Polars Worker keying `(session_id, df_name)` |
| 6 | Conclusion blocks use envelope/v1 `Block` (TextBlock/TableBlock/ChartBlock → Block + FormatSpec; DividerBlock → Block role) | one Block contract (kantheon-architecture invariant 1) |

## 10. Build & version contracts

Tags: `pythia/v0.1.0` (P1), `v0.2.0` (P2), `v0.3.0` (P3), `v0.4.0` (P4), `pythia/v1.0.0` (P5). Gating tags from the sibling kantheon arcs: `charon/v0.3.0` + `charon-mcp/v0.1.0` (P4), `metis/v0.3.0` + `metis-mcp/v0.1.0` (P4 Stage 4.2). Branches `feat/pythia-p<n>-s<n.m>-<short>`. Cross-repo (ai-platform side — **gateway only**; Metis is kantheon-side per §7, sentence fixed 2026-06-12 cohesion review): llm-gateway tier routing tagged per ai-platform conventions; the coordination doc (`aip-v1-gateway-worker-plan.md`, written at Phase 3 time) tracks version pairs + the worker workspace-read-out verification.

---

*Contracts owner: Bora. Locked structure 2026-06-12 (Pythia arc planning). Design §3 remains the semantic authority; §9 lists every deliberate divergence.*
