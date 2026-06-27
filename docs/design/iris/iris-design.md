# Iris — v1 Design

> **⚠ Reality note (2026-06-12).** Two corrections supersede parts of this doc, now reflected in [`../../architecture/iris/architecture.md`](../../architecture/iris/architecture.md) + [`contracts.md`](../../architecture/iris/contracts.md) + [`../../implementation/v1/iris/plan.md`](../../implementation/v1/iris/plan.md):
> 1. **The FE source is `ai-platform/frontends/agents-fe`**, not `golem/frontend/` — agents-fe is the production Vue 3.5 SPA speaking the FormatEnvelope v2 (Stage 07-B) contract over `/v2` SSE. §2/§8/§9 below read accordingly.
> 2. **Iris ships before the Kotlin Golem**: the BFF carries a transitional `/v2` adapter to today's `ai-platform/agents/golem` (Python). The `current_display` (BFF) / `current_view` (agent) ownership question in §10 is **pinned** per architecture §6.1.
>
> **Status:** draft v0.1 — synthesised 2026-05-11 from the Iris-evolution brainstorm decisions captured in [`../../architecture/kantheon-architecture.md`](../../architecture/kantheon-architecture.md), [`iris-brainstorming.md`](./iris-brainstorming.md), and the v2/v2.1 FE work in `/Users/bora/Dev/golem/docs/v2/`.
>
> **Source materials:** [`../../architecture/kantheon-architecture.md`](../../architecture/kantheon-architecture.md) (overall constellation), `golem/docs/v2/architecture.md` (current FE state — direct heritage), `golem/docs/v2/v2-overview.md` (lessons learned), `golem/docs/v2/api-contracts.md` (FormatEnvelope wire format), [`../themis/themis-design.md`](../themis/themis-design.md) (routing-side contract).

## 1. Vision

Iris is the user-facing frontend of the Kantheon constellation. It is a chat-shaped interface — the user types a question, sees a streamed response, drills into rows or chart points, refines via chips, re-issues edited messages, and accumulates conversational context as they go. Iris does *not* answer questions itself; it dispatches each turn to whichever backend agent Themis picks (Pythia for complex / RCA / forecast / simulation, Golem-X for per-domain Q&A) and renders the result.

The name comes from Greek mythology — Iris is the messenger goddess and rainbow bridge between worlds. Fitting: Iris is the bridge between the user and the constellation.

## 2. Physical composition

Iris has two deployable parts:

- **`frontends/iris/`** — Vue 3 + TypeScript single-page application. Dockview workspace (Chat / Tabs / Graph panes), PrimeVue 4 Aura preset, Vega-Lite charts, markdown-it + mermaid for markdown, vue-i18n for Czech + English. Extracted from the current `golem/frontend/` tree.
- **`agents/iris-bff/`** — Kotlin + Ktor backend-for-frontend service. Holds conversation state, slash-command UX state, calls Themis to route each turn, dispatches to the chosen agent's HTTP endpoint, multiplexes streaming responses back to the SPA over SSE.

The two are deployed as separate services. The BFF serves API endpoints (`POST /chat/turn`, SSE `/chat/stream`, slash commands, session list/get/create); the SPA is served by nginx (or equivalent static-content server). The SPA talks only to the BFF; the BFF talks to Themis and the backend agents on behalf of the user.

## 3. Responsibilities

### What Iris owns

**The conversation as the user sees it.** Iris's session model is the chronological turn log + EntityContext + snapshot history. Each turn is represented as a `TurnPointer`:

```
TurnPointer {
  turn_id:          UUID
  agent_id:         AgentId             // pythia | golem-erp | golem-hr | ...
  artifact_ref:     string              // pointer to the agent's persisted per-turn record
  displayed_blocks: [BlockId]           // which blocks the user actually saw (post-edits)
}

IrisSession {
  user_id:        UserId
  tenant_id:      TenantId
  entity_context: EntityContext         // active entity bindings — "active customer = Shell UK PLC"
  snapshots:      [Snapshot]            // rollback history
  turns:          [TurnPointer]
}
```

