# hebe — brainstorming (synthesis)

Opinionated working document for discussion. Synthesis of Claude's draft (the spine) plus useful pushbacks/risks/questions surfaced by GPT, Gemini, and MiniMax. The user explicitly asked for **strong opinions and pushbacks**, so this doc takes positions. Disagree freely.

> **Two of Claude's open questions are now closed by the user's revised brief** and have moved out of §3 into the "decided" pile (see the top section). Remaining questions are still on the table.

---

## 0. Decisions made by the revised brief

| Question | Original status | Now |
|---|---|---|
| **Native vs JVM** | Open in original brief; three of four drafts already pushed JVM | **Closed: JVM.** Native dropped. GraalVM native-image stays open as a v2 distribution-format optimisation. |
| **WASM isolation** | "Hard requirement" in original brief | **Closed: dropped.** Plugins are JVM modules (URLClassLoader-isolated); cross-language extensions go through MCP (process boundary). |
| **Plugin sandbox model** | Extism on Chicory in Claude's draft; Wasmer in MiniMax's; sidecar in GPT's | **Closed: classloader isolation + MCP for untrusted code.** GPT's "sidecar boundary" idea wins, just reframed: MCP *is* the sidecar. |

These three decisions collapse a lot of complexity that was sprawling across the four drafts. Everything below assumes them.

---

Sections:

