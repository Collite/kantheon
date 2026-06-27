# koklyp — architecture

A self-hosted Kotlin/JVM autonomous agent inspired by IronClaw and ZeroClaw, scoped for small-team use. This document is the architectural blueprint we'll iterate on.

## TL;DR

- **Target: JVM**, single fat-JAR (`koklyp.jar`). Native-image is a v2 optimization. See `docs/claws/stack-notes.md` for the reasoning.
- **Kernel ABI: five traits** — `LlmProvider`, `Channel`, `Tool`, `MemoryStore`, `Observer`. Everything else hangs off these.
- **Agent core: koog** (JetBrains) wrapped behind a koklyp-native facade.
- **Plugins: Extism** on top of Chicory. WASI Preview 1 + JSON-string protocol. Not WIT/component-model — that's not viable on JVM yet.
- **Memory: SQLite** (FTS5 + sqlite-vec) with markdown workspace. Postgres pluggable.
- **Channels: Slack, Telegram, WhatsApp, email, web console, CLI** in v1. Each behind the `Channel` trait so platform changes affect one file.
- **Security: autonomy levels + workspace boundary + tool receipts (Ed25519, chained) + leak detector + approval gate**. Subprocess-based OS sandboxing in v2.
- **MCP: first-class.** Both as server (expose koklyp tools) and client (consume external MCP).
- **One mutation funnel** (`ToolDispatcher.dispatch`) lint-enforced from day one.

## 1. Native vs JVM (the architectural question)

### Decision: JVM

The brief asked us to consider Kotlin/Native if libraries permit. **They do not.** Two independent constraints force JVM:

1. **WASM host runtime.** The user said "Wasmtime/WASM is a hard requirement." On JVM, the mature options are Chicory (pure-Java interpreter, WASI Preview 1) and Wasmtime via JNI. On Kotlin/Native, neither has a maintained binding in 2026. Without a WASM host, the plugin sandbox story collapses.

2. **Koog targets JVM, JS, WasmJS, Android, iOS — not Kotlin/Native.** The user wants koog. Koog is JVM-first, and its multiplatform targets do not include `Native`.

Even if these two were resolved, the rest of the surface (Ktor server, Slack/Telegram SDKs, Jakarta Mail, Postgres JDBC) is JVM-native. The cost of a Native port would dwarf the benefit.

### What about GraalVM native-image?

A separate question — JVM is the *language target*; native-image is the *distribution format*. Native-image gives you a single binary (~20MB vs a 100MB JVM bundle), faster cold-start, lower memory.

**Verdict for v1: ship a JAR.** Add native-image as a v2+ optimization. Reflection-config work is not free, and our stack (Ktor + koog + Chicory + JDBC) all work but require effort. For a self-hosted small-team setup, the JAR-vs-native distinction is invisible.

## 2. Layered architecture

```
┌───────────────────────────────────────────────────────────────────────┐
│ L7  Edges      CLI · Web (Ktor) · Slack · Telegram · WhatsApp · Email │
│                MCP server (stdio + HTTP)                               │
├───────────────────────────────────────────────────────────────────────┤
│ L6  Inbound    Channel adapters · pair/allowlist · dedup               │
│                Submission parser (sealed type) → Router                │
├───────────────────────────────────────────────────────────────────────┤
│ L5  Triggers   Heartbeat · Routines (cron) · SOP engine [v2]          │
│                ChannelManager + injectTx for background producers      │
├───────────────────────────────────────────────────────────────────────┤
│ L4  Agent      Skill selector → context build → koog agent loop        │
│                Hooks · cost guard · loop detector · compaction         │
├───────────────────────────────────────────────────────────────────────┤
│ L3  Security   AutonomyLevel · workspace boundary · command policy    │
│                domain matcher · prompt guard · leak detector           │
│                approval gate · tool receipts (Ed25519, chained)       │
├───────────────────────────────────────────────────────────────────────┤
│ L2  Tools      ToolDispatcher · built-ins · MCP client · plugins       │
│                (Extism on Chicory) · subprocess sandbox [v2]          │
├───────────────────────────────────────────────────────────────────────┤
│ L1  Providers  LlmProvider trait · streaming · router · fallback [v2] │
│                Anthropic · OpenAI · Ollama-compat                      │
├───────────────────────────────────────────────────────────────────────┤
│ L0  Storage    MemoryStore · SQLite (FTS5 + vec) · markdown workspace │
│                secrets (AES-GCM, OS keychain) · settings · receipts   │
└───────────────────────────────────────────────────────────────────────┘
```

