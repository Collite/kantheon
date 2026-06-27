# hebe — architecture (synthesis)

A self-hosted Kotlin/JVM autonomous agent inspired by IronClaw and ZeroClaw, scoped for small-team use.

This document is the synthesised architectural blueprint. It supersedes the per-agent drafts under `docs/claude/`, `docs/gpt/`, `docs/gemini/`, `docs/minimax/`. It uses Claude's draft as the spine and folds in the most useful ideas from the other three. See `docs/plan/agent-diff.md` for the line-by-line comparison.

> **Change vs. the original brief.** The user revised `req.md` after reading the four drafts, then closed the open questions in the brainstorming round (see `hebe-brainstorming-responses.md`). Net effect:
> - **JVM only** — Kotlin/Native is dropped.
> - **No WASM** — drop Extism/Chicory and the WASIp1 plugin contract.
> - **Pluggable JVM modules** — third-party extensibility happens through (a) JVM plugin JARs loaded via **PF4J** (manifest + isolated classloaders, distributed via container registry), and (b) MCP servers as a separate process boundary for untrusted code.
> - **Single user, one process** — no multi-tenant design from day one. Drop `TenantScope` and per-user pairings as architectural concerns; one hebe instance per human.
> - **Channels for v1: Web Console + CLI + Telegram.** Email, Slack, WhatsApp pushed out of v1 entirely.
> - **LLM providers for v1: one OpenAI-compatible client (BYOK).** The user has an internal LLM Gateway that exposes the OpenAI protocol; the same client also covers OpenAI proper, Ollama, OpenRouter, Groq. Anthropic-native, Bedrock, Gemini, etc. all deferred — the gateway abstracts upstream choice.
> - **SOPs are v2.** v1 ships routines only.
> - **Plugin signature_mode default: `optional`.**

## TL;DR

- **Target: JVM**, single fat-JAR (`hebe.jar`). Native-image is a v2 distribution-format optimisation. See `docs/claude/docs/claws/stack-notes.md` for the stack reasoning.
- **Kernel ABI: five traits** — `LlmProvider`, `Channel`, `Tool`, `MemoryStore`, `Observer`. Everything else hangs off these. `Plugin` and `PluginHost` live in a sibling `plugin-api` module (not the kernel).
- **Agent core: koog** (JetBrains) wrapped behind a hebe-native facade so we can swap.
- **Extension model — three concentric rings:**
  1. **In-tree Kotlin modules** (trusted, compiled in).
  2. **JVM plugin JARs via PF4J** (semi-trusted, isolated classloaders + manifest, optional Ed25519 signature, distributed via OCI container registry — typically Azure Container Registry). See §8.
  3. **MCP servers** (untrusted by default, process boundary, transport = stdio/SSE/WS/Streamable HTTP).
