# hebe — v1 architecture (solution + contracts)

The concrete contracts that v1 implements. Where the higher-level [`hebe-architecture.md`](hebe-architecture.md) is the blueprint, this is the **wiring diagram** — interfaces, schemas, lifecycle, error taxonomies.

Written so a contributor can pick a slice and implement it without re-deciding the shape.

---

## Contents

1. Module layout (Gradle)
2. Versions and dependency stack
3. Kernel ABI (`api` module) — Kotlin interfaces
4. Plugin ABI (`plugin-api` module) — Kotlin interfaces
5. SQLite schema + Flyway migrations
6. Workspace layout
7. Config schema (`config.toml`)
8. Secrets store
9. Tool dispatcher state machine
10. Loop driver + delegate contract
11. Plugin lifecycle (PF4J integration)
12. OCI/ACR distribution flow
13. Receipts log format
14. Web console API (REST + SSE)
15. MCP server / client transport setup
16. Telegram channel contract
17. CLI channel contract
18. Scheduler + heartbeat
19. Boot sequence (deterministic init order)
20. Error taxonomy + handling rules
21. Logging + observability conventions
22. Security checks (concrete sequencing)

---

## 1. Module layout (Gradle)

```
hebe/
├── settings.gradle.kts
├── build.gradle.kts                ← root: versions, conventions, Detekt + ktlint
├── gradle/libs.versions.toml       ← version catalog (already exists)
├── modules/
│   ├── api/                        ← kernel ABI: zero deps beyond kotlinx-serialization
│   ├── plugin-api/                 ← Plugin + PluginHost; depends on api + pf4j-api
│   ├── observability/              ← OTel + kotlin-logging plumbing
│   ├── config/                     ← TOML schema + secrets store + bootstrap loader
│   ├── memory/                     ← SQLite + Flyway + workspace + chunker + retrieval
│   ├── security/                   ← policy, autonomy, leak, prompt guard, receipts, approval
│   ├── providers/openai-compat/    ← only LLM client in v1
│   ├── tools/builtin/              ← all built-in tools live here, one subdir each
│   ├── tools/mcp-client/           ← MCP-as-tool-source
│   ├── tools/dispatch/             ← ToolDispatcher (the mutation funnel) + execute pipeline
│   ├── core/                       ← agent loop wrapping koog, hooks, delegates, submission
│   ├── plugins/                    ← PF4J host + manifest + signature + OCI distribution
│   ├── channels/channel-manager/    ← Channel manager, IncomingMessage, OutboundMessage, ReplyContext
│   ├── channels/cli/               ← CLI REPL
│   ├── channels/web/               ← Ktor SSE/WebSocket + browser UI
│   ├── channels/telegram/          ← Telegram channel
│   ├── mcp-server/                 ← hebe-as-MCP-server (stdio + Ktor SSE/WS)
│   ├── gateway/                    ← Ktor backend wiring: console + webhook ingress
│   ├── scheduler/                  ← cron, routines, heartbeat, scheduled maintenance
│   ├── detekt-rules/               ← custom Detekt rules (mutation-funnel guard)
│   └── cli-app/                    ← `hebe` binary entry; argparse; subcommands
├── plugin-template/                ← Gradle template repo for internal plugin authors
└── docs/
```

**Dependency rules** (enforced by a build-time check):