1. Pushbacks on the (revised) `req.md`
2. Architectural bets I want to make (and the alternatives I'm rejecting)
3. Open questions I can't answer alone
4. Risks and unknowns
5. Stretch ideas worth considering after v1
6. New questions raised specifically by the JVM-plugin direction
7. Where I want pushback most

---

## 1. Pushbacks on `req.md`

### 1.1 ~~Kotlin/Native is a non-starter~~ — closed

Decided. Move on.

### 1.2 ~~"WASM isolation (ironclaw style)"~~ — closed

Decided. The new direction (JVM modules + MCP) is actually *cleaner* than where Claude's draft was. The capability model survives intact — manifest declarations, permission gates at the host boundary, signed plugins, leak detection on output. We just lose the `mmap`-level memory isolation that WASM gave us, which we backstop with: (a) requiring trust for in-process plugins, (b) routing untrusted code to MCP.

Net effect: less code, fewer dependencies (no Chicory, no Extism), simpler debugging (plugin errors are JVM stacktraces), faster plugin invocation (no FFI / serialisation cost). The cost is loss of strict isolation — accepted because MCP carries it.

### 1.3 "Channels: Slack, WhatsApp, Telegram, email" — ship CLI + web console first

The four channels in `req.md` are all third-party integrations with failure modes outside our control (token expiry, rate limits, API changes, OAuth mazes). Before any of them work end-to-end, you need:

- The agent loop running
- Memory + workspace working
- Tool execution + sandbox + receipts
- Approval flow
- A way to debug all of the above

The fastest way to *test* all of this is a **CLI + a web console**. Build those first; add Slack/Telegram/WhatsApp/email as channels 3, 4, 5, 6 once the core works.

GPT's draft converged on the same conclusion (Web + CLI + Telegram + Slack + Email; WhatsApp v2). MiniMax kept all four in v1 but flagged WhatsApp's complexity. Claude flagged WhatsApp specifically for v1.1.

**Position: v1 ships CLI + web console + one chat channel (pick your most-used: Slack or Telegram) + email.** WhatsApp moves to v1.1. This is a downscope from `req.md` and from three of the four drafts.

### 1.4 "Cron-ish scheduling" — split into routines vs. SOPs early

The brief lumps "scheduling" into one bucket. ZeroClaw teaches us this is two distinct concerns:

- **Routines** — fire a tool/skill on a schedule. No step structure. Cheap. Example: "every morning at 9, run the daily briefing skill."
- **SOPs** — multi-step deterministic procedures with per-step approval gates. Example: "when GitHub posts to the deploy webhook, run preflight → approval → deploy → verify."

Lumping them together = building one engine that's bad at both.

**Position: build routines for v1, defer SOPs to v2.** Routines are 200 LOC and useful immediately. (See §3.1 for the open question: do you have a deploy/runbook use case that earns SOPs in v1?)

### 1.5 "Web console" — keep it small

Easy mistake: choose React + Redux + a state library + an SSR framework + … and burn three weeks on a web console instead of an agent.

**Position: HTMX + a single HTML page, OR Svelte if you want a SPA.** Ship one HTML file in v1, not a build pipeline. (Gemini suggested Svelte; GPT was agnostic; MiniMax went straight for SSE+WS via Ktor without picking a framework.)

### 1.6 "kubectl + K3s/K8s tool" — security-tag this aggressively

A k8s cluster is one `kubectl delete -A` away from a very bad day.

**Position: ship kubectl as `RiskLevel.High` with `requiresApproval = true` from day one for every mutating verb** (`apply`, `delete`, `exec`, `scale`, `patch`, `replace`, `port-forward`, `rollout`, `cordon`, `drain`, `uncordon`, `taint`, `label`). Read-only verbs (`get`, `describe`, `logs`, `top`, `events`, `version`) can be `Medium` (auto-allowed in `Supervised`).

If the operator wants `Full` autonomy for kubectl, that's a deliberate config choice — and they should still see receipts. Gemini suggested inline `[Approve]` / `[Deny]` buttons on the originating channel for these prompts; that's a v2 feature once the approval flow is steady.

### 1.7 "Memory management: IMPORTANT, multi-tier, scheduled internal management"

The brief flags memory as important but vague. GPT's draft did the most useful work here, naming explicit tiers (Live / Transcript / Curated / Derived / Retrieval) and the maintenance jobs (transcript summarisation, fact extraction, daily digest, stale cleanup, embedding refresh, failed-job detection). I've folded both into `hebe-architecture.md` §13.

**v1 needs:**

- A `MemoryStore` trait with a SQLite backend (FTS5 + sqlite-vec).
- Markdown workspace — `MEMORY.md`, identity files, daily logs.
- Hybrid retrieval (RRF over FTS + vector).
- Hygiene (sanitise incoming writes for prompt-injection patterns).
- Compaction (transcript → workspace → summarise → truncate, with refusal-to-truncate on summarisation failure).
- **Preemptive history pruning** — trim BEFORE overflow, not after (MiniMax H8 / Hermes).
- Group-chat detection (exclude `MEMORY.md` from system prompt in group contexts).
- Scheduled maintenance jobs (above list).

**Defer to v2:** decay, consolidation, conflict detection, snapshots, response cache.

**Defer indefinitely:** knowledge graph, "dreaming"/lucid-dreams, pluggable third-party providers (mem0, honcho, supermemory), multiple parallel backends (Qdrant, etc.).

**Position: build memory v1 to be 80% as useful as IronClaw with 20% of the code.** Decay/consolidation/KG are sexy in theory and a pain in practice.

### 1.8 "Persistence: db: SQLite OR PostgreSQL"

**Position: SQLite, default.** Same reasons as Claude's draft and three of four agents converged on this. PostgreSQL stays opt-in for v2 multi-instance / shared deployments.

### 1.9 "Extendable with new skills AND new agents (developed), modular"

Two extension axes blurred. With the new direction, this becomes much cleaner:

- **Extending with new skills** = markdown bundles. agentskills.io format. No code.
- **Extending with new agents** = adding new behaviour in code. Three concentric rings:
  1. **In-tree Kotlin modules** — trusted, compiled in.
  2. **JVM plugin JARs** — manifest-declared, isolated classloaders, capabilities gated at the host boundary, optional Ed25519 signature.
  3. **MCP servers** — process boundary, recommended for any third-party / untrusted code.

"New agents" (in the multi-agent / sub-agent sense) is a *fourth* concept and orthogonal to extension axes — see §2.3.

### 1.10 "Web search" as a built-in tool

Web search needs an external provider (Tavily, Brave, DuckDuckGo, You.com, Serper). The agent doesn't crawl the web itself.

**Position: ship a `WebSearchProvider` trait with a SerpAPI/Tavily/Brave default and a free DuckDuckGo fallback.** Don't make this a "tool" decision; make it a "provider" decision that can degrade gracefully.

---

## 2. Architectural bets

### 2.1 Wrap koog; don't tie our destiny to it

Koog is young (active dev as of March 2026). It might lose support, change APIs, get acquired, or become incompatible. Use it for the hard parts (streaming protocols, history compression, agent persistence, OpenTelemetry) but **never let `ai.koog.*` types appear in our channel/tool/memory code.**

The hebe `LlmProvider` trait is *ours*. Internally, `KoogLlmProvider` adapts koog. If we have to swap, one file changes. (Gemini's brainstorming raised the alternative: build our own lightweight loop. I'd push back — that's reinventing the wheel for boring infrastructure. Wrap, don't replace, and keep the wrap thin.)

### 2.2 The kernel ABI is **five traits, not ten**

`LlmProvider`, `Channel`, `Tool`, `MemoryStore`, `Observer`. ZeroClaw uses three (`Provider`, `Channel`, `Tool`). I add `MemoryStore` because pluggable memory backends are clearly useful (Hermes pattern), and `Observer` because OpenTelemetry adoption is non-negotiable.

I'm explicitly **not adding** `Skill`, `Hook`, `Sandbox`, `Tunnel`, etc. as kernel traits. Those are *implementation patterns*, not extension points. Adding `Plugin` to the kernel ABI is debatable — see §6.1 below.

### 2.3 "New agents (developed)" — sub-agents are tools, not first-class citizens

The brief mentions extending with "new agents." That word is overloaded. Possible meanings:

1. New LLM providers — `LlmProvider`
2. New behaviours — plugins / in-tree modules
3. New autonomous personas — multiple `HebeAgent` instances with different identities
4. Sub-agents — one agent invokes another for a sub-task

**Position: meanings 1, 2, 3 are extension axes; meaning 4 is just a tool.** A "research agent" sub-agent is implemented as a `Tool` that internally spawns a koog agent loop. The parent agent's prompt sees a tool called `research(question)`; the parent doesn't care it runs a whole sub-loop. (MiniMax 5.7 proposed a multi-agent router; that's a v2+ design.)

### 2.4 The single mutation funnel rule, lint-enforced day one

IronClaw's `ToolDispatcher::dispatch` rule, with a pre-commit hook, is the single most valuable design choice in either claw. (MiniMax independently flagged this as their H4: "Everything Goes Through Tools".) **Pick it before writing any code.** Add a Detekt custom rule that flags any direct mutation of `state.{store, workspace, memory, …}` outside `// dispatch-exempt: <reason>` lines.

This is cheap to add now and miserable to retrofit. Do it.

### 2.5 Tool receipts on disk, not in the DB

ZeroClaw's chained Ed25519 receipt log is great. Keep it on disk (`~/.hebe/receipts/YYYY-MM.log`), not in the SQLite DB. Reasons:

- Append-only log file is naturally tamper-evident.
- Easy to grep (`grep tool=shell receipts/*.log`).
- Independent backup story.
- DB corruption doesn't affect audit.

### 2.6 No multi-engine v1/v2 shenanigans

IronClaw shipped a v1 engine and a v2 engine simultaneously, with a bridge crate. The bridge is huge and the migration is incomplete.

**Position: pick our engine model upfront and don't ship two of them.** I propose IronClaw v2's primitives (Thread/Step/Capability/MemoryDoc/Project), but inverted — instead of capability *leases* (time/use-limited grants), start with capability *risk levels + autonomy* (ZeroClaw model). Leases are more principled but harder to design correctly. Risk levels are crude but correct.

### 2.7 `HandleOutcome::Pending` from day one

The Pending vs. NoResponse distinction is subtle but right (see IronClaw issue #2079). Build it into the `Agent.handleMessage` return type from the first commit. Retrofitting is annoying.

### 2.8 Compaction at 60%, not 80%

Hermes compresses at 50% of the context window. IronClaw at 80%.

**Position: default 60%, configurable.** 80% is too late — the agent's reasoning quality starts degrading well before that. 50% is too aggressive for short-context windows. 60% is a reasonable compromise. Make the threshold configurable per-channel (chat-style channels can be more aggressive; long-running jobs more lenient).

Plus **preemptive pruning** (MiniMax H8): we pre-trim before the context overflows, not after.

### 2.9 Memory is an interface, but ship one impl

Hermes ships eight memory providers. We ship one (SQLite + workspace + sqlite-vec). The trait-based design means adding a second is trivial later. **Don't try to be Hermes on day one.**

### 2.10 The web console is a debugging tool first

Treat the web console as the way *we* debug what the agent is doing, not as the user-facing UI. The user-facing UI is whatever chat platform they use.

This re-orders priorities:

- v1: log of every event, every receipt, every approval prompt, every tool invocation.
- v1: memory browser (search + tree view).
- v1: streaming chat (so you can see what the agent does).
- v2: settings editor.
- v2: plugin install UI.
- v2: cron / routine management UI.

If v1 goes well, the web console becomes valuable to power users. But its primary job is to make our development tractable.

---

## 3. Open questions I can't answer alone

### 3.1 Are SOPs in v1?

The req mentions "cron-ish scheduling." It does not explicitly mention SOPs. ZeroClaw treats SOPs as a first-class subsystem. IronClaw doesn't have them (routines instead).

**My current bias: defer SOPs to v2.** Routines are enough for v1.

But if you have a concrete use case — "I want hebe to handle my weekly deploy with explicit approval steps" — then SOPs might earn their place in v1.

### 3.2 Multi-user from day one, or single-user with a `userId` always-binding?

Brief says self-hosted small team. That implies multi-user but with one trust boundary.

**My current bias: build the `TenantScope` wrapper from day one but default to a single-user setup.** Adding multi-user later means going back through every persistence call to add `userId` filtering — painful. Building the seam now is cheap.

But: do we want shared workspaces ("alice + bob can both read /shared/")? Different per-user agent personas? Per-user channel pairings? These need answers before designing the schema. (GPT's draft was firmly single-user-first; MiniMax assumed multi-user; Gemini was agnostic.)

### 3.3 Should we adopt IronClaw's identity-files concept?

IronClaw injects `IDENTITY.md`, `SOUL.md`, `USER.md`, `AGENTS.md`, `TOOLS.md` into every system prompt. It's a **lot** of system-prompt overhead. But it makes the agent's persona stable across sessions.

**My current bias: ship a single `IDENTITY.md` + `MEMORY.md` for v1.** Add the rest only if the shape proves cramped. (None of the other agents pushed back on this; consider it a soft default.)

### 3.4 What's our LLM provider story for v1 — bring-your-own-key, or shipped defaults?

Two options:

- (a) User provides their own Anthropic/OpenAI/Ollama key. We don't ship any defaults.
- (b) We ship a sample config with a free-tier provider (Groq/Gemini-flash/local Ollama) so users can boot without keys.

**My current bias: (b) with Ollama as the default if no key is set.** Friction is the enemy of self-hosted tools. (MiniMax's config example uses OpenRouter; reasonable alternative.)

### 3.5 How seriously do we take "extendable with new agents"?

Re §2.3. If "new agents" means sub-agents-as-tools, we're aligned. If it means "I want a marketplace where users publish custom agent personalities," that's a much bigger feature and changes the architecture. Need clarification.

### 3.6 Are we serving multiple humans through one agent process, or running one agent process per human?

Self-hosted small team could be either:

- **One process, multiple users** — tenant-scoped DB, per-user pairings, single binary running 24/7.
- **One process per user** — each user runs their own hebe, federated channels.

Multi-user-one-process is simpler to deploy but more complex to design. Per-user processes are simpler to design but multiplies systemd unit files.

**My current bias: one process, multiple users.** But this drives a lot of the security and persistence schema, so we should decide early.

### 3.7 What's "tool versioning/rollback" worth?

IronClaw flags this as a missing feature. ZeroClaw doesn't appear to have it either. MiniMax 3.1 lists it as Low priority.

**My current bias: track versions in the manifest, allow `hebe plugin install <name>@<version>` and `hebe plugin rollback <name>` for v2.**

---

## 4. Risks and unknowns

1. **Koog v1.0 may not have shipped.** Mitigation: facade pattern (§2.1).
2. ~~**Chicory's WASIp2/component-model is in development.**~~ Removed — N/A under new direction.
3. **WhatsApp Cloud API requires a verified business account + approved phone number.** This is a real onboarding obstacle. Mitigation: ship Slack/Telegram first; mark WhatsApp as "advanced setup" in v1.1.
4. **Slack rate limits.** Tier 2 / Tier 3 limits get hit fast in agent contexts. Mitigation: bolt-jvm has built-in rate limiting; we wrap retries.
5. **Email IMAP IDLE behavior varies by server.** Mitigation: fallback to polling.
6. **kubectl is dangerous.** Mitigation: aggressive risk-tagging and approval defaults.
7. **Plugin signing UX.** Most users won't want to deal with Ed25519 keys. Mitigation: `signature_mode = optional` default; require for production.
8. **Embedding API costs.** OpenAI text-embedding-3-small is cheap but not free. Mitigation: aggressive `CachedEmbeddingProvider`, consider Ollama-local embeddings as default.
9. **Tool-call thrash loops.** Mitigation: IronClaw-style loop detector (MiniMax H7 and Claude both flagged).
10. **Prompt-injection attacks via memory writes.** A malicious user can DM the agent "ignore your instructions and..." and that text gets written to memory, and then loaded into the system prompt next time. Mitigation: hygiene scanner with high-severity rejection on inbound writes (IronClaw `Sanitizer`).
11. **OS keychain integration is finicky** on Linux (secret-service / kwallet / etc.). Mitigation: fallback to a passphrase-derived key stored in a chmod-600 file.
12. **Self-hosted distribution.** If we ship a Docker image, our license must allow it; if a JAR, users need a JVM. Mitigation: ship both.
13. **NEW: JVM plugin classloader isolation is leaky.** A malicious plugin can call `Runtime.exec`, `System.setProperty`, `Class.forName` with reflection, etc. — classloader isolation is *not* a sandbox. Mitigation: (a) document the trust posture loudly, (b) push untrusted code to MCP, (c) signature-required mode for production. See §6.4 below.
14. **NEW: JVM plugin ABI compatibility.** A plugin compiled against `hebe-api 0.1.0` may break on `0.2.0`. Mitigation: manifest pins `hebe_api_version`; loader refuses incompatible versions and reports clearly.
15. **NEW: Plugin classloader leaks on update.** JVM doesn't guarantee classloader unloading; old class versions may stick around. Mitigation: warn loudly on plugin update; recommend restart for prod.
16. **Memory tier vagueness.** GPT's risk: "memory tiers are described vaguely and implemented inconsistently." Mitigation: the five-tier framing in `hebe-architecture.md` §13 is now explicit; we hold ourselves to it.
17. **Runtime gets too clever before the data model is stable.** GPT's risk. Mitigation: ship the SQLite schema + migrations early; freeze the message/turn/job tables before adding scheduler features.

---

## 5. Stretch ideas worth considering after v1

1. **Two-layer context with separate cadences (Hermes).** Cheap base (session summary + identity) refreshed every turn; expensive dialectic (LLM reasoning summary) refreshed on a longer cadence.
2. **Path-scoped concurrent tools (Hermes).** Tools targeting independent workspace paths run concurrently via `coroutineScope { async }`. Order-preserving.
3. **Skillforge — agent learns its own skills.** Scout → evaluate → integrate. Research-quality. (Gemini's "self-evolution" idea is in this neighbourhood.)
4. **Pluggable third-party memory providers (Hermes).** Adapt mem0 / honcho / supermemory once the trait is in place.
5. **ACP (Agent Client Protocol).** IDE integration via JSON-RPC over stdio.
6. **Native-image distribution (GraalVM).** When binary size or cold-start matters.
7. **Multi-tenant / SaaS-ready topology.** Big architectural change; defer until there's a reason.
8. **Hardware (Peripheral trait).** GPIO/I2C/SPI for homelab/IoT. Low priority.
9. **Cryptographic intent receipts (ZeroClaw `verifiable_intent`).** Pre-action signed intent that can be replayed for audit.
10. **Routine engine becoming a SOP engine.** When routine use cases get complex enough.
11. **Web console plugin marketplace.** Browse, install, configure plugins from the UI. v3+.
12. **Capability leases (IronClaw v2).** Time-limited, use-limited capability grants instead of static permissions.
13. **PF4J adoption.** If the hand-rolled URLClassLoader-based plugin loader gets cumbersome, swap in PF4J.
14. **Subprocess sandbox for risky native execution (`firejail`/`bwrap`/Docker).** GPT's sidecar idea, applied specifically to shell/browser/kubectl, not to plugins.

---

## 6. New questions raised by the JVM-plugin direction

These are the topics I think we should brainstorm next, since the user specifically asked for the brainstorming step after the architecture revision.

### 6.1 Should `Plugin` be in the kernel ABI (`api`)?

Arguments **for** putting `Plugin` in `api`:

- Then the plugin JAR depends only on `hebe-api`. Clean.
- Documents the contract centrally.

Arguments **against**:

- Pollutes the kernel ABI. The kernel doesn't fundamentally care about plugins; the host does.
- Makes `api` aware of `PluginHost`, capabilities, manifest concepts.

**My current bias:** put `Plugin` and `PluginHost` in a separate `plugin-api` module that depends on `api`. Plugin authors compile against `plugin-api`; kernel stays clean.

### 6.2 Hand-rolled URLClassLoader vs. PF4J

The architecture currently says "hand-rolled, ~600 LOC, swap to PF4J if pain." The risk: hand-rolled lifecycle bugs. The reward: no opinionated framework dep.

**My current bias:** spike a 200-LOC hand-roll first. If it works, ship it. PF4J in v1.x if needed. The plugin contract is loader-agnostic, so the swap is cheap.

Pushback wanted: do you want to commit to PF4J upfront and skip the hand-roll spike?

### 6.3 What capabilities ship in v1's `PluginHost`?

Currently planned (from the features doc):

- `http_client` (with `allowlist_domains` from manifest)
- `env_read`
- `secrets:<name>` (host-injected, plugin never sees the value)

Deferred to v2:

- `file_read` / `file_write` (workspace-scoped)
- `memory_read` / `memory_write`

Question: is this enough for the v1 plugin use cases you have in mind? If you're imagining a plugin that wraps "the Linear API," `http_client` + `secrets:linear_api_key` is sufficient. If you're imagining a plugin that wraps "the local filesystem," we need `file_read`/`file_write` in v1.

Tell me what plugin you want to write first; I'll redesign the v1 capability set accordingly.

### 6.4 Trust posture — how loudly do we warn?

JVM plugins in-process are powerful and not strictly sandboxed. We have three audiences:

- **You** — know the trade-offs; will write trusted plugins.
- **A teammate installing your plugin** — needs to make a trust decision.
- **A future "marketplace" user** — high risk if unsigned plugins get installed casually.

I propose:

- v1 default: `signature_mode = optional`. Unsigned plugins log a warning but load.
- v1 docs: bold red box on every plugin-install page about JVM trust.
- v2 default: `signature_mode = required`. Unsigned plugins refused.
- v3: web-console UI flow that surfaces capabilities-requested at install time, modeled on Android-app-permission dialogs. ("This plugin wants to make HTTP requests to api.example.com and read your env vars. Continue?")

Pushback wanted: are you comfortable with `optional` being the v1 default, or should we be stricter from the start?

### 6.5 Do we need a shell-tool sandbox in v1?

Original Claude draft pushed subprocess-sandbox (`firejail`/`bwrap`/Docker) to v2. GPT's draft made it more central ("dedicated sandbox boundary"). Now that we've removed in-process WASM, the *only* real sandbox we have for native execution is the subprocess wrapper.

The question: does v1 ship the shell tool with no native sandbox (relying on `allowed_commands` + `forbidden_commands` + the validator + receipts), or do we pull subprocess-sandbox forward to v1?

**My current bias:** v1 ships shell with policy/validator/receipts only. Subprocess wrapper in v2. Reason: getting `firejail`/`bwrap`/Docker to work cross-platform is real engineering; v1 should focus on the agent loop. Operators who care can wrap hebe itself in a container.

Pushback wanted: is "shell tool inside an unwrapped JVM" too dangerous a default?

### 6.6 Plugin hot-reload in v1?

Hot-reload (watch `~/.hebe/plugins/`, reload changed JARs) is a great dev-loop feature. But classloader unload is best-effort, and it adds bug surface.

**My current bias:** v2 only. v1 just rescans on `hebe plugin install` / restart.

Pushback wanted: would you rather have hot-reload in v1 even if it occasionally leaks classloaders?

### 6.7 What does the plugin template repo look like?

The features doc says "plugin template repo" as a v1 deliverable. Concrete questions:

- Gradle-based template? Maven? Both?
- Kotlin-only, or Java-friendly too?
- How do we ship the `plugin-api` jar — Maven Central, JitPack, GitHub Packages, or "git submodule the hebe repo"?
- Do we want the template to bundle a `hebe test-plugin <jar>` command for round-trip testing?

These are small but they shape the plugin-author DX. Worth discussing.

---

## 7. Where I want pushback most

Top three places I expect you'll disagree with me:

1. **Deferring SOPs to v2** (§1.4 + §3.1). If you have a concrete deploy/runbook use case, SOPs might be the right v1 feature even though they're heavy.
2. **Downscoping channels for v1** (§1.3). I'm pushing back on the four-channel scope. If the brief came from a hard requirement (customer commitments), we'd need to reorganise; if it's aspirational we should down-scope ruthlessly.
3. **JVM plugins as the v1 plugin path with `signature_mode = optional` default** (§6.4). The trust posture is a real design decision; you may want stricter defaults.

Other places I'd love your take:

- §2.6 (no v1/v2 engines coexisting) — I want a hard "yes, one engine model only."
- §3.6 (one-process-multi-user vs per-user) — drives schema design.
- §3.4 (BYO key vs shipped defaults) — drives onboarding.
- §6.3 (which capabilities ship in v1's `PluginHost`) — drives the kind of plugins we can write on day one.
- §6.5 (shell-tool sandbox in v1?) — drives how aggressive the v1 security posture is.

Pick a few and let's argue.
