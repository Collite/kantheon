# Themis — Wire Contracts (kantheon arc, Phases 1–3)

> **Scope.** All cross-service contracts produced or extended by Phases 1, 2, and 3: protobuf packages, MCP tool surfaces, REST endpoints, manifest YAML schemas, heartbeat protocol, persistence shapes (where applicable).
>
> **Authority.** This document is the source of truth. Task lists implementing the work below must match these contracts byte-for-byte. Any deviation is a planning bug.
>
> **Companions.** [`architecture.md`](./architecture.md), [`plan.md`](../../implementation/v1/themis/plan.md), [`themis-design.md`](../../design/themis/themis-design.md).

## 1. Proto packages

### 1.1 `org.tatrman.kantheon.capabilities.v1` (new in Phase 1)

File: `shared/proto/src/main/proto/org/tatrman/kantheon/capabilities/v1/capabilities.proto`

```proto
syntax = "proto3";
package org.tatrman.kantheon.capabilities.v1;

import "org/tatrman/kantheon/common/v1/response_message.proto";  // kantheon-local Rule-6 stand-in
// (2026-06-12 cohesion review): ai-platform's ResponseMessage lives in cz.dfpartner.metadata.v1
// and carries a metadata-domain ObjectRef — not portable. ALL kantheon protos use the
// org.tatrman.kantheon.common.v1 stand-in until ai-platform extracts a domain-free
// cz.dfpartner.common.v1 version (tracked in kantheon-v1.1.md §1).

// =========================================================================
// Capability sealed union
// =========================================================================
message Capability {
  oneof kind {
    ToolCapability tool   = 1;
    AgentCapability agent = 2;
  }
}

// =========================================================================
// Tool capability — `kind: TOOL`
// =========================================================================
message ToolCapability {
  string capability_id          = 1;  // e.g. "model.fit.arima:v1"
  string category               = 2;  // e.g. "model.fit.*"
  string version                = 3;  // semver; embedded in capability_id after :
  repeated Predicate preconditions = 4;
  CostHints cost_hints          = 5;
  repeated string search_tags   = 6;
  string service_endpoint       = 7;  // resolvable from kantheon namespace
  string description            = 8;
  string registration_id        = 9;  // assigned on register; opaque
  google.protobuf.Timestamp last_heartbeat_at = 10;  // unset for fixtures
}

// =========================================================================
// Agent capability — `kind: AGENT`
// =========================================================================
message AgentCapability {
  AgentKind agent_kind                          = 1;
  string agent_id                               = 2;  // "pythia" / "golem-erp" / ...
  string display_name                           = 3;
  repeated IntentKind intent_kinds_supported    = 4;
  string description_for_router                 = 5;  // 1-paragraph; Themis Layer 2 prompt input
  repeated string example_questions             = 6;  // positive few-shot for Layer 2
  repeated string counter_examples              = 7;  // negative few-shot for Layer 2
  repeated string capability_refs               = 8;  // ToolCapability IDs this agent provides/uses
  string service_endpoint                       = 9;
  string health_check_path                      = 10;
  int32 typical_latency_ms                      = 11;
  double typical_cost_usd                       = 12;
  HitlProfile hitl_default                      = 13;
  string registration_id                        = 14;
  google.protobuf.Timestamp last_heartbeat_at   = 15;
  bool non_routable                             = 16;  // excluded from all four Themis routing layers (e.g. Hebe); proto3 default false == routable. Added 2026-06-12 (Hebe arc).
  repeated string visibility_roles              = 17;  // PD-8 (2026-06-12): empty == all authenticated users; convention "kantheon-area-<area>" (renamed from "kantheon-domain-<shem>" 2026-06-25). Themis filters routing view by caller roles BEFORE Layer 1 (invisible, not forbidden); agents re-check at admission. See kantheon-security.md §3.

  // ─────────────────────────────────────────────────────────────────────
  // ShemManifest-specific fields — populated ONLY when agent_kind == AREA_QA
  // (Discriminator semantics; not a separate proto message. "area" renamed
  // from "domain" 2026-06-25 — "domain" is now a TTR value concept.)
  // ─────────────────────────────────────────────────────────────────────
  string area_name                              = 20;  // "accounting" / "HR" / "Sales"
  repeated string area_entities                 = 21;  // entity-type IDs in scope
  repeated TermDef area_terminology             = 22;
  repeated string preferred_queries             = 23;  // curated metadata-mcp query IDs
  repeated string preferred_capabilities        = 24;  // curated ToolCapability IDs
  string style_addendum                         = 25;  // voice/presentation only — NEVER correctness
  repeated LocaleDefault locale_defaults        = 26;
}

enum AgentKind {
  AGENT_KIND_UNSPECIFIED  = 0;
  INVESTIGATOR            = 1;  // Pythia
  AREA_QA                 = 2;  // Golem-* instances (renamed from DOMAIN_QA 2026-06-25)
  PERSONAL_ASSISTANT      = 3;  // Hebe (agents/hebe; registers non_routable=true)
}

enum IntentKind {
  INTENT_KIND_UNSPECIFIED = 0;
  PROCEDURAL              = 1;
  RCA                     = 2;
  FORECAST                = 3;
  SIMULATION              = 4;
}

enum HitlProfile {
  HITL_PROFILE_UNSPECIFIED = 0;
  INTERACTIVE              = 1;  // default for Iris
  SPECULATIVE              = 2;  // for Hebe-class messaging clients
  STRICT                   = 3;  // refuse on any blocker
}

// =========================================================================
// Supporting types
// =========================================================================
message Predicate {
  string expression = 1;        // e.g. "input.shape == Arrow.Table"
  string description = 2;
}

message CostHints {
  double typical_latency_ms = 1;
  double typical_cost_usd   = 2;
  bool   is_idempotent      = 3;
  int32  max_concurrent     = 4;  // per-provider concurrency cap
}

message TermDef {
  string term = 1;            // e.g. "private channel"
  string definition = 2;      // 1-sentence
  repeated string synonyms = 3;
}

message LocaleDefault {
  string locale = 1;          // "cs", "en", "cs-CZ"
  string greeting = 2;
  string date_format = 3;
  string currency = 4;
}

// =========================================================================
// RPC surface
// =========================================================================
message SearchRequest {
  repeated IntentKind intent_kinds   = 1;
  repeated string     entity_types   = 2;
  repeated string     capability_tags = 3;
  // Filter scope:
  optional CapabilityFilter filter   = 4;
}

message CapabilityFilter {
  optional bool include_tools  = 1;  // default true
  optional bool include_agents = 2;  // default true
  optional bool include_pruned = 3;  // default false; if true, returns audit-only entries
}

message SearchResponse {
  repeated Capability entries                  = 1;
  repeated ResponseMessage messages            = 99;
}

message ListRequest {
  optional string category = 1;     // tool-only filter
  optional CapabilityFilter filter = 2;
}

message ListResponse {
  repeated Capability entries                  = 1;
  repeated ResponseMessage messages            = 99;
}

message ListAgentsRequest {
  // Live entries only by default.
  optional CapabilityFilter filter = 1;
}

message ListAgentsResponse {
  repeated AgentCapability agents              = 1;
  repeated ResponseMessage messages            = 99;
}

message GetRequest {
  string id = 1;                  // capability_id OR agent_id (registry knows which by lookup)
}

message GetResponse {
  optional Capability capability  = 1;  // absent → not found
  repeated ResponseMessage messages = 99;
}

message RegisterRequest {
  Capability capability           = 1;
}

message RegisterResponse {
  string registration_id          = 1;
  repeated ResponseMessage messages = 99;
}

message HeartbeatRequest {
  string registration_id          = 1;
}

message HeartbeatResponse {
  google.protobuf.Timestamp accepted_at = 1;
  repeated ResponseMessage messages     = 99;
}
```

