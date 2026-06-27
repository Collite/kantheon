# Themis — Brainstorming Record

> **Purpose.** This document records *how* the Themis design was reached, not *what* it concluded. The conclusions live in [`themis-design.md`](./themis-design.md) and (since the 2026-05-15 re-plan) in [`../../architecture/themis/architecture.md`](../../architecture/themis/architecture.md), [`../../architecture/themis/contracts.md`](../../architecture/themis/contracts.md), and [`../../implementation/v1/themis/plan.md`](../../implementation/v1/themis/plan.md) — the former `tasks-stage-04.4` and `tasks-stage-04.5` docs are superseded by the new phased plan. This document captures the discussion: the questions on the table, alternatives considered, key insights, decisions, and where they were locked.
>
> Themis has two design moments worth recording:
> 1. The **original Resolver brainstorm** (early May 2026, in the `ai-platform` project) — when the service was first conceived as a generic intent-and-entity resolver.
> 2. The **Themis reframe** (2026-05-08 onwards, in the `pythia` / kantheon discussions) — when the service was generalised to also own *agent routing* across the constellation.

---

## Part I — The original Resolver brainstorm (ai-platform, early May 2026)

### How it started

Bora opened with the brief in [`ai-platform/docs/v1/resolver.md`](./themis-brief.md): a service that, given a user question (Czech-first; CE languages broadly), returns a structured package containing the linguistic parse, the entities mentioned, and a typed function-call binding that downstream agents can execute. Probabilistic where needed, with a HITL escape valve for genuine ambiguity, and an inner LLM-as-arbiter loop for compound ambiguity (multiple intents × multiple entity candidates).

The brief explicitly named consumers: Pythia, Wrangler (the evolved Golem), and any future agent that needs to map natural-language questions to platform functions.

### Key insights that shaped the design

**Insight 1 — Three-layer architecture follows the platform's service-vs-MCP rule.** Logic lives in `services/` or `infra/`; MCP servers are thin wrappers. So:
- `agents/resolver/` (Kotlin · Koog) — orchestrator + HITL loop
- `tools/nlp-mcp/` (Kotlin · Ktor) — thin pass-through wrapper
- `infra/nlp/` (Python · FastAPI) — pluggable NLP engine system

The Python/Kotlin language boundary crosses MCP/REST/gRPC, not in-process. Best-in-class Czech NLP libraries are Python-only (Stanza, spaCy, UFAL MorphoDiTa/NameTag); Koog is the agent framework standard going forward. Each piece runs in the language where it is natural.

**Insight 2 — Engine plugin architecture is non-negotiable.** UFAL's MorphoDiTa+NameTag commercial license is justified only if their parse quality materially exceeds Stanza on representative Czech data. So `infra/nlp` supports multiple engines side-by-side from day one, with a `COMPARE` mode that runs all engines in parallel and returns per-engine outputs. This is the mechanism for empirical quality comparison.

**Insight 3 — Joint inference, not chain-of-LLM-calls.** The single most consequential agent-design decision was to resist breaking entity resolution into a chain of smaller LLM calls (pick function → bind entities → coerce args). One FAST-tier LLM call with full context — parse, universal entities, domain candidates per span, registry of functions, contextual state — lets the LLM reason about coherence (the canonical example: "Shell-as-customer ↔ sales-order is consistent because the verb is *prodává*"). Faster, cheaper, more accurate.

**Insight 4 — Stateless HITL via signed resume tokens.** The HITL clarification flow — when confidence is low and rounds remain, Resolver asks one ambiguity at a time — should not require DB or Redis. The `resumeToken` is a signed (HMAC) JSON blob containing prior state — original question, parse hash, per-span candidates, universal entities, the specific ambiguity asked about, the round counter. Token size 2–5 KB, well within HTTP-header and request-body limits. Same endpoint, same caching/tracing/auth path, no separate "resume" RPC.

**Insight 5 — Registry passed in on every call.** Resolver remains stateless with respect to caller domain. Each consumer (Pythia, Wrangler, future agents) publishes its own `Registry` per call — list of functions, entity types, fuzzy-matcher namespaces. There is no platform-wide function-catalog service.

