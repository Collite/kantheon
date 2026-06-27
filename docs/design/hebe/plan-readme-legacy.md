# Hebe — brainstorming record (discussion summary)

A chronological record of the conversation that produced the current v1 plan. The point of this document is to preserve **how we got here** — the reasoning, the alternatives considered, the points where the user's input changed the trajectory — not just the final decisions.

For the final decisions themselves, see:

- [`v1-specs.md`](v1-specs.md) — scope contract.
- [`v1-architecture.md`](v1-architecture.md) — wiring diagram + schemas.
- [`v1-tasks.md`](v1-tasks.md) — task list.
- [`hebe-brainstorming-responses.md`](hebe-brainstorming-responses.md) — the user's answers to the open questions.

This is the meeting-minutes / decision-log layer above all of that.

---

## 1. Where we started

The repo arrived with `req.md` declaring intent ("a Kotlin version of the *Claw agent, an autonomous agent able to receive instructions via different channels and perform actions") and four parallel attempts at a starting plan, written by four different agents in parallel:

- `docs/claude/` — Claude Code: longest, most opinionated, with sub-docs on IronClaw / ZeroClaw architecture and a stack-notes file that did real research on the JVM ecosystem.
- `docs/gpt/` — GPT-5.x via Copilot: well-organised, more cautious, contributed the cleanest framings (5-tier memory, sidecar boundary).
- `docs/gemini/` — Gemini: brief sketches, but contributed two original ideas (JSR-223 trusted scripting, agent self-evolution writing `.kts`).
- `docs/minimax/` — MiniMax: comprehensive features list, recommended Kotlin Native (against the tide), introduced "everything goes through tools" as a hint and preemptive history pruning.

Each agent had produced its own `hebe-architecture.md`, `hebe-features.md`, and `hebe-brainstorming.md` based on the IronClaw/ZeroClaw codebases and inspirations (OpenClaw, Hermes Agent).

The user's first instruction was to **synthesise** these into one coherent plan, with Claude's draft as the spine, and produce a separate diff document showing what each agent contributed and how they differed.

## 2. The first synthesis (`docs/plan/`)

Four documents were produced:

- [`hebe-architecture.md`](hebe-architecture.md) — folded GPT's 5-tier memory naming and "MCP-as-sidecar" framing into Claude's spine; took MiniMax's preemptive pruning, time decay, `tool_search`, catchup execution, tunneling; took Gemini's JGit/Fabric8 implementation picks; discarded MiniMax's KMP module structure and Native recommendation; discarded Gemini's coarse 6-module layout.
- [`hebe-features.md`](hebe-features.md) — full v1/v2/L feature list aligned to the architecture.
- [`hebe-brainstorming.md`](hebe-brainstorming.md) — opinionated working document with pushbacks, architectural bets, and open questions, deliberately taking positions to invite the user to argue back.
- [`agent-diff.md`](agent-diff.md) — overview table comparing all four agents across ~25 dimensions, then per-agent detailed diffs, then convergence/divergence analysis.

The synthesis flagged the following as **already-converged across three of the four agents** (lowest-risk decisions):

- JVM, not Native (only MiniMax dissented).
- Wrap koog rather than build from scratch.
- SQLite as default DB.
- Markdown workspace (memory-as-FS).
- Hybrid search (FTS + vector + RRF).
- MCP as a first-class tool transport.
- Channel-trait abstraction.
- Web console as M1.
- Approval / autonomy framework.
- Secrets at rest + injection at boundary.
- Skills as Markdown bundles.
- Single-user / small-team first.
- SQLite-default + cron-like scheduling.

And the following as **diverging** (where decisions had to be made):

- WASM in-process (Claude: Extism on Chicory; GPT: sidecar boundary; Gemini: Chicory or GraalVM; MiniMax: Wasmer).
- Channels for v1 (varied scopes).
- SOPs in v1 (Claude: defer; GPT: implicit jobs; Gemini: listed; MiniMax: routines required, SOPs not explicit).
- Multi-tenant from day one (Claude: `TenantScope` seam, single-user default; GPT: single-user explicit; Gemini: not detailed; MiniMax: multi-user implicit).
- Multi-agent routing.
- Subprocess sandbox for shell.
- Compaction threshold.

## 3. The user's first revision of `req.md`

After reading the synthesis, the user revised the brief in two important ways:

> "Let's do this JVM-based. So I change my initial requirements in this way:
>
> - It will be Kotlin + JVM
> - I do not need WASM; instead, let's create a pluggable architecture for JVM modules (tools). So all tools will be either JVM, or MCP based"

This collapsed three of the open questions at once:

