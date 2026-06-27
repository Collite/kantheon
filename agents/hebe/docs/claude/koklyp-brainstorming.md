# koklyp — brainstorming

Opinionated starting point for our discussion. The user explicitly asked for **strong opinions and pushbacks**, so this doc takes positions. Disagree freely; the goal is to move both of us off the obvious path and onto a defensible one.

Sections:
1. Pushbacks on `req.md`
2. Architectural bets I want to make (and the alternatives I'm rejecting)
3. Open questions I can't answer alone
4. Risks and unknowns I'd put on the wall now
5. Stretch ideas worth considering after v1

---

## 1. Pushbacks on `req.md`

### 1.1 Kotlin/Native is a non-starter; the brief shouldn't even keep it open

`req.md` says *"I would actually prefer Kotlin native, if possible and the libraries exist."* It doesn't. Both Wasmtime/Chicory and koog are JVM-only. The rest of the stack (Ktor server, Slack/Telegram SDKs, Postgres JDBC, JavaMail) is JVM-shaped. Designing for Kotlin/Native parity costs months and produces a worse product.

**Position: ship JVM. Drop the Native consideration entirely.** GraalVM native-image is a separate question (distribution format, not language target) and can be revisited as a v2+ optimization.

### 1.2 "WASM isolation (ironclaw style)" is a distraction for v1

You said WASM is a hard constraint. I want to push back: ironclaw's WASM story (Wasmtime + WIT + component model + per-call instantiation + epoch-based timeout + persistent compilation cache) is *months* of engineering. It's a sandbox technology, not a feature. Most of what makes ironclaw safe is the **capability model** (allowlist + host-side credential injection + leak scan + rate limit) — and that you can implement against any sandbox.

**Position: adopt Extism on Chicory as the v1 plugin sandbox.** It's WASI Preview 1 + JSON-string in/out — much simpler than ironclaw's component model. We get a real WASM sandbox, but we don't reinvent the wheel. Plugin authors write `tool_metadata`/`execute` exports against a JSON contract. The capability model (declared permissions, host-fn gating, signed manifests) is exactly what ironclaw has, just with a simpler interchange format.

If you specifically want WIT-typed plugins later, watch Chicory's WASIp2 work and flip in v2/v3. Don't block on it now.

### 1.3 "Channels: Slack, WhatsApp, Telegram, email" — ship CLI + web console first

The four channels you listed are all third-party integrations that take real work and have failure modes outside our control (token expiry, rate limits, API changes, OAuth mazes). Before any of them work end-to-end, you need:

- The agent loop running
- Memory + workspace working
- Tool execution + sandbox + receipts
- Approval flow
- A way to debug all of the above

The fastest way to *test* all of this is a **CLI + a web console**. Build those first; add Slack/Telegram/WhatsApp/email as channels 3, 4, 5, 6 once the core works.

**Position: v1 ships CLI + web console + one chat channel (pick your most-used: Slack or Telegram)**. Add the other three in 2-week increments after v1. Don't try to ship four chat platforms simultaneously.

### 1.4 "Cron-ish scheduling" — split into routines vs. SOPs early

The brief lumps "scheduling" into one bucket. ZeroClaw teaches us this is two distinct concerns:

- **Routines** — fire a tool/skill on a schedule. No step structure. Cheap. Example: "every morning at 9, run the daily briefing skill."
- **SOPs** — multi-step deterministic procedures with per-step approval gates. Example: "when GitHub posts to the deploy webhook, run preflight → approval → deploy → verify."

Lumping them together = building one engine that's bad at both. Separating them = each is small and well-shaped.

**Position: build routines for v1, defer SOPs to v2.** Routines are 200 LOC and useful immediately. SOPs are an ambitious subsystem that's hard to design without real use cases.

### 1.5 "Web console" — keep it small, don't make it a SPA framework battle

Easy mistake: choose React + Redux + a state library + an SSR framework + … and burn three weeks on a web console instead of an agent.

**Position: HTMX + a single HTML page, OR Svelte if you want a SPA.** Rationale:
- HTMX gives you 90% of what you need (chat UI, approval prompts, memory browser) with server-rendered fragments.
- Svelte if you want client-side state and dev ergonomics.
- React/Vue/Angular are wrong for this scope.

Either way, ship one HTML file in v1, not a build pipeline.

### 1.6 "kubectl + K3s/K8s tool" — security-tag this aggressively

A k8s cluster is one `kubectl delete -A` away from a very bad day. The brief says *"kubectl + K3s / K8s"* as if it's a normal tool.

**Position: ship kubectl as `RiskLevel.High` with `requiresApproval = true` from day one.** No exceptions. The agent must explicitly ask before any `apply`, `delete`, `exec`, `scale`, `patch`, `replace`, `port-forward`, `rollout`, `cordon`, `drain`, `uncordon`, `taint`, or `label`. Read-only commands (`get`, `describe`, `logs`, `top`, `events`, `version`) can be `Medium` (auto-allowed in `Supervised`).

If the operator wants `Full` autonomy for kubectl, that's a deliberate config choice — and they should still see receipts.

### 1.7 "Memory management: IMPORTANT, multi-tier, scheduled internal management"

The brief flags memory as important but vague. Let me try to nail down what we actually need vs. what's research:

**v1 needs:**
- A `MemoryStore` trait with a SQLite backend (FTS5 + sqlite-vec).
- Markdown workspace ([memory-as-FS](docs/claws/iron/40-memory.md)) — `MEMORY.md`, identity files, daily logs.
- Hybrid retrieval (RRF over FTS + vector).
- Hygiene (sanitize incoming writes for prompt-injection patterns).
- Compaction (transcript → workspace → summarize → truncate, with refusal-to-truncate on summarization failure).
- Group-chat detection (exclude `MEMORY.md` from system prompt in group contexts).

**Defer to v2:**
- Background decay (older items lose importance).
- Background consolidation (merge similar fragments).
- Conflict detection / resolution.
- Snapshots.
- Response cache.

**Defer indefinitely (or until specifically needed):**
- Knowledge graph (entity/relation extraction).
- "Dreaming" / lucid dreams.
- Pluggable third-party providers (mem0, honcho, supermemory).
- Multiple parallel backends (Qdrant, etc.).

**Position: build memory v1 to be 80% as useful as ironclaw with 20% of the code.** Decay/consolidation/KG are sexy in theory and a pain in practice. Add when usage justifies them.

### 1.8 "Persistence: db: SQLite OR PostgreSQL"

Pick one for v1.

**Position: SQLite, default.** Reasons:
- Zero-config for self-hosted small-team.
- FTS5 is built in.
- sqlite-vec gives us vector search.
- Single file → backup is `cp koklyp.db koklyp.db.bak`.
- We won't outgrow it for personal/small-team scale.

Add PostgreSQL in v2 for multi-instance / shared deployments. Don't dual-implement on day one; ironclaw's "all features must work on both backends" rule cost them real engineering time.

### 1.9 "Extendable with new skills AND new agents (developed), modular"

Two extension axes blurred. Distinguish them:

- **Extending with new skills** = markdown bundles. agentskills.io format. No code. Easy.
- **Extending with new agents** = adding new behaviour in code. *This* is what `req.md` calls "modular" — and it should mean **plugins (Extism)** for third-party safe extensions, **modules (in-tree Kotlin code)** for trusted behaviour.

**Position: three extension surfaces, clearly named:**
1. **Skills** — markdown only, no code, sandbox-irrelevant.
2. **Plugins** — WASM via Extism, manifest-declared capabilities and permissions, signed.
3. **In-tree modules** — Rust-equivalents-in-Kotlin, compiled in, fully trusted.

"New agents" (in the multi-agent / sub-agent sense) is a *fourth* concept and orthogonal to the extension axes — see section 2.3.

### 1.10 "Web search" as a built-in tool

Web search needs an external provider (Tavily, Brave, DuckDuckGo, You.com, Serper). The agent doesn't crawl the web itself.

**Position: ship a `WebSearchProvider` trait with a SerpAPI/Tavily/Brave default and a free DuckDuckGo fallback.** Don't make this a "tool" decision; make it a "provider" decision that can degrade gracefully.

---

## 2. Architectural bets I want to make

### 2.1 Wrap koog; don't tie our destiny to it

Koog is young (active dev as of March 2026). It might lose support, change APIs, get acquired and locked behind enterprise tiers, or become incompatible with our needs. We should use it for the hard parts (streaming protocols, history compression, agent persistence, OpenTelemetry) but **never let `ai.koog.*` types appear in our channel/tool/memory code.**

Concretely: the koklyp `LlmProvider` trait is *ours*. Internally, `KoogLlmProvider` adapts koog. If we have to swap, one file changes.

### 2.2 The kernel ABI is **five traits, not ten**

I propose `LlmProvider`, `Channel`, `Tool`, `MemoryStore`, `Observer`. ZeroClaw uses three (`Provider`, `Channel`, `Tool`). I add `MemoryStore` because pluggable memory backends are clearly useful (Hermes pattern), and `Observer` because OpenTelemetry adoption is non-negotiable for production.

I'm explicitly **not adding** `Skill`, `Hook`, `Sandbox`, `Provider`, `Tunnel`, etc. as kernel traits. Those are *implementation patterns*, not extension points. If we need them later, they get added — but starting with five is the right call.

### 2.3 "New agents (developed)" — sub-agents are tools, not first-class citizens

The brief mentions extending with "new agents." That word is overloaded. Possible meanings:
1. New LLM providers — `LlmProvider`
2. New behaviors — plugins / in-tree modules
3. New autonomous personas — multiple `KoklypAgent` instances with different identities
4. Sub-agents — one agent invokes another for a sub-task

**Position: meanings 1, 2, 3 are extension axes; meaning 4 is just a tool.** A "research agent" sub-agent is implemented as a `Tool` that internally spawns a koog agent loop. The parent agent's prompt sees a tool called `research(question)`; the parent doesn't care it runs a whole sub-loop.

This avoids the trap of a "multi-agent framework" with messages between agents and discovery and orchestration — that's a research project, not a v1 deliverable.

### 2.4 The single mutation funnel rule, lint-enforced day one

IronClaw's `ToolDispatcher::dispatch` rule, with the pre-commit hook, is the single most valuable design choice in either claw. **Pick it before writing any code.** Add a Detekt custom rule that flags any direct mutation of `state.{store, workspace, memory, …}` outside `// dispatch-exempt: <reason>` lines.

This is cheap to add now and miserable to retrofit. Do it.

### 2.5 Tool receipts on disk, not in the DB

ZeroClaw's chained Ed25519 receipt log is great. Keep it on disk (`~/.koklyp/receipts/YYYY-MM.log`), not in the SQLite DB. Reasons:
- Append-only log file is naturally tamper-evident.
- Easy to grep (`grep tool=shell receipts/*.log`).
- Independent backup story.
- DB corruption doesn't affect audit.

### 2.6 No multi-engine v1/v2 shenanigans

IronClaw shipped a v1 engine and an v2 engine simultaneously, with a bridge crate adapting between them. The bridge is huge and the migration is incomplete.

**Position: pick our engine model upfront and don't ship two of them.** I propose ironclaw v2's primitives (Thread/Step/Capability/MemoryDoc/Project), but inverted — instead of capability *leases* (time/use-limited grants), start with capability *risk levels + autonomy* (zeroclaw model). Leases are more principled but harder to design correctly without real-world testing. Risk levels are crude but correct.

### 2.7 `HandleOutcome::Pending` from day one

The Pending vs. NoResponse distinction is subtle but right (see ironclaw issue #2079). Build it into the `Agent.handleMessage` return type from the first commit. Retrofitting is annoying.

### 2.8 Compaction at 50%, not 80%

Hermes compresses at 50% of the context window. IronClaw at 80%.

**Position: default 60%, configurable.** 80% is too late — the agent's reasoning quality starts degrading well before that. 50% is too aggressive for short-context windows. 60% is a reasonable compromise. Make the threshold configurable per-channel (chat-style channels can be more aggressive; long-running jobs more lenient).

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

**My current bias: defer SOPs to v2.** Routines are enough for v1. SOPs are a major design effort that's hard to do well without real use cases.

But if you have a concrete use case — "I want koklyp to handle my weekly deploy with explicit approval steps" — then SOPs might earn their place in v1. Need your input.

### 3.2 Multi-user from day one, or single-user with a `userId` always-binding?

Brief says self-hosted small team. That implies multi-user but with one trust boundary. Ironclaw's `TenantScope` wrapper auto-binds `userId` on every operation; ZeroClaw's per-channel `allowed_users` does pairing-time authorization.

**My current bias: build the `TenantScope` wrapper from day one but default to a single-user setup.** Adding multi-user later means going back through every persistence call to add `userId` filtering — painful. Building the seam now is cheap.

But: do we want shared workspaces ("alice + bob can both read /shared/")? Different per-user agent personas? Per-user channel pairings? These need answers before designing the schema.

### 3.3 Should we adopt ironclaw's identity-files concept?

Ironclaw injects `IDENTITY.md`, `SOUL.md`, `USER.md`, `AGENTS.md`, `TOOLS.md` into every system prompt. It's a **lot** of system-prompt overhead. But it makes the agent's persona stable across sessions.

For koklyp v1: do we want this? Or is a simple `identity.txt` + behavior config cleaner?

**My current bias: ship a single `IDENTITY.md` + `MEMORY.md` for v1. Add the rest only if the shape proves to be cramped.**

### 3.4 What's our LLM provider story for v1 — bring-your-own-key, or shipped defaults?

Two options:
- (a) User provides their own Anthropic/OpenAI/Ollama key. We don't ship any defaults.
- (b) We ship a sample config with a free-tier provider (Groq/Gemini-flash/local Ollama) so users can boot without keys.

**My current bias: (b) with Ollama as the default if no key is set.** Friction is the enemy of self-hosted tools.

### 3.5 How seriously do we take "extendable with new agents"?

Re: section 2.3. If "new agents" means sub-agents-as-tools, we're aligned. If it means "I want a marketplace where users publish custom agent personalities," that's a much bigger feature and changes the architecture. Need clarification.

### 3.6 Are we serving multiple humans through one agent process, or running one agent process per human?

Self-hosted small team could be either:
- **One process, multiple users** — tenant-scoped DB, per-user pairings, single binary running 24/7.
- **One process per user** — each user runs their own koklyp, federated channels (e.g. shared Slack workspace) but personal agent instance.

Multi-user-one-process is simpler to deploy but more complex to design. Per-user processes are simpler to design but multiplies systemd unit files.

**My current bias: one process, multiple users.** But this drives a lot of the security and persistence schema, so we should decide early.

### 3.7 What's "tool versioning/rollback" worth?

IronClaw flags this as a missing feature. ZeroClaw doesn't appear to have it either. If a plugin update breaks a workflow, can the user roll back?

**My current bias: track versions in the manifest, allow `koklyp plugin install <name>@<version>` and `koklyp plugin rollback <name>` for v2.** v1 just installs whatever's in `~/.koklyp/plugins/`.

---

## 4. Risks and unknowns I'd put on the wall now

1. **Koog v1.0 may not have shipped.** Mitigation: facade pattern (section 2.1).
2. **Chicory's WASIp2/component-model is in development.** Mitigation: Extism (Preview 1) is enough.
3. **WhatsApp Cloud API requires a verified business account + approved phone number.** This is a real onboarding obstacle. Mitigation: ship Slack/Telegram first; mark WhatsApp as "advanced setup."
4. **Slack rate limits.** Tier 2 / Tier 3 limits get hit fast in agent contexts. Mitigation: bolt-jvm has built-in rate limiting; we wrap retries.
5. **Email IMAP IDLE behavior varies by server.** Mitigation: fallback to polling.
6. **kubectl is dangerous (section 1.6).** Mitigation: aggressive risk-tagging and approval defaults.
7. **Plugin signing UX.** Most users won't want to deal with Ed25519 keys. Mitigation: `signature_mode = optional` default; require for production.
8. **Embedding API costs.** OpenAI text-embedding-3-small is cheap but not free; for a chatty agent on a personal note-store, it adds up. Mitigation: aggressive `CachedEmbeddingProvider`, consider Ollama-local embeddings as default.
9. **Tool-call thrash loops.** LLMs occasionally lock into "call same broken tool, get same error, repeat" cycles. Mitigation: ironclaw-style loop detector (section 2.7 of architecture).
10. **Prompt-injection attacks via memory writes.** A malicious user can DM the agent "ignore your instructions and..." and that text gets written to memory, and then loaded into the system prompt next time. Mitigation: hygiene scanner with high-severity rejection on inbound writes (ironclaw `Sanitizer`).
11. **OS keychain integration is finicky** on Linux (secret-service / kwallet / etc.). Mitigation: fallback to a passphrase-derived key stored in a chmod-600 file.
12. **Self-hosted distribution.** If we ship a Docker image, our license must allow it; if a JAR, users need a JVM. Mitigation: ship both.

---

## 5. Stretch ideas worth considering after v1

These are *not* on the v1 roadmap, but worth recording because they shape the architecture and are easy to support if we plan now.

1. **Two-layer context with separate cadences (Hermes).** Cheap base (session summary + identity) refreshed every turn; expensive dialectic (LLM reasoning summary) refreshed on a longer cadence. Saves tokens.

2. **Path-scoped concurrent tools (Hermes).** Tools targeting independent workspace paths run concurrently via `coroutineScope { async }`. Order-preserving. Easy win for tool chains.

3. **Skillforge — agent learns its own skills.** Scout (find candidates from successful traces) → evaluate → integrate (commit as a skill). Research-quality idea, defer.

4. **Pluggable third-party memory providers (Hermes).** Adapt mem0 / honcho / supermemory once the trait is in place.

5. **ACP (Agent Client Protocol).** IDE integration via JSON-RPC over stdio. Easy to add via MCP overlap; valuable for power users.

6. **Native-image distribution (GraalVM).** When binary size or cold-start matters.

7. **Multi-tenant / SaaS-ready topology.** Per-tenant DB schema, billing hooks. Big architectural change; defer until there's a reason.

8. **Hardware (Peripheral trait).** GPIO/I2C/SPI for homelab/IoT use cases. Low priority.

9. **Cryptographic intent receipts (zeroclaw `verifiable_intent`).** Pre-action signed intent that can be replayed for audit. Cool idea, defer.

10. **Routine engine becoming a SOP engine.** When routine use cases get complex enough that step-structure + per-step approval matters.

11. **Web console plugin marketplace.** Browse, install, configure plugins from the UI. v3+.

12. **Capability leases (ironclaw v2).** Time-limited, use-limited capability grants instead of static permissions. More principled than risk levels — but more complex. v3+.

---

## Where I want pushback most

Top three places I think you'll disagree with me and where I want to be argued out of my position:

1. **Deferring SOPs to v2** (section 1.4 + 3.1). If you have a concrete deploy/runbook use case, SOPs might be the right v1 feature even though they're heavy.
2. **Dropping Native entirely** (section 1.1). You said you preferred Native; I'm telling you to give up on that. I think the cost/benefit is clear, but you may have constraints I don't know about (deployment to ARM SBCs without JVM, etc.).
3. **CLI + web console + one chat channel for v1** (section 1.3). I'm pushing back on the four-channel scope. If the brief came from a hard requirement (e.g. customer commitments), we'd need to reorganize, but if it's aspirational we should down-scope ruthlessly for v1.

Other places I'd love your take:
- Section 2.6 (no v1/v2 engines coexisting) — I want a hard "yes, one engine model only" before we start
- Section 3.6 (one-process-multi-user vs per-user) — drives schema design
- Section 3.4 (BYO key vs shipped defaults) — drives onboarding

Pick a few and let's argue.
