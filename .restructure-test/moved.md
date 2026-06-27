# Kantheon — v1 documentation

This directory is the canonical place to read about the Kantheon constellation. Start with the architecture doc; each agent has its own subdirectory with design + brainstorming records + task docs.

## Start here

- [**`kantheon-architecture.md`**](./kantheon-architecture.md) — the overall architecture: vision, layout, proto packaging, shared libs, module dependency graph, conversation state, routing model, sequencing, resolved decisions. **Read this first.**
- [**`planning-conventions.md`**](./planning-conventions.md) — task → stage → phase hierarchy that applies to every planning arc in this repo. **Read second** if you're going to plan or execute work.
- [**`next-steps.md`**](./next-steps.md) — snapshot of what's done, what's prepared, and what remains. Resumption pointer if you're picking up the work after a break.
- [**`aip-v1-status-audit.md`**](./aip-v1-status-audit.md) — Claude Code audit of ai-platform v1 state (2026-05-12, revision 2). Largely **superseded** by the post-audit gap-closure work on `gap-v1` PR #48 — verify against current `git log` before treating as current. Pre-flight items in `themis/plan.md` §2 cross-check key gaps.
- [**`aip-v1-gap-closure-plan.md`**](./aip-v1-gap-closure-plan.md) — synthesised plan to close every gap blocking Kantheon or Themis. **CRITICAL items closed as of 2026-05-15** per the post-audit `gap-v1` merge; see `themis/plan.md` §2 for the cross-reference.
- [`aip-v1-status-report-2026-05-12.md`](./aip-v1-status-report-2026-05-12.md) — earlier (superseded) Claude Code report. Kept for history; the audit is more accurate.

## Themis arc — active planning (2026-05-15)

The first Kantheon-side arc — bring Themis live in this repo as a Koog-based agent with the routing layer — is fully planned across `themis/`:

- [**`themis/architecture.md`**](./themis/architecture.md) — solution architecture for Phases 1–3.
- [**`themis/contracts.md`**](./themis/contracts.md) — all wire contracts (capabilities/v1, themis/v1, envelope/v1 RoutingPickChip, manifest YAML, heartbeat client).
- [**`themis/plan.md`**](./themis/plan.md) — phased plan: 3 phases, 14 stages, ~80 tasks. Per-stage task lists land at `themis/tasks-p<n>-s<n.m>-*.md` after the artefacts above are reviewed.

## Per-agent documentation

| Agent | Role | Documentation |
|---|---|---|
| **Iris** | User-facing FE + BFF (Vue 3 SPA + Kotlin/Ktor backend-for-frontend) | [`iris/`](./iris/) — design, brainstorming, README |
| **Themis** | Question understanding + routing (Kotlin/Koog; = Resolver post-extraction) | [`themis/`](./themis/) — design, brainstorming, brief, 6 + 2 stage docs, README |
| **Pythia** | Autonomous analytical investigator (Kotlin/Koog + custom DAG executor) | [`pythia/`](./pythia/) — Pythia-v1-Design.md, brainstorming (en + cs), open-questions, framework-evaluation, v1.5-backlog, brief |
| **Golem** | Per-domain Q&A template; one pod per Shem (Kotlin/Koog) | [`golem/`](./golem/) — template design, README |

Plus one platform tool:

| Tool | Role | Documentation |
|---|---|---|
| **capabilities-mcp** | Unified registry of agent + tool capabilities | Phase 1 of the Themis arc — see [`themis/plan.md`](./themis/plan.md) §3 and [`themis/architecture.md`](./themis/architecture.md) §7 |

## Sequencing — what comes when

Documented in `kantheon-architecture.md` §11. Summary, updated 2026-05-15:

1. **ai-platform Resolver (= future Themis) Stage 04 — complete in ai-platform.** Runs on plain Kotlin coroutines (not Koog; Ktor 2.x/3.x conflict).
2. **Kantheon-side Themis arc** (this is the current active arc; see [`themis/plan.md`](./themis/plan.md)):
   - **Phase 1** — kantheon bootstrap + `tools/capabilities-mcp` → deployable: `capabilities-mcp/v0.1.0`.
   - **Phase 2** — Resolver → Themis extraction + Koog migration → deployable: `themis/v0.1.0`.
   - **Phase 3** — Routing layer + Iris co-design → deployable: `themis/v0.2.0`.
3. **Iris BFF + FE extraction from golem** — separate arc; planned after Themis is live.
4. **Golem Python → Kotlin + Koog rewrite** — separate arc; sequenced after Iris.
5. **Cutover** — today's `golem` repo retires.

## Reading order if you're new to the project

1. `kantheon-architecture.md` (this directory) — 20 minutes.
2. `planning-conventions.md` — task / stage / phase definitions.
3. `themis/plan.md` — the active arc; phased plan with stages and DONE criteria.
4. `themis/architecture.md` + `themis/contracts.md` — the implementation surface.
5. `iris/iris-brainstorming.md` then `iris/iris-design.md` — why and what for the FE+BFF (Iris arc is next).
6. `themis/themis-brainstorming.md` then `themis/themis-design.md` — the design history feeding into Themis.
7. `pythia/Pythia-Brainstorming.md` then `pythia/Pythia-v1-Design.md` — why and what for the investigator.
8. `golem/golem-template-design.md` — the per-domain Q&A template; smaller scope, read last.

## Cross-repo orientation

- **ai-platform** (`/Users/bora/Dev/ai-platform`) — platform infrastructure: query-mcp, metadata-mcp, fuzzy-mcp, llm-gateway, nlp-mcp, infra/nlp. Publishes `shared/proto` + `shared/libs/kotlin/*` as Maven artifacts.
- **kantheon** (`/Users/bora/Dev/kantheon`) — this repo: the agent constellation.
- **golem** (`/Users/bora/Dev/golem`) — legacy single-agent (Vue FE + Python LangGraph BE). Stays running until cutover; eventually retires.
- **pythia** (`/Users/bora/Dev/pythia`) — legacy Pythia design folder. Documents have been moved here to `kantheon/docs/v1/pythia/`; the original folder remains for git history continuity.
- **koklyp** (`/Users/bora/Dev/koklyp`) — separate project (Hebe, the personal-assistant agent with messaging channels). Future consumer of Kantheon; not in scope here.