**Notes:**

- Every response carries `repeated ResponseMessage messages = 99;` per ai-platform Rule 6. `ResponseMessage` here is `org.tatrman.kantheon.common.v1.ResponseMessage` — the kantheon-local stand-in (decision 2026-06-12, kantheon-architecture §4); swaps to a domain-free `cz.dfpartner.common.v1` version when ai-platform extracts one.
- `capability_id` and `agent_id` namespaces are disjoint by convention; the registry's `get(id)` resolves both. The `:vN` suffix on tool capability IDs encodes version; an unsuffixed ID resolves to the latest.
- `last_heartbeat_at` unset for source-controlled fixtures (treated as always-live).

### 1.2 `org.tatrman.kantheon.themis.v1` (extracted + extended)

File: `shared/proto/src/main/proto/org/tatrman/kantheon/themis/v1/themis.proto`

This package is **extracted** from `cz.dfpartner.resolver.v1` in Phase 2 Stage 2.2 via `git filter-repo` + proto-package rename, then **extended** in Phase 3 Stage 3.1.

> **⚠ Reconciliation note (2026-06-20, Stage 3.1).** The full proto block below was written as a **Phase-3 design target** and diverged from the proto that was actually extracted and shipped at `themis/v0.1.0`. The **authoritative wire contract is the source file** (`themis.proto`), not this block. Phase 3 Stage 3.1 landed the routing types **additively onto the shipped base** (decision: *additive-to-shipped + fix the doc*, Bora 2026-06-20), which required different field numbers than this block assumed, because the shipped base already uses some of them:
>
> | Field | This block (target) | Shipped + Stage 3.1 (authoritative) | Reason for the shift |
> |---|---|---|---|
> | `ResolveRequest.profile` | 6 | **7** | shipped `mode` (ResolveMode) occupies 6 |
> | `ResolveRequest.routing_hint` | 7 | **8** | follows profile |
> | `ResolveRequest.prior_context` (HandoffContext) | 8 (`prior_context`) | **9** | follows routing_hint |
> | `ResolveResponse.refusal` | 4 | **6** (in `oneof outcome`) | shipped `trace_id=4`/`elapsed_ms=5` |
> | `Resolution.intent_kind` / `routing` | 10 / 11 | **10 / 11** | unchanged — free on the shipped base |
> | `AwaitingClarification.multi_question` | 8 (in `kind` oneof w/ ambiguity=7) | **8** (in a new single-member `kind` oneof) | shipped base has no `kind` oneof; ambiguity stays expressed via `options` |
> | `ResumeAnswer.picked_agent` | 4 (in `answer` oneof) | **4** (flat field) | shipped `ResumeAnswer` has no `answer` oneof; added flat & additive |
>
> **NOT adopted in Stage 3.1** (these belong to the divergent richer base this block assumed, and are not needed by the routing layer; they remain internal Kotlin domain models — `ResolverModels.kt` — until a stage needs them on the wire): wire-level `HitlProfile hitl` on `ResolveContext`, `ResolutionContinuation continuation`, `AwaitingClarification.{resume_token, partial_bindings, round_index, max_rounds}`, the by-span `FreshQuestion` / `EntityBinding` shapes, and `Resolution.continuation`. `AgentId` is **not** redeclared here — it lives in `common/v1` (D2) and is imported.
>
> The block below is retained as the design intent; read it **through** this note. **Bold = new or changed in this arc;** everything else carries over from `cz.dfpartner.resolver.v1`.