**Insight 6 — Eval corpus grows organically from parallel deployment.** Stage 03 seeds ~50 Czech questions from existing material (`docs/v1/ai-ag-vs-erp/`, Golem logs, hand-crafted ambiguity cases). Stage 05 runs Resolver alongside Golem's in-process logic; every disagreement is a candidate test case. The corpus doubles or triples organically during burn-in. Post Stage 06, real user traffic continues feeding it. This sidesteps "how do we build a 500-question Czech eval corpus from scratch?"

### Six-stage phasing

| # | Stage | Goal |
|---|---|---|
| 01 | `infra/nlp` foundation | Clone PoC, add POS/DEP/lang-detect, JSON API, NlpEngine plugin contract, NORMAL mode |
| 02 | `nlp-mcp` thin wrapper | Kotlin/Ktor MCP wrapper, agents can call NLP via MCP |
| 03 | Eval corpus v0 + COMPARE + MorphoDiTa | Seed ~50 Czech questions, COMPARE mode, MorphoDiTa via UFAL API, eval harness |
| 04 | `agents/resolver` (Koog) | Resolver proto, full Koog graph, stateless resume, eval gate |
| 05 | Parallel deployment alongside Golem | Run Resolver in shadow; diff harness; corpus grows from disagreements |
| 06 | Consumer migration | Wrangler primary; Pythia integration when v0 ready; remove Golem in-process logic |

Stages 03 and 04 run in parallel after Stage 02 ships.

### Things locked in the original brainstorm

- Three-layer architecture (Kotlin agent / Kotlin MCP wrapper / Python NLP infra)
- Engine plugin protocol; NORMAL + COMPARE modes
- Joint-inference single LLM call (FAST tier)
- Stateless HMAC resume tokens
- Per-call Registry (no platform-wide function catalog)
- In-process LRU caches at NLP-call and resolution levels
- Six-stage phasing

---

## Part II — The Themis reframe (Pythia/kantheon, 2026-05-08 onwards)

### How it started

The Pythia v1 design originally framed Iris as the FE and Pythia + Golem as backend agents. On 2026-05-08, the architectural picture sharpened into the Iris/Themis/Pythia/Golem constellation: **Iris dispatches, Themis decides which agent answers, Pythia + N Golem instances are peer backend agents.** That framing turned Resolver into something more than entity/intent resolution — it now also owns *agent routing*.

The question became: **how big is the delta between the existing Resolver design and what Themis needs to be?**

### The delta analysis (2026-05-10)

Read of the Resolver brief, design, six stage docs, and the closing of the brainstorm transcript. Verdict: **Resolver is a strong v0.1 of Themis — not "completely off," not even close. Delta is bounded.**

**Carry-over essentially unchanged** (Stages 01–04, ~75–100% reuse):

- Three-layer architecture (Kotlin agent + Kotlin MCP wrapper + Python NLP infra).
- Czech-first NLP foundation with Stanza / spaCy / NameTag / MorphoDiTa COMPARE-mode comparison.
- Stateless HMAC resume tokens.
- `Resolve()` with `oneof input { fresh | resume }` — matches Themis's multi-turn loop exactly.
- Caller-supplied `Registry` — same stateless-about-caller-domain pattern.
- `parse` always present in response (so callers can highlight ambiguous spans).
- HITL one-ambiguity-at-a-time, picked by highest-impact-on-uncertainty, max 3 rounds.
- Two-tier LLM strategy (CHEAP filter + FAST joint inference).
- Stage 05 parallel-deployment / diff-harness pattern for entity-resolution comparison.

**What Themis adds on top of Resolver** (Stage 4.5):

1. **Agent routing.** Resolver returns `Resolution { functionId, argsJson, bindings, … }` against the caller's *function* Registry. Themis additionally returns `RoutingDecision { chosen_agent_id, alternates, rationale, confidence, needs_user_pick }` against the *agent* registry from `capabilities-mcp`. New responsibilities: read agent manifests from `capabilities-mcp.list_agents()`; run a four-layer cascade (override → rule-based registry match → CHEAP-LLM fallback → user-pick chips); classify `intent.kind ∈ {PROCEDURAL, RCA, FORECAST, SIMULATION}` so the Layer-1 rule "RCA → Pythia, PROCEDURAL on multiple Golem domains → Pythia" can fire.

2. **Two profiles — `CHAT_QUICK` (Iris) vs `INVESTIGATION_DEEP` (Pythia).** Iris calls `themis.understand(CHAT_QUICK)` to route. Pythia (when chosen) calls `themis.understand(INVESTIGATION_DEEP, prior_context=...)` to deepen. Same Koog graph, different traversal.