## 3. Module layout

A multi-module Gradle build. Strict acyclic deps.

```
koklyp/
├── settings.gradle.kts
├── build.gradle.kts                        ← root: versions, common conventions
├── modules/
│   ├── api/                                ← kernel ABI; no dependencies
│   │   ├── LlmProvider.kt, Channel.kt, Tool.kt, MemoryStore.kt, Observer.kt
│   │   └── (StreamEvent, ToolSpec, IncomingMessage, …)
│   ├── core/                               ← agent loop wrapper around koog
│   │   ├── agent/
│   │   ├── compaction/
│   │   ├── hooks/
│   │   ├── cost/
│   │   ├── submission/
│   │   ├── dispatcher/                     ← ToolDispatcher (the mutation funnel)
│   │   ├── delegate/                       ← ChatDelegate, JobDelegate
│   │   └── classifier/                     ← optional
│   ├── memory/                             ← SQLite + workspace + retrieval
│   │   ├── sqlite/, postgres/, none/, mock/
│   │   ├── workspace/                      ← markdown FS layout
│   │   ├── chunker/, embeddings/, retrieval/, hygiene/
│   │   └── search/                         ← FTS5 + vector + RRF
│   ├── security/
│   │   ├── policy/, autonomy/, command/, workspace_boundary/
│   │   ├── prompt_guard/, leak_detector/, approval/
│   │   └── receipts/                       ← Ed25519 chained log
│   ├── tools/
│   │   ├── builtin/                        ← file_system, shell, http, …
│   │   ├── mcp_client/                     ← MCP-as-tool-source
│   │   └── execute/                        ← exec pipeline (validate→sandbox→log)
│   ├── plugins/                            ← Extism on Chicory
│   │   ├── host/, manifest/, signature/, capability/
│   ├── channels/
│   │   ├── api/                            ← shared `IncomingMessage`, helpers
│   │   ├── cli/, web/, slack/, telegram/, whatsapp/, email/
│   ├── providers/
│   │   ├── anthropic/, openai/, ollama/, openai_compat/
│   │   └── router/                         ← v2: hint routing + fallback
│   ├── mcp_server/                         ← koklyp-as-MCP-server
│   ├── gateway/                            ← Ktor server: console + webhook ingress
│   ├── scheduler/                          ← cron, routines, heartbeat, SOPs[v2]
│   ├── config/                             ← TOML schema + secrets store
│   ├── observability/                      ← OTel adapter
│   └── cli_app/                            ← `koklyp` binary entry; argparse; subcommands
├── plugins/                                ← Extism plugin templates (Rust/Go)
└── docs/                                   ← architecture, claws, brainstorming
```

Dependencies flow downward. `api` has no deps. `core` depends on `api`, `memory`, `security`, `tools`. Channels depend on `api`. Etc. The kernel never imports a concrete channel/tool/provider.

## 4. Kernel ABI

Five traits define the kernel. Everything else is feature-flagged or pluggable.

