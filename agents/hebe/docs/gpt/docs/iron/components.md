# IronClaw Components

## Component Inventory

| Component | Responsibility | Why it matters to koklyp |
| --- | --- | --- |
| `app`, `bootstrap`, `config` | Startup, environment resolution, settings, service wiring | Suggests a strong bootstrap boundary before agent startup |
| `agent/` | Session lifecycle, submission parsing, commands, shared loop, scheduler, compaction | Core runtime reference for koklyp |
| `channels/` | CLI, web, HTTP, WASM channel ingestion and response delivery | Shows how to isolate messaging adapters from the runtime |
| `llm/` | Provider abstraction, retries, failover, caching | Directly relevant for provider-agnostic architecture |
| `tools/` | Built-ins, MCP, WASM tooling, rate limiting, auth declarations | Strong blueprint for tool abstraction and installation |
| `workspace/` | Durable memory, prompt context assembly, hybrid search | High-value pattern for memory tiers |
| `db/` | Feature-wide persistence interfaces and backend implementations | Good example of storage seams, though possibly overbuilt for MVP |
| `sandbox/`, `worker/`, `orchestrator/` | Isolated execution, container lifecycle, internal APIs | Important if koklyp keeps strong isolation from day one |
| `safety/` and `secrets/` | Prompt-injection protection, leak detection, encrypted secrets | Should influence koklyp security baseline |
| `registry/` | Extension catalog and installer | Useful later, probably not MVP |
| `hooks/` | Lifecycle interception | Good extensibility pattern if kept small |
| `setup/` | Guided onboarding | Relevant because multi-channel setup is otherwise painful |
| `tunnel/` | Remote exposure helpers | Useful later for web console deployment |

## Agent Runtime Components

### Session Manager

Maps external conversation identifiers to internal runtime state. This is the bridge between channel-specific IDs and runtime-specific IDs.

Why it matters:

- Makes the runtime independent from specific channel payloads.
- Provides a stable place for cleanup and session pruning.
- Supports multiple channels sharing one logical conversation model.

### Thread Operations

Handles user-input processing, approval resumption, auth interception, compaction triggers, and undo or redo.

Why it matters:

- Concentrates conversation mutation rules in one slice.
- Keeps channel adapters thinner.
- Makes operational commands part of the runtime rather than ad hoc UI behavior.

### Scheduler

Runs full background jobs and lightweight subtasks separately.

Why it matters:

- Cleanly separates interactive responsiveness from autonomous work.
- Makes routines and long-running tasks possible without forking the architecture.

## Tooling Components

### Built-in Tools

Native tools cover file, web, shell, memory, jobs, routines, secrets, and messaging surfaces.

Koklyp lesson: keep a compact built-in set for first release, but design the registry as if third-party tools will exist.

### WASM Tool Runtime

The WASM runtime gives IronClaw a strong isolation story for user-extensible capabilities.

Key design ideas worth preserving:

- capabilities.json as a declarative contract,
- host-side credential injection,
- runtime memory and fuel limits,
- allowlisted outbound network access,
- per-tool rate limiting.

### MCP Support

IronClaw treats MCP as another tool transport rather than a separate feature family.

That is the right abstraction for koklyp too. The user should care about capabilities, not the transport behind them.

## Memory and Persistence Components

### Workspace

The workspace is both operator-facing and agent-facing memory. That dual role is a strength.

Operator-facing value:

- the user can inspect durable memory in plain files,
- the system can leave traces like daily notes and heartbeat findings,
- the same structure can hold runbooks and instructions.

Agent-facing value:

- hybrid retrieval,
- explicit system prompt sources,
- consistent write paths for reflection and summaries.

### Database Layer

The database layer centralizes persistence contracts across conversations, jobs, routines, settings, and workspace documents.

The main lesson is not dual-backend support itself. The lesson is that memory, jobs, and event logs were treated as first-class persistence concerns early.

## Security Components

### Safety Layer

Runs validation, sanitization, policy checks, and leak detection around tool results and sensitive content paths.

For koklyp, this suggests a reusable security service rather than scattered validation helpers.

### Secrets Store

Secrets are persisted separately and injected during execution rather than pasted into prompts.

This is a non-negotiable pattern to keep.

### Sandbox Stack

IronClaw uses both a WASM sandbox and a Docker-based execution sandbox. The exact mix may change in koklyp, but the layered model is right:

- lightweight isolation for many extensible tools,
- stronger process or container isolation for riskier code execution.

## Operations Components

### Onboarding

IronClaw acknowledges that a multi-provider, multi-channel assistant is unusable without guided setup. That is a product lesson as much as a technical one.

### Heartbeat And Routines

These components make the system proactive. They are not mere convenience features; they are the operational counterpart to durable memory.

### Self-Repair

Self-repair is powerful but costly. It should influence koklyp's long-term architecture, but it should probably be deferred beyond MVP.

## Recommended Carry-Over Priority

Carry over in the first koklyp architecture:

- agent runtime separation,
- tool abstraction,
- workspace memory,
- security service and approvals,
- scheduler and routines,
- onboarding.

Carry over later if needed:

- registry installer,
- tunnel providers,
- self-repair automation,
- full dual-backend parity.