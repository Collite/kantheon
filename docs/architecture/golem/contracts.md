# Golem — Wire Contracts (kantheon arc, Phases 1–4)

> **Companions.** [`architecture.md`](./architecture.md), [`../../implementation/v1/golem/plan.md`](../../implementation/v1/golem/plan.md), [`../iris/contracts.md`](../iris/contracts.md) (envelope/v1 — authoritative there), [`../themis/contracts.md`](../themis/contracts.md) §1.2 (themis/v1 Resolution).
>
> **Authority.** Source of truth for `org.tatrman.kantheon.golem.v1`, the Golem REST/SSE surface, the `golem_turns` persistence shape, and the Shem YAML content checklist. Envelope semantics live in the Iris contracts; this doc only consumes them.

## 1. Proto package `org.tatrman.kantheon.golem.v1`

File: `shared/proto/src/main/proto/org/tatrman/kantheon/golem/v1/golem.proto`

```proto
syntax = "proto3";
package org.tatrman.kantheon.golem.v1;

import "org/tatrman/kantheon/envelope/v1/envelope.proto";
import "org/tatrman/kantheon/themis/v1/themis.proto";
import "org/tatrman/kantheon/common/v1/handoff.proto";          // HandoffContext, ViewProvenance, EntityBinding
import "org/tatrman/kantheon/common/v1/response_message.proto"; // kantheon Rule-6 stand-in (kantheon-architecture §4)

// =========================================================================
// Request
// =========================================================================
message GolemRequest {
  string id        = 1;                       // == Iris turn_id
  string golem_id  = 2;                       // must match the pod's loaded Shem
  string question  = 3;                       // verbatim user question

  // From Themis (trust-upstream decision 2026-06-12). Carries bindings,
  // intent_kind, function binding + argsJson, continuation.
  org.tatrman.kantheon.themis.v1.Resolution resolved_intent = 4;

  GolemContext context = 5;
  Caller caller        = 6;
  HitlPolicy hitl_policy = 7;
  Constraints constraints = 8;
}

message GolemContext {
  repeated org.tatrman.kantheon.envelope.v1.EntityContextSnapshot entity_context = 1;
  repeated ConversationTurn conversation_excerpt = 2;   // last N turns, BFF-built
  string locale = 3;                                    // "cs" | "en"
  optional org.tatrman.kantheon.envelope.v1.CurrentView prior_view = 4;  // for AMEND / DRILL
  // Cohesion review 2026-06-12 (finding 2.5): the PD-1 assembly rule sends the
  // HandoffContext to EVERY routed agent — Pythia had its slot, Golem did not.
  // prior_view (v2-compat CurrentView) is derived from handoff.view during the
  // transition; native Golem reads the handoff directly.
  optional org.tatrman.kantheon.common.v1.HandoffContext handoff = 5;
}

message ConversationTurn {
  string turn_id  = 1;
  string question = 2;
  optional string answer_summary = 3;          // first text block / caption of the turn
  optional string agent_id = 4;
}

message Caller { string user_id = 1; string tenant_id = 2; string correlation_id = 3; }

message HitlPolicy {
  SuspicionAction on_suspicious_result = 1;    // CONTINUE | WARN (no HALT — Golem doesn't pause)
}
enum SuspicionAction { SUSPICION_ACTION_UNSPECIFIED = 0; CONTINUE = 1; WARN = 2; }

message Constraints {
  optional int64 latency_budget_ms = 1;
  optional int32 max_step_count    = 2;        // default 4 — mini-plan cap
}

// =========================================================================
// Mini-plan (composer output; persisted on the turn for AMEND/DRILL/audit)
// =========================================================================
message MiniPlan {
  PlanSource source   = 1;                     // mirrors envelope/v1 PlanSource semantics
  double confidence   = 2;
  string rationale    = 3;
  repeated MiniPlanNode nodes = 4;             // 1..4, ordered; linear deps at v1
  optional string losing_plan_summary = 5;
}

enum PlanSource {
  PLAN_SOURCE_UNSPECIFIED = 0;
  PATTERN = 1; FREE_SQL = 2; AMEND = 3; DRILL = 4; CLARIFICATION = 5;
}

message MiniPlanNode {
  string node_id = 1;
  oneof kind {
    QueryNode query         = 2;
    ReasoningNode reasoning = 3;
    RenderNode render       = 4;
  }
}

message QueryNode {
  string source           = 1;                 // TTR / SQL / pattern-derived source text
  string source_language  = 2;                 // "transdsl" | "sql" | ...
  string params_json      = 3;                 // Rule 7
  optional string pattern_id = 4;              // set when source == PATTERN
  bool compile_first      = 5;                 // true for FREE_SQL
}

message ReasoningNode {
  string prompt_ref   = 1;                     // prompts/ key
  repeated string input_node_ids = 2;
  string output_kind  = 3;                     // STRUCTURED | TEXT
}

message RenderNode {
  org.tatrman.kantheon.envelope.v1.FormatKind kind_hint = 1;  // composer hint; format node may override
  repeated string input_node_ids = 2;
  optional string caption = 3;
}

// =========================================================================
// Response (terminal; streaming events in §3 carry the same pieces)
// =========================================================================
message ConversationalResponse {
  string id         = 1;
  string request_id = 2;
  string golem_id   = 3;

  // One envelope per rendered block — the BFF forwards them as chat bubbles.
  repeated org.tatrman.kantheon.envelope.v1.FormatEnvelope envelopes = 4;

  MiniPlan plan              = 5;
  repeated StepRecord step_records = 6;
  ResourceUsage resource_usage = 7;
  Status status              = 8;              // STATUS_DONE | STATUS_FAILED | STATUS_CLARIFICATION
  string finalised_at        = 9;

  // PD-1/PD-4 echo rule (2026-06-12): every response reports what was actually
  // applied. current_view mirrors new-golem v2 CurrentView (agent-owned; the
  // BFF snapshots it into TurnPointer). applied_context = the entity bindings
  // the turn really used — Iris compares vs dispatched context (PD-4).
  org.tatrman.kantheon.common.v1.ViewProvenance current_view = 10;
  repeated org.tatrman.kantheon.common.v1.EntityBinding applied_context = 11;

  repeated org.tatrman.kantheon.common.v1.ResponseMessage messages = 99;
}

// PD-1 emission rule (2026-06-12): when the mini-plan confidence gate fails AND
// the resolved intent smells analytical (RCA/FORECAST/SIMULATION), Golem attaches
// an envelope/v1 InvestigateChip (handoff filled incl. suggested_focus) to its
// answer or refusal. Golem NEVER calls Pythia — the chip is a user-approved
// re-route via the BFF (routing_hint = pythia).

// Stage 2.1 fix: values prefixed (STATUS_*) — proto3 enum values are siblings at
// package scope, so a bare CLARIFICATION collides with PlanSource.CLARIFICATION.
enum Status { STATUS_UNSPECIFIED = 0; STATUS_DONE = 1; STATUS_FAILED = 2; STATUS_CLARIFICATION = 3; }

message StepRecord {
  string node_id   = 1;
  string node_kind = 2;
  string status    = 3;                        // COMPLETED | FAILED | SKIPPED
  optional int64 row_count  = 4;
  int64 latency_ms = 5;
  double cost_usd  = 6;
  optional string error = 7;
}

message ResourceUsage {
  double total_usd = 1;
  int64 tokens_in  = 2;
  int64 tokens_out = 3;
  int32 query_count = 4;
  int64 total_latency_ms = 5;
}
```