```kotlin
// modules/api

interface LlmProvider {
    suspend fun chat(req: ChatRequest): Flow<StreamEvent>
    fun capabilities(): ProviderCapabilities  // streaming?, toolUse?, multimodal?
}

sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class ToolCall(val call: ParsedToolCall) : StreamEvent()
    data object Done : StreamEvent()
    data class Error(val cause: Throwable, val retriable: Boolean) : StreamEvent()
}

interface Channel {
    val name: String
    suspend fun start(): Flow<IncomingMessage>
    suspend fun reply(ctx: ReplyContext, msg: OutboundMessage)
    fun supportsDraftUpdates(): Boolean = false
    suspend fun updateDraft(ctx: ReplyContext, partial: String) {}
    suspend fun broadcast(userId: String, msg: OutboundMessage)
    suspend fun healthCheck(): ChannelHealth
    suspend fun shutdown()
}

interface Tool {
    val spec: ToolSpec                                    // name, description, JSON Schema
    val risk: RiskLevel                                   // Low | Medium | High
    val requiresApproval: Boolean get() = false
    suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult
}

interface MemoryStore {
    suspend fun append(conversationId: String, msg: ConversationMessage)
    suspend fun loadContext(conversationId: String, limit: Int): List<ConversationMessage>
    suspend fun search(query: String, k: Int): List<MemoryHit>
    suspend fun category(cat: MemoryCategory, ...): ...
    // + workspace ops: read/write/list/append; system_prompt assembly
}

interface Observer {
    fun event(e: ObserverEvent)
    fun span(name: String): Span
}
```

These are pure interfaces. `api` has zero dependencies (besides kotlinx-serialization for the data classes).

## 5. The agent loop

We wrap koog rather than reimplement. Koog gives us:
- Provider streaming + tool-use protocol normalization
- History compression
- Agent persistence (resume from a step)
- Retries
- OpenTelemetry

We add (in `core/agent/`):
- The `Submission` parsing layer (slash-commands, approvals, raw-text)
- `ToolDispatcher.dispatch` — the single mutation funnel
- The koklyp-flavored hooks (BeforeInbound, BeforeToolCall, BeforeOutbound, OnSessionStart/End)
- The skill selector (deterministic prefilter)
- Cost guard + loop detector + compaction ladder
- The `LoopDelegate`-equivalent strategy for Chat/Job/Worker

Wrap pattern:

```kotlin
class KoklypAgent(
    private val koogAgent: ai.koog.agents.Agent,         // dependency hidden
    private val skillSelector: SkillSelector,
    private val dispatcher: ToolDispatcher,
    private val costGuard: CostGuard,
    private val hooks: HookRunner,
    // ...
) {
    suspend fun handleMessage(msg: IncomingMessage): HandleOutcome { ... }
}
```

Channel and tool code never sees `ai.koog.*`. If we swap, change one module.

## 6. Inbound message data flow

```
External event (Slack webhook / Telegram poll / web SSE / IMAP / CLI stdin)
   │
   ▼ Channel adapter
      • decode platform-native payload
      • dedup (replay guard)
      • pair-check + allowed_users + IAM
   │
   ▼ IncomingMessage (canonical)
   │
   ▼ ChannelManager (merges N flows + injectTx)
   │
   ▼ Agent.handleMessage(msg)
       1. attachments middleware (transcribe audio, extract PDF/DOCX text)
       2. SubmissionParser.parse(content) → Submission (sealed)
       3. SystemCommand → bypass session lock; reply directly
          QuitCommand → break loop
          AuthMode → intercept BEFORE turn creation; route to credential store
          Approval → resolve PendingApproval; resume delegate
          UserInput → process_user_input:
             a. BeforeInbound hook
             b. SessionManager.resolveSession (DCL on session map)
             c. ChatDelegate.run via shared agentic loop:
                · skillSelector.prefilter(msg, skills) → top-N skills
                · attenuator(activeSkills, tools) → allowed tools
                · context build = identity files + history + skills + tool defs
                · costGuard.checkAllowed()
                · loop:
                    koogAgent.respond(context) → Flow<StreamEvent>
                       TextDelta → channel.updateDraft (if supported, batched 80+ chars)
                       ToolCall →
                          dispatcher.dispatch(call):
                             • loopDetector.fingerprint
                             • security.validate(name, args, risk) → Allow/RequireApproval/Deny
                             • approval.requestIfNeeded
                             • tool.invoke (subprocess sandbox in v2)
                             • leakDetector.scan(result)
                             • receipts.append (Ed25519 chained)
                             • memory.append(call, result)
                             • hooks.afterToolCall
                          → ChatMessage appended; resume stream
                       Done → final reply text
                · costGuard.recordCall()
                · compactor.maybeCompact(history)
       4. BeforeOutbound hook → modify or suppress
       5. ChannelManager.respond → channel.reply → "Done" status (unless Pending)
       6. memory.append(assistant message)
```

