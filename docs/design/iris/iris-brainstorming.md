# Iris — Brainstorming Record

> **Purpose.** This document records *how* the Iris design was reached, not *what* it concluded. The conclusions live in [`iris-design.md`](./iris-design.md). This document captures the discussion: questions on the table, alternatives considered, key insights, decisions, and where they were locked.

## 0. Pre-history — Iris as a name

Until early May 2026, the user-facing chat surface was discussed as "Wrangler" (in the original Pythia brainstorm), or simply "the FE" (in the current `golem` repo's v2/v2.1 work). The Greek-mythology naming pass on **2026-05-04** renamed Wrangler → Iris (messenger goddess, rainbow bridge between worlds). The rename is captured in the `agent_pantheon` memory note; this brainstorming record uses the locked name.

The reframe to "Iris is a frontend in its own right, distinct from the backend agents" happened on **2026-05-08** as part of the broader architectural reshape. Until then, the Pythia design's framing was "Analytical Agent evolves to consume Pythia"; the reshape clarified that the Analytical Agent splits into Iris (FE+BFF), Themis (routing/understanding), Golem (per-domain template), with Pythia as a peer.

## 1. The Iris-evolution brainstorm (2026-05-10)

### How it started

The Pythia design had a §1.1 ("Iris is one of N peer agents") and a §6.2 ("Iris evolution") that named the reframe but left the *transition story* unsketched. We had: a working `golem` repo (Vue + Python LangGraph BE), a Pythia design referencing constellation pieces, and no doc explaining how today's code became tomorrow's constellation.

Claude opened the brainstorm by framing five questions (E1–E5):

- **E1** — Concrete mapping: what in today's `golem` becomes Iris, what becomes Themis, what becomes Golem template, what's dropped?
- **E2** — Repo strategy: keep `golem` monolith and add new repos, or split, or workspace?
- **E3** — Phasing: parallel-deploy via feature flag vs roadmap rewrite?
- **E4** — Iris's BFF surface: how thin? transport-only / dispatch / assembly?
- **E5** — Where does conversation state live? Iris-BFF / each agent / both?

### Bora's quick decisions

Bora was decisive across E2, E4, E5; parked E3; left E1 for the architecture doc.

**E2 (repo strategy)** — new monorepo "pantheon" (later renamed "kantheon"), agents as modules; existing `golem` repo stays running for cutover. Iris IS extracted from golem; Themis is NOT (it's the Resolver in ai-platform that moves into kantheon).

**E4 (BFF scope)** — **dispatch BFF** (option b): conversation state + dispatch + stream multiplex on the BFF; SPA stays presentational; cross-agent assembly out of scope for v1.

**E5 (conversation state)** — **option (iii)**: Iris owns the conversation as the user sees it (turn log, EntityContext, snapshot history, edit-and-resend); each backend agent owns its per-turn artifact (`ConversationalResponse` for Golem, `InvestigationArtifact` for Pythia). Iris's session = list of `TurnPointer { turn_id, agent_id, artifact_ref, displayed_blocks }`.

### The Kotlin-vs-Python detour

The first round of decisions implied a Python BFF (since today's `golem` BE is Python+LangGraph) and a Python-or-Kotlin choice for the Golem template. Bora initially leaned "Python+LangGraph for Golem pragmatically, mixed-language is fine." After Claude pushed back with a pros/cons analysis showing:

- Most of today's "hard Python" in golem (Czech entity detection, fuzzy, NQ selection) is *moving to Themis*, not staying in Golem.
- Post-extraction Golem template is small enough that a Kotlin port is bounded (~2–3K LoC).
- The Pythia design's "one framework across agents" preference was load-bearing — keeping Golem in Python gave it up.
- Schedule cost of Kotlin rewrite: ~2–4 weeks marginal over the Python rewrite that was already planned for aip-v1-impl Phase 2.

… Bora flipped to **Kotlin + Koog across all backend agents** (Pythia, Themis, Golem) and **Kotlin + Ktor for Iris BFF**. Python phased out as a runtime in the constellation. Sequencing: **Themis first** (via Resolver → extraction), **then Golem rewrite**.

### The shared-libs question

With Kotlin + Koog locked across the constellation, the cross-repo coupling story became real: kantheon's agents call ai-platform's tools (query-mcp, fuzzy-mcp, llm-gateway, nlp-mcp); both repos have `shared/proto`. Two questions:

1. Where do contracts live?
2. How do shared Kotlin libs (`otel-config`, `fuzzy-common`, `mcp-server-base`, build-convention plugins) get shared?

Bora's answer (2026-05-10): **ai-platform publishes shared/proto + shared/libs/kotlin/* as Maven artifacts; kantheon consumes via `libs.versions.toml`.** Kantheon's own `shared/proto` defines constellation-specific contracts; Themis's proto imports `cz.dfpartner.nlp.v1.AnalyzeResponse` from the published ai-platform artifact. Pattern mirrors ai-platform's existing convention. Same publishing pattern available to koklyp/Hebe when it integrates.

### The capabilities-mcp inversion

Themis reads agent manifests from `capabilities-mcp` (which doesn't exist yet at ai-platform — was planned in the Pythia design). Where does capabilities-mcp live? ai-platform (matches platform-service pattern alongside query-mcp/metadata-mcp/fuzzy-mcp) or kantheon (since it stores agent manifests for the constellation)?

Bora's call (2026-05-10): **capabilities-mcp lives in kantheon.** Implication: ai-platform tool services register themselves at startup *into* kantheon's capabilities-mcp via heartbeat. Cross-repo write dependency from ai-platform → kantheon — the inverse of the shared-libs publish direction. "The constellation owns its registry."

## 2. The Themis detour (2026-05-10 → 2026-05-11)

Once Themis-first was locked, the natural next question was: how do we build Themis? Bora pointed out that the Resolver (currently being developed in ai-platform's `agents/resolver/`) was conceived for exactly this role, before the kantheon constellation existed. "Resolver becomes Themis, unless completely off."

Claude read the Resolver design (`ai-platform/resolver-design.md`) and the six stage docs, then computed the delta. Verdict: **Resolver is a strong v0.1 of Themis — not "completely off," not even close.** Stages 01–04 carry forward essentially unchanged; Stages 5–6 reframe lightly; the agent-routing layer needs to be added kantheon-side as a new "Stage 4.5."

The detour answered:

- **Move timing**: Resolver finishes Stage 04 in ai-platform, then extracts to kantheon as `agents/themis`.
- **Stage 4.4 = kantheon bootstrap + capabilities-mcp** (prerequisite for Stage 4.5).
- **Stage 4.5 = the routing layer** added post-extraction.

Six open design points for Stage 4.5 were proposed and resolved 2026-05-11:

1. Intent-kind classifier — rules-first with LLM fallback ✓
2. `relevant_capabilities` computed in `routeToAgent` itself ✓
3. Profile semantics (CHAT_QUICK vs INVESTIGATION_DEEP) confirmed ✓
4. `MultiQuestionDetected` as a separate cheap node ✓
5. Routing eval corpus — skeleton scaffolded; Bora fills in seeds ✓
6. No parallel-deploy comparison for routing (no baseline) ✓

The full detail of the Themis detour lives in [`../themis/themis-brainstorming.md`](../themis/themis-brainstorming.md).

## 3. The kantheon repo + naming (2026-05-11)

Bora finalised the repo name: **`kantheon`** ("Kotlin pantheon") at `/Users/bora/Dev/kantheon`. Proto package root: **`org.tatrman.kantheon`**. Earlier discussions used "pantheon" as the working name; the rename happened at this moment.

The architecture doc (`kantheon-architecture.md`) was written 2026-05-11 capturing the full locked structure: layout, proto packaging (6 packages under `org.tatrman.kantheon.*`), shared Kotlin libs (`capabilities-client`, `envelope-render`; `agent-base` deferred), module dependency graph, conversation state model, routing model, cross-repo coupling, sequencing.

## 4. Things locked across the brainstorm

- Iris is FE + BFF, two physical parts (`frontends/iris/` + `agents/iris-bff/`), top-level siblings (not nested).
- Iris-BFF is Kotlin + Ktor, dispatch-shaped.
- Iris owns the conversation as the user sees it; agents own their per-turn artifacts.
- Iris's session = list of `TurnPointer { turn_id, agent_id, artifact_ref, displayed_blocks }`.
- Iris is extracted from the current `golem` repo's `frontend/` tree (via `git filter-repo`); the BFF is fresh Kotlin code.
- The FormatEnvelope contract becomes proto-sourced from `org.tatrman.kantheon.envelope.v1`; the Vue SPA consumes generated TS bindings via `shared/libs/ts/envelope-ts/`.
- `RoutingPickChip` is a new chip kind (Themis Stage 4.5 co-design with Iris).
- Edit-and-resend re-routes through Themis (the edited message may have a different routing decision).

## 5. Things still on the table

- **iris-bff Stage 1 task doc** (the equivalent of Themis's Stage 04 task doc — phased breakdown of BFF implementation).
- **iris-frontend extraction task doc** (lift from `golem/frontend/` to `kantheon/frontends/iris/`, re-wire env vars and SSE endpoints).
- **`current_view` / `current_display` semantics post-split** — BFF-side or agent-side. Lean: BFF for `current_display`, agent-side for `current_view`. Worth pinning when the BFF is being designed concretely.
- **Session persistence schema** — Postgres DDL for `iris_sessions`, `iris_turns`, `iris_snapshots`.
- **aip-v1-impl distribution** — the existing `golem/docs/aip-v1-impl/` roadmap (Phases 2–8) is no longer the operative plan. Its scope distributes across Iris (here), Themis (Resolver in ai-platform + Stage 4.5 in kantheon), and the Kotlin Golem rewrite. The distribution doc is still pending.

## 6. Process notes

A few things about *how* this brainstorm went that are worth remembering:

- **Bora pushed back on the Python-pragmatism hedge**, which forced the consolidation move (Kotlin everywhere). Pattern: when the design proposal carries two parallel paths, Bora wants the consolidation immediately. Captured in the `bora_consolidation_preference` memory note.
- **Rules-first showed up again** (intent-kind classifier in Stage 4.5). Bora's `bora_design_preferences` memory note flags this preference consistently.
- **Envelope-first showed up again** (agent-base shared lib deferred — wait for cross-agent patterns to surface before extracting abstractions).
- **Typed contracts first** — proto sketches were written before prose explanations in the architecture doc and the Stage 4.5 task doc. Matches Bora's `bora_design_preferences` note.

---

*The brainstorm spanned 2026-05-08 through 2026-05-11 across the Pythia and kantheon discussions. Session transcripts available via the `session_info` tools.*
