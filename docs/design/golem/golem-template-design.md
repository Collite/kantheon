# Golem template — v1 Design

> **⚠ Reality note (2026-06-12).** The porting source is **`ai-platform/agents/golem` (new-golem v2, `graph_v2.py`)** — not the legacy `/Users/bora/Dev/golem` repo this doc cites. new-golem v2 added (post this doc): package context via `metadata.GetModel`, five plan sources (`pattern | free_sql | amend | drill | clarification`) with confidence gates, chips derived inside `format`, clarification resume with HMAC tokens, row-detail drilldowns. The Kotlin rewrite ports **v2 semantics** into the Shem-template shape described here; deltas are tabulated in [`../../architecture/golem/architecture.md`](../../architecture/golem/architecture.md) §4. Also locked 2026-06-12: Golem **trusts Themis upstream** (no direct Resolver/Themis call; v2's `extract_entities` node has no Kotlin descendant), and `ConversationalResponse` carries **envelopes** (not bare blocks) per [`../../architecture/golem/contracts.md`](../../architecture/golem/contracts.md) §1.
>
> **⚠ Reality note (2026-06-13) — the Shem is model + prompts, both from Ariadne.** In ai-platform the domain TTR model and the agent prompts both live in one Git repo (`DFPartner/ai-models`: `model-ttr/<package>/` + `prompts/golem/`) but load through **two separate mechanisms** — the metadata service polls the model, while each Golem pod runs its own git-fetch for prompts (`prompt_source.py`, `GOLEM_PROMPTS_GIT_*`), bundled YAML as fallback. Kantheon consolidates: **Ariadne (the forked metadata service) owns the whole repo and serves both the model (`GetModel`) and the prompts (`GetPrompts`/`get_prompts`)**, so the runtime **Shem = curated model + prompts, loaded from one service**. Golem drops its per-pod prompt git-fetch; bundled YAML is demoted to pure offline fallback. See [`../../architecture/fork/contracts.md`](../../architecture/fork/contracts.md) §1.1 and [`../../architecture/golem/architecture.md`](../../architecture/golem/architecture.md) §4.1. §2 and §7 below are updated; §9 records the prompt-fetch client as not-ported.
>
> **Status:** draft v0.1 — forward-looking design. Captures the post-extraction, post-rewrite shape of the Golem template. Sequenced *after* Themis ships (Resolver Stage 04 → extraction → kantheon-side routing phase). Synthesised 2026-05-11 from [`../../architecture/kantheon-architecture.md`](../../architecture/kantheon-architecture.md), the Pythia v1 design's §6.2 ("Golem template service"), and the existing Python BE in `/Users/bora/Dev/golem/backend/src/agent/`.
>
> **Source materials:** [`../../architecture/kantheon-architecture.md`](../../architecture/kantheon-architecture.md), [`../pythia/Pythia-v1-Design.md`](../pythia/Pythia-v1-Design.md) §6.2, `golem/docs/aip-v1/Analytical Agent on V1.md` (V1-era predecessor), `golem/docs/v2/architecture.md` (current Python BE shape), `golem/backend/src/agent/` (the code that ports).

## 1. Vision

Golem is the **parameterised per-domain Q&A agent template** in Kantheon. One codebase; one Kubernetes pod per **Shem**. Each pod loads its `ShemManifest` at boot, registers itself in `capabilities-mcp` under `kind: AGENT, agent_kind: DOMAIN_QA`, and serves requests from Iris for procedural single-domain questions in its domain.

Three deployments at v1: Golem-ERP, Golem-HR, Golem-Sales. **Adding a new domain is a new ShemManifest YAML + a new pod with the Golem template image and that Shem mounted. No code change.**

Golem is **not** a general-purpose agent. Each instance is scoped to one domain by its Shem. Cross-domain questions and analytical questions (RCA / forecast / simulation) route to Pythia, not to multiple Golems.

Golem is **stateless about plans** — no hypothesis-driven planning, no plan revision, no pause-resume checkpoints. Each turn is a small mini-plan executed straight through; the only persistence is one `ConversationalResponse` row per turn for audit and follow-up turns.

## 2. The Shem

A Shem (Hebrew/Yiddish: inscription) is the curated domain knowledge that brings a Golem to life. Its proto schema is `ShemManifest` in `org.tatrman.kantheon.capabilities.v1`:

```
ShemManifest extends AgentManifest (kind = AGENT, agent_kind = DOMAIN_QA) {
  // From AgentManifest base:
  agent_id:               AgentId           // "golem-erp" / "golem-hr" / …
  display_name:           string
  intent_kinds_supported: [IntentKind]      // typically [PROCEDURAL]
  description_for_router: string
  example_questions:      [string]          // positive few-shot anchors for Themis
  counter_examples:       [string]          // negative few-shot anchors
  capability_refs:        [CapabilityId]
  service_endpoint:       string
  health_check_path:      string
  typical_latency_ms:     int
  typical_cost_usd:       float
  hitl_default:           HitlProfile

  // ShemManifest-specific (correctness-affecting domain knowledge):
  domain_name:            string            // "ERP" / "HR" / "Sales"
  domain_entities:        [EntityTypeRef]   // entity types in scope
  domain_terminology:     [TermDef]         // domain-specific term definitions
  preferred_queries:      [QueryRef]        // curated subset of metadata-mcp's query catalog
  preferred_capabilities: [CapabilityId]    // curated subset of tool capabilities

  // Style only — voice / locale / formatting; NEVER correctness-affecting knowledge:
  style_addendum:         string
  locale_defaults:        [LocaleDefault]
}
```

**Discipline rule** (load-bearing — repeated from Pythia design): anything that affects answer correctness lives in structured fields (`domain_entities`, `domain_terminology`, `preferred_queries`, `preferred_capabilities`). Only voice and presentation live in `style_addendum`. This rule is what makes Pythia's master-of-Golems pattern work — Pythia, when planning a cross-domain question, reads each Golem's `ShemManifest` from `capabilities-mcp` and uses the structured knowledge directly without delegating execution.

`example_questions` and `counter_examples` earn their keep at routing time — Themis's Layer 2 LLM router uses both as few-shot anchors when two Golems' positive sets overlap.

### 2.1 Two faces of the Shem (2026-06-13)

"Shem" names two artefacts that travel together but live in different places:

1. **The routing manifest** — the `ShemManifest` proto above, registered into `capabilities-mcp` at boot. Structural, small, read by **Themis** (routing) and **Pythia** (cross-domain planning). This is the Shem's *public face*: "what this Golem is and can answer."

2. **The runtime domain knowledge** — the curated **TTR model + prompts** the pod loads to actually answer turns. Both come from **Ariadne** (the forked metadata service), which owns the `DFPartner/ai-models` Git repo end-to-end: the model via `GetModel(packages)` → `ModelBundle` → `PackageContext`, and the prompts via `GetPrompts(agent_id, locale)` → raw prompt YAML. This is the Shem's *private face*: the knowledge that makes the answers correct.

The split matters because of the **discipline rule**: correctness-affecting knowledge belongs in the structured model (entities, patterns, queries) and the prompts, both now served from one place (Ariadne); the manifest carries only routing-relevant projections of it (`preferred_queries`, `domain_terminology`) plus style. Consolidating the runtime side onto Ariadne means a domain edit (new pattern, reworded prompt) is **one Git push to `ai-models` + one `/refresh`** — no per-pod prompt repo, no redeploy, and the same refresh path for model and prompts. Bundled prompt YAML survives only as an offline fallback when Ariadne is unreachable at boot.

## 3. Request / response contracts

API surface, sealed in `org.tatrman.kantheon.golem.v1`:

```
golem.answer(request: GolemRequest) → ConversationalResponse        // streamed, block-by-block

GolemRequest {
  id:                UUID                            // ties to the conversation turn
  golem_id:          AgentId                         // matches the pod's loaded Shem
  question:          string                          // verbatim user question
  resolved_intent:   ResolvedIntent                  // from Themis, scoped to this Golem's domain
  context: {
    entity_context:        EntityRef?
    conversation_excerpt:  [Turn]?
    locale:                string                    // "cs" / "en"
  }
  caller:            { user_id, tenant_id, correlation_id }
  hitl_policy: {
    on_suspicious_result: CONTINUE | WARN
    // No plan_approval (no plan), no plan_revision (no revision),
    // no disambiguation (Themis already handled it upstream).
  }
  constraints: {
    latency_budget_ms:  int?
    max_step_count:     int?                         // runaway prevention
  }
}

ConversationalResponse {
  id:               UUID
  request_id:       UUID
  golem_id:         AgentId
  blocks:           [Block]                          // streamed; same Block contract as Pythia's RenderableArtifact
  step_records:     [StepRecord]                     // transparency: which queries we ran
  warnings:         [PlatformWarning]
  resource_usage:   ResourceUsage
  status:           DONE | FAILED                    // no HALTED — Golem doesn't pause
  finalised_at:     timestamp
}
```

The `Block` types come from `org.tatrman.kantheon.envelope.v1` — same as Pythia, same as what Iris consumes. One envelope contract for the constellation.

`AgentResponse = ConversationalResponse | InvestigationArtifact` at Iris's consumption surface — sealed Kotlin type; Iris renders both via the shared `Block` contract.

## 4. Internal loop — mini-plan

Golem's execution is a **mini-plan**: 1–4 typed nodes, composed using the same primitives as Pythia (`QueryNode`, `RenderNode`, occasionally `ReasoningNode`), but **without** hypotheses, plan revision, suspicion classification, checkpointing, or pause states.

Typical shapes:

```
1-node turn:  RenderNode("hello, how can I help?")         (greeting or capability description)

2-node turn:  QueryNode(customersList) → RenderNode(table)  (procedural list)

3-node turn:  QueryNode(invoicesForCustomer)
              → DataFrameNode(filter unpaid)
              → RenderNode(table)                            (procedural with stack)

4-node turn:  QueryNode(productCatalog)
              → ReasoningNode(extract attribute)
              → QueryNode(invoicesForProductFamily)
              → RenderNode(narrative + table)                (small synthesis)
```

The mini-plan is constructed by a single CHEAP-tier LLM call (`task_kind: GOLEM_PLAN`) given:
- The `ResolvedIntent` from Themis (already has `relevant_named_queries` / `relevant_capabilities` scoped to the domain).
- The Shem's `preferred_queries`, `preferred_capabilities`, `domain_terminology` for grounding.
- The conversation excerpt for context.

Output: structured `MiniPlan` (sealed Kotlin type — `[Node]` sequence with typed data-deps between nodes). Validated against capability schemas; rejected and retried (with feedback) if validation fails, max 2 attempts.

The plan executes straight through. Failed nodes propagate to a FAILED `ConversationalResponse` with the partial-results retained. No revision; no recovery beyond per-node retry on transient errors.

## 5. Streaming protocol

Subset of Pythia's protocol. Events emitted:

| Event | Purpose |
|---|---|
| `mini_plan_drafted` | Initial plan published (transparency for users via the Agent Flow / Graph pane) |
| `step_started` | Node begins execution |
| `step_completed` | Node finishes; row counts, latency, cost |
| `step_failed` | Node failed; reason |
| `block_started` | Format-envelope block begins streaming |
| `block_completed` | Block done; appended to ConversationalResponse |
| `warnings` | Forwarded platform warnings (pipeline_warnings from query-mcp etc.) |
| `agent_response_done` | Stream terminator |

The Iris BFF wraps these in `IrisStreamEvent`s and forwards to the Vue SPA — same path as Pythia's events. No special-case handling on the Iris side: the SPA renders block-by-block whether the source is Golem or Pythia.

## 6. Persistence

One row per turn in Postgres. **No checkpoint table, no event log.**

```
golem_turns {
  id:              UUID PRIMARY KEY
  request_id:      UUID
  golem_id:        TEXT
  user_id:         TEXT
  tenant_id:       TEXT
  question:        TEXT
  resolved_intent: JSONB
  blocks:          JSONB             // ConversationalResponse.blocks
  step_records:    JSONB
  warnings:        JSONB
  resource_usage:  JSONB
  status:          TEXT               // DONE | FAILED
  created_at:      TIMESTAMP
  finalised_at:    TIMESTAMP
}

golem_step_records {                  // optional child table — could just be JSONB in golem_turns
  turn_id:         UUID
  step_index:      INT
  node_kind:       TEXT
  node_spec:       JSONB
  status:          TEXT
  row_count:       INT
  latency_ms:      INT
  cost_usd:        NUMERIC
  PRIMARY KEY (turn_id, step_index)
}
```

Iris reads from these rows when constructing the conversation_excerpt for the *next* turn — fetching the last N turns' summary blocks + entity bindings.

## 7. Tool dependencies

Golem agents call (post-fork persona names; the ai-platform originals are in parentheses):

- `theseus-mcp` (was query-mcp) — execute queries, stackable composition, compile-before-run safety net.
- `ariadne-mcp` (was metadata-mcp) — the **Shem source**: model via `GetModel`/`PackageContext` **and prompts via `get_prompts`** (consolidated 2026-06-13). Also ad-hoc entity / attribute / relation lookup.
- `prometheus` (was llm-gateway) — LLM calls via the modality × tier abstraction.
- `capabilities-mcp` (kantheon) — self-registration at startup only (declares own `ShemManifest`).
- `envelope-render` lib (kantheon `shared/libs/kotlin/envelope-render/`) — Vega-Lite spec construction when emitting chart blocks.

Golem **no longer runs its own prompt git-fetch client** (ai-platform's `prompt_source.py` / `GOLEM_PROMPTS_GIT_*`): prompts arrive from `ariadne-mcp` alongside the model, with bundled YAML as an offline fallback only.

Golem does **not** call `fuzzy-mcp` directly — fuzzy entity resolution happens upstream in Themis. Golem receives already-resolved entities in `request.resolved_intent.entities`.

## 8. Module map (`kantheon/agents/golem/`)

```
agents/golem/
├── src/main/kotlin/org/tatrman/kantheon/golem/
│   ├── App.kt                          # Ktor application entry
│   ├── api/
│   │   ├── AnswerRoutes.kt             # POST /v1/answer (REST) + MCP tool surface
│   │   └── HealthRoutes.kt
│   ├── shem/
│   │   ├── ShemLoader.kt               # load + validate the Shem at boot
│   │   ├── ShemRegistration.kt         # register into capabilities-mcp; heartbeat
│   │   └── ShemContext.kt              # in-memory view of the loaded Shem
│   ├── plan/
│   │   ├── MiniPlanComposer.kt         # LLM-driven plan composition (CHEAP tier)
│   │   ├── PlanValidator.kt            # capability-schema checks
│   │   └── nodes/                      # QueryNode / RenderNode / ReasoningNode / DataFrameNode
│   ├── execution/
│   │   ├── MiniPlanExecutor.kt         # straight-through executor; no pause semantics
│   │   ├── QueryClient.kt              # talks to query-mcp
│   │   ├── MetadataClient.kt           # talks to metadata-mcp
│   │   └── HandleTable.kt              # typed pointers between nodes within a turn
│   ├── format/
│   │   ├── FormatEnvelopeNode.kt       # the format pipeline (LLM tool-call → envelope)
│   │   └── (depends on shared/libs/kotlin/envelope-render)
│   ├── persistence/
│   │   ├── TurnsRepository.kt          # Postgres CRUD for golem_turns
│   │   └── migrations/                 # Flyway migrations
│   ├── streaming/
│   │   └── EventEmitter.kt             # streaming protocol per §5
│   └── auth/                           # Keycloak token forwarding to ai-platform tools
├── src/main/resources/
│   ├── application.conf
│   └── manifests/                      # Shem YAML loaded at boot (per-pod; mounted from K8s ConfigMap)
├── src/test/kotlin/                    # Kotest + Testcontainers
├── build.gradle.kts
├── k8s/
│   ├── base/                           # template manifests
│   └── overlays/
│       ├── local/
│       ├── golem-erp/                  # per-Shem overlay with the Shem mounted
│       ├── golem-hr/
│       └── golem-sales/
└── README.md
```

**Deployment**: one image, multiple pods. Each pod's K8s overlay mounts a different Shem YAML as a ConfigMap into `src/main/resources/manifests/`. The Shem is loaded at startup; the pod's `agent_id` is set from the Shem's `agent_id` field.

## 9. Heritage from current `golem` repo

The post-extraction surface that ports from `golem/backend/src/agent/`:

| Today's Python | Becomes (Kotlin) | Notes |
|---|---|---|
| `pattern_catalog.py` | `plan/MiniPlanComposer.kt` + Shem's `preferred_queries` | Pattern-catalog logic distributes — generic procedural patterns live in MiniPlanComposer; Shem-specific patterns are just preferred queries |
| `nodes.py` (LangGraph nodes for query execution + format) | `execution/MiniPlanExecutor.kt` + `format/FormatEnvelopeNode.kt` | Straight Kotlin port of the execution logic; reuses Pythia's QueryNode/RenderNode primitives from `agent-base` (if/when scaffolded) |
| `format_catalog.py` (Pydantic tools per render kind) | Becomes Kotlin sealed types in `format/FormatEnvelopeNode.kt`, exposed to Koog as structured-output tools | Format catalog moves into envelope-render lib for sharing across agents |
| `typed_action_handler.py` (sort/filter/paginate) | `api/AnswerRoutes.kt` typed-action endpoints | Same semantics: re-issue MCP call with updated params, no LLM, emit new envelope |
| `state.py` (`AgentState` TypedDict — last_envelope, current_view, dry_run) | Plumbed through Ktor request context + per-turn record | Some fields (e.g. `current_view`) likely move to Iris BFF (it owns session) |

**What does NOT port from current `golem` BE:**

- `entities.py` (Czech entity detection) — moves to **Themis** (`agents/themis/`)
- `named_query_utils.py` (named-query selection part) — moves to **Themis**
- `dynamic_chip_resolver_heuristic.py` (chip resolver) — moves to **Iris-BFF** (chip generation is FE-side per the reframe)
- LangGraph framework — replaced by Koog
- `MemorySaver` checkpointer — dropped; Golem doesn't pause
- `/agent/graph` introspection — dropped; Iris's Agent Flow pane is a Pythia concern (Pythia is the one with a complex plan worth visualising)
- `prompt_source.py` + the `PromptStore` git-fetch (the `GOLEM_PROMPTS_GIT_*` client) — **dropped** (2026-06-13). Prompts come from `ariadne-mcp.get_prompts`; the Kotlin `PromptStore` reads Ariadne with a bundled-YAML offline fallback. One repo, one loader (Ariadne), one refresh path.

## 10. The format-catalog Koog spike

The current Python `_build_format_envelope` in `nodes.py` is hard-won — see G-21..G-25 in `golem/docs/v2/v2-overview.md`'s Gotchas chapter. Specifically:

- `desired_format=chart` on text-heavy data exhausts retries → tighten prompt + add deterministic table fallback.
- `tool_choice="any"` may not be honoured by every adapter → wrap with try/except.
- Pass structured rows directly; don't make the LLM re-parse markdown.
- Always have a deterministic fallback path.
- Headers must be a stable list; don't trust the LLM to remember — post-process via `infer_table_headers`.

Before committing to the full Golem rewrite, run a **2–3 day Koog spike** validating these patterns port cleanly to Koog's `StructureFixingParser`:

- Port the four render-kind tools (`RenderPlaintext`, `RenderMarkdown`, `RenderTable`, `RenderChart`) as Kotlin data classes annotated for Koog structured output.
- Implement the retry + deterministic-fallback loop in Koog.
- Test against a fixture LLM with the same problem cases the Python G-21..G-25 lessons came from.
- If Koog's structured-output handles these cleanly, the Golem rewrite is bounded. If it requires significant prompt-engineering to get there, plan accordingly.

The spike lands in `shared/libs/kotlin/envelope-render/` so the patterns are reusable across all backend agents (Pythia and future Golem instances).

## 11. Open items

- **Golem rewrite task doc** — phased breakdown of the Python → Kotlin port. Pending — sequenced after Themis Stage 4.5 closes.
- **First ShemManifest content** (Golem-ERP `domain_entities` / `domain_terminology` / `preferred_queries` / `preferred_capabilities` / `example_questions` / `counter_examples`) — Bora populates from existing tribal knowledge. Stage 4.4 task 4 has the YAML stub locations.
- **Format-catalog Koog spike** — run during Themis Stage 04 window so the lessons land before Golem rewrite starts.
- **Format envelope shared library design** — does `envelope-render` host just the render helpers, or also the format-catalog tool definitions (Kotlin sealed types) for use by both Pythia and Golem? Lean: yes, central definitions in `envelope-render`. Pin in the spike.
- **`current_view` ownership** — Pythia/Golem-side (the agent owns "what dataset is the one we're talking about") versus Iris-BFF-side (the BFF owns the session). Lean: agent-side, per the conversation-state model that puts per-turn data with the agent. Pin in the BFF design.
- **DataFrame analytics path** (today's `aip-v1-impl` Phase 7) — Polars Worker integration for `DataFrameNode`. v1.5 work; not in initial Golem rewrite scope.
- **Multi-Shem deployment automation** — Helm chart with values-per-Shem? GitOps with separate overlay per Shem? Operational design decision pending; not urgent until Golem-HR + Golem-Sales are real.

## 12. Resolved decisions — quick reference

| Decision | Locked | Note |
|---|---|---|
| Kotlin + Koog (not Python + LangGraph) | 2026-05-10 | After Bora's pragmatism hedge was rejected; pros/cons favoured one-framework-across-constellation |
| One pod per Shem | 2026-05-04 | Per Pythia design; multi-tenant Shems inside one pod is v1.5+ |
| Mini-plan, not ReAct | 2026-05-08 | Reuses Pythia's QueryNode/RenderNode primitives sans hypotheses |
| One ConversationalResponse row per turn (no checkpointing) | 2026-05-08 | Golem doesn't pause; no AWAITING_* states |
| Themis takes Czech entities + fuzzy + NQ selection out of today's BE | 2026-05-10 | Most of today's "hard Python" is moving to Themis, not staying in Golem |
| Sequencing: rewrite happens *after* Themis ships | 2026-05-10 | Minimises double work; Themis extraction shrinks the residual Golem surface first |
| Format envelope shared via `envelope-render` lib | 2026-05-11 | Constellation-wide patterns live there; per-agent emit code is thin |
| Same `Block` contract as Pythia | 2026-05-04 | Both `ConversationalResponse.blocks` and `InvestigationArtifact.blocks` use envelope/v1 Block types |