**Notes.** (1) `ConversationalResponse.envelopes` replaces the May design's bare `blocks: [Block]` — new-golem v2 proved the envelope (with chips, drilldowns, current_view, plan metadata) is the natural emission unit, and Iris consumes envelopes unchanged. `Block` remains in envelope/v1 for Pythia's artifact internals. (2) `Status.CLARIFICATION` is additive vs the May design (DONE|FAILED): plan-level clarification is a first-class terminal state of a turn, resumed via `/v1/resume`.

## 2. REST + SSE surface (iris-bff is the only intended caller)

| Endpoint | Method | Body → Response |
|---|---|---|
| `/v1/answer` | POST | `GolemRequest` → `text/event-stream` (events per §3; terminal `done`) |
| `/v1/answer/sync` | POST | `GolemRequest` → `ConversationalResponse` JSON (tests, scripts) |
| `/v1/resume` | POST | `{ resume_token, answer: {selected_option_id | free_text_answer} }` → `text/event-stream` |
| `/v1/action` | POST | `{ turn_id, action: TypedAction }` → `text/event-stream` (sort/filter/paginate/select_row re-issue; no LLM) |
| `/v1/refresh` | POST | reload PackageContext from metadata-mcp (ops; cluster-internal) |
| `/health`, `/ready` | GET | ready gates on: Shem loaded, PackageContext loaded, DB migrated |

