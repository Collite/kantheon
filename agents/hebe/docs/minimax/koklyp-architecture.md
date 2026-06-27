# Koklyp Architecture

## Recommendation: Kotlin Native (with JVM fallback strategy)

**After analyzing IronClaw (Rust) and ZeroClaw (Rust), and considering the Kotlin ecosystem, I recommend Kotlin Native as the primary target, with a carefully considered JVM fallback path.**

---

## Tech Stack

- **Language**: Kotlin 2.3.x
- **Primary Target**: Kotlin Native (with beta support)
- **Fallback**: Kotlin JVM (if native libraries aren't available)
- **Web Framework**: Ktor (supports both Native and JVM)
- **Serialization**: kotlinx.serialization (works on both targets)
- **Agents**: koog (Kotlin agent framework — verify multiplatform support)
- **Database**: SQLite (native-friendly) + PostgreSQL (server deployments)

---

## Why Kotlin Native

### Advantages

1. **Memory Safety** — Kotlin Native uses a custom memory model (not Rust's ownership, but still safe from GC pauses and memory leaks in hot paths)

2. **Coroutines** — Kotlin's structured concurrency works well on Native (worker-based model), enabling the async patterns we need

3. **Ktor Framework** — Full-featured async web framework with Native support (via kotlinx.coroutines core)

4. **Multiplatform** — Share code between JVM server, Native CLI, and potential mobile companion

5. **Ecosystem** — kotlinx.serialization, Koin DI, and other libs have Native support

6. **Existing Libraries** — Wasmer has Kotlin bindings, giving us WASM sandbox support without writing Rust

### Challenges and Mitigations

| Challenge | Mitigation |
|-----------|------------|
| **WASM sandbox** | Use Wasmer Kotlin bindings or write a thin wrapper around the C library |
| **SQLite drivers** | Use `sqlite-kt` or `kotlinx-sql` with native binaries |
| **LLM provider SDKs** | Most offer HTTP APIs — use Ktor client, no native-specific SDK needed |
| **koog agents** | Verify multiplatform support; if not, implement our own agent loop (we have IronClaw/ZeroClaw as reference) |

### Native vs JVM Comparison

| Aspect | Kotlin Native | Kotlin JVM |
|--------|---------------|------------|
| Startup time | **Fast** (<100ms) | Slow (2-5s) |
| Memory footprint | **Small** (~30-50MB) | Large (~200-500MB) |
| WASM sandbox | Via Wasmer bindings | Via pure JVM |
| SQLite | Native bindings | JDBC |
| Threading model | Worker-based | JVM threads |
| GC pauses | Minimal | Possible |
| Deployment | Single binary | JAR + JVM |
| Cold start | **Excellent** | Poor |

**For a CLI-first agent that needs to run continuously as a daemon, startup time matters less than memory footprint and deployment complexity.**

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Koklyp                                      │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                     Channel Layer                              │  │
│  │  ┌─────────┐ ┌──────────┐ ┌─────────┐ ┌────────┐ ┌─────────┐  │  │
│  │  │ Slack   │ │ Telegram │ │ WhatsApp│ │ Email  │ │ Webhook │  │  │
│  │  └─────────┘ └──────────┘ └─────────┘ └────────┘ └─────────┘  │  │
│  │  ┌─────────┐ ┌──────────┐ ┌─────────┐                        │  │
│  │  │ Web UI  │ │ iMessage │ │ Signal  │  ...                   │  │
│  │  └─────────┘ └──────────┘ └─────────┘                        │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│  ┌───────────────────────────▼───────────────────────────────────┐ │
│  │                     Agent Core                                  │ │
│  │                                                                  │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────────────┐  │ │
│  │  │ Session  │  │ Dispatch │  │  Tools   │  │   Memory       │  │ │
│  │  │ Manager  │  │  er      │  │ Registry │  │   (Workspace)  │  │ │
│  │  └──────────┘  └──────────┘  └──────────┘  └─────────────────┘  │ │
│  │                                                                  │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────────────┐  │ │
│  │  │ Scheduler│  │ Skills   │  │ Routines │  │   Safety       │  │ │
│  │  │          │  │ Engine   │  │ Engine   │  │   Layer        │  │ │
│  │  └──────────┘  └──────────┘  └──────────┘  └─────────────────┘  │ │
│  │                                                                  │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                              │                                      │
│  ┌───────────────────────────▼───────────────────────────────────┐ │
│  │                     Tool Execution                              │ │
│  │  ┌─────────┐ ┌──────────┐ ┌─────────┐ ┌────────┐ ┌──────────┐  │ │
│  │  │ Builtin │ │   WASM   │ │   MCP   │ │  Git   │ │  kubectl │  │ │
│  │  │ (file,  │ │ Sandbox  │ │ Servers │ │  Hub   │ │   K8s    │  │ │
│  │  │ shell)  │ │          │ │         │ │        │ │          │  │ │
│  │  └─────────┘ └──────────┘ └─────────┘ └────────┘ └──────────┘  │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                              │                                      │
│  ┌───────────────────────────▼───────────────────────────────────┐ │
│  │                     LLM Provider Layer                         │ │
│  │  ┌─────────┐ ┌──────────┐ ┌─────────┐ ┌────────┐               │ │
│  │  │ OpenAI  │ │Anthropic │ │ Ollama  │ │ Nous   │  ...         │ │
│  │  └─────────┘ └──────────┘ └─────────┘ └────────┘               │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    Persistence Layer                          │   │
│  │  ┌────────────┐ ┌────────────┐ ┌─────────────────────────┐  │   │
│  │  │  SQLite    │ │ PostgreSQL │ │   Workspace (Files)      │  │   │
│  │  │  (local)   │ │  (server)  │ │   MEMORY.md, daily/      │  │   │
│  │  └────────────┘ └────────────┘ └─────────────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Module Structure

```
koklyp/
├── koklyp-core/              # Shared code, interfaces, models
│   ├── src/
│   │   ├── commonMain/      # Pure Kotlin (works on all targets)
│   │   │   ├── api/         # Core traits (Agent, Channel, Tool, etc.)
│   │   │   ├── model/       # Data classes (Message, Session, ToolCall, etc.)
│   │   │   ├── serial/      # kotlinx.serialization models
│   │   │   └── util/        # Shared utilities
│   │   ├── nativeMain/      # Native-specific implementations
│   │   ├── jvmMain/         # JVM-specific implementations
│   │   └── linuxMain/, etc. # Platform-specific
│   └── build.gradle.kts
│
├── koklyp-agent/            # Agent loop implementation
│   ├── src/
│   │   ├── commonMain/
│   │   │   ├── Agent.kt
│   │   │   ├── Dispatcher.kt
│   │   │   ├── SessionManager.kt
│   │   │   ├── Scheduler.kt
│   │   │   └── ContextManager.kt
│   │   └── nativeMain/      # Native coroutine support
│   └── build.gradle.kts
│
├── koklyp-channels/          # Channel integrations
│   ├── src/
│   │   ├── commonMain/
│   │   │   └── Channel.kt   # Channel interface
│   │   ├── jvmMain/
│   │   │   ├── SlackChannel.kt
│   │   │   ├── TelegramChannel.kt
│   │   │   ├── WhatsAppChannel.kt
│   │   │   └── EmailChannel.kt
│   │   └── nativeMain/
│   │       └── WebhookChannel.kt  # Simple webhook works on Native
│   └── build.gradle.kts
│
├── koklyp-tools/            # Tool system
│   ├── src/
│   │   ├── commonMain/
│   │   │   ├── Tool.kt
│   │   │   ├── ToolRegistry.kt
│   │   │   └── ToolDispatcher.kt
│   │   ├── nativeMain/
│   │   │   ├── FileTool.kt
│   │   │   ├── ShellTool.kt
│   │   │   └── BuiltinTools.kt
│   │   └── nativeMain/      # WASM sandbox via Wasmer Kotlin
│   │       └── WasmSandbox.kt
│   └── build.gradle.kts
│
├── koklyp-memory/           # Workspace/memory system
│   ├── src/
│   │   ├── commonMain/
│   │   │   ├── Workspace.kt
│   │   │   ├── HybridSearch.kt
│   │   │   └── MemoryStore.kt
│   │   └── nativeMain/
│   │       └── SqliteMemoryStore.kt
│   └── build.gradle.kts
│
├── koklyp-server/           # Web server (Ktor)
│   ├── src/
│   │   ├── jvmMain/         # Ktor runs best on JVM
│   │   │   ├── Server.kt
│   │   │   ├── WebChannel.kt
│   │   │   ├── ApiRoutes.kt
│   │   │   └── SseManager.kt
│   │   └── nativeMain/      # Limited Ktor Native support
│   └── build.gradle.kts
│
├── koklyp-cli/              # CLI application
│   ├── src/
│   │   ├── jvmMain/         # Rich TUI on JVM (Apache Raton)
│   │   └── nativeMain/      # Simple CLI on Native
│   └── build.gradle.kts
│
└── build.gradle.kts         # Root project
```

---

## Key Design Decisions

### 1. WASM Isolation Strategy

Since native Kotlin doesn't have mature WASM sandbox support like Rust, we have two options:

**Option A: Wasmer Kotlin Bindings**
- Uses the Wasmer C library
- Works on both Native and JVM
- Proven technology (used in production)

**Option B: Process Isolation + JSON-RPC**
- Run tools in separate processes
- Communicate via JSON-RPC
- Simpler but higher overhead

**Recommendation**: Option A with Option B as fallback for tools that can't be WASM-ified.

### 2. Database Strategy

| Use Case | Recommendation | Reason |
|----------|----------------|--------|
| Local/CLI | SQLite | Zero config, native-friendly |
| Server | PostgreSQL | Better concurrency, production-ready |
| Memory | Workspace filesystem | Human-readable, git-friendly |

**Implementation**: Use `kotlinx.sql` for unified API, with dialect switching based on configuration.

### 3. Channel Architecture

Use the same trait-based approach as IronClaw/ZeroClaw:

```kotlin
interface Channel {
    val name: String
    suspend fun send(message: SendMessage): Result<Unit>
    suspend fun listen(handler: (IncomingMessage) -> Unit): Result<Nothing>
    suspend fun healthCheck(): Boolean = true
}
```

Add channels via dependency injection, not hardcoded.

### 4. Tool Execution Pipeline

```
ToolDispatcher.dispatch(toolCall)
    │
    ├──▶ SafetyLayer.validate()
    │       │
    │       ├──▶ Input sanitization
    │       ├──▶ Credential injection
    │       └──▶ Rate limiting check
    │
    ├──▶ ToolExecutor.execute()
    │       │
    │       ├──▶ WASM sandbox (if WASM tool)
    │       ├──▶ Process spawn (if MCP)
    │       └──▶ Direct call (if builtin)
    │
    └──▶ SafetyLayer.sanitizeOutput()
            │
            ├──▶ Secret leak detection
            └──▶ Output sanitization
```

### 5. Skills System

Inspired by ZeroClaw's self-improving skills but simplified for v1:

```kotlin
interface Skill {
    val name: String
    val description: String
    val instructions: String  // SKILL.md content
    val triggerKeywords: List<String>
}

class SkillsEngine(
    private val workspace: Workspace,
    private val llm: LlmProvider
) {
    suspend fun selectSkills(userMessage: String): List<Skill>
    suspend fun createSkillFromExperience(task: Task, result: Result): Skill?
    suspend fun improveSkill(skill: Skill, feedback: Feedback): Skill?
}
```

### 6. Memory Tier Model

| Tier | Storage | TTL | Use Case |
|------|---------|-----|----------|
| **Cache** | In-memory LRU | Minutes | Hot data |
| **Session** | SQLite | Session | Conversation history |
| **Workspace** | Filesystem | Permanent | MEMORY.md, daily logs |
| **Long-term** | Vector DB | Permanent | Semantic search |

### 7. Multi-Agent Support

Similar to OpenClaw's multi-agent routing:

```kotlin
class AgentRouter(
    private val agents: Map<String, Agent>,
    private val rules: List<RoutingRule>
) {
    suspend fun route(message: IncomingMessage): Agent {
        val agentId = rules.match(message.channel, message.sender)
        return agents[agentId] ?: agents["default"]!!
    }
}
```

---

## Deployment Models

### 1. Local CLI Agent

```
┌─────────────────────────┐
│      User's Machine      │
│                          │
│  ┌─────────────────────┐ │
│  │     koklyp-cli      │ │
│  │  (Kotlin Native)    │ │
│  │                     │ │
│  │  - SQLite           │ │
│  │  - Workspace ~       │ │
│  │  - Webhook channel  │ │
│  └─────────────────────┘ │
│                          │
│  Connected to:           │
│  - Telegram bot          │
│  - Slack workspace       │
│  - Email (SMTP)          │
└─────────────────────────┘
```

### 2. Server Deployment

```
┌─────────────────────────────────────────────────────────┐
│                     Server                                │
│                                                          │
│  ┌─────────────────┐    ┌─────────────────────────────┐ │
│  │  koklyp-server  │    │      PostgreSQL             │ │
│  │  (Kotlin JVM)   │    │      - Sessions              │ │
│  │                 │    │      - Messages              │ │
│  │  - Ktor HTTP    │    │      - Jobs                  │ │
│  │  - WebSocket    │    │      - LLM calls             │ │
│  │  - SSE          │    └─────────────────────────────┘ │
│  └────────┬────────┘                                    │
│           │                                               │
│  ┌────────▼────────┐    ┌─────────────────────────────┐ │
│  │  Channel Workers │    │      Workspace (NFS)        │ │
│  │                 │    │      - MEMORY.md            │ │
│  │  - Slack        │    │      - daily/               │ │
│  │  - Telegram     │    │      - skills/              │ │
│  │  - WhatsApp      │    └─────────────────────────────┘ │
│  │  - Email        │                                     │
│  └─────────────────┘                                     │
└─────────────────────────────────────────────────────────┘
```

### 3. Hybrid (Local gateway + Cloud services)

```
┌─────────────────────────┐         ┌─────────────────────────┐
│    User's Machine       │         │      Cloud Services     │
│                         │         │                         │
│  ┌───────────────────┐  │   TLS   │  ┌───────────────────┐  │
│  │   koklyp-gateway  │◄─┼─────────┼─▶│   PostgreSQL       │  │
│  │   (Kotlin JVM)    │  │         │  │   (Turso Cloud)    │  │
│  │                   │  │         │  └───────────────────┘  │
│  │  - Ktor           │  │         │                         │
│  │  - SQLite (local) │  │         │  ┌───────────────────┐  │
│  │  - Workspace      │  │         │  │   Vector DB      │  │
│  └───────────────────┘  │         │  │   (Pinecone/etc)  │  │
│                         │         │  └───────────────────┘  │
│  Local channels:         │         │                         │
│  - iMessage              │         │  Remote channels:       │
│  - Terminal             │         │  - Slack                 │
│                         │         │  - Telegram              │
└─────────────────────────┘         └─────────────────────────┘
```

---

## Non-Functional Requirements

### Performance
- Agent turn latency: <2s for simple tasks
- Memory footprint: <100MB (Native), <300MB (JVM)
- Concurrent channels: 10+ simultaneous

### Security
- All secrets encrypted at rest (AES-256-GCM)
- WASM sandbox with resource limits
- Credential injection (tools never see raw tokens)
- Secret leak detection in tool output
- DM pairing for untrusted channels

### Reliability
- Graceful degradation when LLM unavailable
- Circuit breaker for external services
- Job persistence (resume after restart)
- Self-repair for stuck jobs

### Observability
- Structured logging (JSON)
- SSE/log streaming for debugging
- Tool execution auditing
- Cost tracking per user/provider

---

## Configuration

All configuration via `koklyp.yaml` (or `~/.koklyp/config.yaml`):

```yaml
agent:
  name: "Koklyp"
  autonomy_level: "assistant"  # supervised, assistant, agent, full
  max_tool_iterations: 10
  temperature: 0.7

providers:
  primary:
    type: "openrouter"  # openai, anthropic, ollama, nous, openrouter
    api_key: "${OPENROUTER_API_KEY}"
    model: "anthropic/claude-3.5-sonnet"
  fallback:
    type: "ollama"
    base_url: "http://localhost:11434"
    model: "llama3"

channels:
  slack:
    enabled: true
    bot_token: "${SLACK_BOT_TOKEN}"
  telegram:
    enabled: true
    bot_token: "${TELEGRAM_BOT_TOKEN}"
  email:
    enabled: true
    smtp_host: "smtp.gmail.com"
    smtp_port: 587

database:
  backend: "sqlite"  # sqlite, postgres
  path: "~/.koklyp/koklyp.db"
  # For postgres:
  # host: "localhost"
  # port: 5432
  # database: "koklyp"

workspace:
  root: "~/.koklyp/workspace"
  memory_file: "MEMORY.md"
  identity_file: "IDENTITY.md"

security:
  wasm_enabled: true
  sandbox_policy: "read_only"  # read_only, workspace_write, full
  pairing_required: true

tools:
  git:
    enabled: true
    github_token: "${GITHUB_TOKEN}"
  kubectl:
    enabled: false
    kubeconfig_path: "~/.kube/config"
```

---

## Build & Release

### Kotlin Native Binary

```bash
# Build for current platform
./gradlew :koklyp-cli:linkNative

# Cross-compile for Linux (requires Linux host or Docker)
./gradlew :koklyp-cli:linkNativeRelease -Pltarget=linux_x64

# Output
build/bin/native/releaseExecutable/koklyp.kexe
```

### JVM Server

```bash
./gradlew :koklyp-server:jibDockerBuild
# Produces docker image ready to deploy
```

---

## Future Considerations

1. **Mobile companion app** — Share core code with Kotlin Multiplatform Mobile (KMM)
2. **Plugin marketplace** — Similar to ClawHub/agentskills.io
3. **Team collaboration** — Multi-user support with permission model
4. **TEE integration** — Confidential computing for sensitive workflows