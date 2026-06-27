# Next Steps

> **Current next step (2026-06-12, post-fork-decision): execute the platform fork, Phase 1.** The fork (ai-platform's intelligent services copied in, renamed into the pantheon; kantheon becomes a self-contained platform) is decided and planned — [`fork/plan.md`](./fork/plan.md). Sequencing: fork Phases 1–4 run **before Iris task-list execution**; Iris/Golem/Pythia task-list *writing* may proceed in parallel. The aip-v1-impl distribution doc (§3 below) is **closed — superseded by the fork** (ai-platform is maintenance-only; there is no roadmap to redistribute).
>
> **Previous next step (2026-06-12, end of day): the final architecture review.** Read [`handover-2026-06-12-architecture-review.md`](./handover-2026-06-12-architecture-review.md) — the full state handover (Hebe arrival + arc, all 15 PD resolutions, today's contract-delta table, and the 10-point review checklist). The review gates per-stage task planning (Iris arc first).
>
> **Further superseded 2026-06-12.** The three remaining constellation arcs are now planned: [`iris/plan.md`](./iris/plan.md), [`golem/plan.md`](./golem/plan.md), [`pythia/plan.md`](./pythia/plan.md) (execution order Iris → Golem → Pythia, locked). This closes §3's "plans that need to be written" rows for Iris BFF, Iris-frontend extraction, and the Golem rewrite, resolves §4's `current_view`/`current_display` and heartbeat-location questions, and unparks Pythia. The format-catalog Koog spike (§4, §7) became Golem Phase 1 (`envelope-render`). Still open from this doc: the **aip-v1-impl distribution doc** (§3) and the E3 phasing question (§4) — the latter is now largely answered by the Iris transitional `/v2` adapter + Golem Stage 4.2 side-by-side soak strategy.
>
> **Partially superseded 2026-05-15** by [`themis/plan.md`](./themis/plan.md), which is now the authoritative phased plan for the Themis arc. The body below — written 2026-05-12 — captures the broader cross-arc resumption pointer (Iris BFF, Pythia, Golem) and references to the old Stage 4.4 / 4.5 numbering have been folded into Phases 1 / 3 of the new plan. Read this doc for the wider context; read `themis/plan.md` for the active operational plan.
>
> Snapshot written 2026-05-12 at the end of the Iris-evolution / Themis-detour / kantheon-consolidation arc.

## 1. State of play

The architecture brainstorm is complete and the constellation is documented end-to-end in `kantheon/docs/v1/`. Six load-bearing decisions are locked in writing (see `kantheon-architecture.md` §13 for the quick-reference table); each agent has its own design + brainstorming doc. The Resolver implementation is in flight in `ai-platform/agents/resolver/`; everything downstream of Resolver Stage 04 is documented but not yet started.

The big inflection-point that triggers a lot of follow-on work is **Resolver Stage 04 closing in `ai-platform`**. Until that happens, most kantheon-side execution is pre-staged but blocked. Several design and content tasks *can* run in parallel with Stage 04 — those are the obvious places to invest brainstorm time now.

## 2. Plans already prepared (executable work with task docs)

### In flight (ai-platform side)

| Stage | Doc | Status |
|---|---|---|
| Resolver Stage 01 — `infra/nlp` foundation | [`themis/tasks-stage-01-infra-nlp.md`](./themis/tasks-stage-01-infra-nlp.md) | in progress in ai-platform |
| Resolver Stage 02 — `nlp-mcp` thin wrapper | [`themis/tasks-stage-02-nlp-mcp.md`](./themis/tasks-stage-02-nlp-mcp.md) | not started |
| Resolver Stage 03 — eval corpus + COMPARE + MorphoDiTa | [`themis/tasks-stage-03-eval-compare.md`](./themis/tasks-stage-03-eval-compare.md) | not started; parallel with 04 once 02 ships |
| Resolver Stage 04 — Resolver Koog graph | [`themis/tasks-stage-04-resolver-agent.md`](./themis/tasks-stage-04-resolver-agent.md) | not started; **critical-path gate for everything kantheon-side** |

### Queued (kantheon-side; gated by Resolver Stage 04 + extraction)

| Stage | Doc | Status |
|---|---|---|
| **Superseded** | [`themis/plan.md`](./themis/plan.md) | The former Stages 4.4 + 4.5 are reorganised into Phases 1 / 2 / 3 (14 stages) per the 2026-05-15 re-plan. |

### Reframe-pending (existing docs need rewriting under the kantheon split)

| Stage | Doc | What needs to change |
|---|---|---|
| Stage 05 — parallel deployment | [`themis/tasks-stage-05-parallel-deployment.md`](./themis/tasks-stage-05-parallel-deployment.md) | Diff harness emits events in ai-platform-side Golem; curation pipeline + eval corpus now in kantheon. Two-locus coordination needs documenting. Routing-layer comparison excluded (no baseline). |
| Stage 06 — consumer migration | [`themis/tasks-stage-06-consumer-migration.md`](./themis/tasks-stage-06-consumer-migration.md) | "Wrangler" → "Iris"; consumer list updates to Iris + Pythia + Hebe. |

## 3. Plans that need to be written (no task doc yet, design implied)

| Item | Why it's needed | Triggering event |
|---|---|---|
| **Iris BFF Stage-1 task doc** | Phased breakdown of the Kotlin/Ktor BFF implementation. Equivalent of Themis's Stage 04 doc for the dispatch-BFF surface. | Can start once kantheon repo is bootstrapped (Stage 4.4 task 1). |
| **Iris-frontend extraction task doc** | How to lift `golem/frontend/` → `kantheon/frontends/iris/` via `git filter-repo`; re-wire env vars and SSE endpoints; cut Python-side services; consume `envelope-ts/` generated bindings. | Can start in parallel with Iris BFF design; ships when both are ready. |
| **Golem template rewrite task doc** | Phased breakdown of the Python → Kotlin + Koog port. Inherits `golem-template-design.md` as design spec. | Sequenced after Themis Stage 4.5 closes. Don't start writing until Themis is real. |
| **`capabilities-mcp-design.md`** | Short companion design doc to `tasks-stage-04.4-capabilities-mcp.md`. Captures the design rationale (one MCP for tools + agents, push-from-tools heartbeat, source-controlled fixtures vs runtime registrations). | Should land alongside Stage 4.4 implementation. ~1 day. |
| **`themis-design.md` revision pass** | Current `themis-design.md` is the Resolver design verbatim. After Stage 4.5 lands, fold the routing-layer sections in-line: routing cascade, `MultiQuestionDetected`, `RefusalWithGaps`, `Profile`, `IntentKind`. Drop "Wrangler" references throughout. | Closes alongside Stage 4.5. |
| **`pythia/Pythia-v1-Design.md` revision pass** | Scrub: vocabulary sweep (`named query` → `query`, `stackable pattern` → `stack`, ShemManifest field names per `pythia_vocabulary` memory note); `capabilities-mcp` now in kantheon (§6.2 platform-service diagram update); drop "Golem reuses Pythia's executor library" claim until Kotlin rewrite lands. | Can happen any time; not blocking. ~1–2 days. |
| **`aip-v1-impl` distribution doc** | Today's `golem/docs/aip-v1-impl/` Phases 2–8 are no longer the operative plan. Phase 1 is in flight. The doc explains: which work moved to Themis (Phase 3 — Czech entities; parts of Phase 2 and 5 — composer logic), which to Iris (most of FE-side AR-N requirements), which to Golem-template-rewrite (Phase 4 quality, Phase 5 composer, Phase 7 DataFrame), and what becomes operationally redundant. | This is the **"review and re-assess the aip-v1 situation"** Bora parked. Should happen before any aip-v1-impl Phase 2 work starts (which is currently planned as Python; under the reframe, the Python Phase 2 doesn't happen — that work routes to the Kotlin Golem rewrite). |

## 4. Open architectural questions (need decisions)

| Question | Where it surfaced | Lean (Claude's read) |
|---|---|---|
| **E3 — full phasing**: parallel-deploy via feature flag vs roadmap rewrite for the current single-agent `golem` cutover. | Parked from the Iris-evolution brainstorm (2026-05-10). | Hard to call without repo-state review. Probably parallel-deploy (kantheon brings up Iris+Themis+Golem-ERP on a new URL; golem monolith keeps serving until cutover); but the existing `AGENT_VARIANT=v1|legacy` flag in golem was designed for one-agent-two-shapes, not two-stacks-side-by-side. Worth a dedicated brainstorm session. |
| **`current_view` / `current_display` ownership post-split**: BFF-side or agent-side. | `iris-design.md` §10; `golem-template-design.md` §11. | Lean: BFF-side for `current_display` (the user's rendering choices), agent-side for `current_view` (which dataset is "the one we're talking about"). Pin during Iris BFF design. |
| **Heartbeat client library location**: kantheon-side (`shared/libs/kotlin/capabilities-client`) vs ai-platform-side. | Stage 4.4 task 8 left this open. | Lean: kantheon-side. Constellation lib; ai-platform consumes via published Maven artifact like the other kantheon→ai-platform libs. Confirm during Stage 4.4. |
| **Format-catalog Koog spike outcome**: does Koog's `StructureFixingParser` handle the four-render-kinds + retry + deterministic-fallback + header-inference patterns cleanly? | `golem-template-design.md` §10. | Unknown until the 2–3 day spike runs. Should happen *during the Resolver Stage 04 window* so the lessons land in `envelope-render` before Golem rewrite or Stage 4.5 routing prompts need them. |
| **Multi-Shem deployment automation**: Helm chart with values-per-Shem? GitOps with separate overlay per Shem? | `golem-template-design.md` §11. | Not urgent until Golem-HR and Golem-Sales are real (v1 has only Golem-ERP). Decide when the third Shem is being scaffolded. |
| **Session-persistence schema**: Postgres DDL for `iris_sessions`, `iris_turns`, `iris_snapshots`. | `iris-design.md` §10. | Sketched in `iris-design.md` §4; full DDL to be written as part of Iris BFF Stage-1 task doc. |
| **DataFrame analytics path** (Polars Worker integration for `DataFrameNode`). | `golem-template-design.md` §11; was originally `aip-v1-impl` Phase 7. | Not in initial Golem rewrite scope; v1.5 work. |

## 5. Content gaps that need Bora input

Several places where Claude wrote "skeleton-only — Bora fills in" or "stub with the required structure":

| Where | What's needed |
|---|---|
| `tasks-stage-04.5-routing-layer.md` task 8 — routing eval corpus seed | ~30 questions per intent-kind bucket: PROCEDURAL single-Golem-ERP, PROCEDURAL cross-domain (→ Pythia), RCA (→ Pythia), FORECAST (→ Pythia), SIMULATION (→ Pythia), ambiguous (Layer 3 needs_user_pick). File scaffolded in pantheon-side path. |
| `tasks-stage-04.4-capabilities-mcp.md` task 4 — Pythia's `AgentManifest` YAML | `description_for_router`, `example_questions`, `counter_examples`. The structural file is created; Bora populates content. |
| `tasks-stage-04.4-capabilities-mcp.md` task 4 — Golem-ERP `ShemManifest` YAML | `domain_entities`, `domain_terminology`, `preferred_queries`, `preferred_capabilities`, `example_questions`, `counter_examples`, `style_addendum`, `locale_defaults`. The structural file is created; Bora populates content. |
| `classifyIntentKind` rules-yaml extension | Czech / English trigger words beyond the first-pass seed set. Grows iteratively from eval-corpus disagreements. |
| Layer 1 scoring weights | The +0.5 / +0.4 / +0.3 / +0.2 weights in Themis Stage 4.5 are hand-tuned guesses. Eval-corpus baseline will tell us where they need adjustment. |

## 6. Hygiene / cleanup (small mechanical work)

These are dust-bunnies that have accumulated across the brainstorm. None blocking; worth knocking off in a focused session or on a slow day.

- **Vocabulary sweep** across `pythia/Pythia-v1-Design.md` and the older brainstorming records: `named query` → `query`; `stackable pattern` → `stack`; ShemManifest field names canonicalised per the `pythia_vocabulary` memory note (`preferred_queries`, not `preferred_named_queries`; `counter_examples`, not `negative_examples`).
- **"Wrangler" → "Iris"** sweep across the existing Resolver task docs (Stages 04, 05, 06 have lingering "Wrangler" references from before the rename).
- **Companion-link references inside copied task docs**: the kantheon-side `themis/tasks-stage-*` mirrors still link to `docs/v1/resolver-design.md` in their top header. After Themis extraction, those references should point to `themis-design.md` (same dir). Low priority — fix at extraction time.
- **`Pythia-Brainstormin-cs.md`** filename has a typo (missing the trailing `g`). Easy rename if Bora wants.
- **`kantheon-architecture.md` §6** Mermaid diagram is a bit cluttered (text labels mixing English and proto-namespace strings). Could be tightened on a polish pass.

## 7. Decision priorities — what to tackle when

This is a suggested order if Bora wants a single "what should I do next" thread. Each item assumes the previous ones aren't blockers; many of these are parallelisable.

**Now (in flight or immediately startable):**
1. Resolver Stages 01 → 02 → (03 + 04) in ai-platform. Critical path.
2. **`aip-v1-impl` distribution doc** — Bora explicitly parked this for "review the whole aip-v1 situation." Should happen before Phase 1 of the existing roadmap closes, so the team has the post-Phase-1 plan in hand.
3. **Format-catalog Koog spike** — derisks Stage 4.5 routing prompts (which use Koog structured output) and the Golem rewrite. 2–3 days; can run any time during the Resolver Stage 04 window.

**As Resolver Stage 04 approaches close:**
4. **Pythia's `AgentManifest` and Golem-ERP's `ShemManifest` content** — Bora populates the YAML fixtures so Stage 4.4 task 4 can land.
5. **Iris BFF Stage-1 task doc** — write the phased breakdown so kantheon-side work has a clear runway after extraction.
6. **`capabilities-mcp-design.md`** — short companion doc; ~1 day.

**Immediately after Stage 4.4 ships:**
7. **Resolver → Themis extraction** — mechanical move via `git filter-repo`; rewire image names + manifests + dashboards + alert rules; rename proto package.

**After extraction:**
8. **Stage 4.5 routing layer** — execute the documented plan.
9. **Routing eval corpus seed** — Bora populates the 6 buckets × ~30 questions each.
10. **Iris BFF + Vue extraction** — kantheon-side implementation.

**After Themis with routing is live:**
11. **Golem rewrite** (Python → Kotlin + Koog).
12. **Stage 05 reframe under cross-repo split** — diff harness wiring; curation pipeline.
13. **Stage 06 consumer migration** — Iris cuts over to Themis; Pythia integration when v0 is callable.

**Background / can-happen-any-time:**
- Pythia design-doc revision pass.
- Themis-design.md revision (fold routing-layer in).
- Vocabulary + Wrangler-rename sweeps.
- E3 phasing brainstorm (parallel-deploy vs roadmap rewrite) — should land somewhere before Stage 06.

## 8. Where to resume the brainstorm

If you want to pick up where this arc ended, the natural next thread is **the aip-v1-impl distribution doc** — Bora parked it explicitly ("We will then review and re-assess the whole aip-v1-situation") and it's the most operationally urgent of the open items, since Phase 1 of that roadmap is in flight and whoever's on it needs the post-Phase-1 plan.

The two other natural threads are:

- **Iris BFF design + Stage-1 task doc** — substantial design work; co-design with Themis Stage 4.5's chip flow makes it timely.
- **Format-catalog Koog spike** — small, derisks two downstream pieces (Stage 4.5 + Golem rewrite), can be done by someone other than the current designer in parallel.

For anything else, this `docs/v1/` tree should give you enough context to pick up cold. Start with `README.md`, then `kantheon-architecture.md`, then the per-agent directories.

---

*Document owner: Bora. Snapshot date: 2026-05-12. Update when items move from "next steps" → "in flight" → "done."*
