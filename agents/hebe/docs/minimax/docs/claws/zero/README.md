# ZeroClaw Architecture Documentation

## Overview

ZeroClaw is a Rust-first autonomous agent runtime optimized for performance, efficiency, stability, extensibility, sustainability, and security. It is the successor/reference implementation for what became IronClaw.

**Key characteristics:**
- Rust-first with microkernel architecture (16 crates)
- Trait-driven modular design (Provider, Channel, Tool, Memory, Observer, Peripheral, RuntimeAdapter)
- WASM plugin system for extensions
- 30+ messaging channel integrations
- Multi-tier memory with time decay
- Sophisticated security model (OS-level sandboxes, pairing guards, secret stores)

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         ZeroClaw Runtime                            │
│                                                                      │
│  ┌─────────────┐  ┌──────────────────────────────────────────────┐  │
│  │   CLI /     │  │              Agent Core                       │  │
│  │  Gateway    │  │                                              │  │
│  └──────┬──────┘  │  ┌──────────┐ ┌──────────┐ ┌──────────────┐  │  │
│         │         │  │  Agent   │ │  Loop    │ │  Dispatcher  │  │  │
│         │         │  │ (turn)   │ │ (loop_)  │ │ (tool exec)  │  │  │
│         │         │  └──────────┘ └──────────┘ └──────────────┘  │  │
│         │         │                                              │  │
│         │         │  ┌──────────┐ ┌──────────┐ ┌──────────────┐  │  │
│         │         │  │ Skills   │ │ Routines │ │   Hooks      │  │  │
│         │         │  │ (create/ │ │ (cron/   │ │ (lifecycle)  │  │  │
│         │         │  │ improve) │ │  events) │ │              │  │  │
│         │         │  └──────────┘ └──────────┘ └──────────────┘  │  │
│         │         └──────────────────────────────────────────────┘  │
│         │                        │                                  │
│  ┌──────▼──────────────────────────────────────────────────────────┐ │
│  │                     Tool System                                 │ │
│  │  ┌─────────┐ ┌──────────┐ ┌─────────┐ ┌────────┐ ┌──────────┐ │ │
│  │  │ Builtin  │ │   MCP    │ │   WASM  │ │ Skill  │ │ Dynamic  │ │ │
│  │  │ (shell, │ │ Servers  │ │ Plugins │ │ Tools  │ │ Composio │ │ │
│  │  │ file,    │ │          │ │          │ │        │ │          │ │ │
│  │  │ memory)  │ │          │ │          │ │        │ │          │ │ │
│  │  └─────────┘ └──────────┘ └─────────┘ └────────┘ └──────────┘ │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    Security Layer                            │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌────────┐ ┌──────────┐  │   │
│  │  │ Sandbox │ │ Pairing │ │ Secrets │ │ Prompt │ │ Leak     │  │   │
│  │  │ (Docker,│ │ Guard   │ │ Store   │ │ Guard  │ │ Detector │  │   │
│  │  │ Firejail│ │         │ │         │ │        │ │          │  │   │
│  │  │ Bubble) │ │         │ │         │ │        │ │          │  │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └────────┘ └──────────┘  │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    Memory / Storage                           │   │
│  │  ┌────────────┐ ┌────────────┐ ┌─────────────────────────┐  │   │
│  │  │  Memory    │ │  Response  │ │   SQLite / PostgreSQL    │  │   │
│  │  │  (markdown,│ │  Cache     │ │   (zeroclaw-memory)      │  │   │
│  │  │  embeddings│ │            │ │                         │  │   │
│  │  └────────────┘ └────────────┘ └─────────────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
         │                        │
         ▼                        ▼
┌─────────────────┐      ┌─────────────────────┐
│  Providers      │      │  Channels          │
│  (LLM APIs)     │      │  (30+ platforms)   │
│                 │      │                     │
│ - OpenRouter    │      │ - Slack, Discord    │
│ - OpenAI        │      │ - Telegram, Signal  │
│ - Anthropic    │      │ - WhatsApp, iMessage│
│ - Ollama        │      │ - Matrix, IRC       │
│ - NVIDIA NIM    │      │ - ...               │
│ - Nous Portal   │      │                     │
└─────────────────┘      └─────────────────────┘
```

---

## Crate Structure (Microkernel Architecture)

| Crate | Stability | Description |
|-------|-----------|-------------|
| `zeroclaw-api` | Experimental | **Public trait definitions**: Provider, Channel, Tool, Memory, Observer, Peripheral, RuntimeAdapter |
| `zeroclaw-config` | Beta | Schema, config loading/merging with configurable derive macro |
| `zeroclaw-providers` | Beta | Model providers and resilient wrapper with multi-provider routing |
| `zeroclaw-memory` | Beta | Memory backends (markdown, sqlite, embeddings, vector merge, response cache) |
| `zeroclaw-infra` | Beta | Shared infrastructure (debounce, session, stall watchdog) |
| `zeroclaw-tool-call-parser` | Beta | Tool call parsing (native + XML/markdown fallback) |
| `zeroclaw-channels` | Experimental | Messaging platform integrations (30+ channels) |
| `zeroclaw-tools` | Experimental | Tool execution surface (shell, file, memory, browser) |
| `zeroclaw-runtime` | Experimental | **Agent loop, security, cron, SOP, skills, onboarding, observability** |
| `zeroclaw-gateway` | Experimental | Webhook/gateway server (separate binary) |
| `zeroclaw-tui` | Experimental | TUI onboarding wizard |
| `zeroclaw-plugins` | Experimental | **WASM plugin system** — foundation for v1.0.0 plugin ecosystem |
| `zeroclaw-hardware` | Experimental | USB discovery, peripherals, serial, GPIO |
| `zeroclaw-macros` | Beta | Configurable derive macro (tightly coupled to config schema) |

**Stability tiers:**
- **Stable** — covered by breaking-change policy
- **Beta** — breaking changes permitted in MINOR with changelog notes
- **Experimental** — no stability guarantee

Tiers are promoted, never demoted, through deliberate team decision.

---

## Core Components

### 1. Agent (`zeroclaw-runtime/src/agent/`)

The agent core handles conversation turns, tool execution, context management, and model routing.

| File | Role |
|------|------|
| `agent.rs` | `Agent` struct with builder pattern, `turn()` and `turn_streamed()` methods |
| `loop_.rs` | Core `run_tool_call_loop()` — the shared agentic iteration engine |
| `dispatcher.rs` | `ToolDispatcher` trait + `NativeToolDispatcher` / `XmlToolDispatcher` implementations |
| `classifier.rs` | Query classification for model routing by hint |
| `context_analyzer.rs` | Context analysis for memory loading |
| `context_compressor.rs` | Context window management |
| `cost.rs` | Budget enforcement and cost tracking |
| `eval.rs` | Auto-classification of query complexity |
| `history.rs` | History management, trimming, session persistence |
| `history_pruner.rs` | Proactive history pruning before context overflow |
| `loop_detector.rs` | Detects runaway tool-call loops |
| `memory_loader.rs` | Loads relevant memories into context |
| `personality.rs` | Personality/tone configuration |
| `prompt.rs` | System prompt building |
| `system_prompt.rs` | System prompt templates |
| `thinking.rs` | Thinking/thorn reasoning support |
| `tool_execution.rs` | Tool execution helpers (parallel/sequential) |
| `tool_receipts.rs` | Tool execution receipts for audit |

**Agent Builder Pattern:**
```rust
Agent::builder()
    .provider(provider)
    .tools(tools)
    .memory(memory)
    .observer(observer)
    .tool_dispatcher(tool_dispatcher)
    .config(config)
    .model_name(model_name)
    .temperature(0.7)
    .workspace_dir(path)
    .identity_config(identity)
    .skills(skills)
    .security_summary(summary)
    .autonomy_level(level)
    .build()?
```

**Turn Flow:**
```
turn(user_message)
  → build_system_prompt() if history empty
  → memory_loader.load_context() — pull relevant memories
  → auto_save to memory (if enabled)
  → enrich message with date/time + context
  → loop (max_tool_iterations):
      → tool_dispatcher.to_provider_messages() — prepare history
      → response_cache.check() — cache hit?
      → provider.chat() — LLM call
      → tool_dispatcher.parse_response() — extract text + tool calls
      → if no tool calls → return final text
      → execute_tools() — parallel or sequential
      → tool_dispatcher.format_results() — format for history
      → trim_history() — keep within max limit
  → if max iterations exceeded → error
```

**Streaming (`turn_streamed`):**
- Uses `provider.stream_chat()` for streaming responses
- Forwards `TurnEvent` through a channel (Thinking, Chunk, ToolCall, ToolResult)
- Cancellation support via `CancellationToken`
- Falls back to non-streaming on provider stream failure

### 2. Tool System (`zeroclaw-runtime/src/tools/`)

Extensible tool system with multiple execution backends.

**ToolDispatcher Trait:**
```rust
pub trait ToolDispatcher: Send + Sync {
    fn prompt_instructions(&self, tools: &[Box<dyn Tool>]) -> String;
    fn to_provider_messages(&self, history: &[ConversationMessage]) -> Vec<ChatMessage>;
    fn should_send_tool_specs(&self) -> bool;
    fn parse_response(&self, response: &ChatResponse) -> (String, Vec<ParsedToolCall>);
    fn format_results(&self, results: &[ToolExecutionResult]) -> ConversationMessage;
}
```

**Implementations:**
- **`NativeToolDispatcher`** — for providers with native function calling (OpenAI, Anthropic)
- **`XmlToolDispatcher`** — for providers requiring XML/markdown tool call parsing

**Tool Filtering:**
- Built-in tools always pass through
- MCP tools filtered by `tool_filter_groups`:
  - `Always` mode: included unconditionally
  - `Dynamic` mode: included only if user message contains keywords

**Tool Execution:**
```rust
async fn execute_tools(&self, calls: &[ParsedToolCall]) -> Vec<ToolExecutionResult> {
    if self.config.parallel_tools {
        futures_util::future::join_all(calls.iter().map(|call| self.execute_tool_call(call))).await
    } else {
        // Sequential
        let mut results = Vec::new();
        for call in calls { results.push(self.execute_tool_call(call).await); }
        results
    }
}
```

**Hook System:**
- `before_tool_call` hook — can modify tool name/args, cancel execution
- `after_tool_call` hook — fire-and-forget side effects (auditing, logging)

### 3. Memory System (`zeroclaw-memory/`)

Multi-tier memory with time decay, FTS, and embeddings.

**Memory Trait:**
```rust
pub trait Memory: Send + Sync {
    async fn store(&self, key: &str, content: &str, category: MemoryCategory, session_id: Option<&str>) -> anyhow::Result<()>;
    async fn recall(&self, query: &str, limit: usize, session_id: Option<&str>, min_score: Option<f64>, category: Option<MemoryCategory>) -> anyhow::Result<Vec<MemoryEntry>>;
    // ... many more methods
}
```

**Categories:**
- `Conversation` — per-turn messages
- `Core` — evergreen memories (no time decay)
- `Daily` — daily log entries
- `UserPreference` — user preferences

**Time Decay:**
```rust
decay::apply_time_decay(&mut entries, decay::DEFAULT_HALF_LIFE_DAYS);
// Older non-Core memories score lower over time
```

**Response Cache:**
- SHA-256 cache key: (model, system_prompt, last_user_message)
- TTL-based expiry with LRU eviction
- Only cached when `temperature == 0.0` (deterministic)

**Hybrid Search:**
- FTS5 for keyword search (SQLite)
- Vector embeddings for semantic similarity
- Reciprocal Rank Fusion (RRF) for combined results

### 4. Skills System (`zeroclaw-runtime/src/skills/`)

Skills are self-improving procedural memories. The system can create new skills from experience.

| File | Role |
|------|------|
| `mod.rs` | Skill loading and management |
| `creator.rs` | Creates new skills from agent experience |
| `improver.rs` | Improves existing skills during use |
| `audit.rs` | Skill execution auditing |
| `skill_tool.rs` | Tool wrapper for skill execution |
| `skill_http.rs` | HTTP-based skill registry (agentskills.io) |

**Skill Creation Flow:**
1. Agent completes complex task
2. Creator analyzes the interaction
3. Generates SKILL.md with instructions + examples
4. Skill is registered and available for future use

**Skill Improvement:**
- Monitors skill effectiveness
- Suggests/applies improvements based on usage patterns

### 5. Security System (`zeroclaw-runtime/src/security/`)

Comprehensive security with multiple defense layers.

**Autonomy Levels:**
```rust
pub enum AutonomyLevel {
    Supervised,     // All tool calls require approval
    Assistant,      // Read-only tools auto-approved
    Agent,          // Most tools auto-approved, sensitive require approval
    FullControl,    // All tools auto-approved
}
```

**SecurityPolicy:**
- Enforces autonomy level
- Defines workspace boundaries
- Access control rules per tool/category

**Sandbox Backends (via `Sandbox` trait):**
| Backend | Feature flag | Description |
|---------|-------------|-------------|
| Docker | default | Docker container isolation |
| Firejail | `sandbox-firejail` | Linux firejail sandbox |
| Bubblewrap | `sandbox-bubblewrap` | Linux bubblewrap sandbox |
| Landlock | `sandbox-landlock` | Linux Landlock sandbox |
| Seatbelt | (macOS) | macOS seatbelt sandbox |

**PairingGuard:**
- Device/channel pairing for authentication
- Code-based pairing flow for new devices

**SecretStore:**
- Encrypted credential storage
- AES encryption with OS keychain integration

**LeakDetector:**
- Scans tool output for credential patterns
- Regex-based sensitive data detection
- Redacts found secrets

**PromptGuard:**
- Detects prompt injection attacks
- Analysis of user input for malicious patterns

### 6. Routines System (`zeroclaw-runtime/src/routines/`)

Scheduled and event-driven automation.

**Trigger Types:**
- Cron-based scheduling
- Event-based triggers
- System events
- Manual triggers

**RoutineEngine:**
```rust
pub struct RoutineEngine { /* ... */ }
impl RoutineEngine {
    pub async fn tick(&self) { /* check triggers */ }
    pub async fn trigger(&self, routine: &Routine) { /* execute */ }
}
```

**Event Matcher:**
- Matches events against routine triggers
- Supports regex and pattern matching

### 7. Providers (`zeroclaw-providers/`)

Multi-provider LLM integration with fallback and routing.

**Provider Trait:**
```rust
#[async_trait]
pub trait Provider: Send + Sync {
    async fn chat(&self, request: ChatRequest<'_>, model: &str, temperature: Option<f64>) -> anyhow::Result<ChatResponse>;
    fn stream_chat(&self, request: ChatRequest<'_>, model: &str, temperature: Option<f64>, options: StreamOptions) -> BoxStream<'static, StreamResult<StreamEvent>>;
    fn supports_native_tools(&self) -> bool;
    fn supports_vision(&self) -> bool;
    // ... many more methods
}
```

**Provider Capabilities:**
```rust
pub struct ProviderCapabilities {
    pub native_tool_calling: bool,
    pub vision: bool,
    pub prompt_caching: bool,
}
```

**Routing:**
- Model routing by hint (query classification)
- Fallback provider chain
- Per-provider retry/cooldown

**Supported Providers:**
- OpenRouter (200+ models)
- OpenAI
- Anthropic
- Ollama (local)
- NVIDIA NIM (Nemotron)
- Nous Portal
- Xiaomi MiMo
- Kimi/Moonshot
- Hugging Face
- Any OpenAI-compatible endpoint

### 8. Channels (`zeroclaw-channels/`)

30+ messaging platform integrations.

**Channel Trait:**
```rust
#[async_trait]
pub trait Channel: Send + Sync {
    fn name(&self) -> &str;
    async fn send(&self, message: &SendMessage) -> anyhow::Result<()>;
    async fn listen(&self, tx: tokio::sync::mpsc::Sender<ChannelMessage>) -> anyhow::Result<()>;
    async fn health_check(&self) -> bool { true }
    async fn request_approval(&self, recipient: &str, request: &ChannelApprovalRequest) -> anyhow::Result<Option<ChannelApprovalResponse>>;
}
```

**Supported Channels:**
- Slack, Discord, Telegram, WhatsApp, Signal
- iMessage, Matrix, IRC, Microsoft Teams
- Mattermost, Nextcloud Talk, Nostr
- Feishu, LINE, Synology Chat, Tlon
- Twitch, Zalo, WeChat, QQ, WebChat

**Channel Orchestrator:**
- Manages lifecycle of all channel instances
- Routes messages to appropriate agents
- Media pipeline for attachments (audio, images, video)

### 9. Gateway (`zeroclaw-gateway/`)

HTTP webhook/gateway server (separate binary from main runtime).

**Endpoints:**
- Webhook handlers for each channel
- REST API for agent management
- SSE/WebSocket for real-time streaming

### 10. Observability (`zeroclaw-runtime/src/observability/`)

Pluggable event/metric recording.

**Observer Trait:**
```rust
pub trait Observer: Send + Sync {
    fn record_event(&self, event: &ObserverEvent);
}
```

**ObserverEvent:**
- `LlmRequest`, `LlmResponse`
- `ToolCall`
- `CacheHit`, `CacheMiss`
- Custom events

**Backends:**
- `noop` — no-op implementation
- `log` — logging backend
- `multi` — fan-out to multiple backends

---

## Logic Flows

### 1. Agent Turn Flow (with streaming)

```
User message
    │
    ▼
Agent::turn_streamed()
    │
    ├──▶ System prompt (if history empty)
    │
    ├──▶ Memory context load (memory_loader)
    │
    ├──▶ Auto-save to memory (if enabled)
    │
    ├──▶ Date/time enrichment
    │
    ▼
Loop (max_tool_iterations):
    │
    ├──▶ Context trim check (preemptive)
    │
    ├──▶ Tool specs build (filter by excluded)
    │
    ├──▶ Provider selection (vision routing if images)
    │
    ├──▶ on_delta: send "Thinking..." status
    │
    ├──▶ Hook: fire_llm_input (void)
    │
    ├──▶ Budget check
    │
    ├──▶ Streaming LLM call
    │       │
    │       ├──▶ TextDelta → forwarded as StreamDelta::Text
    │       │
    │       └──▶ ToolCall → collected
    │
    ├──▶ Parse response (native + fallback XML/markdown)
    │
    ├──▶ Hook: after_tool_call (void)
    │
    ├──▶ if no tool calls:
    │       │
    │       ├──▶ Store in response cache
    │       │
    │       ├──▶ Push to history
    │       │
    │       └──▶ Return final text
    │
    └──▶ Execute tools:
            │
            ├──▶ before_tool_call hook
            │
            ├──▶ Tool execution (parallel/sequential)
            │
            ├──▶ after_tool_call hook
            │
            └──▶ Format results → push to history
```

### 2. Tool Execution with Hooks

```
ToolDispatcher calls execute_tool_call()
    │
    ▼
Hook: before_tool_call (modifying)
    │ Can modify tool_name, tool_args
    │ Can cancel with reason
    │
    ▼
Tool found in registry?
    │
    ├──▶ Yes → tool.execute(args)
    │
    └──▶ No → Check activated_tools (MCP deferred)
              │
              └──▶ Found → tool.execute(args)
                  Not found → Unknown tool error
    │
    ▼
Record ObserverEvent::ToolCall
    │
    ▼
Hook: after_tool_call (void)
    │
    ▼
Return ToolExecutionResult
```

### 3. Memory Recall with Time Decay

```
Agent calls memory.recall(query, limit, session_id, min_score, category)
    │
    ▼
Storage backend recall (FTS + vector)
    │
    ▼
Apply time decay to non-Core entries
    │
    ▼
Filter by min_relevance_score
    │
    ▼
Skip autosave keys and tool_result blocks
    │
    ▼
Return MemoryEntry vec sorted by score
```

### 4. Skill Creation Flow

```
Agent completes complex task successfully
    │
    ▼
skills::creator::analyze_interaction()
    │
    ▼
Generate SKILL.md with:
    - Task description
    - Trigger conditions
    - Action steps
    - Examples
    - Edge cases
    │
    ▼
Register skill in skills registry
    │
    ▼
Skill available for future use
```

### 5. Model Routing Flow

```
User message
    │
    ▼
classifier::classify_with_decision(config, message)
    │
    ├──▶ Match against query classification rules
    │
    ├──▶ Check if hint is in available_hints
    │
    └──▶ Return Decision { hint, priority }
    │
    ▼
Resolve model from route_model_by_hint
    │
    ▼
effective_model = "hint:{hint}"
    │
    ▼
Used in provider.chat() instead of default model
```

---

## Key Design Patterns

### 1. Microkernel Architecture

Core functionality in `zeroclaw-runtime`. Extensions via WASM plugins in `zeroclaw-plugins`. Interface stability through `zeroclaw-api` traits.

### 2. Builder Pattern

`Agent::builder()` with fluent API for dependency injection. All dependencies optional with sensible defaults.

### 3. Trait-Based Extensibility

Key extension points:
- `Provider` — add new LLM backends
- `Channel` — add new messaging platforms
- `Tool` — add new capabilities
- `Memory` — add new storage backends
- `Observer` — add new observability backends
- `Sandbox` — add new isolation backends

### 4. Deferred MCP Loading

When `mcp.deferred_loading = true`:
1. Only `tool_search` tool is registered initially
2. User triggers tool_search with query
3. Relevant MCP tools are activated and cached
4. Activated tools available for this session

### 5. Shared Agentic Loop

`run_tool_call_loop()` is the single shared implementation used by:
- CLI interactive mode
- WebSocket gateway
- Daemon mode
- Any channel integration

### 6. Tool Filtering

MCP tools can be dynamically filtered per-turn:
```rust
filter_tool_specs_for_turn(tool_specs, groups, user_message)
  // Always: built-in tools pass through
  // Dynamic: only if message contains keywords
```

### 7. Response Cache

Cache key is deterministic — only when temperature is 0:
```rust
ResponseCache::cache_key(model, system_prompt, last_user_message)
```

### 8. Time-Decay Memory

Non-Core memories decay over time:
```rust
score = score * (0.5 ^ (age_days / HALF_LIFE_DAYS))
```

---

## Configuration

ZeroClaw uses a single `Config` struct (from `zeroclaw-config`) with all settings.

Key configuration areas:
- `providers` — LLM provider selection, API keys, model routing
- `agents` — agent behavior, tool settings
- `memory` — storage, embedding, cache settings
- `autonomy` — security policy, autonomy level
- `workspace_dir` — workspace root path
- `mcp` — MCP server configuration
- `hooks` — hook enablement (command_logger, webhook_audit)
- `browser`, `http_request`, `web_fetch` — tool-specific settings
- `runtime` — terminal backend selection

---

## Inspirations from OpenClaw & Hermes

### From OpenClaw:
- Multi-channel inbox (30+ platforms)
- DM pairing/security model
- Skills system with marketplace (ClawHub → agentskills.io)
- Workspace filesystem structure (MEMORY.md, IDENTITY.md, SOUL.md, AGENTS.md)
- TUI onboarding wizard

### From Hermes:
- Self-improving skills (creation + improvement during use)
- Time-decay memory
- Query classification for model routing
- Tool receipt system
- Loop detection for runaway tool calls
- Preemptive history pruning before context overflow

---

## Key Differences from IronClaw

| Aspect | ZeroClaw | IronClaw |
|--------|----------|----------|
| Language | Rust | Rust |
| Architecture | Microkernel (16 crates) | Monolithic |
| Stability tiers | Yes (Stable/Beta/Experimental) | No |
| Channel count | 30+ | ~6 (web, telegram, TUI, http, repl, wasm) |
| Skills | Self-improving (create + improve) | Static SKILL.md files |
| Memory | Time decay, RRF hybrid search | FTS + vector (RRF) |
| Model routing | Query classification + hints | SmartRoutingProvider |
| Tool filtering | Per-turn MCP dynamic filtering | Static allowlists |
| Streaming | Native provider streaming | Not supported |
| Sandbox | Multiple backends (Docker, Firejail, Bubble, Landlock) | Docker only |
| WASM plugins | First-class (zeroclaw-plugins) | Extensions via registry |
| Hardware | Peripherals support (STM32, RPi GPIO) | Not supported |
| Config | Centralized Config struct | Scattered env vars |