Concurrency: each user/session has a single agent loop. Parallelism happens at the scheduler level (background jobs, SOPs, routines). Path-scoped concurrent tools (Hermes idea) is a v2 enhancement using `coroutineScope { async { ... } }` per path-group.

## 7. Persistence layout

```
~/.koklyp/
├── config.toml                  ← bootstrap config (env-resolvable refs)
├── secrets.db                   ← AES-256-GCM encrypted secrets, master key in OS keychain
├── koklyp.db                    ← main DB: settings, sessions, memory, jobs
├── workspace/                   ← markdown filesystem
│   ├── README.md
│   ├── MEMORY.md
│   ├── HEARTBEAT.md
│   ├── IDENTITY.md, SOUL.md, USER.md, AGENTS.md
│   ├── BOOTSTRAP.md             ← deleted after onboarding
│   ├── context/, daily/, projects/
│   └── .system/settings/**       ← dual-writes from settings table
├── skills/                      ← user-authored markdown skill bundles
├── plugins/<name>/              ← Extism plugins; manifest.toml + plugin.wasm
├── receipts/                    ← Ed25519 tool-receipts log, append-only by month
│   └── 2026-04.log
└── logs/                        ← rotated structured-JSON logs
```

Three orthogonal layers, never collapsed:

1. **Bootstrap config** — `config.toml`, loaded before DB exists.
2. **DB-backed settings** — `settings` table; dual-writes to `workspace/.system/settings/*`.
3. **Encrypted secrets** — AES-256-GCM in `secrets.db`; master key in OS keychain.

Critical invariant: **LLM data is never deleted.** Reasoning, tool calls, messages — all retained. "Cleanup" means evicting in-memory caches; rows persist.

## 8. The plugin model

Plugins extend koklyp with new tools (and, later, channels/memory/observers). They run in a WASM sandbox via Extism on top of Chicory.

```
~/.koklyp/plugins/my-plugin/
├── manifest.toml
└── plugin.wasm
```

Manifest:

```toml
name = "my-plugin"
version = "0.1.0"
description = "..."
wasm_path = "plugin.wasm"
capabilities = ["tool"]                     # tool | skill (v1); channel | memory | observer (v2/L)
permissions  = ["http_client", "env_read"]
signature = "base64url..."                  # Ed25519 over plugin.wasm (optional/required by config)
publisher_key = "hex..."
```

Plugin contract:
- Export `tool_metadata(input: String) -> String` — JSON spec for LLM
- Export `execute(input: String) -> String` — JSON in/out

Host functions (gated by manifest permissions):
- `kk_http_request(json) -> json` (`http_client`)
- `kk_env_read(name) -> value` (`env_read`)
- (v2) `kk_workspace_read(path)`, `kk_workspace_write(path, body)`, `kk_memory_search(q, k)` etc.

The host validates URL host/path against the per-plugin allowlist, injects credentials at the boundary (plugin never sees secret values), and scans responses with the leak detector.

For ironclaw-style WIT-typed plugins: parked. WASI Preview 2 / component model isn't stable on JVM. Watch Chicory's progress; flip later if it makes sense.