No MCP tool surface at v1 (decision 2026-06-12: Iris dispatches over REST/SSE; MCP exposure adds no consumer). Registration into capabilities-mcp still happens via `capabilities-client` heartbeat with the pod's `ShemManifest`.

**Request admission (PD-8, normative in [`kantheon-security.md`](../kantheon-security.md) §3.3 — note added 2026-06-12 cohesion review):** every endpoint validates the inbound bearer and re-checks the pod's own `visibility_roles` against the caller's realm roles; failure → 403 with a Rule-6 message. Covers Themis-bypassed paths (direct API callers). Downstream, **all query-mcp/metadata calls carry the user's OBO token, never a service identity** — the inbound bearer is forwarded (kantheon-security §2).

## 3. Streaming events (SSE)

Same framing as iris/v1 (`event:` = name, `data:` = JSON). Golem emits; the BFF maps onto `IrisStreamEvent` (`step` / `envelope` / `error` / `done`) one-to-one or with `detail_json`:

| Event | Payload | BFF mapping |
|---|---|---|
| `mini_plan_drafted` | `{ plan: MiniPlan }` | `step{node:"plan", phase:"completed", detail_json}` |
| `step_started` / `step_completed` / `step_failed` | `{ step: StepRecord }` | `step{node, phase}` |
| `envelope` | `{ envelope: FormatEnvelope }` | `envelope` |
| `warnings` | `{ warnings: [str] }` | folded into next envelope / `step.detail` |
| `clarification` | `{ envelope }` with `pending_clarification` set | `envelope`; BFF records issuer = golem_id |
| `agent_response_done` | `{ status, resource_usage }` | `done{outcome}` |

## 4. Persistence shapes (Postgres, per-pod database/schema)

```sql
CREATE TABLE golem_turns (
  id              UUID PRIMARY KEY,            -- == ConversationalResponse.id
  request_id      UUID NOT NULL,               -- == GolemRequest.id == Iris turn_id
  golem_id        TEXT NOT NULL,
  user_id         TEXT NOT NULL,
  tenant_id       TEXT NOT NULL,
  question        TEXT NOT NULL,
  resolved_intent JSONB NOT NULL,              -- themis Resolution snapshot
  plan            JSONB NOT NULL,              -- MiniPlan (AMEND/DRILL read this back)
  envelopes       JSONB NOT NULL,              -- [FormatEnvelope]
  current_view    JSONB,                       -- the view this turn produced (AMEND/DRILL target)
  step_records    JSONB NOT NULL DEFAULT '[]',
  resource_usage  JSONB NOT NULL DEFAULT '{}',
  pending_resume_token TEXT,                   -- set while status = 'clarification'
  status          TEXT NOT NULL CHECK (status IN ('done', 'failed', 'clarification')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  finalised_at    TIMESTAMPTZ
);
CREATE INDEX golem_turns_request ON golem_turns(request_id);
```

`status` is a denormalised **lowercase** string (`done`/`failed`/`clarification`), distinct from the golem/v1 `Status` enum's `STATUS_*` names — the JSONB columns carry proto3-JSON, but `status` is a derived column written from `GolemTurnStatus.wire`. The `CHECK` guards against drift.

`artifact_ref` handed to Iris = `golem_turns.id`. AMEND/DRILL resolution: `GolemContext.prior_view.bubble_id` → look up the producing turn's `current_view` + `plan` here. No checkpoint table, no event log (Golems don't pause — clarification is a terminal-and-resume, not a checkpoint).

