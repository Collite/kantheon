# koklyp Brainstorming Notes

This document is the working discussion sheet for the next round. It captures assumptions, open questions, pushbacks, and improvement ideas rather than pretending every major decision is already settled.

## Working Assumptions

1. koklyp is a personal assistant first, not a team platform first.
2. The first serious implementation should be JVM-based.
3. The product should be useful locally before it is clever in the cloud.
4. Memory management is central, not an add-on feature.
5. Channels, tools, and agent variants should be extensible without changing the core runtime every time.

## Current Pushbacks

### Pushback 1: Native-first is attractive, but probably the wrong first bet

Ktor now supports native servers, and koog is clearly multiplatform in its core story. That still does not make koklyp a good Native-first candidate right now because the product depends heavily on integration surfaces where JVM support is stronger and simpler.

If we force Native-first too early, we risk spending time fighting platform friction instead of delivering the assistant.

### Pushback 2: The initial feature list is already larger than a sensible MVP

Even before adding inspiration from IronClaw, ZeroClaw, OpenClaw, and Hermes, req.md already implies:

- four messaging channels,
- web console,
- scheduling,
- wiki,
- git and GitHub,
- kubectl and K8s,
- memory tiers,
- extensibility with tools and agents,
- WASM isolation.

That is enough for multiple releases. We should explicitly choose what makes the first release coherent.

### Pushback 3: WASM isolation is strategically right, but not necessarily the first extension mechanism to finish

MCP is the fastest path to broad capability. WASM is the better long-term isolation story. We should not force the first release to prove both at full depth if one of them can arrive a phase later.

## Questions To Settle Together

### Product scope

1. Is koklyp strictly single-user, or do we want to leave space for shared channels or family or team usage later?
2. Do we want one assistant with multiple skill packs, or several named specialized agents from the start?
3. Should the assistant be local-first but remotely reachable, or primarily deployed on a VPS or home server?

### Channel priority

1. Which channels are truly MVP-critical: Telegram, Slack, email, WhatsApp, or the web console itself?
2. Is WhatsApp required for the first release, or just required in the target roadmap?
3. Do we want inbound email only, or full outbound workflow automation as well?

### Tool scope

1. Should kubectl start read-only?
2. Is GitHub integration mostly repository automation, issue or PR workflows, or both?
3. Does wiki mean a local markdown wiki, a structured note store, or an external service integration?
4. Is web search acceptable through one provider at first, or do we need provider pluggability immediately?

### Memory

1. What exactly are the memory tiers we want the user to understand?
2. Should the assistant write to MEMORY.md directly, or should curated memory require stricter rules than raw note capture?
3. How aggressive should scheduled summarization be?
4. Do we want user profile memory to be explicit and inspectable at all times?

### Security and autonomy

1. Which operations must always require approval?
2. Are there any tools we do not want the LLM to call directly at all, even in supervised mode?
3. Should unknown senders be blocked everywhere by default, or do we want an open mode for selected channels?

## Proposed MVP

The cleanest MVP I can defend right now is:

1. JVM runtime.
2. Web console and CLI.
3. Telegram, Slack, and email.
4. SQLite persistence.
5. File, wiki, REST, web-search, git, memory, and scheduling tools.
6. MCP integration.
7. Skills plus the ability to define a small number of specialized agents.
8. Multi-tier memory with scheduled maintenance.
9. Strong approvals, pairing, and secret handling.

This is already ambitious, but it is coherent.

## Ideas Worth Stealing Aggressively

### From IronClaw

- workspace-as-memory,
- shared agentic loop for jobs and chat,
- approval pauses as resumable runtime state,
- durable daily notes and heartbeat behavior,
- declarative tool capabilities.

### From ZeroClaw

- explicit provider, channel, and tool boundaries,
- streaming-first lifecycle,
- autonomy-level language,
- gateway treated as a first-class subsystem,
- separate memory subsystem.

### From OpenClaw

- strong onboarding and operator tooling,
- pairing defaults for messaging channels,
- practical local-first product stance,
- multi-agent routing as a future direction.

### From Hermes Agent

- strong emphasis on learned memory and procedural knowledge,
- scheduled automations as a core product feature,
- migration and export mindset,
- subagents and delegated work as a later extension.

## Risks

1. The runtime becomes too clever before the data model is stable.
2. Memory tiers are described vaguely and implemented inconsistently.
3. WhatsApp or WASM consumes too much early schedule.
4. The first architecture overfits to a reference system instead of the actual koklyp goals.
5. PostgreSQL support is added too early and doubles persistence work before the schema settles.

## Research Items Still Worth Checking

1. The best Kotlin-JVM story for secure WASM hosting versus sidecar isolation.
2. The exact koog surface we want to rely on versus wrap behind our own interfaces.
3. A realistic Slack, Telegram, email, and WhatsApp authentication and delivery matrix.
4. The simplest practical full-text plus vector retrieval stack for SQLite first.

## Concrete Discussion Prompts

1. Which three channels do you want in the first real milestone?
2. Do you want WhatsApp in MVP badly enough to delay something else?
3. How visible and editable should memory be to the user?
4. Should koklyp optimize for home-server deployment, desktop-local deployment, or both equally?
5. Do you want skill packs only, or true multi-agent behavior in the first release?