- `api` depends only on `kotlinx-serialization-core/json`, `kotlinx-coroutines-core`, `kotlinx-datetime`.
- `plugin-api` depends on `api` and `pf4j` only.
- `observability`, `config`, `memory`, `security` depend only on `api` (+ third-party libs).
- `providers/openai-compat` depends on `api` + Ktor client + serialization.
- `tools/builtin` depends on `api` + `security` + tool-specific libs (jgit, etc.).
- `tools/dispatch` depends on `api` + `security` + `memory` + `observability`.
- `core` depends on `api` + `memory` + `security` + `tools/dispatch` + koog.
- `plugins` depends on `plugin-api` + `pf4j` + ORAS Java client + Bouncy Castle.
- `channels/*` depend on `api` only.
- `mcp-server` depends on `api` + MCP Kotlin SDK + Ktor server.
- `tools/mcp-client` depends on `api` + MCP Kotlin SDK.
- `gateway` depends on `api` + Ktor server + `core`.
- `scheduler` depends on `api` + `memory` + `tools/dispatch`.
- `cli-app` depends on every concrete module (it's the wiring root).

`core` never imports `ai.koog.*` from a public type. `KoogLlmProvider` adapter is the only place `ai.koog.*` is referenced.

## 2. Versions and dependency stack

Pinned in `gradle/libs.versions.toml`:

| Concern | Library | Version target |
|---|---|---|
| Kotlin | `org.jetbrains.kotlin` | 2.2.x (latest stable; bump to 2.3.x when GA) |
| Coroutines | `kotlinx-coroutines` | 1.x latest |
| Serialization | `kotlinx-serialization-json` | 1.x latest |
| Datetime | `kotlinx-datetime` | latest |
| Agent runtime | `ai.koog:koog-core`, `ai.koog:koog-ktor` | latest stable |
| HTTP server | `io.ktor:ktor-server-*` | 3.x |
| HTTP client | `io.ktor:ktor-client-*` | 3.x |
| MCP | `io.modelcontextprotocol:kotlin-sdk` | latest |
| SQLite | `org.xerial:sqlite-jdbc` | latest with sqlite-vec compatibility |
| sqlite-vec | bundled native lib loaded via JDBC URL | 0.x |
| Migrations | `org.flywaydb:flyway-core` | latest |
| Telegram | `org.telegram:telegrambots` | latest |
| Git | `org.eclipse.jgit:org.eclipse.jgit` | latest |
| Crypto | `org.bouncycastle:bcprov-jdk18on` | latest |
| Plugin framework | `org.pf4j:pf4j` | latest 3.x |
| OCI client | `land.oras:oras-java-sdk` (or equivalent) | latest |
| Logging | `io.github.oshai:kotlin-logging-jvm` + `ch.qos.logback:logback-classic` | latest |
| OTel | `io.opentelemetry:opentelemetry-api` (driven by koog) | matched to koog |
| TOML | `org.tomlj:tomlj` | latest |
| TOTP (v2) | n/a in v1 | — |
| Build | Gradle 8.x + Shadow plugin | latest |
| Static analysis | Detekt + ktlint Gradle plugins | latest |
| Tests | JUnit 5, Kotest assertions, MockK, Testcontainers | latest |

## 3. Kernel ABI (`api` module)

```kotlin
// modules/api/src/main/kotlin/com/hebe/api/

/* ── LlmProvider ───────────────────────────────────────────── */

interface LlmProvider {
    suspend fun chat(req: ChatRequest): Flow<StreamEvent>
    fun capabilities(): ProviderCapabilities
}

data class ProviderCapabilities(
    val streaming: Boolean,
    val toolUse: Boolean,
    val multimodal: Boolean,
    val maxContextTokens: Int,
    val supportsPromptCaching: Boolean = false,
)

data class ChatRequest(
    val model: String,
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val stream: Boolean = true,
)

sealed interface ChatMessage {
    data class User(val content: String, val attachments: List<Attachment> = emptyList()) : ChatMessage
    data class Assistant(val content: String, val toolCalls: List<ParsedToolCall> = emptyList()) : ChatMessage
    data class ToolResult(val callId: String, val content: String, val isError: Boolean = false) : ChatMessage
    data class System(val content: String) : ChatMessage
}

sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Required : ToolChoice
    data class Specific(val name: String) : ToolChoice
}

sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class ToolCall(val call: ParsedToolCall) : StreamEvent()
    data class TokenUsage(val input: Int, val output: Int, val cached: Int = 0) : StreamEvent()
    data object Done : StreamEvent()
    data class Error(val cause: Throwable, val retriable: Boolean) : StreamEvent()
}

data class ParsedToolCall(
    val id: String,                     // provider-side call id
    val name: String,                   // hebe-side tool name
    val args: JsonObject,
)

/* ── Tool ──────────────────────────────────────────────────── */

interface Tool {
    val spec: ToolSpec
    val risk: RiskLevel
    val requiresApproval: Boolean get() = risk == RiskLevel.High
    suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult
}

data class ToolSpec(
    val name: String,
    val description: String,
    val schema: JsonObject,             // JSON Schema for params
)

enum class RiskLevel { Low, Medium, High }

interface ToolContext {
    val sessionId: String
    val turnId: String
    val userId: String
    val requestor: Channel
    val workspace: WorkspacePath
    val approvalGate: ApprovalGate
    val observer: Observer
    val secretLookup: SecretLookup       // *handles*, never raw values inside the tool
}

sealed interface ToolResult {
    data class Ok(val content: JsonElement, val artifacts: List<Artifact> = emptyList()) : ToolResult
    data class Err(val message: String, val retriable: Boolean = false) : ToolResult
    data class NeedsApproval(val prompt: String, val payload: JsonObject) : ToolResult
}

data class Artifact(val mime: String, val bytes: ByteArray, val name: String? = null)

/* ── Channel ──────────────────────────────────────────────── */

interface Channel {
    val name: String
    suspend fun start(scope: CoroutineScope): Flow<IncomingMessage>
    suspend fun reply(ctx: ReplyContext, msg: OutboundMessage)
    fun supportsDraftUpdates(): Boolean = false
    suspend fun updateDraft(ctx: ReplyContext, partial: String) {}
    suspend fun broadcast(userId: String, msg: OutboundMessage) {}
    suspend fun healthCheck(): ChannelHealth
    suspend fun shutdown()
}

data class IncomingMessage(
    val id: UUID,
    val channel: String,
    val userId: String,
    val senderId: String,
    val content: String,
    val attachments: List<Attachment>,
    val threadId: ExternalThreadId?,
    val metadata: JsonObject,
    val receivedAt: Instant,
    val isInternal: Boolean = false,
    val isAgentBroadcast: Boolean = false,
    val triggeringMissionId: String? = null,
)

sealed interface ExternalThreadId {
    val raw: String
    data class Trusted(override val raw: String) : ExternalThreadId
    data class Untrusted(override val raw: String) : ExternalThreadId
}

data class ReplyContext(
    val incomingId: UUID,
    val sessionId: String,
    val threadId: ExternalThreadId?,
    val routingTargets: List<String> = emptyList(),
)

data class OutboundMessage(
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val approvalRequest: ApprovalRequest? = null,
)

data class Attachment(val mime: String, val bytes: ByteArray, val name: String? = null)

data class ApprovalRequest(val id: String, val tool: String, val argsRedacted: JsonObject, val expiresAt: Instant)

enum class ChannelHealth { Up, Degraded, Down }

/* ── MemoryStore ──────────────────────────────────────────── */

interface MemoryStore {
    suspend fun appendMessage(conversationId: String, msg: ConversationMessage)
    suspend fun loadContext(conversationId: String, limit: Int = 64): List<ConversationMessage>
    suspend fun search(query: String, k: Int = 10, scope: MemoryScope = MemoryScope.Default): List<MemoryHit>
    suspend fun appendDoc(path: String, content: String, scope: MemoryScope = MemoryScope.Default)
    suspend fun readDoc(path: String): String?
    suspend fun listDocs(prefix: String): List<String>
    suspend fun systemPrompt(): String  // assembles identity + memory + heartbeat into a prompt fragment
    suspend fun snapshot(): MemorySnapshot   // for backup; v1 is a stub
}

data class ConversationMessage(
    val id: UUID, val role: ChatRole, val content: String,
    val toolCalls: List<ParsedToolCall>, val ts: Instant,
)

enum class ChatRole { User, Assistant, System, Tool }

data class MemoryHit(
    val docPath: String, val chunkIdx: Int, val snippet: String,
    val score: Double, val source: HitSource,
)

enum class HitSource { Fts, Vector, Both }

enum class MemoryScope { Default, Identity, Daily }     // identity files always loaded from Default

/* ── Observer ─────────────────────────────────────────────── */

interface Observer {
    fun event(e: ObserverEvent)
    fun span(name: String, attrs: Map<String, Any> = emptyMap()): Span
}

interface Span : AutoCloseable {
    fun setAttribute(key: String, value: Any)
    fun recordError(t: Throwable)
}

sealed class ObserverEvent {
    data class TurnStart(val sessionId: String, val turnId: String) : ObserverEvent()
    data class TurnEnd(val sessionId: String, val turnId: String, val outcome: String) : ObserverEvent()
    data class ToolDispatched(val turnId: String, val tool: String, val durationMs: Long, val ok: Boolean) : ObserverEvent()
    data class LlmCall(val turnId: String, val tokensIn: Int, val tokensOut: Int, val ms: Long) : ObserverEvent()
    data class ApprovalRequested(val turnId: String, val tool: String) : ObserverEvent()
    data class ApprovalResolved(val turnId: String, val approved: Boolean) : ObserverEvent()
}

/* ── Submission (parsed inbound) ──────────────────────────── */

sealed interface Submission {
    data class UserInput(val msg: IncomingMessage) : Submission
    data class SystemCommand(val msg: IncomingMessage, val command: SlashCommand) : Submission
    data class Approval(val msg: IncomingMessage, val approvalId: String, val approved: Boolean) : Submission
    data class AuthMode(val msg: IncomingMessage, val purpose: String, val secret: String) : Submission
    data class QuitCommand(val msg: IncomingMessage) : Submission
}

sealed interface SlashCommand {
    data object Compact : SlashCommand
    data object Status : SlashCommand
    data object Help : SlashCommand
    data class SkillList(val filter: String?) : SlashCommand
    // ... grow as needed
}

/* ── HandleOutcome ────────────────────────────────────────── */

sealed interface HandleOutcome {
    data class Done(val reply: OutboundMessage) : HandleOutcome
    data class Pending(val reason: PendingReason) : HandleOutcome
    data class NoResponse(val cause: String) : HandleOutcome
    data class Failed(val error: Throwable) : HandleOutcome
}

sealed interface PendingReason {
    data class Approval(val request: ApprovalRequest) : PendingReason
    data class AuthEntry(val purpose: String) : PendingReason
}
```

## 4. Plugin ABI (`plugin-api` module)

```kotlin
// modules/plugin-api/src/main/kotlin/com/hebe/plugin/

abstract class HebePlugin(wrapper: org.pf4j.PluginWrapper) : org.pf4j.Plugin(wrapper) {
    open fun tools(host: PluginHost): List<Tool> = emptyList()
    open fun channels(host: PluginHost): List<Channel> = emptyList()
    open fun memoryStores(host: PluginHost): List<MemoryStore> = emptyList()
    open fun observers(host: PluginHost): List<Observer> = emptyList()

    /** Called once between PF4J `start()` and tool registration. */
    open fun init(host: PluginHost) {}

    /** Called before PF4J `stop()`. Plugins close resources here. */
    open fun teardown() {}
}

/** The single seam through which a plugin reaches host-side facilities. */
interface PluginHost {
    val pluginId: String
    val manifest: PluginManifest

    /** http_client capability. Allowlist enforced at the boundary. */
    fun http(): GatedHttpClient

    /** env_read capability. Returns curated subset; null if not declared. */
    fun env(name: String): String?

    /** secrets:<name> capability. Returns a *handle* the host can resolve at request time, never the value. */
    fun secret(name: String): SecretHandle?

    val observer: Observer
    val log: org.slf4j.Logger
}

interface GatedHttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
    suspend fun post(url: String, body: ByteArray, headers: Map<String, String> = emptyMap()): HttpResponse
    // Throws PluginCapabilityException if the URL fails the allowlist or the plugin lacks `http_client`.
}

data class HttpResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray)

/** Opaque handle. The plugin passes this to GatedHttpClient.post(authed=...) etc.; the host expands it. */
@JvmInline value class SecretHandle(val name: String)

class PluginCapabilityException(message: String) : RuntimeException(message)

data class PluginManifest(
    val hebeApiVersion: String,
    val capabilities: Set<Capability>,
    val permissions: Set<Permission>,
    val allowlistDomains: List<String>,
    val signature: String?,
    val publisherKey: String?,
)

enum class Capability { Tool, Skill /* channel/memory/observer reserved */ }

sealed interface Permission {
    data object HttpClient : Permission
    data object EnvRead : Permission
    data class Secret(val name: String) : Permission
}
```

## 5. SQLite schema + Flyway migrations

Migrations live in `modules/memory/src/main/resources/db/migration/`. Filename convention: `V{n}__{snake_name}.sql`. v1 ships migrations V1–V5; later versions add tables without rewrites.

```sql
-- V1__core.sql

CREATE TABLE conversations (
  id            TEXT PRIMARY KEY,
  channel       TEXT NOT NULL,
  user_id       TEXT NOT NULL,                 -- always the single configured operator in v1
  external_id   TEXT,                          -- channel-side thread id
  started_at    INTEGER NOT NULL,
  ended_at      INTEGER,
  metadata_json TEXT
);

CREATE INDEX idx_conversations_channel ON conversations(channel);
CREATE INDEX idx_conversations_external ON conversations(external_id);

CREATE TABLE messages (
  id              TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL REFERENCES conversations(id),
  role            TEXT NOT NULL,               -- user | assistant | system | tool
  content         TEXT NOT NULL,
  tool_calls_json TEXT,                        -- ParsedToolCall[]
  tool_call_id    TEXT,                        -- present when role=tool
  ts              INTEGER NOT NULL,
  redaction_json  TEXT,                        -- spans we redacted; for re-inflation in audit
  meta_json       TEXT
);

CREATE INDEX idx_messages_conv_ts ON messages(conversation_id, ts);

-- V2__memory.sql

CREATE TABLE memory_docs (
  path        TEXT PRIMARY KEY,
  content     TEXT NOT NULL,
  scope       TEXT NOT NULL DEFAULT 'Default', -- Default | Identity | Daily
  ts          INTEGER NOT NULL,
  byte_size   INTEGER NOT NULL,
  hash_sha256 TEXT NOT NULL
);

CREATE TABLE memory_chunks (
  doc_path     TEXT NOT NULL REFERENCES memory_docs(path) ON DELETE CASCADE,
  chunk_idx    INTEGER NOT NULL,
  content      TEXT NOT NULL,
  token_count  INTEGER NOT NULL,
  embedding    BLOB,                            -- f32[] serialized; null until indexed
  ts           INTEGER NOT NULL,
  PRIMARY KEY (doc_path, chunk_idx)
);

CREATE VIRTUAL TABLE memory_chunks_fts USING fts5(
  doc_path UNINDEXED,
  chunk_idx UNINDEXED,
  content,
  content='memory_chunks',
  content_rowid='rowid',
  tokenize='porter unicode61'
);

-- sqlite-vec virtual table
CREATE VIRTUAL TABLE memory_chunks_vec USING vec0(
  doc_path TEXT,
  chunk_idx INTEGER,
  embedding FLOAT[1536]                          -- match the embedding model dimension
);

-- V3__settings_jobs_routines.sql

CREATE TABLE settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  ts    INTEGER NOT NULL
);

CREATE TABLE jobs (
  id           TEXT PRIMARY KEY,
  kind         TEXT NOT NULL,                   -- routine | maintenance | adhoc | heartbeat
  status       TEXT NOT NULL,                   -- pending | running | done | failed | cancelled | stuck
  started_at   INTEGER,
  ended_at     INTEGER,
  trigger_at   INTEGER,                         -- next-run for cron-driven kinds
  payload_json TEXT,
  result_json  TEXT,
  attempt      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_jobs_kind_status ON jobs(kind, status);
CREATE INDEX idx_jobs_trigger ON jobs(trigger_at) WHERE status = 'pending';

CREATE TABLE routines (
  id            TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  cron          TEXT NOT NULL,
  body_kind     TEXT NOT NULL,                  -- skill | tool | sop_v2
  body_ref      TEXT NOT NULL,                  -- skill name or tool name
  body_json     TEXT,                           -- args
  enabled       INTEGER NOT NULL DEFAULT 1,
  created_at    INTEGER NOT NULL,
  last_run_at   INTEGER,
  next_run_at   INTEGER
);

-- V4__llm_calls_tool_calls.sql

CREATE TABLE llm_calls (
  id              TEXT PRIMARY KEY,
  conversation_id TEXT,
  turn_id         TEXT,
  model           TEXT NOT NULL,
  tokens_in       INTEGER NOT NULL,
  tokens_out      INTEGER NOT NULL,
  tokens_cached   INTEGER NOT NULL DEFAULT 0,
  cost_micros_usd INTEGER,                      -- best-effort; may be null when gateway hides it
  ms              INTEGER NOT NULL,
  ts              INTEGER NOT NULL
);

CREATE TABLE tool_calls (
  id            TEXT PRIMARY KEY,
  turn_id       TEXT,
  tool          TEXT NOT NULL,
  risk          TEXT NOT NULL,
  args_redacted TEXT NOT NULL,                  -- JSON with sensitive keys masked
  result_json   TEXT,
  ok            INTEGER NOT NULL,
  ms            INTEGER NOT NULL,
  ts            INTEGER NOT NULL,
  receipt_seq   INTEGER NOT NULL                -- seq into receipts/<YYYY-MM>.log
);

CREATE INDEX idx_tool_calls_turn ON tool_calls(turn_id);
CREATE INDEX idx_tool_calls_ts ON tool_calls(ts);

-- V5__pending_approvals.sql

CREATE TABLE pending_approvals (
  id           TEXT PRIMARY KEY,
  turn_id      TEXT NOT NULL,
  tool         TEXT NOT NULL,
  args_redacted TEXT NOT NULL,
  prompt       TEXT NOT NULL,
  channel      TEXT NOT NULL,
  thread_ext_id TEXT,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  resolved_at  INTEGER,
  approved     INTEGER                          -- null until resolved
);
```

**Critical invariants:**

- Rows in `messages`, `llm_calls`, `tool_calls` are **never deleted**. "Cleanup" only evicts in-memory caches.
- `memory_chunks_fts` and `memory_chunks_vec` are **derived**. They can be dropped and rebuilt by the embedding-refresh routine.
- `pending_approvals.resolved_at` is set when an approval is granted/denied; the row is retained for audit.

## 6. Workspace layout

```
~/.hebe/workspace/
├── README.md                   ← static; explains the workspace to a new operator
├── BOOTSTRAP.md                ← deleted by `hebe onboard` after setup
├── IDENTITY.md                 ← agent persona; loaded from Default scope every turn
├── MEMORY.md                   ← long-term facts/preferences; loaded for 1:1 contexts
├── HEARTBEAT.md                ← checklist read by the heartbeat routine
├── USER.md                     ← (optional) what hebe knows about the operator
├── AGENTS.md                   ← (optional) sub-agent registry; v1 has none
├── daily/
│   └── 2026-05-04.md           ← daily logs created by the daily-digest routine
├── context/
│   └── *.md                    ← project / topic notes the agent is allowed to read
├── projects/
│   └── <name>/                 ← project-scoped notes; one subdir per project
└── .system/
    └── settings/               ← dual-write target from the settings table; human-readable
```

## 7. Config schema (`config.toml`)

```toml
# ~/.hebe/config.toml

[hebe]
data_dir = "~/.hebe"
log_level = "info"

[llm]
base_url       = "https://llm-gateway.example.com/v1"
api_key_secret = "llm.api_key"             # references a key in secrets.db
default_model  = "gpt-4o-mini"
embedding_model = "text-embedding-3-small"
embedding_dim   = 1536

[autonomy]
level = "Supervised"                       # ReadOnly | Supervised | Full | YOLO

[security]
forbidden_paths        = ["/etc", "~/.ssh", "~/.aws", "~/.azure"]
allowed_command_globs  = ["git *", "kubectl get *", "kubectl describe *", "ls *", "cat *"]
forbidden_command_globs = ["rm -rf /*", "kubectl delete *", "shutdown *"]
http_allowlist_domains = ["api.brave.com", "api.duckduckgo.com", "api.linear.app"]
plugin_signature_mode  = "optional"        # optional | required | disabled

[scheduler]
heartbeat_cron     = "0 */6 * * *"         # every 6 h
daily_digest_cron  = "5 0 * * *"
summarisation_cron = "*/30 * * * *"
fact_extract_cron  = "10 * * * *"

[channels.cli]
enabled = true

[channels.web]
enabled        = true
bind           = "127.0.0.1"
port           = 8765
admin_password_secret = "web.password"

[channels.telegram]
enabled         = false                    # flipped on by `hebe onboard`
bot_token_secret = "telegram.bot_token"
operator_telegram_id = 123456789

[plugins]
registry        = "acr.example.com/hebe-plugins"
auto_pull       = []                       # list of "<name>:<version>" to pull on boot
publisher_keys  = []                       # hex Ed25519 keys trusted for verification

[mcp.server]
enabled    = true
stdio      = true
http_bind  = ""                            # empty disables HTTP transport
http_port  = 0

[mcp.client]
servers = []                               # see schema below

# Per-server entry (under [[mcp.client.servers]]):
# [[mcp.client.servers]]
# name = "filesystem"
# transport = "stdio"
# command = ["npx", "@modelcontextprotocol/server-filesystem", "/Users/me/Documents"]
# always_tools = ["read_file", "write_file"]
# dynamic_tools = []
# dynamic_keywords = []
```

Validation: tomlj load → typed projection (`HebeConfig`) via kotlinx-serialization or hand-rolled mapper. Bad config fails fast on boot with a precise diagnostic.

## 8. Secrets store

```
~/.hebe/secrets.db                         ← SQLite, AES-256-GCM at rest
master key                                    ← OS keychain (Keychain / secret-service / Cred Mgr)
                                                fallback: passphrase-derived (PBKDF2-HMAC-SHA256, 600k rounds), stored in chmod 600 file
```

API:

```kotlin
interface SecretStore {
    fun put(name: String, value: String)
    fun get(name: String): String?
    fun delete(name: String): Boolean
    fun list(): List<String>                 // names only; values never logged
}
```

Used by:

- `LlmProvider` for `api_key` resolution.
- `Channel` adapters for bot tokens, app secrets.
- `Tool`s that need creds (`github`, plugins via `secret(name)`).

## 9. Tool dispatcher state machine

`ToolDispatcher.dispatch` is the only path by which any side-effect happens. Lint-enforced via a custom Detekt rule.

```
input: ParsedToolCall + ToolContext
output: DispatchOutcome

states:
  ─→ FINGERPRINT      (loop detector hashes name+args; tracks per-turn duplicates)
  ─→ POLICY_VALIDATE  (security.validate → Allow | RequireApproval | Deny)
       Deny → emit ToolResult.Err to LLM; ObserverEvent.ToolDispatched(ok=false)
       RequireApproval → ApprovalGate.requestIfNeeded; if not yet resolved → return Pending
       Allow → continue
  ─→ INVOKE
       run inside CoroutineScope(Dispatchers.IO) bounded by tool.timeout
       capture result as ToolResult.Ok | ToolResult.Err
  ─→ LEAK_SCAN
       LeakDetector.scan(serialised result)
       hit → replace with ToolResult.Err("output blocked: leak detector"); record severity
  ─→ RECEIPT_APPEND
       Receipts.append(receipt) → returns seq; tool_calls.receipt_seq stored
  ─→ MEMORY_APPEND
       MemoryStore.appendMessage(role=Tool, …)
  ─→ OBSERVER_EVENT
       ObserverEvent.ToolDispatched(ok)
  ─→ HOOKS
       hooks.afterToolCall(ctx, call, result)
  ─→ DispatchOutcome.Result(result) | .Pending(approvalId)
```

Concurrency: dispatcher is single-threaded per session-loop. Background producers funnel through `injectChannel`; they never call dispatcher directly.

## 10. Loop driver + delegate contract

```kotlin
interface LoopDelegate {
    suspend fun checkSignals(): LoopSignal      // estop, cancel, deadline
    suspend fun beforeLlmCall(ctx: ReasoningContext, iter: Int): LoopOutcome?
    suspend fun callLlm(reasoning: Reasoning, ctx: ReasoningContext): RespondOutput
    suspend fun handleTextResponse(text: String): TextAction
    suspend fun executeToolCalls(calls: List<ParsedToolCall>, ctx: ReasoningContext): LoopOutcome?
    suspend fun afterIteration(iter: Int)
}

sealed interface LoopOutcome {
    data class Response(val text: String) : LoopOutcome
    data object Stopped : LoopOutcome
    data object MaxIterations : LoopOutcome
    data class Failure(val message: String) : LoopOutcome
    data class NeedApproval(val request: ApprovalRequest) : LoopOutcome
    data class AuthPending(val purpose: String) : LoopOutcome
}

suspend fun runAgenticLoop(
    delegate: LoopDelegate,
    reasoning: Reasoning,
    ctx: ReasoningContext,
    config: LoopConfig,           // maxIterations, costBudget, compactionThreshold
): LoopOutcome
```

`ChatDelegate` is the v1 implementation. `JobDelegate` is a stripped-down variant used by the scheduler for routine bodies.

## 11. Plugin lifecycle (PF4J integration)

```
            (PF4J state)               (hebe action)
─────────────────────────────────────────────────────────────────────
  CREATED       ← PluginManager scans ~/.hebe/plugins
                   for each: pf4j-side classloader created, classes loaded
  ↓
  RESOLVED      ← PF4J validates plugin.properties, dependency graph
                   hebe parses plugin.toml (capabilities/permissions/signature)
                   verify Ed25519 signature against publisher_key (per signature_mode)
                   verify hebe_api_version compatibility
  ↓
  STARTED       ← plugin.start() runs (PF4J)
                   hebe builds PluginHost with capability gates
                   plugin.init(host) called
                   plugin.tools(host) collected → registered with ToolRegistry
                                                  with id = "<plugin>:<tool>"
                   ObserverEvent.PluginLoaded
  ↓ (running)
  STOPPED       ← plugin.teardown() called
                   hebe removes the plugin's tools from ToolRegistry
                   plugin.stop() runs (PF4J)
  ↓
  UNLOADED      ← classloader closed
                   warn if classes are still pinned (best-effort; restart recommended)
```

ABI compatibility:

- `hebe_api_version` is a SemVer range (`"0.1.x"` matches `0.1.*`).
- We never break `api` types within a major. Fields can be added; never removed within `0.x` until v1 ships and we cut `1.0`.

## 12. OCI/ACR distribution flow

```
$ hebe plugin install acr.example.com/hebe-plugins/linear:0.3.1

  1. Resolve registry auth.
     - DefaultAzureCredential chain: env (AZURE_*) → managed identity → az login token
     - For non-ACR registries, fall back to docker config or ORAS auth file.
  2. ORAS pull <ref> → tarball at ~/.hebe/cache/oci/<sha256>/.
  3. Verify Ed25519 signature.
     - signature in `plugin.toml`; publisher_key matched against config.plugins.publisher_keys.
     - signature_mode = required → reject unsigned or untrusted publishers.
     - signature_mode = optional → log warning, continue.
  4. Verify hebe_api_version compatibility.
  5. Extract to ~/.hebe/plugins/<name>-<version>/.
  6. Trigger PluginManager.loadPlugin(path) → starts PF4J lifecycle (§11).
  7. Persist install record into settings: plugins.installed = ["<name>:<version>", ...]

$ hebe plugin list
  → reads installed records + PF4J state

$ hebe plugin remove <name>
  → stops via PluginManager, deletes ~/.hebe/plugins/<name>-<version>/
  → removes install record
```

OCI artifact shape:

- Manifest media type: `application/vnd.hebe.plugin.v1+json`.
- Layer 0: `application/vnd.hebe.plugin.archive.v1.tar+gzip` (the tar containing plugin.toml + plugin.properties + classes/ + lib/).
- Optional layer: `application/vnd.hebe.plugin.signature.v1+ed25519` (binary Ed25519 over the archive layer SHA-256).

## 13. Receipts log format

Append-only file at `~/.hebe/receipts/YYYY-MM.log`. One JSON document per line (NDJSON). Hash-chained.

```
{
  "seq": 12345,
  "ts": "2026-05-04T08:42:13Z",
  "session_id": "...", "turn_id": "...",
  "tool": "shell",
  "args_redacted": {"cmd": "git status"},
  "risk": "Medium",
  "approval": {"required": false},
  "duration_ms": 23,
  "ok": true,
  "result_hash": "sha256:abc123…",
  "prev_hash": "sha256:def456…",
  "self_hash": "sha256:789…",
  "sig": "ed25519:base64url…"
}
```

- `prev_hash` = `self_hash` of the previous record in the file. First record uses the all-zero hash.
- `self_hash` = SHA-256 over the canonical-form record minus `self_hash` and `sig`.
- `sig` = Ed25519 over `self_hash` using the agent's signing key (generated on first boot, stored in `secrets.db` under `receipts.signing_key`).

Verification is `hebe memory show receipts/2026-05.log --verify`: walks the file, checks chain + signatures.

## 14. Web console API (REST + SSE)

All under `/api`. Auth: HTTP Basic against `web.password` secret. Origin-checked for browsers.

```
GET    /                                  HTML chat shell (HTMX or small Svelte)
POST   /api/messages                      { content, attachments[] } → { sessionId, turnId }
GET    /api/sessions/{id}/events          SSE stream of StreamEvent (text/event-stream)
POST   /api/approval/{id}                 { approved: bool } → { ok: true }
GET    /api/memory/search?q=…&k=…         [{ docPath, snippet, score, source }]
GET    /api/memory/tree?prefix=…          [string]
GET    /api/memory/doc?path=…             { path, content, ts }
GET    /api/receipts?since=…&limit=…      [Receipt]
GET    /api/receipts/verify               { ok, lastSeq, errors? }
GET    /api/plugins                       [{ id, version, status, capabilities, permissions }]
GET    /api/status                        { uptimeMs, channels: {...}, llm: {...} }
POST   /api/webhooks/telegram             Telegram webhook ingress (signed by Telegram)
POST   /api/webhooks/<channel>/<endpoint> Generic webhook ingress (HMAC shared secret)
POST   /api/estop                         emergency stop
```

SSE event shape (one frame per event):

```
event: text_delta
data: {"text": "hello "}

event: tool_call
data: {"id": "...", "name": "file_system_read", "argsRedacted": {...}}

event: approval_requested
data: {"id": "...", "tool": "shell", "argsRedacted": {...}, "expiresAt": "..."}

event: token_usage
data: {"in": 1234, "out": 56}

event: done
data: {}

event: error
data: {"message": "...", "retriable": true}
```

## 15. MCP server / client transport setup

**Server** (`mcp-server` module):

- stdio: `hebe mcp serve` reads JSON-RPC framed messages from stdin, writes to stdout.
- HTTP/SSE: when `mcp.server.http_bind` is set, hebe's gateway exposes `/mcp/sse` (SSE transport from MCP Kotlin SDK) and `/mcp/ws` (WebSocket transport).
- Tools advertised: every entry in `ToolRegistry` whose `risk = Low | Medium`. `High` + `requiresApproval` tools are gated behind a feature flag (`mcp.server.expose_high_risk = false` default).

**Client** (`tools/mcp-client` module):

- For each `[[mcp.client.servers]]` entry, spawn via the configured transport.
- Tools imported with name `mcp_<server>_<tool>`.
- Filter groups:
  - `always_tools = ["..."]` → exposed every turn.
  - `dynamic_tools = ["..."]` + `dynamic_keywords = ["..."]` → exposed only when one of the keywords appears in the user's incoming message.
- Per-server credential injection: `[[mcp.client.servers]]` may declare `secrets = { ENV_NAME = "secret_handle" }` so the host injects env when launching stdio servers.

## 16. Telegram channel contract

- Auth: `bot_token_secret` resolved from `secrets.db`.
- Operator gate: `operator_telegram_id` in config; any `Update` whose `from.id` ≠ operator is rejected at the adapter, logged at INFO, never reaches `ChannelManager`.
- Inbound: long-poll by default (`getUpdates`); webhook variant when `web` is publicly addressable.
- Outbound:
  - `reply` → `sendMessage`.
  - `updateDraft` → `editMessageText` on the last assistant message; throttled to one update per 800 ms / 80 chars.
  - Errors mapped to `IncomingMessage` only when the error is actionable (rate-limit hits trigger a `ChannelHealth.Degraded` ping).
- Recursion guard: messages with `metadata.is_agent_broadcast = true` are not delivered back into the loop.
- v1 explicitly does not support inline approve/deny buttons (deferred); approvals are resolved via the web console or by replying with `/approve <id>` from CLI/Telegram.

## 17. CLI channel contract

A blocking REPL backed by the same `Channel` interface.

```
$ hebe run
hebe> hi
agent> Hi, Bora.
hebe> read README.md and summarise it
agent> [streams reply, possibly with [tool: file_system_read] inline annotation]
hebe> /compact
[compaction summary]
hebe> /approve 8a4f
agent> Approved. Continuing.
hebe> /quit
$
```

Slash commands map to `Submission.SystemCommand`. `Ctrl-C` once cancels current turn; twice exits.

## 18. Scheduler + heartbeat

Single-threaded job loop reading from `jobs(status='pending', trigger_at <= now)`.

- **Routines**: triggered by cron expression in `routines`. On fire, the engine inserts a `jobs` row of `kind=routine`, picks it up, runs through `JobDelegate` with the routine's body (skill or tool).
- **Maintenance jobs**: scheduled via cron entries in `[scheduler]`. Each maintenance kind has a dedicated job runner:
  - `summarisation` — rolling-window transcript → compressed summary appended to `MEMORY.md` if it crosses a threshold.
  - `fact_extract` — recent assistant outputs scanned for "remember X"/"the user prefers Y" patterns; high-confidence facts promoted to `MEMORY.md`.
  - `daily_digest` — generates `daily/YYYY-MM-DD.md` from the day's transcript + receipts.
  - `embedding_refresh` — finds chunks with NULL embeddings; runs the embedding provider in batches.
  - `failed_job_detection` — finds jobs stuck in `running` past their deadline, marks `stuck`, retries once (idempotency required for retryable kinds).
- **Heartbeat**: cron-driven; reads `HEARTBEAT.md`, runs a turn with a deterministic prompt; if the agent emits anything other than `OK`, the result is delivered to the configured `notify_channel` (CLI/web/Telegram), otherwise silent.

## 19. Boot sequence (deterministic init order)

```
1.  parse CLI args (subcommand + options)
2.  load ~/.hebe/config.toml; validate; resolve env-refs
3.  open OS keychain → get master key (with passphrase fallback)
4.  open secrets.db
5.  open hebe.db
    - run Flyway migrations (V1, V2, …) in order; abort on failure
6.  build Observer (logback + OTel exporter)
7.  build LlmProvider (OpenAiCompatProvider with secrets-resolved api_key)
8.  build MemoryStore (SqliteMemoryStore + WorkspaceFs)
    - run startup hygiene: scan workspace docs, ensure FTS index in sync
9.  build SkillRegistry: bundled-skills/* + ~/.hebe/skills/*
10. build PluginManager (PF4J)
    - load plugins from ~/.hebe/plugins/
    - on auto_pull entries, ORAS pull missing ones
    - failures here are non-fatal: log + continue, expose via doctor
11. build ToolRegistry = builtin tools + plugin tools + mcp-client tools
12. build SecurityPolicy (autonomy, workspace, command, leak detector, prompt guard)
13. build ToolDispatcher (registry + security + receipts + memory)
14. build HebeAgent (wraps koog with the above)
15. build ChannelManager
    - register CLI / Web / Telegram (whichever are enabled in config)
    - set up injectChannel
16. build Scheduler (heartbeat + routines + maintenance) — start after channels are up
17. build McpServer (stdio: bind to stdin/stdout if subcommand is `mcp serve`; else only HTTP/SSE/WS via gateway)
18. build WebGateway (Ktor)
    - mount channel webhook routes
    - mount /api/* console routes
    - bind to web.bind:web.port
19. signal-handler register (SIGTERM, SIGINT → graceful shutdown)
20. log "hebe ready"; start blocking on Ctrl-C / `/quit`
```

Graceful shutdown reverses the order: stop accepting new messages → drain in-flight turns (best-effort, with deadline) → mark pending approvals as expired → close gateway → close scheduler → close channels → close MCP server → flush observer → close memory + secrets DBs.

## 20. Error taxonomy + handling rules

```kotlin
// modules/api
sealed class HebeException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Config(message: String) : HebeException(message)
    class Provider(val retriable: Boolean, message: String, cause: Throwable? = null) : HebeException(message, cause)
    class Tool(val tool: String, val retriable: Boolean, message: String) : HebeException(message)
    class Plugin(val pluginId: String, message: String, cause: Throwable? = null) : HebeException(message, cause)
    class Security(message: String) : HebeException(message)
    class PolicyDenied(message: String) : HebeException(message)
    class Approval(message: String) : HebeException(message)
    class Memory(message: String) : HebeException(message)
    class Channel(val channel: String, message: String, cause: Throwable? = null) : HebeException(message, cause)
}
```

Rules:

- **Provider errors** with `retriable = true` → caller retries with capped backoff. `retriable = false` → surface to the user with a diagnostic, do not loop.
- **Tool errors** with `retriable = true` → loop detector decides if a retry is allowed; never silently retry the same call twice in a turn.
- **Plugin load errors** → non-fatal; log; remove the plugin from the registry; `doctor` reports.
- **Security / PolicyDenied** → never retried; emitted as a `ToolResult.Err` so the LLM can choose another path.
- **Memory errors during write** → fatal for the turn (we don't want the agent to think it remembered something it didn't); the loop returns `Failed`.
- **Channel errors during reply** → degrade the channel to `ChannelHealth.Degraded`; retry once; if still failing, fall back to logging + a structured `ToolResult.Err` for the originating turn.

## 21. Logging + observability conventions

- Log format: JSON via logback `JsonEncoder`. Required fields: `ts`, `level`, `logger`, `msg`, `session_id?`, `turn_id?`, `tool?`, `plugin?`, `channel?`, `trace_id?`, `span_id?`.
- Sensitive params are redacted before reaching the log. The redaction list is in `security` module: `api_key`, `apikey`, `token`, `secret`, `password`, `auth`, `bearer`, `signature`, `cookie`, `email`, `phone` (configurable).
- OTel: koog's exporter is used for spans inside the agent loop; we add hebe-specific spans for `dispatch.<tool>`, `memory.search`, `plugin.start`, `channel.reply`. Defaults: OTLP to `http://localhost:4318` if `OTEL_EXPORTER_OTLP_ENDPOINT` is set, otherwise no-op exporter.
- `hebe doctor --verbose` prints OTel + log path + last 50 events from the in-memory ring buffer.

## 22. Security checks (concrete sequencing)

For every inbound message and every tool call, the order of checks is fixed:

```
INBOUND (channel-side):
  1. dedup (replay guard) by external message id
  2. operator allowlist (Telegram operator id, web Basic auth, CLI is implicit)
  3. metadata.is_agent_broadcast === false (recursion guard)
  4. SubmissionParser.parse → Submission

TOOL CALL (dispatcher-side):
  1. loop detector fingerprint
  2. tool exists + within active skill's attenuated tool list
  3. autonomy level vs. tool.risk
  4. workspace boundary (for file_* tools)
  5. command policy (for shell)
  6. domain matcher (for http)
  7. prompt-injection guard on the original LLM output (run once at turn start, results cached)
  8. ApprovalGate.requestIfNeeded
  9. invoke
  10. leak detector on output
  11. receipts append + memory append + observer event
```

Failure at any step short-circuits with a structured `ToolResult.Err` to the LLM (so it can recover or stop), except for `ApprovalGate.requestIfNeeded` which returns `Pending`.