- **Native vs JVM** → JVM (closed).
- **WASM in-process** → dropped entirely (Extism, Chicory, Wasmer all gone).
- **Plugin sandbox** → JVM plugin JARs (URLClassLoader-isolated initially) + MCP servers as the cross-language boundary. GPT's "sidecar" intuition reframed: **MCP is the sandbox**.

Net effect: less code, fewer deps, simpler debugging. The capability model from the WASM design (manifest declarations, permission gates at the host boundary, signed plugins, leak detection on output) survived intact, just transposed onto a JVM-classloader substrate.

## 4. Architecture + features re-aligned

The architecture and features docs were updated in place to:

- Drop all WASM features.
- Replace the WASM plugin host with a JVM plugin model (initially proposed as a hand-rolled URLClassLoader, with PF4J as a fallback if the hand-roll got cumbersome).
- Add a "trust posture" section explicitly: **JVM plugins are for trusted extensions you'd be willing to compile in. Anything you wouldn't trust inside your JVM should be an MCP server.**
- Reframe MCP as the **primary cross-language extension story** (where previously WASM had been).

The brainstorming doc grew a new §6 with questions specifically raised by the JVM-plugin direction:

1. Where does `Plugin` live? (Kernel ABI or sibling module?)
2. Hand-rolled URLClassLoader vs PF4J?
3. Which capabilities ship in v1's `PluginHost`?
4. Trust posture — how loud do we warn? What's the default `signature_mode`?
5. Do we need a shell-tool sandbox in v1?
6. Plugin hot-reload in v1?
7. What does the plugin template repo look like?

The user was asked to argue back on the top three places where pushback was expected (deferring SOPs, downscoping channels, the JVM trust posture default), plus a few drivers (multi-tenant, BYO-key vs shipped defaults, the shell-tool sandbox question).

## 5. The brainstorming responses

The user replied via [`hebe-brainstorming-responses.md`](hebe-brainstorming-responses.md), closing every open question. Highlights and reasoning:

| Topic | Decision | Reasoning |
|---|---|---|
| Channels for v1 | **Web Console + CLI + Telegram** | Agreed with the downscope; fastest debugging surface + one chat channel. |
| Routines vs SOPs | **Routines v1, SOPs v2** | Agreed defer SOPs. |
| Web console framework | HTMX / small Svelte (no React) | Agreed. |
| kubectl risk-tagging | High + always-approve on mutating verbs | Agreed. |
| Memory plan | All accepted | Including preemptive pruning, scheduled maintenance jobs, hygiene. |
| DB | SQLite v1 | Agreed. |
| Three-ring extensibility | Agreed | Skills (markdown) / JVM modules / MCP. |
| Web search | **Brave + DuckDuckGo** | Picked specifically; Brave default, DDG fallback. |
| Bets §2 | Accepted all | Including koog wrap, single-mutation funnel, receipts on disk, no v1/v2 engine coexistence, compaction at 60%, web console as debugging tool first. |
| **SOPs v1** | **No, v2 only** | No concrete deploy use case justifies the lift now. |
| **Multi-tenant** | **Single user, single human per instance** | Closes both §3.2 and §3.6 in one stroke; `TenantScope` dropped entirely. |
| Identity files | Single `IDENTITY.md` v1 | Agreed. |
| **LLM provider** | **OpenAI API + BYOK** | The user has an internal LLM Gateway speaking the OpenAI protocol; it handles upstream routing/fallback/Anthropic-native concerns. hebe ships **one** OpenAI-compat client. |
| Sub-agents | Sub-agents-as-tools | Agreed. |
| Tool versioning | Not v1 priority | Agreed. |
| **Plugin loader** | **PF4J** | Skipped the hand-rolled spike. The user wanted to spend novelty budget elsewhere. |
| Plugin v1 capabilities | `http_client` + `env_read` + `secrets:<name>` | Three is enough for v1. |
| Plugin signature default | **`optional`** | Stricter `required` documented as the prod posture. |
| Shell-tool sandbox | v2 | Subprocess wrapper deferred. |
| Plugin hot-reload | Not a priority | Restart hebe on plugin update. |
| **Plugin distribution** | **OCI / container registry (ACR)** | Plugins are first-party / internal-only for v1, v2, and beyond. No public marketplace. |

The single biggest collapse: the OpenAI API + BYOK answer. Once the user said "we have an internal LLM Gateway that handles that," **provider routing, fallback chains, native Anthropic adapter, Bedrock/Gemini/Azure adapters, and Hermes-style `ProviderTransport` all collapsed out of v1 scope**. hebe doesn't try to duplicate gateway responsibility; it ships one OpenAI-compatible client.

