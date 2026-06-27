# Inspirations from openclaw and hermes-agent

Quick research notes on two adjacent open-source agent projects, with ideas worth borrowing for koklyp. Sourced primarily from web search + repo READMEs (not exhaustive code reads).

## OpenClaw — `github.com/openclaw/openclaw`

Self-hosted personal-AI assistant in Python. Reportedly very popular (100k+ GitHub stars in early 2026; numbers from third-party blogs, take with salt). Already cited as inspiration in IronClaw's workspace docs (`workspace/README.md:3`).

### Architecture (from third-party deep-dives)

- **Gateway / orchestration layer** — handles routing and session management.
- **Context assembly step** — packages history + memory + instructions before each inference.
- **ReAct loop** — model reasons, calls tools, integrates results.
- **Tool layer** — concrete capabilities (shell, browser, HTTP).
- **Skill / prompt system** — domain-specific expertise loaded on demand.
- **Cron-triggered agentic loop** — *"instead of only responding to human input, the agent is periodically woken up and asked to evaluate its task list."* This is the load-bearing idea behind ironclaw's `HEARTBEAT.md` and zeroclaw's `runtime/heartbeat`.

### Skills

> *"Instead of embedding all tool instructions in every prompt (token-expensive), OpenClaw lists skills as metadata and lets the model read them on demand, analogous to a developer having an IDE with documentation."*

Skills are a separate axis from tools: terse metadata in the prompt; the model self-loads the full skill body via a tool call when it determines it needs it. This is *different* from ironclaw's deterministic prefilter (host-side selector picks skills before the model sees the prompt). Two valid designs; openclaw's is more LLM-driven, ironclaw's is more deterministic.

### Memory (the big one)

> *"OpenClaw remembers things by writing plain Markdown files in your agent's workspace — the model only 'remembers' what gets saved to disk with no hidden state."*

- **`MEMORY.md`** — long-term store. Durable facts, preferences, decisions. **Loaded at the start of every DM session.**
- **No hidden state** — if it's not on disk, the agent forgot.
- **Dreaming** — *"an optional background consolidation pass for memory that collects short-term signals, scores candidates, and promotes only qualified items into long-term memory."* This is where ZeroClaw's `lucid.rs` and IronClaw's compaction strategies probably trace from.

