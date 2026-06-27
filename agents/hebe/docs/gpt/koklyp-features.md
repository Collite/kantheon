# koklyp Features

This document turns the source-system analysis and req.md into a feature inventory for koklyp. It is intentionally broader than an MVP. The point is to make prioritization explicit before implementation starts.

## Priority Labels

- M1: first usable release
- M2: important after the core stabilizes
- M3: valuable but deferrable

## Product Direction

koklyp should be a single-user, always-available assistant that can:

- receive messages from several channels,
- reason over durable memory and workspace context,
- call tools safely,
- schedule and run background work,
- expose an operator-friendly web console,
- remain extensible with new tools and new specialized agents.

## Runtime And Agent Features

| Feature | Priority | Why it matters | Source | Implementation direction |
| --- | --- | --- | --- | --- |
| Session -> thread -> turn conversation model | M1 | Gives clear boundaries for context, undo, approvals, and jobs | IronClaw | Keep explicit IDs and states in the runtime layer |
| Shared runtime loop for chat and background jobs | M1 | Prevents separate behavior between interactive and autonomous work | IronClaw, ZeroClaw | Build one orchestration engine with delegate variants |
| Streaming responses | M1 | Needed for good UX in console and channels, and for mid-stream tool calls | ZeroClaw, OpenClaw, Hermes | Model providers and channel adapters should emit stream events rather than final strings |
| Explicit control commands | M1 | Undo, compact, stop, retry, status, and thread controls should not be treated as normal chat text | IronClaw, Hermes | Parse commands before turn creation |
| Approval pauses and resumable execution | M1 | Safer tool use and clearer UX | IronClaw, ZeroClaw | Represent approvals as runtime state, not UI-only modals |
| Background jobs | M1 | Required for long-running work and internal maintenance | IronClaw, Hermes | Scheduler plus durable job store |
| Subagents or delegated tasks | M2 | Useful for parallel work and specialized reasoning | Hermes, OpenClaw | Start with lightweight delegated runtime tasks before richer agent hierarchies |
| Provider routing and fallback | M2 | Helps reliability and cost control | ZeroClaw, OpenClaw | Separate provider abstraction from routing policy |
| Cost and rate guardrails | M2 | Needed for always-on autonomy | IronClaw, ZeroClaw | Track call counts, token spend, and per-job budgets |

## Channels And Interaction Surfaces

| Feature | Priority | Why it matters | Source | Implementation direction |
| --- | --- | --- | --- | --- |
| Web console | M1 | Explicit requirement and the best operator surface for approvals, memory, and debugging | Req, IronClaw, ZeroClaw, OpenClaw | Ktor gateway plus browser UI |
| Terminal or CLI interaction | M1 | Essential for development, ops, and local-first usage | IronClaw, ZeroClaw, Hermes | Start with a pragmatic CLI; richer TUI can follow |
| Telegram channel | M1 | Listed in req.md and relatively tractable for an early messaging adapter | Req, ZeroClaw, OpenClaw | Build as a channel adapter over the shared runtime |
| Slack channel | M1 | Listed in req.md and valuable for work contexts | Req, IronClaw, ZeroClaw, OpenClaw | Support pairing, allowlist, streaming fallback |
| Email channel | M1 | Listed in req.md and useful for async workflows | Req, ZeroClaw, Hermes | Start with inbound polling or webhook plus outbound SMTP/API |
| WhatsApp channel | M2 | Listed in req.md but often more operationally complex than Telegram or Slack | Req, OpenClaw, Hermes | Treat as a dedicated adapter with strict pairing and delivery rules |
| Channel pairing and allowlists | M1 | Needed before any exposed messaging surface is safe | OpenClaw, ZeroClaw | Channel adapters enforce pair or allow rules before runtime delivery |
| Draft updates on supported channels | M2 | Improves conversational feel and makes streaming visible | ZeroClaw, OpenClaw | Optional capability on channel adapters |
| Cross-channel conversation continuity | M2 | High-value personal assistant behavior | OpenClaw, Hermes | Map multiple external IDs onto one internal conversation namespace carefully |

## Tools, Skills, And Extensibility

| Feature | Priority | Why it matters | Source | Implementation direction |
| --- | --- | --- | --- | --- |
| File read and write tools | M1 | Explicit requirement and core for agent utility | Req, IronClaw, ZeroClaw | Support markdown, json, yaml, html, and plain text |
| Wiki read and write tools | M1 | Explicit requirement and natural extension of durable workspace docs | Req | Implement as a structured document backend with path rules |
| REST API calling | M1 | Explicit requirement and foundation for many integrations | Req, IronClaw, ZeroClaw | HTTP tool with auth headers, schemas, timeouts, and allowlists |
| Web search | M1 | Explicit requirement and needed for research workflows | Req, ZeroClaw, Hermes | Start with one provider and abstract the result format |
| Git and GitHub tools | M1 | Explicit requirement and important for software workflows | Req, IronClaw, Hermes | Separate local git operations from remote GitHub API operations |
| kubectl and K3s/K8s tools | M2 | Explicit requirement but high-risk without strong approval and sandboxing | Req, Hermes | Read-only inspection first, mutation later behind approvals |
| Cron-ish scheduling tool | M1 | Explicit requirement and necessary for proactive automation | Req, IronClaw, ZeroClaw, Hermes | Scheduler plus a tool to create and manage routines |
| MCP integration | M1 | Best path to adopt external tool ecosystems without reimplementing everything | IronClaw, Hermes, Koog | Treat MCP as another tool transport, not a separate product |
| WASM tool isolation | M2 | Explicit requirement and strong long-term extension mechanism | Req, IronClaw | Start with a sidecar-hosted sandbox boundary if in-process hosting is awkward |
| Skill system | M1 | Required for reusable instruction packs and domain-specific behavior | IronClaw, OpenClaw, Hermes | Markdown-based skill definitions plus tool and requirement metadata |
| New specialized agents | M2 | Explicit requirement for modularity beyond just skills | Req, OpenClaw, Hermes | Reuse runtime core with different prompts, toolsets, and policies |
| Tool capability declarations | M1 | Needed for safe auth, network, and approval decisions | IronClaw | Declarative metadata attached to each tool or tool bundle |