3. **`needs_user_pick` chips for routing ambiguity.** When Layer-2 routing confidence is low, Iris renders the alternate agents as chips and the user picks. New chip kind in the `envelope/v1` schema (`RoutingPickChip`); Iris BFF tracks `alternates_offered` for validation.

4. **`MultiQuestionDetected` clarification variant.** When the user packs N independent questions into one turn, Themis flags it; Iris decomposes UI-side. New variant of `AwaitingClarification.kind`.

5. **`RefusalWithGaps` for STRICT mode.** Third terminal outcome for the response (alongside `Resolution` and `AwaitingClarification`). Returns structured `Gap[]` listing what blocked resolution.

6. **`themis_prior_context` continuation across agents.** The Iris CHAT_QUICK → Pythia DEEP handoff means the resume token is passed not just by the user across HITL rounds but by another agent across a profile change. Extends the existing token shape minimally.

7. **Naming + `capabilities-mcp` integration.** Renames Wrangler→Iris, drops "Golem template" alias, introduces dependency on `capabilities-mcp` (which is new and lives in kantheon).

### The six open design points — all resolved 2026-05-11

1. **Intent-kind classifier — rules-first?** **Yes.** Rules + NLP triggers (Czech "proč" / English "why" → RCA; "predikce"/"forecast"/future-tense → FORECAST; "co kdyby"/"what if" → SIMULATION; default → PROCEDURAL). CHEAP-tier LLM fallback only when rules tie or none fire.
2. **`relevant_capabilities` provenance.** **Option (c)** — `routeToAgent` computes it itself by matching entities + intent against capabilities-mcp `search_tags`. Keeps routing-specific logic in the routing node; doesn't touch `jointInference`.
3. **Profile semantics.** **Confirmed.** `CHAT_QUICK` = 3 fuzzy candidates per span, skip alt-bindings expansion, max-rounds=1, run `routeToAgent`. `INVESTIGATION_DEEP` = 10 fuzzy candidates, full alt-bindings, max-rounds=3, skip `routeToAgent` (caller is the agent).
4. **`MultiQuestionDetected` placement.** **Separate node** before `extractUniversal`. Cheap dependency-parse rule (count independent clause roots; if N>1 with disjoint intents, fire). Conservative bias toward false negatives.
5. **Eval corpus for routing.** **Skeleton-only.** Stage 4.5 creates the file structure + bucket comments (`# PROCEDURAL — single Golem domain`, `# PROCEDURAL — cross-domain (should route to Pythia)`, etc.); Bora populates the actual ~30 questions per bucket.
6. **Stage 05 parallel-deploy for routing.** **Skip.** Today's golem implicit-routes via pattern catalog — no clean shadow comparison. Routing quality gated by eval corpus + Iris chip-pick telemetry post-launch.

### Things locked in the reframe

- Themis = Resolver post-extraction. Move timing: after Resolver Stage 04 closes in ai-platform.
- Stage 4.4 (capabilities-mcp scaffolding + kantheon bootstrap) is a prerequisite for Stage 4.5.
- Stage 4.5 (routing layer) is post-extraction, kantheon-side.
- The Layer 1 scoring weights (+0.5 / +0.4 / +0.3 / +0.2) are hand-tuned and will be re-tuned against the populated eval corpus.
- The Layer 2 prompt rivals `jointInference` for stage-quality-driver-status; budget prompt-iteration time.
- Iris-side `needs_user_pick` chip flow is co-design work, not Themis-only.

---

## Open items still on the table

- Stage 5 reframe in detail under the kantheon split (events emitted in ai-platform-side Golem; curation pipeline + eval corpus in kantheon post-move — two-locus coordination).
- Stage 6 reframe (consumer list: Iris primary, Pythia integration when v0, Hebe later).
- Heartbeat client library location (kantheon-side `shared/libs/kotlin/capabilities-client` vs ai-platform-side).
- Renames inside the existing Stage 04 task doc: "Wrangler" appears in places where it should now read "Iris"; lightweight cleanup.

---

*Brainstorming records this format: original Resolver thread (early May 2026 in ai-platform project) + Themis reframe (May 8–11, 2026 in Pythia/kantheon discussions). The session transcripts are preserved separately.*