- **Sandbox boundary for risky native execution** (shell, `kubectl`, browser): a thin "sidecar" subprocess wrapper using `firejail`/`bwrap`/Docker where available. v2.
- **Memory: SQLite** (FTS5 + sqlite-vec) with a markdown workspace. Postgres deferred to v2 (and likely beyond — single-user-one-instance keeps SQLite sufficient).
- **Channels for v1:** **Web Console + CLI + Telegram.** Email / Slack / WhatsApp deferred. (Confirmed in `hebe-brainstorming-responses.md` §1.3 + §7.)
- **LLM providers for v1:** **one OpenAI-compatible client, BYOK.** The user runs an internal LLM Gateway speaking the OpenAI protocol; the same client also drives OpenAI, Ollama, OpenRouter, Groq. Provider router / fallback / native Anthropic / Bedrock / Gemini all deferred — the gateway is responsible for upstream routing and failover.
- **Single user, one process.** No `TenantScope`, no multi-user pairings, no per-user agent personas. One hebe instance per human; multi-instance deployment is out of scope for the foreseeable future.
- **Security:** autonomy levels + workspace boundary + tool receipts (Ed25519, chained) + leak detector + approval gate. OS-level sandbox in v2. Plugin `signature_mode = optional` by default in v1.
- **MCP: first-class.** Server (expose hebe tools) and client (consume external MCP) on day one. With JVM-module plugins now the in-tree path, MCP becomes the primary cross-language extension story.
- **One mutation funnel** (`ToolDispatcher.dispatch`) lint-enforced from day one. (IronClaw's load-bearing rule; MiniMax also flagged it.)

## 1. Native vs JVM (the architectural question — closed)

**Decision: JVM.** Three of the four drafts (Claude, GPT, Gemini) converged on this; MiniMax pushed Native but with a JVM fallback. The user has now accepted JVM. The reasons that already mattered (no mature WASM host on Native, koog is JVM-first, Ktor server / Slack / Telegram / Jakarta Mail / JDBC are JVM-shaped) are now reinforced by the decision to drop WASM altogether.

GraalVM native-image stays open as a v2 distribution-format optimisation — separate from the language target.

## 2. Layered architecture

```
┌───────────────────────────────────────────────────────────────────────┐
│ L7  Edges      CLI · Web (Ktor) · Telegram                            │
│                MCP server (stdio + HTTP/SSE/WS)                        │
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
│ L2  Tools      ToolDispatcher · built-ins · MCP client · JVM plugins   │
│                (PF4J classloader isolation) · subprocess wrapper [v2]  │
├───────────────────────────────────────────────────────────────────────┤
│ L1  Providers  LlmProvider trait · streaming                           │
│                OpenAI-compatible client (BYOK)                         │
├───────────────────────────────────────────────────────────────────────┤
│ L0  Storage    MemoryStore · SQLite (FTS5 + vec) · markdown workspace │
│                secrets (AES-GCM, OS keychain) · settings · receipts   │
└───────────────────────────────────────────────────────────────────────┘
```

## 3. Module layout

A multi-module Gradle build. Strict acyclic deps. The plugin host module replaces what was the WASM host in Claude's draft.

```
hebe/
├── settings.gradle.kts
├── build.gradle.kts                        ← root: versions, common conventions
├── modules/
│   ├── api/                                ← kernel ABI; no dependencies
│   │   ├── LlmProvider.kt, Channel.kt, Tool.kt, MemoryStore.kt, Observer.kt
│   │   └── (StreamEvent, ToolSpec, IncomingMessage, …)
│   ├── plugin-api/                         ← Plugin / PluginHost; depends on api
│   │   ├── Plugin.kt, PluginHost.kt
│   │   └── (capability gates: HttpClient, EnvReader, SecretLookup)
│   ├── core/                               ← agent loop wrapper around koog
│   │   ├── agent/, compaction/, hooks/, cost/, submission/
│   │   ├── dispatcher/                     ← ToolDispatcher (the mutation funnel)
│   │   ├── delegate/                       ← ChatDelegate, JobDelegate
│   │   └── classifier/                     ← optional
│   ├── memory/                             ← SQLite + workspace + retrieval
│   │   ├── sqlite/, none/, mock/           ← (postgres/ deferred to v2+)
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
│   ├── plugins/                            ← PF4J-based plugin host
│   │   ├── host/                           ← PF4J PluginManager wrapper + lifecycle
│   │   ├── manifest/                       ← plugin.toml schema (extends PF4J properties)
│   │   ├── signature/                      ← Ed25519 over plugin.jar
│   │   ├── capability/                     ← PluginHost impl + permission gates
│   │   └── distribution/                   ← OCI/ACR pull + local cache
│   ├── channels/
│   │   ├── api/                            ← shared `IncomingMessage`, helpers
│   │   └── cli/, web/, telegram/           ← (slack/, email/, whatsapp/ deferred)
│   ├── providers/
│   │   └── openai_compat/                  ← single OpenAI-compatible client (BYOK)
│   ├── mcp_server/                         ← hebe-as-MCP-server
│   ├── gateway/                            ← Ktor server: console + webhook ingress
│   ├── scheduler/                          ← cron, routines, heartbeat, SOPs[v2]
│   ├── config/                             ← TOML schema + secrets store
│   ├── observability/                      ← OTel adapter
│   └── cli_app/                            ← `hebe` binary entry; argparse; subcommands
├── plugin-template/                        ← Gradle template for internal plugin authors
└── docs/                                   ← architecture, claws, brainstorming, plan
```

Dependencies flow downward. `api` has no deps. `plugin-api` depends on `api` only — third-party plugin authors compile against `plugin-api`. `core` depends on `api`, `memory`, `security`, `tools`. Channels depend on `api`. Plugin host depends on `plugin-api` + PF4J.

## 4. Kernel ABI

Five traits define the kernel; everything else is feature-flagged or pluggable. Unchanged from Claude's draft; included here for completeness because the rest of the document references them.

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
    suspend fun category(cat: MemoryCategory, /*…*/): /*…*/
    // + workspace ops: read/write/list/append; system_prompt assembly
}

