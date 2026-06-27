# IronClaw Architecture Documentation

## Overview

IronClaw is a secure, open-source AI agent framework built in Rust. It is deployed on NEAR AI Cloud and enables creating AI agents with access to tools and services while keeping credentials safe and private.

**Key characteristics:**
- Rust-based with WASM sandbox isolation
- Multi-channel (web, Telegram, terminal, HTTP webhooks)
- Multi-provider LLM support (NEAR AI, Anthropic, OpenAI, Ollama, Tinfoil, Bedrock)
- Extension system with WASM modules
- Docker sandbox isolation for project workspaces
- PostgreSQL + libSQL/Turso dual-backend persistence

---

## High-Level Architecture

```
                                    ┌─────────────────┐
                                    │   LLM Providers │
                                    │ (Anthropic,     │
                                    │  OpenAI, etc.)  │
                                    └────────┬────────┘
                                             │
┌──────────┐    ┌────────────────────────────▼────────────────────────────┐
│ Channels │    │                    IronClaw Core                         │
│          │    │  ┌─────────────────────────────────────────────────────┐ │
│ - Web    │    │  │                   Agent Loop                         │ │
│ - Telegram│   │  │  (dispatcher.rs → agentic_loop.rs → tool execution)  │ │
│ - TUI    │──────▶│                                                     │ │
│ - HTTP   │    │  └───────────────┬─────────────────────────────────────┘ │
│ - WASM   │    │                  │                                       │
└──────────┘    │  ┌───────────────▼─────────────────────────────────────┐ │
                │  │                    Tool System                      │ │
                │  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌───────────┐  │ │
                │  │  │ Builtin │ │  MCP    │ │  WASM   │ │ Dynamic   │  │ │
                │  │  │ (Rust)  │ │ Servers │ │ Sandbox │ │ Builder   │  │ │
                │  │  └─────────┘ └─────────┘ └─────────┘ └───────────┘  │ │
                │  └─────────────────────────────────────────────────────┘ │
                │                                                              │
                │  ┌───────────────┬─────────────────┬─────────────────┐   │
                │  │  Workspace    │    Scheduler     │    Safety       │   │
                │  │  (Memory)     │    (Jobs)        │    Layer        │   │
                │  └───────────────┴─────────────────┴─────────────────┘   │
                │                                                              │
                └────────────────────────────────────────────────────────────┘
                              │                    │                │
                    ┌─────────▼────────┐  ┌───────▼──────┐  ┌──────▼──────┐
                    │   PostgreSQL      │  │  libSQL      │  │   Secrets   │
                    │   (production)   │  │  (Turso)     │  │   (AES-256) │
                    └───────────────────┘  └──────────────┘  └─────────────┘
```

---

## Core Components

### 1. Agent (`src/agent/`)

The agent is the central orchestration engine.

| File | Role |
|------|------|
| `agent_loop.rs` | `Agent` struct, `AgentDeps`, main `run()` event loop. Delegates to siblings. |
| `dispatcher.rs` | Agentic loop for conversational turns: LLM call → tool execution → repeat. Injects skill context. Returns `Response` or `NeedApproval`. |
| `agentic_loop.rs` | **Shared agentic loop engine**: `run_agentic_loop()`, `LoopDelegate` trait, `LoopOutcome`, `LoopSignal`, `TextAction`. All three execution paths (chat, job, container) delegate to this. |
| `session.rs` | Data model: `Session` → `Thread` → `Turn`. State machines for threads and turns. |
| `session_manager.rs` | Lifecycle: create/lookup sessions, map external thread IDs to internal UUIDs, prune stale sessions. |
| `scheduler.rs` | Parallel job scheduling. Maintains `jobs` map and `subtasks` map. |
| `compaction.rs` | Context window management: summarize old turns, write to workspace daily log. |
| `routine_engine.rs` | Cron ticker and event matcher. Fires routines when triggers match. |
| `heartbeat.rs` | Proactive periodic execution. Reads `HEARTBEAT.md`, notifies via channel. |
| `self_repair.rs` | Detects stuck jobs and broken tools, attempts recovery. |