```proto
syntax = "proto3";
package org.tatrman.kantheon.themis.v1;

import "cz/dfpartner/nlp/v1/analyze.proto";                       // ai-platform Maven; AnalyzeResponse
import "org/tatrman/kantheon/common/v1/response_message.proto";   // kantheon Rule-6 stand-in (see §1.1 note)
import "org/tatrman/kantheon/common/v1/handoff.proto";            // HandoffContext (PD-1) + AgentId

// =========================================================================
// RPC envelope
// =========================================================================
message ResolveRequest {
  string conversationId = 1;
  oneof input {
    FreshQuestion fresh   = 2;
    ResumeAnswer  resume  = 3;
  }
  Registry registry            = 4;
  ResolveContext context       = 5;

  Profile profile              = 6;   // NEW: default = CHAT_QUICK if PROFILE_UNSPECIFIED
  optional org.tatrman.kantheon.common.v1.AgentId routing_hint = 7;  // NEW: Layer 0 override

  // PD-1 (2026-06-12): typed prior context, assembled by Iris-BFF from the
  // previous TurnPointer + EntityContext. This IS "themis_prior_context".
  // Coreference ("it", "they", "that drop") resolves against prior_context.entities;
  // prior_context.view tells Themis what result the user is looking at.
  optional org.tatrman.kantheon.common.v1.HandoffContext prior_context = 8;
}

// Caller-roles transport (PD-8; locked 2026-06-12, cohesion review D3): the BFF
// forwards the USER'S BEARER on the Themis hop — no roles field on the proto.
// Themis validates the token and reads realm_access.roles itself, then derives
// its routing view per request: drop non_routable entries → filter by the
// caller's roles against visibility_roles → run Layers 0–3. One identity
// mechanism everywhere (same OBO discipline as agent→query-mcp;
// kantheon-security.md §2).

message ResolveResponse {
  org.tatrman.kadmos.v1.AnalyzeResponse parse = 1;  // always present (fork Stage 2.6: was cz.dfpartner.nlp.v1)
  oneof outcome {
    Resolution            resolution = 2;
    AwaitingClarification awaiting   = 3;
    RefusalWithGaps       refusal    = 4;  // NEW: STRICT-mode terminal
  }
  string traceId               = 5;
  int64  elapsedMs             = 6;
  repeated ResponseMessage messages = 99;
}

// =========================================================================
// Input shapes
// =========================================================================
message FreshQuestion {
  string text             = 1;
  ResolveContext context  = 2;
}

message ResumeAnswer {
  string resume_token         = 1;  // HMAC-signed JSON blob
  oneof answer {
    string selected_option_id = 2;
    string free_text_answer   = 3;
    org.tatrman.kantheon.common.v1.AgentId picked_agent = 4;  // NEW: Layer 3 chip-pick re-entry
  }
}

message ResolveContext {
  string locale = 1;                // "cs", "en", "cs-CZ"
  HitlProfile hitl = 2;             // INTERACTIVE / SPECULATIVE / STRICT
  // RENAMED from `themis_prior_context` (cohesion review 2026-06-12, finding 2.3):
  // this is the HMAC continuation token for profile handoff across HITL rounds —
  // an unrelated mechanism to PD-1's HandoffContext. "prior context" now refers
  // exclusively to ResolveRequest.prior_context (HandoffContext).
  optional ResolutionContinuation continuation = 3;
}

// =========================================================================
// Resolution outcomes
// =========================================================================
message Resolution {
  string function_id    = 1;        // bound function from caller's Registry
  string args_json      = 2;        // camelCase JSON; validated against ParamSpec
  repeated EntityBinding bindings = 3;
  double confidence     = 4;
  string rationale      = 5;
  ResolutionContinuation continuation = 6;  // opaque token for downstream profile changes

  IntentKind intent_kind            = 10;   // NEW
  optional RoutingDecision routing  = 11;   // NEW; absent when profile == INVESTIGATION_DEEP
}

message AwaitingClarification {
  string question         = 1;      // localised; from caller's locale
  string resume_token     = 2;
  optional ContextSpan context_span = 3;  // for UI highlight in original question
  repeated PartialBinding partial_bindings = 4;
  int32 round_index       = 5;       // current round, 1..3
  int32 max_rounds        = 6;
  oneof kind {
    AmbiguityClarification ambiguity      = 7;
    MultiQuestionDetected  multi_question = 8;  // NEW: Themis Phase 3 Stage 3.2
  }
}

message AmbiguityClarification {
  repeated ClarificationOption options = 1;
  string ambiguity_kind                = 2;  // "entity_unmapped" / "intent_undecided" / ...
}

message MultiQuestionDetected {            // NEW
  repeated string sub_questions = 1;       // self-standing strings for Iris to reissue
  // PD-13 (2026-06-12): comparative-intent verdict. SPLIT = disjoint intents →
  // Iris decomposes into N turns (original behaviour). KEEP_TOGETHER = clauses
  // joined by a relating intent (compare / correlate / explain-by / rank-across)
  // → the WHOLE turn routes as one cross-domain question (Layer 1 → Pythia).
  // Detection: relation-cue patterns in the existing cheap rule node; ambiguous
  // cases get the verdict from the existing joint-inference LLM call (one more
  // output field — no new LLM call). Iris decomposes ONLY on SPLIT.
  Decomposition decomposition = 2;
  string decomposition_rationale = 3;
}

enum Decomposition {
  DECOMPOSITION_UNSPECIFIED = 0;
  SPLIT                     = 1;
  KEEP_TOGETHER             = 2;
}

message RefusalWithGaps {                  // NEW
  repeated Gap gaps   = 1;
  string rationale    = 2;
  string traceId      = 3;
}

message Gap {                              // NEW
  GapKind kind       = 1;
  string description = 2;
  optional string suggested_action = 3;
}

enum GapKind {                             // NEW
  GAP_KIND_UNSPECIFIED   = 0;
  ENTITY_UNMAPPED        = 1;
  CAPABILITY_UNAVAILABLE = 2;
  OUT_OF_DATA_SCOPE      = 3;
  AMBIGUOUS_INTENT       = 4;
  NO_ENTITLED_AGENT      = 5;  // PD-8 (2026-06-12): no agent survives the visibility_roles
                               // filter. Policy = reveal existence, deny access: when the user
                               // explicitly names an inaccessible domain, the Gap.description
                               // names it ("the HR domain exists; your account has no access").
                               // See kantheon-security.md §3.2.
}

// =========================================================================
// Routing decision (Phase 3)
// =========================================================================
enum Profile {                             // NEW
  PROFILE_UNSPECIFIED  = 0;
  CHAT_QUICK           = 1;   // Iris's fast path; routeToAgent runs
  INVESTIGATION_DEEP   = 2;   // Pythia's deep resolution; routeToAgent skipped
}

// AgentId MOVED to org.tatrman.kantheon.common.v1 (cohesion review 2026-06-12, D2):
// envelope/v1 references it (RoutingPickChip) and must not import themis/v1 —
// that import transitively dragged cz.dfpartner.nlp/metadata protos into the
// FE's envelope-ts bundle. common/v1 is the declared bottom layer; the one-field
// wrapper lives there. Definition:
//   message AgentId { string value = 1; }   // in common/v1/handoff.proto
// All references below are org.tatrman.kantheon.common.v1.AgentId (aliased
// "common.v1.AgentId" in this doc for brevity).

message RoutingDecision {                  // NEW
  org.tatrman.kantheon.common.v1.AgentId chosen_agent_id = 1;
  repeated AgentAlternate alternates = 2;
  string rationale                   = 3;  // surfaced to user as "I sent this to X because…"
  double confidence                  = 4;
  bool   needs_user_pick             = 5;  // true → Iris renders alternates as RoutingPickChips
  int32  layer_hit                   = 6;  // 0/1/2/3 — for observability + debugging
}

message AgentAlternate {                   // NEW
  org.tatrman.kantheon.common.v1.AgentId agent_id = 1;
  double  score    = 2;
  string  why      = 3;   // one-line rationale for chip subtext
}

enum IntentKind {                          // NEW (also in capabilities/v1; same enum semantics)
  INTENT_KIND_UNSPECIFIED = 0;
  PROCEDURAL              = 1;
  RCA                     = 2;
  FORECAST                = 3;
  SIMULATION              = 4;
}

// =========================================================================
// Continuation token (themis_prior_context)
// =========================================================================
message ResolutionContinuation {
  string token = 1;        // HMAC-signed JSON; opaque to callers
}

// =========================================================================
// Entity binding (unchanged from Resolver)
// =========================================================================
message EntityBinding {
  string span = 1;
  ContextSpan context_span = 2;
  oneof binding {
    UniversalEntity universal = 3;
    DomainEntity    domain    = 4;
  }
}

message UniversalEntity {
  string label             = 1;
  string source_engine     = 2;
  string normalized_value  = 3;
}

message DomainEntity {
  string entity_type           = 1;
  string resolved_id           = 2;
  string resolved_label        = 3;
  double match_score           = 4;
  repeated DomainEntityAlt alternatives = 5;
}

message DomainEntityAlt {
  string resolved_id    = 1;
  string resolved_label = 2;
  double match_score    = 3;
}

message ContextSpan {
  int32 begin_token = 1;
  int32 end_token   = 2;
  int32 begin_char  = 3;
  int32 end_char    = 4;
}

message PartialBinding {
  string span      = 1;
  string entity_id = 2;
  double confidence = 3;
}

message ClarificationOption {
  string id    = 1;
  string label = 2;
  optional string description = 3;
}

// =========================================================================
// Caller-supplied Registry (unchanged from Resolver)
// =========================================================================
message Registry {
  repeated FunctionSpec functions       = 1;
  repeated EntityTypeSpec entity_types  = 2;
  string registry_version               = 3;
}

message FunctionSpec {
  string id                = 1;
  string description       = 2;
  repeated ParamSpec params = 3;
}

message ParamSpec {
  string name      = 1;
  string type_hint = 2;       // "string" / "number" / "date" / "entity:customer"
  bool   required  = 3;
  string description = 4;
}

message EntityTypeSpec {
  string id                      = 1;
  string fuzzy_matcher_namespace = 2;
  repeated string aliases        = 3;
}
```