interface Observer {
    fun event(e: ObserverEvent)
    fun span(name: String): Span
}
```

`api` is a pure interface module — zero deps besides kotlinx-serialization for the data classes. Plugin authors compile against `api` only.

## 5. The agent loop

We wrap koog rather than reimplement. Koog gives us provider-streaming protocol, history compression, retries, agent persistence, OTel — all of the "boring engine" parts.

We add (in `core/agent/`):

- `Submission` parsing layer (slash-commands, approvals, raw-text) before the dispatcher sees the message — IronClaw pattern, also called out by GPT.
- `ToolDispatcher.dispatch` — the single mutation funnel.
- hebe-flavoured hooks (`BeforeInbound`, `BeforeToolCall`, `BeforeOutbound`, `OnSessionStart/End`).
- The skill selector (deterministic prefilter — IronClaw style).
- Cost guard + loop detector + compaction ladder.
- `ChatDelegate` / `JobDelegate` / `WorkerDelegate` strategy split (IronClaw `LoopDelegate`).

```kotlin
class HebeAgent(
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

One agent loop per session. Parallelism happens at the scheduler level (background jobs, SOPs, routines). Path-scoped concurrent tools (Hermes idea) is a v2 enhancement.

## 7. Persistence layout

```
~/.hebe/
├── config.toml                  ← bootstrap config (env-resolvable refs)
├── secrets.db                   ← AES-256-GCM encrypted secrets, master key in OS keychain
├── hebe.db                    ← main DB: settings, sessions, memory, jobs
├── workspace/                   ← markdown filesystem
│   ├── README.md
│   ├── MEMORY.md
│   ├── HEARTBEAT.md
│   ├── IDENTITY.md, SOUL.md, USER.md, AGENTS.md
│   ├── BOOTSTRAP.md             ← deleted after onboarding
│   ├── context/, daily/, projects/
│   └── .system/settings/**      ← dual-writes from settings table
├── skills/                      ← user-authored markdown skill bundles
├── plugins/<name>-<version>/    ← PF4J plugin layout: pulled from OCI registry, then cached
├── receipts/                    ← Ed25519 tool-receipts log, append-only by month
│   └── 2026-04.log
└── logs/                        ← rotated structured-JSON logs
```

Three orthogonal layers, never collapsed (also called out in GPT's draft as "structured configuration model"):

1. **Bootstrap config** — `config.toml`, loaded before DB exists.
2. **DB-backed settings** — `settings` table; dual-writes to `workspace/.system/settings/*`.
3. **Encrypted secrets** — AES-256-GCM in `secrets.db`; master key in OS keychain.

Critical invariant (consistent across all four drafts): **LLM data is never deleted.** Reasoning, tool calls, messages — all retained. "Cleanup" means evicting in-memory caches; rows persist.

## 8. The plugin model — JVM modules via PF4J

Plugins extend hebe with new tools (and, later, channels / memory / observers). They run inside the hebe JVM in an isolated classloader managed by **PF4J** (Plugin Framework for Java), with capabilities gated by manifest declarations.

> **Adoption note.** The first round of this doc proposed a hand-rolled URLClassLoader-based loader with PF4J as a fallback. Closed in `hebe-brainstorming-responses.md` §6.2: **adopt PF4J directly.** The reasoning: classloader lifecycles are tricky, plugin discovery semantics are well-trodden ground, and we'd rather spend our novelty budget on the agent loop than on a plugin-loader spike.

### Layout

PF4J's standard plugin layout:

```
~/.hebe/plugins/my-plugin-0.1.0/
├── plugin.properties     ← PF4J manifest (id, version, provider, plugin class)
├── plugin.toml           ← hebe manifest (capabilities, permissions, signature)
└── classes/ + lib/       ← compiled classes + bundled deps (PF4J convention)
```

PF4J also accepts a single fat-JAR layout (`my-plugin-0.1.0.jar` with the manifest in `META-INF`); we support both, but the directory layout is preferred for plugins that bundle native libs or non-JVM resources.

### Distribution: container registry (OCI / ACR)

Closed in §6.7: **plugins are distributed as OCI artifacts via a container registry**, in the user's case Azure Container Registry (ACR). For v1 / v2 / near-term roadmap all plugins are internal — we are not designing a public marketplace.

This shapes the v1 distribution flow:

```
hebe plugin install <oci-ref>
  e.g.  hebe plugin install acr.example.com/hebe-plugins/linear:0.3.1

  1. Authenticate to registry (token / managed identity / az login chain).
  2. Pull OCI artifact → cache under ~/.hebe/cache/oci/<sha256>/.
  3. Verify Ed25519 signature against publisher_key (signature_mode permitting).
  4. Extract into ~/.hebe/plugins/<name>-<version>/.
  5. Register with PF4J PluginManager.
  6. Lifecycle: PF4J `start()` → hebe `init(host)` → tool/channel registration.
```

We use the [oras](https://oras.land) Java client (or a small wrapper around `ORAS` HTTP) for OCI pulls. ACR-specific auth uses `DefaultAzureCredential` semantics (env vars / managed identity / Azure CLI fallback).

### Manifest (`plugin.toml`)

This is layered on top of PF4J's `plugin.properties`. PF4J's properties handle `id`, `version`, `provider`, `plugin.class`. Our `plugin.toml` adds hebe-specific capability and permission metadata:

```toml
# Required: ABI pin
hebe_api_version = "0.1.x"

# What the plugin contributes
capabilities = ["tool"]                    # tool | skill (v1); channel | memory | observer (v2/L)

# What the plugin needs from PluginHost
permissions = ["http_client", "env_read", "secrets:linear_api_key"]

# Allowlist for http_client
allowlist_domains = ["api.linear.app"]

# Optional Ed25519 signature
signature = "base64url..."
publisher_key = "hex..."
```

### Plugin contract

```kotlin
// modules/plugin-api/  — depends only on api/

abstract class HebePlugin(wrapper: PluginWrapper) : org.pf4j.Plugin(wrapper) {
    open fun tools(host: PluginHost): List<Tool> = emptyList()
    open fun channels(host: PluginHost): List<Channel> = emptyList()
    open fun memoryStores(host: PluginHost): List<MemoryStore> = emptyList()
    open fun observers(host: PluginHost): List<Observer> = emptyList()
}

// PluginHost is the only seam through which a plugin reaches host-side facilities.
// Capabilities are gated here, NOT by stdlib reachability.
interface PluginHost {
    fun http(): HttpClient        // requires "http_client"; allowlist-enforced
    fun env(): EnvReader          // requires "env_read"
    fun secrets(): SecretLookup   // requires "secrets:<name>"
    fun workspace(): WorkspaceFs  // requires "file_read" / "file_write" (v2)
    fun memory(): MemoryClient    // requires "memory_read" / "memory_write" (v2)
    fun observer(): Observer
}
```

### Loading mechanism (delegated to PF4J)

- PF4J creates one classloader per plugin; we configure it as **child-first for the plugin's own classes and `lib/` JARs, parent-first for `hebe-api` and `hebe-plugin-api`**. This prevents transitive-dep collisions with the host while ensuring the plugin sees a single canonical copy of our API types.
- The host classpath exposed to plugins is **only `hebe-api` + `hebe-plugin-api`**. `koog`, `slack-bolt`, JDBC drivers, etc. are hidden — plugins that want HTTP go through `host.http()`, not Ktor directly.
- Plugin discovery: PF4J's annotation-based scan (`@Extension`) for tool/channel implementations, plus the `HebePlugin.tools()` collection method as the explicit registration path. Both mechanisms work; explicit registration is preferred because it documents the contributions in one place.
- Lifecycle: PF4J's `created → resolved → started → stopped → unloaded`. We hook `started` to call `HebePlugin.tools(host)` and register; `stopped` to deregister; `unloaded` to close PF4J's classloader. JVM class unloading is best-effort, so we warn on plugin update that a restart is recommended for prod.

### v1 capability set

Closed in §6.3. Three permissions ship in v1:

- `http_client` — gated `HttpClient` injected by the host; URL host/path validated against `allowlist_domains` before the request leaves the JVM.
- `env_read` — read-only access to a curated subset of env vars (no `*_TOKEN`, `*_SECRET`, `*_KEY` patterns leak through).
- `secrets:<name>` — host injects the named secret value into request headers / wherever; the plugin sees the *handle*, not the value.

`file_read` / `file_write` / `memory_read` / `memory_write` deferred to v2.

### Trust model

JVM modules are **not** sandboxed in the strict WASM sense — a malicious plugin can call `Runtime.getRuntime().exec`, read arbitrary files, mutate static state. PF4J classloader isolation buys us version-conflict insulation, not security isolation. We mitigate, not eliminate:

1. **Internal-only distribution.** Plugins come from the user's own ACR; supply-chain attack surface is the registry, not a public marketplace.
2. **Manifest declarations.** Plugin states up front which capabilities it wants; the user reviews on install.
3. **Capability gates at the `PluginHost` boundary.** `http_client` permission is checked when the plugin calls `host.http()`. If not declared, the plugin gets a stub that throws.
4. **Domain allowlists.** `host.http()` enforces the manifest's `allowlist_domains`.
5. **Credential injection at the boundary.** Secrets are injected by the host into outgoing requests; the plugin never sees the secret value.
6. **Output scanning.** `LeakDetector` scans plugin output for secret patterns before it's surfaced.
7. **Ed25519 signature verification.** `signature_mode = optional | required | disabled`. **Default v1: `optional`** (closed in §6.4). Unsigned plugins log a warning but load. Production deployments should set `required`.
8. **For untrusted code: use MCP, not a plugin.** Recommended posture in our docs. MCP servers are a separate process — kernel-level isolation, not classloader-level.

**The trust posture is: JVM plugins are for first-party / trusted-team extensions you'd be willing to compile in.** Anything you wouldn't trust inside your JVM should be an MCP server.

## 9. The skill model

Unchanged from Claude's draft. Skills are markdown bundles; selection is a deterministic prefilter (IronClaw); skill trust ceiling attenuates the tool list per turn.

```
skills/my-skill/
├── SKILL.md               ← required: YAML frontmatter + body
├── scripts/               ← optional helpers (NOT executed by hebe; they're docs)
└── references/            ← optional reference material
```

Frontmatter / scoring are described in §9 of Claude's original; ported verbatim.

## 10. Channel model

The `ChannelManager` merges N `Flow<IncomingMessage>`s into one and owns an `injectChannel: Channel<IncomingMessage>` (capacity 64) so background producers (heartbeat, routines, MCP server) push into the same flow without being a full `Channel`.

`ExternalThreadId` is a sealed type with `TrustedThreadId` and `UntrustedThreadId` — pick the right constructor at the boundary.

**v1 channel set:** Web Console, CLI, Telegram. That is the entire channel scope for v1.

- **Web Console** — Ktor SSE + WebSocket. Doubles as our primary debugging surface; see §15.
- **CLI** — local interactive REPL. Fastest iteration loop while building.
- **Telegram** — TelegramBots library; webhook + long-poll. Draft-update support via `editMessageText`.

Slack, Email, WhatsApp, Discord are explicitly **not** in v1. The decision (`hebe-brainstorming-responses.md` §1.3 + §7) is "ship the core agent loop with one chat channel, add more later." Email and Slack are the most likely v2 additions when the core is steady.

Single-user note: because hebe runs one-instance-per-human, channel pairing and `allowed_users` allowlists collapse to "is this the configured operator?" The `senderId` / `userId` distinction stays in the data model (we still want to reject DMs from strangers), but there is no multi-user resolution table.

## 11. Security architecture

Six concentric rings (outer to inner):

1. **Channel pairing + allowlist** — at the adapter, before runtime sees the event.
2. **Autonomy level** — `ReadOnly | Supervised | Full` (+ `YOLO` preset). Each tool's `risk` matched against level.
3. **Workspace boundary** — `forbidden_paths` always blocked; tool `file_*` calls confined to workspace by default.
4. **Command policy** — `allowed_commands` / `forbidden_commands` + pattern-based validator before shell exec.
5. **Subprocess wrapper for risky native execution (v2)** — `firejail`/`bwrap`/Docker for shell + browser. JVM plugins run in-process (with classloader isolation, see §8); the subprocess wrapper is for *native* commands, not for our plugins.
6. **Tool receipts** — Ed25519 signed, hash-chained, on disk. Source of truth for "what did the agent do" (ZeroClaw pattern; MiniMax also lists this).

Plus cross-cutting:

- **Prompt-injection guard** — pattern-scan model output before tool dispatch.
- **Leak detector** — pattern-scan outbound for secret formats; block on hit.
- **OTP gate** — TOTP per-action, configurable. v2.
- **Emergency stop** — `hebe estop` halts in-flight; resume requires OTP if configured.
- **Sensitive-param redaction** — auto-redact known param names in logs/UI.

The risk MiniMax flagged (kubectl is a footgun) sticks: `kubectl` ships as `RiskLevel.High` with `requiresApproval = true` for any mutating verb (`apply`, `delete`, `exec`, `scale`, `patch`, `replace`, `port-forward`, `rollout`, `cordon`, `drain`, `uncordon`, `taint`, `label`). Read-only verbs (`get`, `describe`, `logs`, `top`, `events`, `version`) are `Medium` (auto-allowed in `Supervised`).

## 12. The agent loop strategy split

Following IronClaw's `LoopDelegate`:

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
class JobDelegate(...) : LoopDelegate  { /* background; arc-based ownership; can plan */ }
class WorkerDelegate(...) : LoopDelegate{ /* containerised job; sequential tool exec */ }
```

`runAgenticLoop(delegate, reasoning, ctx, config) → LoopOutcome` is the shared driver. Variants: `Response(String)`, `Stopped`, `MaxIterations`, `Failure(String)`, `NeedApproval(PendingApproval)`, `AuthPending(String)`.

`HandleOutcome::Pending` (turn paused, no `Done` emitted) is distinct from `NoResponse` (turn done, no text). The web-console SSE safety net depends on this distinction.

## 13. Memory architecture

`MemoryStore` is the trait; SQLite the default impl.

GPT's "five-tier memory" framing is useful as documentation even though Claude's draft already covered the same ground; folding it in for clarity:

| Tier | Name | Purpose | Storage |
|---|---|---|---|
| 0 | Live working set | Current turn context + active task state | In-memory + checkpointable |
| 1 | Durable transcript log | Full conversations, tool calls, approvals, jobs | `messages` table |
| 2 | Curated workspace memory | `MEMORY.md`, identity files, runbooks | Markdown FS |
| 3 | Derived knowledge | Summaries, daily logs, heartbeat outputs | Workspace + structured tables |
| 4 | Retrieval projections | FTS index + embeddings | `memory_chunks_fts`, `memory_chunks_vec` |

```
SQLite schema (Flyway-migrated):
  conversations(id, channel, user_id, started_at, ended_at)
  messages(id, conv_id, role, content, tool_calls, ts, …)
  memory_docs(path PK, content, ts, scope)             ← markdown FS view
  memory_chunks(doc_path, chunk_idx, content, embedding BLOB, ts)
  memory_chunks_fts (FTS5 virtual table)               ← keyword index
  memory_chunks_vec (sqlite-vec virtual table)         ← vector index
  settings(key PK, value)
  jobs(id, kind, status, started_at, …)                ← background scheduler
```

Hybrid retrieval (`memory.search(q, k)`): FTS5 + vector cosine + RRF fusion (`k₀ = 60`). Workspace API mirrors IronClaw — `read`, `write`, `append`, `list`, `search`, `appendMemory`, `appendDailyLog`, `systemPrompt`. Identity files always read from primary scope; multi-scope reads only for shared content.

**Scheduled internal management** (called out in `req.md` and emphasised in GPT's draft):

- transcript summarisation,
- fact extraction and preference updates,
- daily digest generation,
- stale task cleanup,
- embedding refresh / reindex,
- failed-job / failed-tool detection.

Implemented as `Routine` entries owned by the scheduler.

**Preemptive history pruning** (MiniMax's H8): trim history *before* it overflows the context window, not after. Default threshold 60% (Hermes uses 50%, IronClaw 80% — split the difference; configurable per-channel).

## 13a. LLM providers

Closed in `hebe-brainstorming-responses.md` §3.4 + §7: **v1 ships one OpenAI-compatible provider client. BYOK.** The user runs an internal LLM Gateway that exposes the OpenAI protocol; the same client also drives OpenAI proper, Ollama, OpenRouter, Groq, and any other OpenAI-compatible endpoint.

```kotlin
class OpenAiCompatProvider(
    private val baseUrl: String,         // e.g. https://gateway.example.com/v1
    private val apiKey: SecretLookup,    // BYOK; resolved from secrets store
    private val defaultModel: String,
) : LlmProvider { ... }
```

What this collapses out of v1:

- ❌ Native Anthropic adapter — gateway abstracts Anthropic upstream.
- ❌ Native Ollama / Bedrock / Gemini / Azure adapters — same reason, or covered by the OpenAI-compat shape.
- ❌ Provider router with hint-based model selection — gateway-side concern.
- ❌ Fallback chain on transport errors — gateway-side concern.
- ✅ The `LlmProvider` trait stays — wrapping koog into the trait keeps us swap-ready.
- ✅ Capability check (`streaming?`, `tool_use?`, `multimodal?`) stays — we still need to know what the configured endpoint supports.
- ✅ Cost tracking (per-call token counts → daily $ budget) stays. The budget is informational at the hebe layer; the gateway is the one seeing real provider invoices.

Config shape (`config.toml`):

```toml
[llm]
base_url = "https://llm-gateway.example.com/v1"
api_key  = "${KOKLYP_LLM_API_KEY}"        # env-resolved, written to secrets.db
default_model = "gpt-4o-mini"
```

If users without the gateway want to point straight at OpenAI: change `base_url` and `default_model`. If they want Ollama locally: same shape, `base_url = http://localhost:11434/v1`. The trait stays multi-provider-ready for v2; v1 just ships one client.

## 14. MCP integration

Both directions, day one. With JVM plugins now the in-tree extension path, MCP becomes the **primary cross-language extension story** — it's how someone in Python/Rust/Go ships capabilities to hebe.

- **Server** — hebe exposes its built-in tools as an MCP server. Transports: stdio (default), SSE (Ktor extension), WebSocket (Ktor extension), Streamable HTTP. Lets Claude Desktop / Cursor / Windsurf call hebe tools.
- **Client** — hebe consumes external MCP servers as tool sources. Tools imported with names prefixed `mcp_<server>_<tool>`.

MCP tool filtering (from ZeroClaw, also called out by MiniMax) prevents prompt-token explosion when MCP servers advertise hundreds of tools:

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

Auth: single password by default (HTTP Basic over TLS); OAuth optional in v2. Single-user / single-instance scope (one hebe instance per human; not multi-tenant).

UI tech: small SPA — HTMX or Svelte. Avoid React — wrong scope.

(Per Claude's argument, the web console is a *debugging tool* first, a user-facing UI second. v1 prioritises the receipts/event log/memory browser surfaces over settings UIs.)

## 16. Boot sequence

1. Parse CLI (subcommand: `run | onboard | service | doctor | tool | plugin | mcp | memory | pairing | estop | status | completion`).
2. Load `~/.hebe/config.toml`.
3. Build `AppComponents`:
   - `ConfigStore`, `SecretsStore` (master key from keychain), `Db`, `Observer`
   - `LlmProvider` (single `OpenAiCompatProvider` instance; BYOK from secrets)
   - `MemoryStore` (SQLite) + `Workspace`
   - `SkillRegistry` from bundled + user dirs
   - `PluginHost` (PF4J `PluginManager`) — pull-on-demand from OCI registry, load enabled plugins
   - `ToolRegistry` (built-ins + plugin-tools + MCP-client tools)
   - `ToolDispatcher`
   - `SecurityPolicy` (autonomy, workspace, command, prompt-guard, leak-detector)
   - `HebeAgent` (wraps koog)
   - `ChannelManager` + each enabled channel (web / cli / telegram) + `injectChannel`
   - `Scheduler` (heartbeat, routines, [v2] SOPs, scheduled memory maintenance)
   - `WebGateway` (Ktor)
   - `McpServer` (stdio + Ktor SSE/WS routes)
4. `app.run()` — start channels, start gateway, start scheduler, block on Ctrl-C / `/quit`.

Each `init_*` is module-owned. `cli_app/` only orchestrates.

## 17. Distribution

- **Fat JAR** via Gradle Shadow plugin.
- **`./hebe` shell wrapper** that runs `java -jar hebe.jar`.
- **Docker image** in v2.
- **Native-image (GraalVM)** in v2+ if size/cold-start matter.
- **Service registration** via `hebe service install` — generates and installs systemd unit / launchctl plist / Windows-Service definition.

## 18. Deferred / parked

- **WIT-typed plugins (component model)** and the entire WASM angle. Decision: not in hebe. If we ever need cross-language sandboxing, MCP carries that load.
- **SOP engine.** Closed: v2.
- **Slack, Email, WhatsApp channels.** Closed: not in v1. Email + Slack likely v2; WhatsApp later.
- **Native Anthropic / Bedrock / Gemini / Azure providers.** Gateway abstracts these; v2+ if we ever drop the gateway assumption.
- **Provider router and fallback chain.** Gateway concern; not hebe's.
- **Multi-tenant / multi-user.** Closed: out of scope. One hebe instance per human. `TenantScope` wrapper dropped from the architecture (was floated in the first round).
- **Tool versioning + rollback.** Closed: not v1 priority.
- **Plugin hot-reload.** Closed: not a priority. Plugin updates require a hebe restart.
- Knowledge graph, decay, consolidation, conflict, snapshots.
- Hardware (Peripheral trait, GPIO/I2C/etc).
- Tauri desktop app.
- Most of zeroclaw's 40+ channels — pick on demand.
- Skillforge (agent learns its own skills).
- ACP — defer; MCP-server overlap covers most use.
- Hermes-style "self-evolution" (Gemini's agent-writes-its-own-Kotlin-script idea). Interesting but research-quality.

## 19. Comparison summary: where hebe lands relative to the claws

| Dimension | IronClaw | ZeroClaw | hebe v1 (this plan) |
|---|---|---|---|
| Language | Rust | Rust | Kotlin/JVM |
| Plugin sandbox | Wasmtime + WIT/component model | Extism (Wasmtime + WASI P1 + JSON) | **JVM plugin JARs (PF4J + manifest, OCI/ACR distribution) + MCP for untrusted** |
| Channels (v1) | ~10 native + 5 WASM | 40+ native | 3 native: **Web Console + CLI + Telegram** |
| LLM providers (v1) | Multi-provider router | Multi-provider router | **One OpenAI-compatible client (BYOK; gateway-agnostic)** |
| Skills | Deterministic prefilter (IronClaw) | Mode-based + skillforge | Deterministic prefilter (IronClaw-style) |
| Memory | Workspace markdown + DB + RRF | SQLite/PG/Qdrant + KG + decay/consolidation | SQLite + workspace markdown + RRF + scheduled maintenance |
| Approval | per-tool `requires_approval` (v1) / capability lease (v2) | AutonomyLevel + `risk()` | AutonomyLevel + `risk()` |
| Receipts | ActionRecord (audit) | Ed25519 chained | Ed25519 chained |
| Multi-tenant | optional | not-default | **Single user, one process** (multi-tenant out of scope) |
| MCP | request-response only | first-class client | **first-class server + client** |
| Web UI | gateway + dashboard | gateway + dashboard + Tauri | web console (Ktor + small SPA) |
| Hardware | n/a | first-class | n/a |
| SOP engine | n/a (routines instead) | first-class | **routines v1, SOPs v2** |