## 9. The skill model

Skills are markdown bundles in agentskills.io format:

```
skills/my-skill/
├── SKILL.md               ← required: YAML frontmatter + body
├── scripts/               ← optional helpers
└── references/            ← optional reference material
```

Frontmatter:

```yaml
name: code-review
version: "1.0.0"
description: "Conduct rigorous code review of pending changes."
activation:
  keywords:        ["review", "feedback", "audit"]
  exclude_keywords: ["just", "skip"]
  patterns:        ["\\breview\\s+(this|the)\\s+pr"]
  tags:            ["coding", "review"]
  max_context_tokens: 1500
```

Selection is **deterministic** (no LLM call):

```
score = keyword_exact * 10 (cap 30)
      + keyword_substr * 5 (cap 30)
      + tag_match     * 3 (cap 15)
      + pattern_match * 20 (cap 40)
      - (exclude_keyword_match ? ∞ : 0)        // hard zero
```

Top-scoring skills are loaded until token budget exhausts. The trust ceiling (`min(activeSkillTrusts)`) attenuates the tool list per turn — Installed skills can only see read-only tools.

Source: ironclaw `prefilter_skills`; pure functions; ~300 LOC port to Kotlin.

## 10. Channel model

```kotlin
interface Channel {
    val name: String
    suspend fun start(): Flow<IncomingMessage>
    suspend fun reply(ctx: ReplyContext, msg: OutboundMessage)
    suspend fun updateDraft(ctx: ReplyContext, partial: String) { /* default no-op */ }
    fun supportsDraftUpdates(): Boolean = false
    suspend fun broadcast(userId: String, msg: OutboundMessage)
    suspend fun healthCheck(): ChannelHealth
    suspend fun shutdown()
}

data class IncomingMessage(
    val id: UUID,
    val channel: String,
    val userId: String,                  // resolved (may differ from senderId after pairing)
    val senderId: String,                // raw channel-side actor id
    val content: String,
    val attachments: List<Attachment>,
    val threadId: ExternalThreadId?,     // sealed: TrustedThreadId | UntrustedThreadId
    val metadata: JsonObject,
    val receivedAt: Instant,
    val isInternal: Boolean = false,
    val isAgentBroadcast: Boolean = false,  // recursion guard
)
```

The `ChannelManager` merges N `Flow<IncomingMessage>`s into one and owns an `injectChannel: Channel<IncomingMessage>` (capacity 64) so background producers (heartbeat, routines, MCP server) push into the same flow without being a full `Channel`.

Trust split: `ExternalThreadId` is a sealed type with `TrustedThreadId` (validated already) and `UntrustedThreadId` (validated at construction). Pick the appropriate constructor at the system boundary.

## 11. Security architecture

Six layers, outer to inner:

1. **Channel pairing + allowlist** — at the adapter, before runtime sees the event.
2. **Autonomy level** — `ReadOnly | Supervised | Full` (+ `YOLO` preset). Each tool's `risk` matched against level.
3. **Workspace boundary** — `forbidden_paths` always blocked; tool `file_*` calls confined to workspace by default.
4. **Command policy** — `allowed_commands` / `forbidden_commands` + pattern-based validator before shell exec.
5. **OS sandbox (v2)** — subprocess via `firejail`/`bwrap`/Docker for shell + browser + plugin runtimes when available.
6. **Tool receipts** — Ed25519 signed, hash-chained, on disk. Source of truth for "what did the agent do".

Plus cross-cutting:
- **Prompt-injection guard** — pattern-scan model output before tool dispatch.
- **Leak detector** — pattern-scan outbound for secret formats; block on hit.
- **OTP gate** — TOTP per-action, configurable. v2.
- **Emergency stop** — `koklyp estop` halts in-flight; resume requires OTP if configured.
- **Sensitive-param redaction** — auto-redact known param names in logs/UI.

## 12. The agent loop strategy split