## 5. Resume token (Golem-issued, plan-level only)

Same HMAC codec pattern as Themis (`resume/` package; key from `golem.hmac.secret-key`, versioned). Payload:

```json
{ "v": 1, "golemId": "golem-erp", "turnId": "...", "kind": "intent_choice|missing_arg",
  "planCandidates": [...], "missingArg": {"name": "...", "paramSpec": {...}},
  "issuedAt": "...", "expiresAt": "+24h" }
```

Entity-choice tokens are never Golem-issued (Themis owns entity disambiguation). The BFF routes resume by `pending_clarification.issued_by_agent_id`.

## 6. The Shem — assembled `AgentCapability` (converged design, 2026-06-25)

> **Supersedes the 2026-06-12 "ShemManifest content checklist."** That checklist had Bora
> hand-curate a rich per-agent manifest (`domain_entities`, `preferred_queries`,
> `domain_terminology`, …). Reconciling with the live **ai-models agent registry**
> (`agents/<id>.yaml`) showed those fields are **already served by the model** — re-listing
> them by hand only invites drift. The Shem is now **assembled at boot**, not authored as one
> rich file. Vocabulary: **"area"** (subject area), not "domain"; the `agent_kind` is **`AREA_QA`**.

A Golem's `capabilities/v1.AgentCapability` is assembled from four sources:

| Source | Supplies | Authored where |
|---|---|---|
| **ai-models agent definition** | identity (`id` → `agent_id=golem-<id>`, `label` → `display_name`) + model slice (`shem.areas`/`shem.packages`/`shem.entities`) | `ai-models/agents/<id>.yaml` (BA-owned, minimal) |
| **Ariadne model** (by package) | `area_entities`, `preferred_queries`, `area_terminology`; the area `description`/`tags` (seeds `description_for_router`) | the model (`GetModel`, `ResolveArea`) |
| **kantheon overlay** (`shem.yaml`) | `visibility_roles` + OPTIONAL `description_for_router` / `example_questions` / `counter_examples` / `locale_defaults` overrides | `agents/golem/shems/<agent_id>/shem.yaml` |
| **Golem-template constants** | `agent_kind=AREA_QA`, `intent_kinds_supported=[PROCEDURAL]`, `capability_refs`/`preferred_capabilities` (`theseus.query`, `theseus.compile`, `render.table`, `render.chart`), `hitl_default=INTERACTIVE`, `service_endpoint=golem-<id>.kantheon.svc:7420`, `health_check_path=/health` | the template |

`typical_latency_ms` / `typical_cost_usd` are **measured** (Stage 4.x), not authored.

**Dropped from the authored surface:** `domain_entities` / `preferred_queries` /
`domain_terminology` (now `area_*`, sourced from the model — see §6.3) and `style_addendum`.
The discipline rule still holds end-to-end: correctness-affecting knowledge lives in the
**model** (TTR labels/synonyms), never in a hand-authored manifest or a prompt.

### 6.1 The overlay file

`agents/golem/shems/<agent_id>/shem.yaml` (mounted into the pod via ConfigMap). The first
instance is `agents/golem/shems/golem-ucetnictvi/`. Shape:

```yaml
apiVersion: kantheon.shem/v1
kind: golem-shem
source:                          # (1) identity + model slice
  repo: ai-models
  agentDef: agents/ucetnictvi.yaml
  id: ucetnictvi                 # ai-models agent id → kantheon agent_id = golem-ucetnictvi
  areas: [accounting]            # Ariadne ResolveArea → packages [obchodni_doklady, ucetnictvi]
overlay:                         # (3) per-agent residue kantheon owns
  visibility_roles: [kantheon-area-accounting]   # Keycloak realm role; group-mapping lives in Keycloak
  description_for_router: |      # OPTIONAL — else seeded from the area description/tags
    …
  example_questions: […]         # OPTIONAL — else from the routing/eval corpus
  counter_examples: […]          # OPTIONAL
  locale_defaults: [cs-CZ, en]   # OPTIONAL — else template defaults
```