The second-biggest collapse: ACR distribution + PF4J. Originally we proposed plugins as files in `~/.hebe/plugins/`. The user clarified plugins will come from a container registry (OCI artifacts, in their case ACR). This shapes a new `oras pull` flow, Azure auth integration, and the plugin SDK as an internal Gradle template (not a public marketplace).

## 6. The architecture + features re-aligned (second pass)

Both `hebe-architecture.md` and `hebe-features.md` were updated to reflect every closed decision. Visible deltas:

- TL;DR rewritten with all eight decisions baked in.
- Layer 7 trimmed to CLI · Web · Telegram only.
- Module layout: `plugin-api` added as a sibling to `api`; `providers/openai-compat/` is the only LLM provider; `channels/` ships only `cli`, `web`, `telegram`.
- §8 plugin model rewritten end-to-end: PF4J `PluginManager`, manifest layered on `plugin.properties` + `plugin.toml`, `signature_mode = optional` default, OCI/ACR install flow with the steps spelled out (auth → pull → verify → extract → load).
- New §13a documenting the single OpenAI-compatible LLM client with a config example.
- §10 channel model documents the three v1 channels and reframes pairing for the single-operator case.
- `TenantScope` and multi-user pairing removed throughout.
- Comparison-with-the-claws table at the bottom updated.

The features doc grew a "what was removed" footer that catalogues every closure (WASM, native Anthropic adapter, provider router, Slack/email/WhatsApp v1 channels, `TenantScope`, hot-reload, tool versioning, etc.) so future readers can audit the path back to the original draft.

## 7. The v1 trio

Once the architecture and features were stable, the user asked for three concrete v1 documents:

- **[`v1-specs.md`](v1-specs.md)** — the scope contract. v1 thesis ("a single human can run `hebe run` and end up with a personal autonomous agent that…"), full in/out lists, NFRs with verification methods, 15 numbered acceptance criteria, milestone overview, risks, "what we explicitly bet on / what falsifies the bet".
- **[`v1-architecture.md`](v1-architecture.md)** — the wiring diagram. 22 sections covering Gradle module layout, version pins, kernel ABI in Kotlin, plugin ABI, full SQLite DDL for migrations V1–V5, workspace layout, full TOML config schema, secrets store, dispatcher state machine, loop driver + delegate contract, PF4J lifecycle ↔ hebe lifecycle mapping, OCI/ACR install flow with media types, NDJSON receipts log format with chain + signature shapes, web console REST + SSE contract, MCP transports, Telegram + CLI contracts, scheduler/heartbeat, deterministic boot sequence, error taxonomy, logging conventions, and the fixed security-check ordering.
- **[`v1-tasks.md`](v1-tasks.md)** — the implementable task list. ~95 tasks across M0–M10 + cross-cutting, each with dependencies, sizes, and acceptance. **The PF4J spike is M6.T1**, deliberately scoped to "load a hello-world plugin from a local JAR and call its tool" — narrow enough to derisk the plugin model before the rest of the loader is built. The doc ends with a single-engineer build order, a two-engineer split, and a cut-line list (least painful first) for if v1 goes long.

## 8. Where we landed (decisions, in one place)

These are the closed decisions. Linked back to the document where each is most fully described.

1. **Kotlin / JVM only.** GraalVM native-image stays open as a v2 distribution-format optimisation. ([architecture §1](hebe-architecture.md))
2. **No WASM.** Plugins are JVM JARs loaded via **PF4J**; cross-language extensions go through MCP servers. ([architecture §8](hebe-architecture.md))
3. **PF4J directly** as the plugin framework (no hand-rolled URLClassLoader spike).
4. **Plugin distribution: OCI / container registry (Azure Container Registry).** Plugins are internal-only for v1, v2, and beyond.
5. **Plugin `signature_mode = optional`** default in v1.
6. **Plugin v1 capabilities:** `http_client` + `env_read` + `secrets:<name>`.
7. **Single user, single human per instance.** No multi-tenancy. `TenantScope` dropped.
8. **v1 channels: Web Console + CLI + Telegram.** Slack / Email / WhatsApp deferred.
9. **v1 LLM providers: one OpenAI-compatible client (BYOK).** The user's internal LLM Gateway speaks OpenAI; same client also drives OpenAI / Ollama / OpenRouter / Groq.
10. **SOPs in v2.** Routines in v1.
11. **Subprocess sandbox in v2** (firejail/bwrap/Docker for shell, browser, kubectl).
12. **Plugin hot-reload in v2 or later.** Plugin updates require a restart.
13. **Tool versioning + rollback in v2 or later.**
14. **SQLite by default.** Postgres deferred (and likely beyond v1, since single-user-one-instance keeps SQLite sufficient).
15. **Web search providers: Brave + DuckDuckGo.** Brave default; DDG fallback.
16. **Compaction threshold default 60%** (configurable).
17. **Tool receipts: Ed25519-signed, hash-chained, NDJSON on disk.**
18. **Web console is a debugging tool first**, not a user-facing UI. (User-facing UI is whatever chat platform.)
19. **kubectl** ships High + always-approve on every mutating verb.
20. **Single mutation funnel** (`ToolDispatcher.dispatch`) lint-enforced with a custom Detekt rule.