**Field-number contract (authoritative — as landed in Stage 3.1; supersedes the pre-shipped numbers in the block above):**

- `Resolution.intent_kind = 10` and `Resolution.routing = 11` — leave 7–9 free for additive Resolver-side fields. Do not renumber.
- `ResolveResponse.outcome.refusal = 6` — the next free slot in the message (oneof members 2/3 plus top-level `trace_id = 4`/`elapsed_ms = 5` are taken; `messages = 99` reserved).
- `ResolveRequest.profile = 7`, `routing_hint = 8`, `prior_context = 9` (HandoffContext) — additive; field 6 is the shipped `mode` (ResolveMode). Default unmarshalling of `profile == PROFILE_UNSPECIFIED` resolves to `CHAT_QUICK` in code.
- `ResumeAnswer.picked_agent = 4` — flat additive field (no `answer` oneof on the shipped base).
- `AwaitingClarification.multi_question = 8` — sole member of a new `kind` oneof.
- `ResolveContext.hitl = 4` (Stage 3.4) — a themis-local `HitlProfile` enum (UNSPECIFIED/INTERACTIVE/SPECULATIVE/STRICT, mirroring capabilities/v1's). Field 4 (locale/recent_entities/recent_turns are 1–3). UNSPECIFIED → INTERACTIVE in code; STRICT makes `decideHitlOrEmit` emit `RefusalWithGaps` on any blocker. The "richer ResolveContext" in the block above put `hitl` at field 2 — that base never shipped; 4 is the additive slot.
- All additions are wire-compatible with `themis/v0.1.0`: a v0.1.0 client deserialises a Phase-3 response with the new fields at their defaults (`intent_kind = INTENT_KIND_UNSPECIFIED`, `routing`/`refusal` unset, `hitl = HITL_PROFILE_UNSPECIFIED`) and does not crash.

### 1.3 `org.tatrman.kantheon.envelope.v1` (extended in Phase 3)

File: `shared/proto/src/main/proto/org/tatrman/kantheon/envelope/v1/envelope.proto`

Phase 3 Stage 3.6 adds **only** the `RoutingPickChip` type. The full envelope schema (`FormatEnvelope`, `Block`, other `Chip` kinds, `Drilldown`, `TableDetails`, `ChartDetails`, `ChartIntent`) is the Golem-rewrite / Iris-extraction arc's responsibility — out of scope here. Phase 3 lays down only the chip type so the Themis-Iris co-design round-trips.

```proto
// Addition only — full file lives in the broader envelope/v1 schema.

message RoutingPickChip {
  org.tatrman.kantheon.common.v1.AgentId agent_id = 1;  // moved from themis/v1 — cohesion review 2026-06-12
  string label = 2;     // user-facing display name from capabilities-mcp
  string why   = 3;     // one-line rationale; matches AgentAlternate.why verbatim
}
```

Phase 3 Stage 3.6 also defines the `Chip.kind` oneof extension to host `RoutingPickChip` — coordinated with the Iris BFF co-design owner.

## 2. capabilities-mcp surfaces

The capabilities-mcp service exposes its registry via two equivalent surfaces: an **MCP tool** surface (for LLM agents) and a **REST mirror** (for non-MCP consumers and for ad-hoc testing).

### 2.1 MCP tool surface

All tools follow the Kotlin MCP SDK pattern with the MCP/Ktor base from `ktor-configurator` (`mcp-server-base` does not exist — corrected 2026-06-12; see `ai-platform/EXAMPLES.md` §3). Input/output is JSON; structured input is validated against the proto schemas above.

#### `capabilities.search`

Returns ranked `Capability` entries mixing tools and agents.

```jsonc
// Input:
{
  "intentKinds": ["PROCEDURAL", "RCA"],            // optional
  "entityTypes": ["customer"],                      // optional
  "capabilityTags": ["model.fit.*"],                // optional
  "filter": {
    "includeTools": true,
    "includeAgents": true,
    "includePruned": false
  }
}

// Output (structuredContent):
{
  "entries": [
    { "kind": "tool",  "tool": { /* ToolCapability */ } },
    { "kind": "agent", "agent": { /* AgentCapability */ } }
  ],
  "messages": []
}
```

#### `capabilities.list`

```jsonc
// Input:
{
  "category": "model.fit.*",                        // optional; tool-only
  "filter": { /* CapabilityFilter */ }
}

// Output:
{
  "entries": [ /* same shape as search */ ],
  "messages": []
}
```

#### `capabilities.list_agents`

Convenience for Themis's `routeToAgent` Layer 1.

**Routing-view rules (2026-06-12):** before any layer runs, Themis derives its routing view from `list_agents()` by (1) dropping `non_routable: true` entries (Hebe arc) and (2) dropping entries whose `visibility_roles` don't intersect the caller's roles (PD-8 — invisible, not forbidden; never scored, never in a Layer 2 prompt, never a Layer 3 alternate). Empty view → `RefusalWithGaps(NO_ENTITLED_AGENT)` with reveal-existence-deny-access wording when the user explicitly named a domain. See [`kantheon-security.md`](../kantheon-security.md) §3.

```jsonc
// Input:
{
  "filter": { "includePruned": false }
}

// Output:
{
  "agents": [ /* AgentCapability[] */ ],
  "messages": []
}
```

#### `capabilities.get`

```jsonc
// Input:
{ "id": "pythia" }            // or "model.fit.arima:v1"

// Output:
{
  "capability": { /* Capability or null */ },
  "messages": []
}
```

`get` returns pruned entries too (audit semantics).

#### `capabilities.register`

```jsonc
// Input:
{
  "capability": {
    "kind": "tool",
    "tool": { /* ToolCapability — registration_id and last_heartbeat_at ignored on register */ }
  }
}

// Output:
{
  "registrationId": "550e8400-e29b-41d4-a716-446655440000",
  "messages": []
}
```

Idempotent: re-register with same `capability_id` (or `agent_id`) replaces the existing entry; same `registration_id` is returned.

#### `capabilities.heartbeat`

```jsonc
// Input:
{ "registrationId": "550e8400-..." }

// Output:
{ "acceptedAt": "2026-05-15T12:00:00Z", "messages": [] }
```

Refreshes `last_heartbeat_at`. Unknown `registration_id` returns a 404-equivalent `messages` entry + empty `acceptedAt`.

### 2.2 REST mirror

Each MCP tool has a REST equivalent at `/v1/capabilities/...`. Request/response shapes are the same JSON. Auth via existing platform mechanism (Keycloak bearer token forwarded; service-internal calls trusted within the cluster boundary).

```
POST   /v1/capabilities/search                  → SearchResponse
GET    /v1/capabilities                          → ListResponse  (query params: category, filter.*)
GET    /v1/capabilities/agents                   → ListAgentsResponse  (query params: filter.*)
GET    /v1/capabilities/{id}                    → GetResponse
POST   /v1/capabilities/register                → RegisterResponse
POST   /v1/capabilities/{registrationId}/heartbeat → HeartbeatResponse

GET    /health                                   → 200 once Ktor + registry are up
GET    /ready                                    → 200 once YAML fixtures finish loading
```

All endpoints return `messages: []` per Rule 6. Tracing headers (`traceparent`) honoured per OTel convention.

## 3. Manifest YAML schema

Source-controlled fixtures live at `tools/capabilities-mcp/src/main/resources/manifests/`. Two file layouts: `agents/<agent_id>.yaml` for `AgentCapability`, `tools/<capability_id>.yaml` for `ToolCapability`.

### 3.1 AgentManifest YAML (Pythia)

```yaml
# tools/capabilities-mcp/src/main/resources/manifests/agents/pythia.yaml
agent_kind: INVESTIGATOR
agent_id: pythia
display_name: "Pythia"
intent_kinds_supported: [RCA, FORECAST, SIMULATION, PROCEDURAL]  # PROCEDURAL only for cross-domain
description_for_router: |
  # Bora fills: 1-paragraph description of when to route to Pythia.
  # Used by Themis Layer 2 LLM prompt as a few-shot anchor.
example_questions:
  # Bora fills: ~10 positive examples
  - "Proč klesly tržby Castrolu v soukromých garážích?"
  - "Forecast our Q3 revenue from the German market"
  - "What if we drop the Sheron line in Slovakia?"
counter_examples:
  # Bora fills: ~5 examples of questions that should NOT route to Pythia
  - "Které faktury Shell ještě neuhradil?"        # single-domain → Golem-ERP
  - "Jaké je balení 5lt Sheron Rally?"             # product lookup → Golem-ERP
capability_refs:
  - model.fit.arima:v1
  - model.fit.prophet:v1
  - decompose.variance:v1
  - move.materialize.seaweed:v1
service_endpoint: "http://pythia.kantheon.svc.cluster.local:7301"
health_check_path: /health
typical_latency_ms: 30000
typical_cost_usd: 0.15
hitl_default: INTERACTIVE
```

### 3.2 ShemManifest YAML (Golem-ERP)

```yaml
# tools/capabilities-mcp/src/main/resources/manifests/agents/golem-erp.yaml
agent_kind: AREA_QA
agent_id: golem-erp
display_name: "Golem-ERP"
intent_kinds_supported: [PROCEDURAL]
description_for_router: |
  # Bora fills: scope of ERP domain Q&A.
example_questions:
  # Bora fills: ~10 positive examples
  - "Které faktury Shell ještě neuhradil?"
  - "Jaké jsou objednávky od Kauflandu za tento měsíc?"
  - "Show me unpaid invoices for customer Shell"
counter_examples:
  # Bora fills: ~5 examples that should NOT route to Golem-ERP
  - "Proč klesly tržby Castrolu?"                 # RCA → Pythia
  - "What's the wage cost on CC 4902?"            # HR domain → Golem-HR (future)
capability_refs:
  - query.named:v1
  - query.compile:v1
  - render.table:v1
  - render.chart:v1
service_endpoint: "http://golem-erp.kantheon.svc.cluster.local:7401"
health_check_path: /health
typical_latency_ms: 5000
typical_cost_usd: 0.02
hitl_default: INTERACTIVE

# ─── Shem-specific ───
area_name: "ERP"
area_entities:
  # Bora fills: entity-type IDs from ai-platform metadata
  - customer
  - supplier
  - product
  - invoice
  - sales_order
  - purchase_order
  - cost_center
area_terminology:
  # Bora fills: domain-specific term definitions
  - term: "objednávka"
    definition: "v ERP může znamenat zákaznickou objednávku (sales_order) NEBO objednávku k dodavateli (purchase_order)"
    synonyms: ["order"]
preferred_queries:
  # Bora fills: curated metadata-mcp query IDs in scope
  - listUnpaidInvoices
  - listOrdersByCustomer
  - listProductsByCategory
preferred_capabilities:
  # Bora fills: curated ToolCapability IDs
  - query.named:v1
  - query.compile:v1
style_addendum: |
  # Bora fills: voice/presentation only. NEVER correctness-affecting knowledge.
  # E.g.: "Czech responses default to plural-formal address; tables show currency in CZK by default."
locale_defaults:
  - locale: cs-CZ
    greeting: "Dobrý den, jak vám mohu pomoci?"
    date_format: "dd.MM.yyyy"
    currency: "CZK"
  - locale: en
    greeting: "Hi, how can I help?"
    date_format: "yyyy-MM-dd"
    currency: "EUR"
```

### 3.3 ToolCapability YAML (example, populated post-arc)

Not required to land in Phases 1–3 (only the YAML loader and one heartbeating tool — query-mcp — for the PoC), but the schema is fixed now:

```yaml
# tools/capabilities-mcp/src/main/resources/manifests/tools/query.named.yaml
capability_id: query.named:v1
category: query.*
version: "v1"
preconditions:
  - expression: "registry.entity_types includes the named-query's bound entities"
    description: "The caller's Registry must include the entity types the named query is defined over."
cost_hints:
  typical_latency_ms: 800
  typical_cost_usd: 0.001
  is_idempotent: true
  max_concurrent: 50
search_tags:
  - sql
  - named-query
  - parametric
service_endpoint: "http://query-mcp.ai-platform.svc.cluster.local:7201"
description: |
  Executes a parameterised named query from metadata-mcp's catalog with optional
  TransDSL stack (Filter/Project/Sort).
```

### 3.4 Loader rules

- Boot-time scan of `src/main/resources/manifests/{agents,tools}/`.
- Each YAML file deserialised against the proto schema (Jackson YAML + kotlinx.serialization).
- Validation failures: emit a metric (`capabilities_yaml_validation_failed_total{file}`), log the error, **skip** the file (do not crash the service).
- The fixture's content is merged into the in-memory registry with `last_heartbeat_at == null` (exempt from TTL pruning).
- Runtime registrations (`capabilities.register`) override fixture content if `capability_id` or `agent_id` collides — **runtime wins** (production over fixture).
- Readiness probe (`GET /ready`) returns 200 only after all fixtures are loaded. K8s `readinessProbe` blocks dependents during cold start.

## 4. Heartbeat client library

File: `shared/libs/kotlin/capabilities-client/src/main/kotlin/org/tatrman/kantheon/capabilities/client/`

Published as `cz.tatrman:capabilities-client` to GitHub Packages (kantheon's publisher namespace).

### 4.1 Public API

```kotlin
package org.tatrman.kantheon.capabilities.client

import org.tatrman.kantheon.capabilities.v1.Capability

/**
 * Drop-in startup hook for any service to register itself with kantheon's capabilities-mcp.
 *
 * Usage in a Ktor service's App.kt:
 *   CapabilitiesClient.startupRegister(
 *       capability = loadOwnManifest(),
 *       endpoint = config.capabilities.endpoint,
 *       heartbeatIntervalMs = 30_000,
 *   )
 *
 * - Registers idempotently on startup.
 * - Schedules periodic heartbeat in a coroutine on Dispatchers.IO.
 * - On registration failure: exponential backoff (1s, 2s, 4s, ..., max 60s) and KEEP RETRYING in the background.
 * - The service ALWAYS starts even if capabilities-mcp is unreachable (warn-and-continue).
 * - Returns a `CapabilitiesClientHandle` for status inspection and graceful shutdown.
 */
object CapabilitiesClient {
    fun startupRegister(
        capability: Capability,
        endpoint: String,
        heartbeatIntervalMs: Long = 30_000,
        otelTracer: Tracer? = null,
    ): CapabilitiesClientHandle
}

/** Read-mostly client for consumers (Themis, Iris-BFF). */
class CapabilitiesReadClient(
    private val endpoint: String,
    private val cacheTtlMs: Long = 30_000,
) {
    suspend fun listAgents(): ListAgentsResponse           // throws on unreachable; cache served otherwise
    suspend fun search(req: SearchRequest): SearchResponse
    suspend fun get(id: String): GetResponse
    fun invalidateCache()
}

class CapabilitiesClientHandle {
    val registrationId: String?
    val lastHeartbeatStatus: HeartbeatStatus               // OK / STALE / FAILED
    fun shutdown()                                          // de-registers + stops heartbeat
}

enum class HeartbeatStatus { OK, STALE, FAILED, NEVER_REGISTERED }
```

### 4.2 Wire shape

`CapabilitiesClient` calls capabilities-mcp via the REST mirror (`POST /v1/capabilities/register` + `POST /v1/capabilities/{regId}/heartbeat`). HTTP rather than MCP because clients are services, not LLM agents.

### 4.3 Configuration

In each consuming service's `application.conf`:

```hocon
capabilities {
  endpoint = "http://capabilities-mcp.kantheon.svc.cluster.local:7501"
  endpoint = ${?CAPABILITIES_MCP_URL}
  heartbeat_interval_ms = 30000
  read_cache_ttl_ms = 30000
}
```

### 4.4 Themis-side use (Phase 3)

Themis at boot:

```kotlin
// agents/themis/src/main/kotlin/.../App.kt
val capabilitiesRead = CapabilitiesReadClient(endpoint = config.capabilities.endpoint)

// Fail-fast at boot per task 5 of Stage 3.3:
runBlocking {
    val agents = capabilitiesRead.listAgents().agents
    require(agents.isNotEmpty()) {
        "capabilities-mcp returned no agents at startup — refusing to start to avoid silent routing-everything-to-Layer-3"
    }
}
```

Themis does **not** register itself in v1 (no Themis manifest — it's the router, not a routed-to agent). Phase 4+ may add a `themis` agent entry for introspection.

## 5. Themis HMAC resume token

Carried over verbatim from Resolver (`themis-design.md` §HITL contract). Phase 2 extraction does not change shape; Phase 3 adds one new field for the cross-agent profile handoff.

```jsonc
// Decoded payload of ResolutionContinuation.token (HMAC-SHA256 signed):
{
  "version": 1,
  "issuedAt": "2026-05-15T12:00:00Z",
  "expiresAt": "2026-05-15T12:30:00Z",
  "conversationId": "...",
  "questionHash": "sha256:...",
  "parseHash": "sha256:...",
  "spanCandidates": [/* per-span fuzzy candidates from this round */],
  "universalEntities": [/* normalised universal entities */],
  "askedAbout": { "spanIndex": 2, "ambiguityKind": "..." },
  "roundIndex": 1,
  "maxRounds": 3,

  // Phase 3 additions:
  "profileAtIssue": "CHAT_QUICK",
  "themisPriorContext": "...",   // opaque; for Pythia → Themis(INVESTIGATION_DEEP) handoff
  "alternatesOffered": ["pythia", "golem-erp"]  // for Layer 3 chip-pick validation
}
```

HMAC key rotation is out of scope for Phases 1–3 (carries over from Resolver design).

## 6. Persistence shapes

None in Phases 1–3.

- capabilities-mcp is in-memory only at v1; restart wipes runtime registrations (fixtures reload from YAML on every start).
- Themis is stateless across requests (resume tokens are stateless).

This is deliberate. Postgres-backed registry persistence is a v1.5+ concern (`tools/capabilities-mcp` `Out of scope` in original task doc).

## 7. Eval-corpus schemas

Two eval-corpus files; both JSONL.

### 7.1 Resolution-quality corpus (Phase 2 — carry-over from Resolver Stage 03)

File: `agents/themis/eval/corpus/seed.jsonl` (50 entries; carried over from `ai-platform/infra/nlp/eval/corpus/seed.jsonl`).

```jsonc
{
  "question": "Které faktury Shell ještě neuhradil?",
  "lang": "cs",
  "expected": {
    "tokens": [/* expected parse tokens */],
    "lemmas": [/* expected lemmas */],
    "entities": [
      { "span": [3, 4], "type": "customer", "resolvedLabel": "Shell UK PLC" }
    ],
    "functionId": "listUnpaidInvoices",
    "args": { "customerId": "<placeholder>" }
  }
}
```

### 7.2 Routing corpus (Phase 3 — new)

File: `agents/themis/eval/corpus/routing-seed.jsonl`.

```jsonc
// Each line is one routing test case.
{
  "question": "...",
  "lang": "cs|en",
  "expected": {
    "intent_kind": "PROCEDURAL|RCA|FORECAST|SIMULATION",
    "chosen_agent_id": "pythia|golem-erp|golem-hr|golem-sales",
    "alternates_present": [/* optional: agents that should appear as alternates */],
    "routing_layer_expected": 1
  }
}
```

Buckets (Phase 3 Stage 3.5 scaffolds the buckets as comment-headers; Bora populates ~30 questions per bucket):

```
# PROCEDURAL — single Golem-ERP domain
# PROCEDURAL — cross-domain (should route to Pythia)
# RCA (should route to Pythia)
# FORECAST (should route to Pythia)
# SIMULATION (should route to Pythia)
# Ambiguous (should fire Layer 3 needs_user_pick)
```

CI gate thresholds (Phase 3 Stage 3.5):

- Routing accuracy ≥ baseline (set after first run on populated corpus).
- Layer 1 hit-rate ≥ 60% on non-ambiguous buckets.
- Intent-kind classification accuracy ≥ baseline.

## 8. Prompts (externalised)

Themis ships externalised prompts under `agents/themis/prompts/`. Each is loaded at boot and recompiled on file change in dev mode.

### 8.1 Files added/extended in this arc

| File | Phase | Used by |
|---|---|---|
| `prompts/joint_inference.md` | extracted from Resolver | `jointInference` (FAST) |
| `prompts/filter_relevant_spans.md` | extracted from Resolver | `filterRelevantSpans` (CHEAP) |
| `prompts/intent_kind_rules.yaml` | Phase 3 Stage 3.2 | `classifyIntentKind` rules layer |
| `prompts/intent_kind_llm.md` | Phase 3 Stage 3.2 | `classifyIntentKind` LLM fallback (CHEAP) |
| `prompts/route_to_agent_layer2.md` | Phase 3 Stage 3.3 | `routeToAgent` Layer 2 (CHEAP) |

### 8.2 `intent_kind_rules.yaml` schema

```yaml
# agents/themis/prompts/intent_kind_rules.yaml
rules:
  - intent: RCA
    triggers:
      cs: ["proč", "co způsobilo"]
      en: ["why", "what caused"]
    operates_on: lemmas    # always lemmas, never raw text
  - intent: FORECAST
    triggers:
      cs: ["predikce", "prognóza", "očekávat"]
      en: ["forecast", "predict", "expect"]
    operates_on: lemmas
    extra_signals:
      - future_tense_temporal_reference
      - explicit_future_date
  - intent: SIMULATION
    triggers:
      cs: ["co kdyby"]
      en: ["what if"]
    operates_on: lemmas
    extra_signals:
      - hypothetical_conditional
  - intent: PROCEDURAL
    is_default: true
```

`extra_signals` are computed inside the `classifyIntentKind` Koog node from `parse.tokens` (POS + dependency graph). LLM fallback runs only when rules tie or no rule fires.

## 9. Build & version contracts

### 9.1 Gradle Maven coordinates (kantheon-published)

If kantheon ever publishes back to Maven (for koklyp/Hebe future consumption), the coordinates are:

```
cz.tatrman:capabilities-client  - shared/libs/kotlin/capabilities-client
cz.tatrman:kantheon-proto       - shared/proto (all packages bundled)
```

Not published in Phases 1–3 — internal to kantheon's multi-module build.

### 9.2 Gradle Maven coordinates (ai-platform-consumed)

Resolved via GitHub Packages declared in `kantheon/settings.gradle.kts`:

```
cz.dfpartner:shared-proto       - nlp/v1, metadata/v1, etc.
cz.dfpartner:otel-config
cz.dfpartner:fuzzy-common
cz.dfpartner:ktor-configurator   # incl. the MCP/Ktor base (mcp-server-base does not exist — corrected 2026-06-12)
cz.dfpartner:ktor-configurator
cz.dfpartner:logging-config
```

Version refs centralised in `kantheon/gradle/libs.versions.toml`. See `architecture.md` §13 → ai-platform gap-closure plan Gap 1 for the full consumer-side setup.

### 9.3 Git tag scheme (kantheon-side)

Per ai-platform pattern: `<service>/v<x.y.z>`. First releases:

- `capabilities-mcp/v0.1.0` — Phase 1 Stage 1.4 close.
- `themis/v0.1.0` — Phase 2 Stage 2.4 close (Koog-based, no routing yet).
- `themis/v0.2.0` — Phase 3 Stage 3.6 close (routing live).

---

*Contracts-doc owner: Bora. Source of truth for Phases 1–3 cross-service wire. Update on every contract change; bump versions per semver discipline.*