### 6.2 Prompts belong to the Shem (model is just the model — 2026-06-25)

**Reverses the 2026-06-13 "Ariadne serves model + prompts" consolidation.** Prompts are part of
the **Shem** (the inscription that animates each Golem), not the model. The model is *just* the
model: entities, packages, areas. Consequences:

- Each Shem carries its own `prompts/{cs,en}/{intent,free-sql,chip-topup}.yaml`, mounted with the
  Shem. Seeded verbatim from `ai-models/prompts/golem/{cs,en}` (the current generic set); per-Shem
  divergence is now possible.
- Golem's `PromptStore` loads from the **mounted Shem**, not Ariadne. The `{{ name }}` contract,
  in-node substitution, and atomic reload are unchanged; the *source* is the Shem.
- **Ariadne loses prompt-serving** — `GetPrompts` RPC + `get_prompts` tool are removed (see
  [`../fork/contracts.md`](../fork/contracts.md) §1.1). Ariadne serves only the model + `ResolveArea`.

### 6.3 Area resolution (the Ariadne work item)

Ariadne serves the model by **package** and has no area concept today (`accounting.ttrm`: areas are
an "editor/registr koncept, který ai-platform NENAČÍTÁ do metadat"). The Shem references `areas`, so
Ariadne gains **`ResolveArea(area) → packages`** (loading `model-ttr/areas/*.ttrm`) plus area
`description`/`tags` so Golem can resolve `areas: [accounting]` → `[obchodni_doklady, ucetnictvi]` and
seed the router description. This is the Phase 4 Ariadne stage — see
[`../../implementation/v1/golem/plan.md`](../../implementation/v1/golem/plan.md) §6.

## 7. Configuration (application.conf keys)

```
golem.shem.path                    (/etc/golem/shem.yaml)
golem.db.{url, user, password}
golem.query-mcp.{base-url, timeout-ms}
golem.metadata.{host, port, timeout-ms}            # gRPC — Ariadne; serves GetModel AND GetPrompts
golem.prompts.agent-id             (golem)          # selects ariadne get_prompts(agent_id=…) tree
golem.prompts.locale               (cs)             # v1 cs-only; "" = all locales
                                                   # NO golem-own prompt git config — prompts come from Ariadne;
                                                   #   src/main/resources/prompts/ is the offline fallback only (2026-06-13)
golem.llm-gateway.{base-url, timeout-ms, plan-model-tag, format-model-tag}
golem.capabilities.{base-url}
golem.plan.thresholds.{auto=0.95, warn=0.85, clarify-below=0.6}
golem.plan.max-nodes               (4)
golem.format.max-retries           (2)               # then deterministic fallback
golem.hmac.{secret-key, key-version}
golem.package-context.ttl-s        (600)             # PackageContext + PromptStore share the boot fetch + /v1/refresh
golem.auth.{keycloak-issuer, audience}             # PD-8 admission re-check (bearer validation);
                                                   #   user bearer forwarded to query-mcp (OBO rule)
```

## 8. Eval & diff-harness fixtures

- `eval/corpus/conversations/*.jsonl` — recorded new-golem v2 sessions: `{turn: {question, resolved_entities, locale}, expected_envelope: {...}}`. Captured during Phase 3 from the live v2 stack; ids/timestamps normalised.
- `eval/diff-harness/` — replay CLI (`just eval-golem`): drives Kotlin Golem with synthesised `GolemRequest`s (Resolution built from the recorded resolved entities), diffs envelopes field-wise, emits Markdown report. CI gate from Phase 4: zero semantic divergences on the curated set.

## 9. Build & version contracts

Tags: `envelope-render/v0.1.0` (P1), `golem/v0.1.0` (P2), `golem/v0.2.0` (P3), `golem/v1.0.0` (P4 cutover). Branches `feat/golem-p<n>-s<n.m>-<short>`. envelope-render is consumed in-repo (no Maven publication at v1; Pythia consumes it in its arc).

---

*Contracts owner: Bora. Locked structure 2026-06-12 (Golem arc planning).*