## Memory And Knowledge Features

| Feature | Priority | Why it matters | Source | Implementation direction |
| --- | --- | --- | --- | --- |
| Durable transcript and event log | M1 | Base layer for debugging, search, and summarization | IronClaw, ZeroClaw | Keep structured conversation, tool, and job events in storage |
| Workspace-style durable documents | M1 | Makes memory inspectable and editable by both user and agent | IronClaw, OpenClaw | Support named system docs plus project trees |
| Multi-tier memory architecture | M1 | Explicitly called out as important in req.md | Req, IronClaw, ZeroClaw, Hermes | Separate live context, durable transcript, curated memory, derived summaries, and search projections |
| Hybrid search | M1 | Needed for both exact recall and semantic recall | IronClaw | Combine full-text search with embeddings |
| Scheduled memory consolidation | M1 | Required to keep context usable over time | Req, ZeroClaw, Hermes | Run summarization, fact extraction, and cleanup jobs on a schedule |
| Daily logs and heartbeat notes | M2 | Good operator visibility and proactive behavior | IronClaw | Persist autonomous findings into readable artifacts |
| User profile and preferences memory | M2 | Important for personal-assistant continuity | Hermes, OpenClaw | Separate curated user facts from raw transcript data |
| Conversation insights and summaries | M2 | Helps long-running usage without context blowup | IronClaw, Hermes | Generate on demand and on schedule |
| Shared versus private memory scopes | M3 | Useful if multi-workspace or shared documents appear later | IronClaw | Keep scope model in mind even if MVP stays single-user |

## Security And Isolation Features

| Feature | Priority | Why it matters | Source | Implementation direction |
| --- | --- | --- | --- | --- |
| Secret storage and execution-time injection | M1 | Mandatory for safe tool integration | IronClaw, ZeroClaw | Keep secrets out of prompts and durable plain-text logs |
| Tool approval policy | M1 | Required for risky operations | IronClaw, ZeroClaw, OpenClaw | Autonomy levels plus tool-specific approval hints |
| Channel pairing and unknown-sender protection | M1 | Critical for public messaging surfaces | OpenClaw, ZeroClaw | Default to closed or pairing-based channels |
| Network allowlists for tools | M2 | Reduces blast radius of integrations | IronClaw | Bind allowed domains or APIs to tool metadata |
| Tool execution audit log | M1 | Needed for operator trust and debugging | ZeroClaw, IronClaw | Store structured tool-call records; tamper-evident receipts later |
| WASM or process sandbox | M2 | Needed for untrusted or user-authored extensions | Req, IronClaw, Hermes | Prefer a strict boundary around code execution |
| Prompt-injection and leak checks | M2 | Important once tools and web content expand | IronClaw | Start with output scanning and high-risk source tagging |
| Role or autonomy modes | M2 | Useful when moving from conservative to more autonomous behavior | ZeroClaw | Define ReadOnly, Supervised, and Full styles |

## Persistence, Ops, And Product Features

| Feature | Priority | Why it matters | Source | Implementation direction |
| --- | --- | --- | --- | --- |
| SQLite default persistence | M1 | Good local-first default | Req, ZeroClaw, Hermes | Start with SQLite for single-user installs |
| PostgreSQL option | M2 | Better for always-on hosted deployments | Req, IronClaw, ZeroClaw | Add after the domain model is stable |
| Structured configuration model | M1 | Multi-channel and multi-provider systems need reliable config | ZeroClaw | Keep schema-validated config separate from runtime |
| Guided onboarding | M1 | Setup friction otherwise becomes the product's main failure mode | IronClaw, OpenClaw, Hermes | Wizard for provider, channels, storage, and console |
| Service or daemon mode | M2 | Needed for always-on behavior | IronClaw, ZeroClaw, OpenClaw | Background process with health and restart support |
| Observability | M1 | Needed for debugging tools, channels, and jobs | IronClaw, ZeroClaw | Structured logs, metrics hooks, execution traces |
| Backup and export | M2 | Important for trust and migration | OpenClaw, Hermes | Export memory, config, and skills in readable formats |
| Health checks and diagnostics | M2 | Helps operators validate integrations | OpenClaw, Hermes | Doctor-style checks for channels, secrets, and providers |

## Suggested MVP Slice

If koklyp needs a disciplined first cut, the best M1 package is:

1. JVM runtime with shared agent loop.
2. Web console plus CLI.
3. Telegram, Slack, and email channels.
4. SQLite persistence.
5. File, wiki, REST, web-search, git, and scheduling tools.
6. Skill packs and MCP integration.
7. Multi-tier memory with scheduled consolidation.
8. Approval policy, secret injection, and channel pairing.

## Features To Defer Deliberately

The most expensive areas that should not silently sneak into MVP are:

- full WhatsApp support if it slows the rest of the messaging baseline,
- deep WASM hosting if a process boundary gets koklyp shipping sooner,
- PostgreSQL support before the domain model settles,
- self-repair automation,
- broad subagent hierarchies,
- hardware integrations.