The session lives in Iris's persistence layer (Postgres). Each agent persists its own per-turn record (Pythia's `InvestigationArtifact`, Golem's `ConversationalResponse`) in its own storage; Iris holds only the pointer.

**Routing dispatch.** On every conversational turn, the BFF calls `themis.understand(question, conversation_excerpt, profile=CHAT_QUICK, routing_hint?)` and receives `UnderstandingResult { resolution, routing_decision }`. The BFF then dispatches to whichever agent Themis named (`routing_decision.chosen_agent_id`), streaming the agent's `FormatEnvelope`-shaped response back to the SPA.

**Stream multiplexing.** The backend agent's response is an SSE stream of typed events (`envelope` / `step` / `tool_call` / `thinking` / `error`). The BFF wraps these in `IrisStreamEvent`s and forwards to the SPA, possibly enriching with metadata (turn_id, agent display name from capabilities-mcp). The SPA renders.

**Slash-command UX state.** `/clear /reset /help /format /export /sql` and others. Some resolve client-side (`/clear`, `/help`, `/export`); others involve the BFF (`/reset` clears the session; `/sql` arms a one-shot dry-run flag for the next turn). The dispatch infrastructure is in the BFF.

**Chip rendering.** Three chip kinds:
- **Static chips** — suggested topics shown before any turn (sourced from `capabilities-mcp.list_agents()` + `metadata-mcp.list_queries()`).
- **Dynamic chips** — context-aware suggestions after a turn (e.g. "filter by region" after a customer list).
- **RoutingPickChip** *(new with Themis Stage 4.5)* — alternates rendered when `themis.routing_decision.needs_user_pick == true`. Chip click reissues the turn with `routing_hint = picked_agent_id`.

**Edit-and-resend.** User clicks a pencil icon on their own bubble; the message becomes editable; on save, Iris (a) optimistically discards turns after the edited point, (b) re-issues the edited message as a fresh turn, (c) re-routes via Themis (the edited message may have a different routing decision than the original). Snapshot history retains the discarded turns for undo.

### What Iris does NOT own

- **The decision of which agent answers.** Themis owns routing; the BFF dispatches based on what Themis decides.
- **Per-turn analytical work.** Pythia's investigation plan + hypotheses + checkpoints; Golem's mini-plan + format envelope generation. The BFF transports, doesn't analyse.
- **The data itself.** All agent responses are `FormatEnvelope`-shaped block sequences; the underlying data lives in agent storage (Pythia's handle table, Golem's row caches) and platform storage (Seaweed, Redis, Worker session DFs). Iris renders references, not data.

## 4. Conversation state lifecycle

The model is captured in `kantheon-architecture.md` §7; this section adds the operational detail.

**Session creation.** On user login (or fresh chat from the side nav), the BFF creates a new `IrisSession` row with empty `entity_context`, empty `snapshots`, empty `turns`. Session ID flows in all subsequent calls as `X-Session-Id` header.