**Session/Thread/Turn Model:**
```
Session (per user)
└── Thread (per conversation — can have many)
    └── Turn (per request/response pair)
        ├── user_input: String
        ├── response: Option<String>
        ├── tool_calls: Vec<ToolCall>
        └── state: TurnState (Pending | Running | Complete | Failed)
```

**Agentic Loop (shared by all execution paths):**
```
run_agentic_loop(delegate, reasoning, reason_ctx, config)
  1. Check signals (stop/cancel) via delegate.check_signals()
  2. Pre-LLM hook via delegate.before_llm_call()
  3. LLM call via delegate.call_llm()
  4. If text response → delegate.handle_text_response() → Continue or Return
  5. If tool calls → delegate.execute_tool_calls() → Continue or Return
  6. Post-iteration hook via delegate.after_iteration()
  7. Repeat until LoopOutcome returned or max_iterations reached
```

Three delegates:
- **`ChatDelegate`** — conversational turns, tool approval, skill context injection
- **`JobDelegate`** — background scheduler jobs, planning support, completion detection
- **`ContainerDelegate`** — Docker container worker, sequential tool exec, HTTP event streaming

### 2. Channels (`src/channels/`)

Multi-channel input abstraction. All channels implement the `Channel` trait.

| Channel | Type |
|---------|------|
| `web/` | Browser UI with SSE/WebSocket streaming, Axum-based |
| `tui.rs` | Terminal UI with Ratatui |
| `http.rs` | HTTP webhook (axum) with secret validation |
| `repl.rs` | Simple REPL for testing |
| `wasm/` | WASM channel runtime for bundled extensions |

**Channel Trait (`channel.rs`):**
```rust
#[async_trait]
pub trait Channel: Send + Sync {
    fn name(&self) -> &str;
    async fn send(&self, message: &SendMessage) -> anyhow::Result<()>;
    async fn listen(&self, tx: tokio::sync::mpsc::Sender<IncomingMessage>) -> anyhow::Result<()>;
    async fn health_check(&self) -> bool { true }
    async fn request_approval(&self, recipient: &str, request: &ChannelApprovalRequest) -> anyhow::Result<Option<ChannelApprovalResponse>>;
}
```

### 3. Tool System (`src/tools/`)

Extensible tool system with multiple execution environments.

| Type | Description | Location |
|------|-------------|-----------|
| Built-in (Rust) | Core tools implemented in Rust | `src/tools/builtin/` |
| WASM | Sandboxed execution with fuel metering, memory limits | `src/tools/wasm/` |
| MCP | Model Context Protocol servers | `src/tools/mcp/` |
| Dynamic | User-built tools compiled and executed | `src/tools/builder/` |

**Key files:**
- `tool.rs` — `Tool` trait definition
- `registry.rs` — `ToolRegistry` for discovery
- `dispatch.rs` — `ToolDispatcher::dispatch()` for unified execution
- `rate_limiter.rs` — Shared sliding-window rate limiter

**WASM Tool Architecture:**
- Full sandbox with wasmtime
- Fuel metering and memory limiting
- Network endpoint allowlisting
- Credential injection (tool code never sees actual tokens)
- Output scanned for secret leakage before returning to LLM

### 4. LLM Module (`src/llm/`)

Multi-provider LLM integration with circuit breaker, retry, failover, and response caching.

**Provider Chain:**
```
Raw provider
  → RetryProvider           (per-provider backoff)
  → SmartRoutingProvider    (cheap/primary split when configured)
  → FailoverProvider        (fallback model)
  → CircuitBreakerProvider  (fast-fail on repeated errors)
  → CachedProvider          (response cache)
  → RecordingLlm            (trace capture for E2E testing)
```