The "memory is database, not RAM" principle (which ironclaw's `workspace/README.md:5` quotes verbatim) originates here.

### Tools, skills, cron, webhooks

OpenClaw advertises tools, skills, cron jobs, and webhooks as the four extension surfaces. Plus internal concepts: architecture, agent, session model, gateway protocol — i.e. the gateway is a stable contract, not a private detail.

### Sources

- [openclaw/openclaw on GitHub](https://github.com/openclaw/openclaw)
- [Memory overview](https://docs.openclaw.ai/concepts/memory)
- [How OpenClaw Works (Bibek Poudel, Medium)](https://bibek-poudel.medium.com/how-openclaw-works-understanding-ai-agents-through-a-real-architecture-5d59cc7a4764)
- [openclaw-arch-deep-dive (gist)](https://gist.github.com/royosherove/971c7b4a350a30ac8a8dad41604a95a0)
- [VoltAgent/awesome-openclaw-skills](https://github.com/VoltAgent/awesome-openclaw-skills) — 5,400+ filtered skills

## Hermes Agent — `github.com/NousResearch/hermes-agent`

Nous Research's "self-improving" agent (v0.x in 2026, written in Python). Tagline: *"the agent that grows with you."*

### Tool use

- **Sequential or concurrent** via `ThreadPoolExecutor` — up to 8 parallel workers.
- **Path-scoped tools** — concurrent only when targeting independent paths. Same-path tools serialise. Clean way to allow parallelism without races on the workspace.
- **Order-preserving** — message and result ordering preserved when reinserting tool responses into history.
- Full TUI with multiline editing, slash-command autocomplete, streaming tool output, interrupt-and-redirect.
- MCP servers exposed as standalone toolsets, configurable interactively in `hermes tools`.

### Memory architecture (the most interesting bit)

A pluggable memory system with multiple production providers as first-class:

> Built-in providers: `honcho`, `mem0`, `supermemory`, `byterover`, `hindsight`, `holographic`, `openviking`, `retaindb`. Each implements `MemoryProvider` ABC; orchestrated by `agent/memory_manager.py`.

This is the single most useful idea in Hermes for koklyp: **memory is a pluggable interface, and there are real third-party providers in the ecosystem worth integrating with**. Treating memory as a SaaS-or-local choice (rather than always-local) is a different bet from openclaw/ironclaw/zeroclaw, and worth at least leaving the door open for.

Other notable mechanics:

- **Agent-curated memory with periodic nudges** — the agent itself decides what to commit, prompted to do so periodically.
- **FTS5 session search with LLM summarization for cross-session recall** — keyword recall + on-demand summarization.
- **Two-layer context injection**:
  - **Base layer** — session summary + agent representation + peer card; refreshed on `contextCadence`.
  - **Dialectic supplement** — LLM-generated reasoning context; refreshed on `dialecticCadence`.

The two-layer design is a fresh idea: separate the *cheap* refresh (session state + identity) from the *expensive* one (LLM reasoning summary), with independent cadences. Ironclaw and zeroclaw mostly run a single context build per turn.

### Core architecture

- **`ProviderTransport` ABC** abstracts format conversion + HTTP transport behind one interface. Concrete impls: `AnthropicTransport`, `ChatCompletionsTransport`, `ResponsesApiTransport`, `BedrockTransport`. Each owns message conversion, tool conversion, kwargs assembly, response normalization. Streaming/retries/cache stay on `AIAgent`.
- **`ContextCompressor`** — monitors token usage, compresses context when approaching the limit (default threshold: **50%** of context window — much more aggressive than ironclaw's 80%).

### Sources

- [NousResearch/hermes-agent](https://github.com/nousresearch/hermes-agent)
- [Memory providers docs](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/memory-providers.md)
- [Hermes Agent Documentation](https://hermes-agent.nousresearch.com/docs/)
- [Issue #346: Structured Memory System (typed nodes + graph edges)](https://github.com/NousResearch/hermes-agent/issues/346)

## Ideas worth borrowing for koklyp

1. **(openclaw)** Memory-as-markdown-files on disk, with `MEMORY.md` loaded per session. Simple, debuggable, works without infrastructure. Already adopted by ironclaw verbatim.
2. **(openclaw)** Cron-triggered agentic loop ("agent wakes up and re-evaluates"). Mirror via koklyp's heartbeat + routine engine. Don't make it the *only* loop, but make it cheap to add.
3. **(openclaw)** "Skills as metadata, body loaded on demand via a tool call" — an alternative to ironclaw's deterministic prefilter. Worth offering both modes (deterministic OR on-demand) and letting the user pick.
4. **(openclaw + zeroclaw)** Memory consolidation as a scheduled background job ("dreaming" / `lucid.rs`). Defer for v1; bake in the seam.
5. **(hermes)** Pluggable memory provider interface. Even if v1 ships only the SQLite backend, the `MemoryProvider`-style ABC means later integration with mem0 / honcho / supermemory is trivial.
6. **(hermes)** Path-scoped concurrent tool execution. Tools targeting independent workspace paths run concurrently; same-path tools serialise. Easy win for latency in long tool chains.
7. **(hermes)** Two-layer context with separate cadences. Refresh "session summary + identity" cheaply; refresh "LLM reasoning summary" on a longer cadence. Saves tokens.
8. **(hermes)** `ProviderTransport` ABC: format conversion + HTTP transport per-provider, streaming/retries/cache shared. Cleaner than putting per-provider logic in the agent loop.
9. **(hermes)** Aggressive default for context compression (50% threshold rather than 80%). For chat-style use the cheaper compaction earlier; for agentic-reasoning use the higher threshold. Make it configurable.
10. **(hermes)** Order-preserving concurrent tool execution. Even with parallelism, the model sees results in original-call order.
11. **(openclaw)** A skills registry / marketplace ecosystem (5,400+ third-party skills). For koklyp, adopt the agentskills.io format so users can pull existing skills from the openclaw/zeroclaw ecosystems.
12. **(both)** Tools that can dispatch *other tools* (or sub-agents). MCP-via-tool is the cleanest expression of this — koklyp should ship an MCP client tool from day one.

## Pass on this (attractive but not a fit for koklyp v1)

- **Path-scoped concurrency model with `ThreadPoolExecutor` semantics** is a Python concurrency idea. Kotlin coroutines + structured concurrency give you better primitives — the *idea* (tools targeting independent paths run concurrently) ports; the implementation doesn't. Use `coroutineScope { async { ... } }` per path-group.
- **Hermes' eight bundled memory providers.** Most are SaaS or fashionable startups; integrating with all of them on day one is busywork. Ship the trait + one local backend; add providers when users ask.
- **OpenClaw's "model self-loads the skill body via a tool call".** Adds an LLM round-trip per skill activation. For latency-sensitive chat (Slack/Telegram) this is annoying. Stick with deterministic prefilter for v1; consider the on-demand mode as an opt-in for the web console / long-running jobs.
- **Hermes' rich TUI.** Neat for power users; non-essential for a self-hosted small-team agent that lives behind chat channels and a web console. Defer.
- **OpenClaw's "5,400 skills" gravitational pull.** Importing the full skill registry would mean importing 5,400 prompts of unknown provenance. Curate a small bundled set, then let users pull individual skills explicitly.