## 9. What's open (where the discussion will resume)

The plan is concrete enough to start coding, but a few things will surface during implementation and warrant discussion when they do:

- **What if koog v1.0 doesn't ship?** The facade pattern means a swap is a week's work, but we'd want to decide whether to delay v1 or temporarily build our own loop.
- **Bundled starter skills.** v1 ships 3–5 markdown skills (placeholders mentioned: `daily-briefing`, `code-review-prep`, `wiki-organiser`). The actual content needs to be written.
- **What plugin do we want to write first?** Knowing the answer would let us validate that the v1 capability set (`http_client` + `env_read` + `secrets:<name>`) is sufficient. If the first real plugin is "the local filesystem," we'd need `file_read` / `file_write` brought into v1; if it's "the Linear API," the v1 set is fine.
- **Telegram inline approval buttons** (deferred to v2). Approvals in v1 are resolved via the web console or `/approve <id>` text command. Worth revisiting once we live with that for a few weeks.
- **OTel collector target.** v1 wires OTLP if `OTEL_EXPORTER_OTLP_ENDPOINT` is set, no-op otherwise. We may want a default local collector recipe.
- **Dispatcher Detekt rule.** Listed at v2 in features, but the rule is cheap and the cost of retrofitting later is high — likely worth pulling forward once the dispatcher exists. Documented as a v2 "should we move it?" item in the original brainstorming, never closed; default action: **pull into v1** when M2.T6 (dispatcher) lands.
- **Soak test environment.** v1 acceptance includes a 7-day continuous run; we haven't decided where (laptop, dev VM, an internal cluster).
- **Embedding cost.** Open recommendation in onboarding: "use Ollama-local embeddings as the default unless you really want gateway-routed embeddings." Verify this guidance once we measure costs against the user's actual gateway.

## 10. Naming

The project is being renamed from the working title **hebe** to **Hebe** — Greek mythology's bronze automaton, forged by Hephaestus to guard Crete: autonomous, single-instance, ran a daily routine (three circumnavigations of the island), tool-using (boulders), with a bounded trust posture (a single ichor vein sealed by a bronze nail). Every angle of the v1 design maps onto Hebe: the daemon-mode loop, the one-instance-per-human scope, the routine + heartbeat, the `shell` / `kubectl` tool surface, the deliberately-bounded JVM-plugin trust model.

The rename was argued for in this conversation against two strong alternatives: **Mnemosyne** (Titaness of memory — perfect for the memory subsystem alone, too narrow for the agent as a whole) and **Argus Panoptes** (the many-eyed all-seeing guardian — perfect for the receipts/observability subsystem alone, but with name-collision pressure from Argo CD/Workflows in the K8s ecosystem we'll run alongside).

**Rename scope is deferred.** This document records the choice; the actual sweep across the plan docs (human references) and architecture identifiers (module names like `hebe-api` → `hebe-api`, package paths `com.hebe.*` → `com.hebe.*`, binary/config `hebe run` → `hebe run`, data dir `~/.hebe/` → `~/.hebe/`, class names `HebePlugin` → `HebePlugin`, etc.) will happen once a few related decisions are made — repo/directory rename, any tagline/concept identity, whether to keep "hebe" anywhere as historical record.

---

## Reading order for someone joining the project

1. `req.md` — what the user wants in one page.
2. `Hebe Brainstorming.md` (this file) — how we got from `req.md` to the current plan.
3. `v1-specs.md` — what's in v1, what's out, what passes acceptance.
4. `v1-architecture.md` — concrete contracts, schemas, lifecycles.
5. `v1-tasks.md` — pick a task and start coding.
6. The original synthesis docs (`hebe-architecture.md`, `hebe-features.md`, `agent-diff.md`) when context on a specific decision is needed.

The original per-agent drafts under `docs/{claude,gpt,gemini,minimax}/` are kept as historical record — a contributor curious about *why* we picked a particular framing will find the alternative framings there.