**Supported Providers:**
| Backend | Env Var | Notes |
|---------|---------|-------|
| `nearai` (default) | `NEARAI_SESSION_TOKEN` or `NEARAI_API_KEY` | Dual auth modes |
| `openai` | `OPENAI_API_KEY` | |
| `anthropic` | `ANTHROPIC_API_KEY` | |
| `github_copilot` | `GITHUB_COPILOT_TOKEN` | Two-step OAuth |
| `ollama` | `OLLAMA_BASE_URL` | Local models |
| `openai_compatible` | `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL` | |
| `tinfoil` | `TINFOIL_API_KEY` | TEE inference |
| `bedrock` | `BEDROCK_REGION`, `BEDROCK_MODEL` | AWS SDK |

### 5. Database (`src/db/`)

Dual-backend persistence layer supporting PostgreSQL and libSQL/Turso.

**Sub-traits:**
| Trait | Methods | Covers |
|-------|---------|--------|
| `ConversationStore` | 12 | Conversations, messages |
| `JobStore` | 13 | Agent jobs, actions, LLM calls |
| `SandboxStore` | 13 | Sandbox jobs, job events |
| `RoutineStore` | 15 | Routines, routine runs |
| `ToolFailureStore` | 4 | Self-repair tracking |
| `SettingsStore` | 8 | Per-user key-value settings |
| `WorkspaceStore` | 13 | Memory documents, chunks, hybrid search |

**Key tables:**
- `conversations`, `conversation_messages` — conversation tracking
- `agent_jobs`, `job_actions`, `job_events` — job execution
- `dynamic_tools` — agent-built tools
- `llm_calls` — cost/token tracking
- `memory_documents`, `memory_chunks` — hybrid search (FTS + vector)
- `secrets` — AES-256-GCM encrypted credentials
- `wasm_tools` — installed WASM tool binaries

### 6. Workspace / Memory (`src/workspace/`)

Persistent memory with hybrid search (FTS + vector via RRF).

**Filesystem Structure:**
```
workspace/
├── README.md              ← Root runbook/index
├── MEMORY.md              ← Long-term curated memory
├── HEARTBEAT.md           ← Periodic checklist
├── IDENTITY.md            ← Agent name, nature, vibe
├── SOUL.md                ← Core values
├── AGENTS.md              ← Behavior instructions
├── USER.md                ← User context
├── TOOLS.md               ← Environment-specific tool notes
├── BOOTSTRAP.md           ← First-run ritual (deleted after onboarding)
├── context/               ← Identity-related docs
├── daily/                 ← Daily logs
└── projects/              ← Arbitrary structure
```

**Memory Tools:**
- `memory_search` — Hybrid FTS + vector search (RRF)
- `memory_write` — Write to any path
- `memory_read` — Read any file by path
- `memory_tree` — View workspace structure

**Hybrid Search (RRF):**
```rust
score(d) = Σ 1/(k + rank(d)) for each method where d appears
```
Default k=60. Combines keyword (FTS) and semantic (vector) results.

### 7. Skills System (`src/skills/`)

SKILL.md files extend the agent's prompt with domain-specific instructions.

**Selection Pipeline:**
1. **Gating** — check bin/env/config requirements
2. **Scoring** — keywords/patterns/tags matching
3. **Budget** — fit within `SKILLS_MAX_TOKENS`
4. **Attenuation** — trust-based tool ceiling

**Trust Model:**
- **Trusted** — user-placed in `~/.ironclaw/skills/` or workspace `skills/`, full tool access
- **Installed** — registry, read-only tools

### 8. Safety (`crates/ironclaw_safety/`)

Extracted crate for prompt injection, validation, leak detection, and policy.

**Safety Layer:** Tool results pass through `SafetyLayer` before returning to LLM:
- Sanitizer
- Validator
- Policy enforcement
- Leak detector

### 9. Extensions / Registry (`src/registry/`)

Extension registry catalog with WASM artifact management.

- `ExtensionManifest`, `ArtifactSpec`, `BundleDefinition` types
- `RegistryCatalog` — load from filesystem and embedded JSON
- `RegistryInstaller` — download, verify, install WASM artifacts

### 10. Hooks (`src/hooks/`)