Following ironclaw's `LoopDelegate`:

```kotlin
interface LoopDelegate {
    suspend fun checkSignals(): LoopSignal
    suspend fun beforeLlmCall(ctx: ReasoningContext, iter: Int): LoopOutcome?
    suspend fun callLlm(reasoning: Reasoning, ctx: ReasoningContext): RespondOutput
    suspend fun handleTextResponse(text: String): TextAction
    suspend fun executeToolCalls(calls: List<ToolCall>, ctx: ReasoningContext): LoopOutcome?
    suspend fun afterIteration(iter: Int)
}

class ChatDelegate(...) : LoopDelegate { /* foreground; tracks turns, holds session lock */ }
class JobDelegate(...) : LoopDelegate { /* background; arc-based ownership; can use planning */ }
class WorkerDelegate(...) : LoopDelegate { /* containerised job; sequential tool exec */ }
```

`runAgenticLoop(delegate, reasoning, ctx, config) -> LoopOutcome` is the shared driver. Variants: `Response(String)`, `Stopped`, `MaxIterations`, `Failure(String)`, `NeedApproval(PendingApproval)`, `AuthPending(String)`.

`HandleOutcome::Pending` (turn paused, no `Done` emitted) is distinct from `NoResponse` (turn done, no text). The web-console SSE safety net depends on this distinction.

## 13. Memory architecture

`MemoryStore` is the trait; SQLite the default impl.

```
SQLite schema (Flyway-migrated):
  conversations(id, channel, user_id, started_at, ended_at)
  messages(id, conv_id, role, content, tool_calls, ts, …)
  memory_docs(path PK, content, ts, scope)             ← markdown FS view
  memory_chunks(doc_path, chunk_idx, content, embedding BLOB, ts)
  memory_chunks_fts (FTS5 virtual table)               ← keyword index
  memory_chunks_vec (sqlite-vec virtual table)          ← vector index
  settings(key PK, value)
  jobs(id, kind, status, started_at, …)                ← background scheduler
```

Hybrid retrieval (`memory.search(q, k)`):
- FTS5 lookup on chunks → ranked list A
- Vector cosine over query embedding → ranked list B
- RRF fusion: `score(d) = Σ 1/(k0 + rank(d))`, `k0 = 60`
- Documents in both lists boosted (intentional)

Workspace API (mirrors ironclaw):
- `read(path)`, `write(path, body)`, `append(path, body)`, `list(prefix)`, `search(q, k)`, `appendMemory(content)`, `appendDailyLog(content)`, `systemPrompt()`.
- Identity files always read from primary scope; multi-scope reads only for shared content.

## 14. MCP integration

Both directions, day one:

- **Server** — koklyp exposes its built-in tools as an MCP server. Transports: stdio (default), SSE (Ktor extension), WebSocket (Ktor extension), Streamable HTTP. Lets Claude Desktop / Cursor / Windsurf call koklyp tools.
- **Client** — koklyp consumes external MCP servers as tool sources. Tools imported with names prefixed `mcp_<server>_<tool>`.

MCP tool filtering — adopted from zeroclaw — prevents prompt-token explosion when MCP servers advertise hundreds of tools:

```kotlin
data class McpToolGroup(
    val mode: GroupMode,             // Always | Dynamic
    val patterns: List<String>,      // glob match against tool name
    val keywords: List<String>,      // for Dynamic: substring match in user message
)
```

`Always` group always advertises matching tools. `Dynamic` requires both a name match and a keyword in the user message.

## 15. Web console

Ktor server hosting:

- `GET /` — chat UI (SPA)
- `POST /api/messages` — submit user message
- `GET /api/sessions/{id}/events` — SSE stream of `StreamEvent`
- `POST /api/approval/{id}` — resolve approval prompt
- `GET /api/memory/search?q=...` — memory browse
- `GET /api/receipts?since=...` — auditable receipts view
- `POST /api/webhooks/<channel>/<endpoint>` — channel webhook ingress