**Turn lifecycle.**
1. SPA → BFF: `POST /chat/turn { session_id, question }` (or via SSE `/chat/stream` for the streaming variant — current v2 uses GET-SSE).
2. BFF reads session's recent turns → builds `conversation_excerpt` (last N relevant turns, configurable).
3. BFF → Themis: `understand(question, conversation_excerpt, profile=CHAT_QUICK)`.
4. Themis returns `UnderstandingResult { resolution, routing_decision }`.
5. If `routing_decision.needs_user_pick`: BFF emits an `IrisStreamEvent { envelope: { chips: [RoutingPickChip × N] } }` to the SPA. SPA renders chips. User clicks → BFF reissues with `routing_hint = picked_agent_id` → Themis Layer 0 fixes the choice.
6. Otherwise: BFF calls `{chosen_agent_id}.answer(request)` over HTTP (or via the agent's MCP surface). The agent streams its response.
7. BFF wraps the agent's events in `IrisStreamEvent`s and forwards to the SPA.
8. On stream completion, BFF appends a new `TurnPointer` to the session.

**EntityContext updates.** When an agent's response carries `entity_context_update` (typically when the user has selected a single entity from a chip or table row), the BFF updates `IrisSession.entity_context` so subsequent turns inherit the binding ("show me Kaufland orders" → BFF carries `entity_context.customer = Kaufland` into the next Themis call as part of `conversation_excerpt`).

**Snapshot rollback.** Two trigger paths: (a) user invokes `/reset` (clears the conversation and all session state — drops everything); (b) edit-and-resend (drops turns *after* the edited point but keeps prior context). Snapshots are point-in-time copies of `(entity_context, turns)`; undo restores from snapshot.

## 5. FormatEnvelope rendering

The wire contract is `org.tatrman.kantheon.envelope.v1.FormatEnvelope`, the same envelope every backend agent emits. Block kinds at v1:

- **plaintext** — `<pre>` body, no markup.
- **markdown** — markdown-it (`html: false`) + mermaid via fenced code blocks (`securityLevel: 'strict'`).
- **table** — PrimeVue DataTable wrapper. Handles object-shaped (key/value) and array-shaped (multi-column) content. Format directives (`%d`, `%f`, `%e`, `%g`, `%x`, `%s` with precision + padding) on a per-column basis. Sort / filter / paginate are typed actions (v2.1 model — covered in `golem/docs/v2/architecture.md` §3.4).
- **chart** — Vega-Lite via vega-embed. Agent emits `ChartIntent`; the agent's `chart-formatter` lib compiles to Vega-Lite spec; SPA renders the spec directly. Chart toolbar: SelectButton (Line / Bar / Pie), MultiSelect (hide series), ToggleButton (stack).

The shared library `shared/libs/ts/envelope-ts/` provides:
- Generated TypeScript bindings from `envelope/v1` proto.
- `FormatRenderer` Vue component that resolves a renderer from `formatCatalog[kind]` and hands it the payload.
- Format directive interpreter for table cells.

The catalog is static at build time. An envelope with an unknown `kind` renders as `plaintext` with an "Unsupported format" warning — agents cannot smuggle arbitrary HTML in via `kind`.

## 6. Dispatch flow

```
User types in SPA
   │
   ▼
SPA → BFF  (POST /chat/turn or GET /chat/stream)
   │
   ▼
BFF builds conversation_excerpt from session
   │
   ▼
BFF → Themis  (themis.understand, profile=CHAT_QUICK, routing_hint?)
   │
   ▼
Themis returns UnderstandingResult { resolution, routing_decision }
   │
   ├── routing_decision.needs_user_pick == true?
   │      ├── BFF emits IrisStreamEvent with RoutingPickChip alternates
   │      ├── SPA renders chips
   │      ├── User clicks one
   │      └── SPA → BFF reissue with routing_hint = picked
   │           (loop back to "BFF → Themis"; Layer 0 fixes the choice)
   │
   └── routing_decision.chosen_agent_id ∈ {pythia, golem-erp, golem-hr, …}
          │
          ▼
       BFF → {chosen}.answer(request, themis_prior_context=resolution_continuation)
          │
          ▼
       Agent streams response (FormatEnvelope events + step / tool_call / thinking)
          │
          ▼
       BFF forwards as IrisStreamEvent to SPA
          │
          ▼
       SPA renders blocks into Chat / Tabs panes
          │
          ▼
       Stream completes; BFF appends TurnPointer to session
```

## 7. Module map (`agents/iris-bff/`)

```
agents/iris-bff/
├── src/main/kotlin/org/tatrman/kantheon/iris/bff/
│   ├── App.kt                          # Ktor application entry
│   ├── api/
│   │   ├── ChatRoutes.kt               # /chat/turn, /chat/stream (SSE)
│   │   ├── SessionRoutes.kt            # /session/{create,get,list}, /session/{id}/reset
│   │   ├── SlashRoutes.kt              # slash-command-specific endpoints
│   │   └── HealthRoutes.kt             # /health, /ready
│   ├── conversation/
│   │   ├── SessionStore.kt             # Postgres-backed session repository
│   │   ├── ConversationExcerpt.kt      # build excerpt from recent turns
│   │   ├── EntityContext.kt            # active entity bindings
│   │   └── Snapshot.kt                 # rollback / edit-resend mechanics
│   ├── routing/
│   │   └── ThemisClient.kt             # talks to themis-mcp
│   ├── dispatch/
│   │   ├── PythiaClient.kt             # talks to pythia
│   │   ├── GolemClient.kt              # talks to {golem-erp, golem-hr, ...}
│   │   └── AgentDispatcher.kt          # picks the right client per chosen_agent_id
│   ├── stream/
│   │   ├── IrisStreamMux.kt            # multiplex agent SSE → SPA SSE
│   │   └── EnvelopeForwarder.kt        # IrisStreamEvent wrapping
│   ├── capabilities/
│   │   └── CapabilitiesCache.kt        # cached capabilities-mcp.list_agents() for display names
│   └── auth/
│       └── KeycloakFilter.kt           # bearer-token validation per ai-platform pattern
├── src/main/resources/
│   ├── application.conf
│   └── db/migrations/                  # Flyway migrations for sessions / turns tables
├── src/test/kotlin/                    # Kotest + Testcontainers + Wiremock
├── build.gradle.kts
├── k8s/{base,overlays/local}/
└── README.md
```

## 8. Module map (`frontends/iris/`)

Extracted from the current `golem/frontend/` tree. The directory structure largely carries over — see `golem/docs/v2/architecture.md` §2.2 and `golem/docs/v2/v2-overview.md` §3 for the existing layout. Key adjustments at extraction:

- `frontend/src/services/agentService.ts` → splits into `services/irisStream.ts` (SSE consumer pointing at the BFF) and `services/typedAction.ts` (typed actions; chip clicks, edit-resend).
- `frontend/src/types/envelope.ts` → replaced by the generated bindings from `shared/libs/ts/envelope-ts/`. Hand-written display state types stay local.
- `frontend/src/stores/auth.ts`, `chatStore.ts`, `tabsStore.ts`, `layoutStore.ts` → kept; minor rename if "agent" → "iris" makes sense in store names.
- Catalog (`frontend/src/catalog/formatCatalog.ts`) → consumed from `envelope-ts/` lib; renderer SFCs (PlainText / Markdown / Table / Chart / Unsupported) stay locally for now.
- Internationalization (vue-i18n with `en.json` + `cs.json`) → carries over.

The BFF endpoint URLs change (was Python FastAPI on port 7901 in the current `golem`; becomes Kotlin/Ktor on a kantheon-side port) — config-driven via `VITE_BFF_*` env vars.

## 9. Heritage from current `golem` v2 work

The v2/v2.1 work in the current `golem` repo *is* effectively Iris's prototype. v2 shipped:
- 3-pane dockview workspace (Chat / Tabs / Graph)
- FormatEnvelope contract over SSE
- PrimeVue Aura Red preset
- Slash commands (`/clear /reset /help /format /export /sql`)
- Table rendering with format directives
- Chart rendering (originally ECharts, migrated to Vega-Lite per `golem-charting-migration.md`)
- Drill-down menus on rows + chart points
- Edit-and-resend
- Czech / English i18n

v2.1 added:
- Typed-action channel (`POST /agent/action` — sort / filter / paginate without LLM round-trip)
- Three-slot workspace + Agent Flow / Logs / Queries panes
- Stubbed-but-visible drill-down UX

Phase 1 of `golem/docs/aip-v1-impl/phase-1-fe-bundle-mvp.md` is in flight and adds: typed actions for FE-1 (row-click → `select_row`), FE-2 (chip-click → `chip_invocation`), FE-4 (edit-and-resend → `edit_resend`); heuristic emission of `pending_selection` / `dynamic_chips` / `static_chips` from the current Python BE.

**What changes when Iris becomes Kantheon-side:**

- The BE shifts from one Python LangGraph monolith to (a) Iris-BFF (Kotlin/Ktor — does the routing dispatch), (b) Themis (Kotlin/Koog — does entity/intent resolution + agent routing), (c) one or more backend agents (Pythia, Golem instances) that produce the FormatEnvelope content.
- `static_chips` and `dynamic_chips` move from the BE-emits-them model to: BFF builds them from capabilities-mcp data + the current session's EntityContext. (Or: emitted by the chosen agent and BFF forwards.)
- The typed-action channel (`POST /agent/action`) survives but targets the BFF, which in turn re-issues against the agent that produced the in-question table.

**What doesn't change:**

- The FormatEnvelope contract (`FormatEnvelope`, `Block` variants, `Chip`, `Drilldown`, `TableDetails`, `ChartDetails`, `ChartIntent`) is the same — just becomes proto-sourced from `envelope/v1` instead of hand-written Pydantic + TS.
- The dockview lockdown, the PrimeVue theming, the markdown-it+mermaid setup, the vega-embed chart rendering, the slash-command popup, the chip rendering, the edit-and-resend optimistic discard — all carry over essentially unchanged.

## 10. Open items

- **iris-bff Stage 1 task doc** — the equivalent of Themis's Stage 04 task doc, breaking down the BFF implementation into a phased plan. Pending.
- **iris-frontend extraction task doc** — how to lift `golem/frontend/` into `kantheon/frontends/iris/` with `git filter-repo`, what re-wires (env vars, SSE endpoint URLs, auth flow), what cuts (Python-specific service stubs). Pending.
- **Session-persistence schema** — Postgres tables for `iris_sessions`, `iris_turns`, `iris_snapshots`. Sketch above; full DDL pending.
- **Co-design of `RoutingPickChip` with Themis Stage 4.5** — chip rendering on the SPA side, alternates-offered tracking on the BFF side. Coupled work; called out in `tasks-stage-04.5-routing-layer.md` task 10.
- **`current_view` / `current_display` semantics post-split** — v2.1 introduced these as BE-side state. Under the split, they may move to the BFF (since the BFF is the per-session state holder) or stay agent-side (since the agent owns the "last data view we produced"). Lean: BFF for `current_display` (the user's current rendering choices); agent-side for `current_view` (which dataset is "the one we're talking about"). Worth pinning when the BFF is being designed concretely.

## 11. Resolved decisions — quick reference

| Decision | Locked | Note |
|---|---|---|
| Iris-BFF in Kotlin + Ktor | 2026-05-10 | Was open as "what language?" — Kotlin to align with Themis / Pythia / Golem |
| Dispatch BFF (option b from E4) | 2026-05-10 | Owns conversation state + dispatch + stream multiplex; not cross-agent assembly |
| Iris owns session, agents own per-turn artifacts (option iii from E5) | 2026-05-10 | TurnPointer list; agents persist `ConversationalResponse` / `InvestigationArtifact` |
| iris-bff and iris-frontend as siblings | 2026-05-11 | `agents/iris-bff/` + `frontends/iris/` (not nested under `agents/iris/`) |
| FormatEnvelope rendered via `envelope-ts/` lib | 2026-05-11 | Generated TS from `envelope/v1` proto + hand-written render helpers |
| Iris extracted from current `golem` repo | 2026-05-10 | `git filter-repo` for the `frontend/` tree; BFF is fresh Kotlin code |
| Iris-side chip kind `RoutingPickChip` co-designed with Themis Stage 4.5 | 2026-05-11 | Coupled work; alternates-offered validation on the BFF side |