Lifecycle hooks with 6 points:
1. `BeforeInbound` — user message pre-processing
2. `BeforeToolCall` — tool execution pre-validation
3. `BeforeOutbound` — response pre-processing
4. `OnSessionStart` — session initialization
5. `OnSessionEnd` — session cleanup
6. `TransformResponse` — response modification

### 11. Sandbox (`src/sandbox/`)

Docker execution sandbox for project isolation.

- `SandboxConfig`, `SandboxPolicy` (ReadOnly/WorkspaceWrite/FullAccess)
- `SandboxManager` orchestration
- `ContainerRunner`, Docker lifecycle
- Network proxy: domain allowlist, credential injection, CONNECT tunnel

**Engine v2 Per-Project Sandbox:**
When `SANDBOX_ENABLED=true`, filesystem/shell tools for `/project/` paths route through a per-project Docker container. Host directory at `~/.ironclaw/projects/<user_id>/<project_id>/` bind-mounted at `/project/` inside container.

### 12. Tunnel (`src/tunnel/`)

Tunnel abstraction for public internet exposure.

| Provider | Implementation |
|----------|----------------|
| Cloudflare | `cloudflared` binary |
| Ngrok | ngrok binary |
| Tailscale | serve/funnel modes |
| Custom | Arbitrary command with `{host}/{port}` |
| None | Local-only, no exposure |

---

## Logic Flows

### 1. Message Processing Flow

```
User Message (any channel)
    │
    ▼
ChannelManager (merges streams)
    │
    ▼
SubmissionParser (parses commands: /undo, /interrupt, /compact, etc.)
    │
    ▼
Router (routes /commands to MessageIntent; natural language bypasses)
    │
    ▼
Agent Loop (dispatcher.rs → run_agentic_loop)
    │
    ├──▶ LLM call (via LlmProvider chain)
    │       │
    │       ▼
    │   Tool Calls?
    │       │
    │       ├─── Yes ──▶ ToolDispatcher::dispatch()
    │       │                   │
    │       │                   ▼
    │       │           SafetyLayer (sanitize → validate → policy → leak detect)
    │       │                   │
    │       │                   ▼
    │       │           Tool Execution (builtin/WASM/MCP/dynamic)
    │       │                   │
    │       │                   ▼
    │       │           process_tool_result() → back to LLM
    │       │
    │       └─── No ──▶ Response to user (via channel)
    │
    ▼
SessionManager (persist messages, update thread state)
```

### 2. Tool Dispatch Flow (Everything Goes Through Tools)

**Core principle:** All actions from gateway handlers, CLI commands, routine engine, WASM channels, or any other non-agent caller MUST go through `ToolDispatcher::dispatch()`.

This ensures:
- Same audit trail (`ActionRecord`)
- Safety pipeline (param validation, sensitive-param redaction, output sanitization)
- Channel-agnostic surface (new channels inherit full pipeline for free)

### 3. Job Scheduling Flow

```
CreateJob command
    │
    ▼
Scheduler::dispatch_job()  ← Preferred entry point
    │
    ├──▶ Persist to DB (FK references from job_actions/llm_calls valid immediately)
    │
    ▼
Scheduler::schedule()
    │
    ├──▶ Check-insert under write lock (prevents TOCTOU races)
    │
    ▼
Worker spawned with JobDelegate
    │
    ▼
run_agentic_loop() with JobDelegate
    │
    ├──▶ Planning support (use_planning flag)
    │
    └──▶ Completion detection → cleanup task removes from jobs map
```

### 4. Extension Installation Flow

```
User initiates install
    │
    ▼
RegistryCatalog lookup
    │
    ▼
RegistryInstaller::install()
    │
    ├──▶ Download WASM artifact
    │
    ├──▶ Verify checksum
    │
    └──▶ Install to ~/.ironclaw/extensions/
    │
    ▼
ExtensionManager::activate()
    │
    ├──▶ Load WASM module
    │
    ├──▶ Inject channel credentials
    │
    └──▶ Register tools/channels
```