Auth: single password by default (HTTP Basic over TLS); OAuth optional in v2. Self-hosted small-team scope, not multi-tenant.

UI tech: small SPA — Svelte/SolidJS/HTMX. Avoid React for a tool that should be lightweight.

## 16. Boot sequence

1. Parse CLI (subcommand: `run | onboard | service | doctor | tool | plugin | mcp | memory | pairing | estop | status | completion`).
2. Load `~/.koklyp/config.toml`.
3. Build `AppComponents`:
   - `ConfigStore`, `SecretsStore` (master key from keychain), `Db`, `Observer`
   - `LlmProvider` chain (router + concrete providers)
   - `MemoryStore` (SQLite) + `Workspace`
   - `SkillRegistry` from bundled + user dirs
   - `PluginHost` (Extism on Chicory) — load enabled plugins
   - `ToolRegistry` (built-ins + plugin-tools + MCP-client tools)
   - `ToolDispatcher`
   - `SecurityPolicy` (autonomy, workspace, command, prompt-guard, leak-detector)
   - `KoklypAgent` (wraps koog)
   - `ChannelManager` + each enabled channel + `injectChannel`
   - `Scheduler` (heartbeat, routines, [v2] SOPs)
   - `WebGateway` (Ktor)
   - `McpServer` (stdio + Ktor SSE/WS routes)
4. `app.run()` — start channels, start gateway, start scheduler, block on Ctrl-C / `/quit`.

Each `init_*` is module-owned. `cli_app/` only orchestrates.

## 17. Distribution

- **Fat JAR** via Gradle Shadow plugin.
- **`./koklyp` shell wrapper** that runs `java -jar koklyp.jar`.
- **Docker image** in v2.
- **Native-image (GraalVM)** in v2+ if size/cold-start matter.
- **Service registration** via `koklyp service install` — generates and installs systemd unit / launchctl plist / Windows-Service definition. Mirrors zeroclaw.

## 18. Deferred / parked

- WIT-typed plugins (component model). Watch Chicory.
- SOP engine. v2; see brainstorming for whether to include in v1.
- Knowledge graph, decay, consolidation, conflict, snapshots.
- Multi-tenant / SaaS topology.
- Hardware (Peripheral trait, GPIO/I2C/etc).
- Tauri desktop app.
- Most of zeroclaw's 40+ channels — pick on demand.
- Skillforge.
- ACP — defer; MCP-server overlap covers most use.

## 19. Comparison summary: where koklyp lands relative to the claws

| Dimension | IronClaw | ZeroClaw | koklyp v1 |
|---|---|---|---|
| Language | Rust | Rust | Kotlin/JVM |
| Plugin sandbox | Wasmtime + WIT/component model | Extism (Wasmtime + WASI P1 + JSON) | Extism on Chicory (WASI P1 + JSON) |
| Channels | ~10 native + 5 WASM | 40+ native | 6 native (Slack/TG/WA/Email/Web/CLI) |
| Skills | Deterministic prefilter (ironclaw) | Mode-based + skillforge | Deterministic prefilter (ironclaw-style) |
| Memory | Workspace markdown + DB + RRF | SQLite/PG/Qdrant + KG + decay/consolidation | SQLite + workspace markdown + RRF |
| Approval | per-tool `requires_approval` (v1) / capability lease (v2) | AutonomyLevel + `risk()` | AutonomyLevel + `risk()` |
| Receipts | ActionRecord (audit) | Ed25519 chained | Ed25519 chained |
| Multi-tenant | optional | not-default | not-default (small-team) |
| MCP | request-response only | first-class client | first-class server + client |
| Web UI | gateway + dashboard | gateway + dashboard + Tauri | web console (Ktor + SPA) |
| Hardware | n/a | first-class | n/a |
| SOP engine | n/a (routines instead) | first-class | open question for v1 |