### 5. WASM Tool Execution Flow

```
Tool call from LLM
    │
    ▼
ToolDispatcher::dispatch()
    │
    ▼
WASM runtime lookup
    │
    ▼
Module compilation (cached)
    │
    ▼
Fuel metering + memory limits enforced
    │
    ▼
Host functions called (logging, time, workspace)
    │
    ▼
Network allowlist check
    │
    ▼
Credential injection (from SecretsStore)
    │
    ▼
Tool execution
    │
    ▼
Output scanned for secret leakage
    │
    ▼
Result returned to LLM
```

### 6. Database Dual-Backend Pattern

```
Feature needs persistence
    │
    ▼
Database supertrait (unified interface)
    │
    ├──▶ PostgreSQL implementation (postgres.rs)
    │         │
    │         └──▶ Delegates to Store + Repository in history/
    │
    └──▶ libSQL implementation (libsql/*.rs)
              │
              └──▶ Connection per operation (no pooling)
```

Both backends must be supported for all new persistence features.

---

## Key Design Patterns

### 1. Trait-Driven Extensibility

Key traits for extensibility:
- `Database` — persistence operations
- `Channel` — messaging platform integration
- `Tool` — capability execution
- `LlmProvider` — LLM integration
- `SuccessEvaluator` — task completion evaluation
- `EmbeddingProvider` — vector embeddings
- `NetworkPolicyDecider` — sandbox network policy
- `Hook` — lifecycle callbacks
- `Observer` — event/metric recording
- `Tunnel` — public internet exposure

### 2. Module-Owned Initialization

Module-specific initialization (database connection, transport creation, channel setup) lives in the owning module as a public factory function — not in `main.rs` or `app.rs`. Feature-flag branching confined to the module that owns the abstraction.

### 3. Everything Goes Through Tools

All UI-initiated mutations go through `ToolDispatcher::dispatch()` — never directly through `state.store`, `workspace`, `extension_manager`, `skill_registry`, or `session_manager`.

### 4. Multi-Tier Memory

1. **In-memory** — caches, HashMaps (LRU)
2. **Database** — source of truth for all LLM data
3. **Workspace** — filesystem-based persistent memory

"Cleanup" means evicting from in-memory caches, never deleting database rows.

### 5. Identity Isolation

Identity files (AGENTS.md, SOUL.md, USER.md, IDENTITY.md) read from **primary scope only** — never from secondary scopes in multi-scope configurations.

---

## Security Model

1. **WASM sandbox** — fuel metering, memory limits, network allowlist
2. **Credential injection** — tool code never sees actual tokens
3. **Secret leakage detection** — output scanned before returning to LLM
4. **AES-256-GCM encryption** — secrets stored with OS keychain master key
5. **Docker isolation** — project workspaces in separate containers
6. **Safety layer** — prompt injection validation, output sanitization
7. **DM pairing** — unknown senders require approval before processing

---

## Configuration

All configuration via environment variables. See `.env.example` for complete list.

Key configuration areas:
- `src/config/agent.rs` — agent behavior (max iterations, timeouts)
- `src/config/llm.rs` — LLM provider selection and parameters
- `src/config/channels.rs` — channel-specific settings
- `src/config/database.rs` — PostgreSQL/libSQL selection
- `src/config/sandbox.rs` — Docker sandbox policy
- `src/config/skills.rs` — skill selection and budget
- `src/config/tunnel.rs` — tunnel provider configuration

---

## Current Limitations

1. Domain-specific tools (`marketplace.rs`, `restaurant.rs`) are stubs
2. Integration tests need testcontainers for PostgreSQL
3. MCP: no streaming support; stdio/HTTP/Unix transports all use request-response
4. WIT bindgen: auto-extract tool schema from WASM is stubbed
5. Built tools get empty capabilities; need UX for granting access
6. No tool versioning or rollback
7. Observability: only `log` and `noop` backends (no OpenTelemetry)
8. No native streaming support in LLM providers