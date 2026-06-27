# hebe — feature list (synthesis)

Long-list of features synthesised from `req.md` (revised), the IronClaw / ZeroClaw / OpenClaw / Hermes analyses (`docs/claude/docs/claws/`), and the four agent drafts (`docs/claude/`, `docs/gpt/`, `docs/gemini/`, `docs/minimax/`).

Tier tags:

- **[v1]** — target the first usable release
- **[v2]** — target a follow-up; not on the critical path
- **[L]** — long-term / research / nice-to-have
- **[?]** — explicitly contested / undecided — see `hebe-brainstorming.md`

> **Deltas baked in (after `hebe-brainstorming-responses.md`):**
> - All WASM features removed. Plugins are JVM JARs loaded via **PF4J**, distributed via OCI container registry (ACR). See `hebe-architecture.md` §8.
> - **v1 channels: Web Console + CLI + Telegram.** Email/Slack/WhatsApp deferred to v2+.
> - **v1 LLM providers: one OpenAI-compatible client (BYOK).** Anthropic-native, Ollama-native, provider router, fallback chain — all dropped from v1; the user's LLM Gateway handles upstream routing.
> - **Single user, one process.** No multi-tenant features. `TenantScope` wrapper dropped.
> - **SOPs are v2.** v1 ships routines only.
> - **Plugin signature_mode default: `optional`** in v1.

---

## 1. Kernel ABI

| Feature | Tier | Notes |
|---|---|---|
| `LlmProvider` trait — `chat(req): Flow<StreamEvent>` | **v1** | Core abstraction. `StreamEvent`: `TextDelta`, `ToolCall`, `Done`, `Error`. ZeroClaw `Provider`. |
| `Channel` trait — `deliver`, `reply`, `updateDraft`, `supportsDraftUpdates` | **v1** | ZeroClaw-style. Distinguish `senderId` (raw) from `userId` (resolved). |
| `Tool` trait — `spec`, `invoke`, `risk` | **v1** | JSON-schema spec for the LLM; risk used by autonomy gate. |
| `MemoryStore` trait — append, load_context, search, category | **v1** | Hermes-style pluggable. Default impl: SQLite. |
| `Observer` trait — events, metrics, traces | **v1** | OpenTelemetry adapter. |
| One mutation funnel: `ToolDispatcher.dispatch` | **v1** | Lint-enforced via custom Detekt rule. (IronClaw + MiniMax both flagged this.) |
| Sealed `Submission` type for parsed inbound messages | **v1** | Slash-commands, approvals, user input parsed *before* the dispatcher sees them. |
| `HebePlugin` base class (extends `org.pf4j.Plugin`) — lives in sibling `plugin-api` module | **v1** | Contract for JVM plugin JARs. Methods return `tools/channels/memoryStores/observers`. PF4J handles classloader + lifecycle. |
| `PluginHost` SPI — gated host functions for plugins | **v1** | Capability gates at the boundary; `http_client`, `env_read`, `secrets:<name>` in v1. |

## 2. LLM providers

Closed in `hebe-brainstorming-responses.md` §3.4 + §7: v1 ships **one OpenAI-compatible client, BYOK.** The user's LLM Gateway (which speaks the OpenAI protocol) is the upstream routing layer; hebe doesn't try to duplicate that responsibility.

| Feature | Tier | Notes |
|---|---|---|
| `LlmProvider` trait — `chat(req): Flow<StreamEvent>` | **v1** | Kept for swap-readiness; only one impl ships. |
| OpenAI-compatible client (works against the user's LLM Gateway, OpenAI proper, Ollama, OpenRouter, Groq, …) | **v1** | Streaming + tool use + multimodal as available. Configured by `base_url` + BYO API key. |
| Capability check (`streaming?`, `tool_use?`, `multimodal?`) | **v1** | Don't issue requests the configured endpoint can't fulfil. |
| Cost tracking per call (token counts → daily $ budget) | **v1** | Informational at the hebe layer (the gateway is authoritative for billing). |
| Prompt caching (provider-side) | **v1** | If endpoint supports it (e.g. OpenAI/Anthropic-via-gateway prompt caching), opt in via config. |
| Native Anthropic / Bedrock / Gemini / Azure adapters | [L] | Gateway handles. Add only if we ever drop the gateway assumption. |
| Provider router (per-hint model selection) | [L] | Gateway concern. Not hebe's. |
| Fallback chain on transport errors | [L] | Gateway concern. Not hebe's. |
| `ProviderTransport` ABC (Hermes pattern) | [L] | Re-evaluate if we end up with multiple adapters. |

## 3. Agent loop

| Feature | Tier | Notes |
|---|---|---|
| Streaming end-to-end | **v1** | Provider streams tokens; channel.update_draft during; full message at Done. |
| Mid-stream tool calls (pause → validate → invoke → resume) | **v1** | Core agent shape. |
| Max-iteration guard | **v1** | Default 10/turn. |
| Loop detector (duplicate tool-call fingerprint) | **v1** | IronClaw-style: 3-warn / 5-force-text. ~200 LOC. (MiniMax H7.) |
| Cost guard (daily $ + per-turn budget) | **v1** | Explicit before/after contract. |
| Approval gate (Supervised + Medium/High) | **v1** | Operator response via originating channel. |
| Auth-mode interception (credential entry never enters chat history) | **v1** | Critical security. |
| `HandleOutcome::Pending` distinction | **v1** | Don't emit `Done` when waiting for approval. |
| Hooks: `BeforeInbound`, `BeforeToolCall`, `BeforeOutbound`, `OnSessionStart/End` | **v1** | Lifecycle pre/post. Fail-open. |
| `ChatDelegate` / `JobDelegate` / `WorkerDelegate` strategy | **v1** | Shared loop, swappable strategy (IronClaw `LoopDelegate`). |
| Path-scoped concurrent tools | **v2** | Hermes idea. Tools targeting independent workspace paths run concurrently. |
| Two-layer context (cheap base + expensive dialectic) | **v2** | Hermes idea. Independent cadences. |
| Compaction ladder (move-to-workspace → summarize → truncate) | **v1** | Three thresholds (60% default; channel-overridable). Refuse-to-truncate on summarisation failure. |
| Preemptive history pruning (trim BEFORE overflow) | **v1** | MiniMax H8 / Hermes pattern. |
| Manual `/compact` command | **v1** | |
| Self-repair (stuck-job detection + retry) | **v2** | IronClaw pattern; MiniMax also calls out. |
| Heartbeat (read `HEARTBEAT.md`, run a turn, notify on non-OK) | **v1** | OpenClaw-origin. Cheap; high value. Silence-on-OK. |
| Cron-triggered routines | **v1** | Lightweight. Fire a tool/skill on schedule. |
| Cron-triggered SOPs | **v2** [?] | Heavier; deterministic step-driven. See SOPs section. |
| Catchup execution for missed cron runs | **v2** | MiniMax flagged. |
| Auto-classification (route message → hint → model) | **v2** | Optional speed-up. Defer if it adds complexity. |

## 4. Skills

| Feature | Tier | Notes |
|---|---|---|
| Skills as markdown bundles (agentskills.io format) | **v1** | Frontmatter (name, description, activation, tags) + body. |
| Skill registry — bundled, user (`~/.hebe/skills/`), plugin-bundled | **v1** | Plugin-namespaced IDs `plugin:<name>/<skill>`. |
| Deterministic prefilter (LLM-free scoring; hard caps) | **v1** | IronClaw selector ported to Kotlin. Pure functions. |
| Skill trust ceiling (Installed < User < Bundled) | **v1** | Attenuates available tools by lowest trust. |
| Skill credentials (frontmatter declares; host registers) | **v1** | OAuth / API key wiring per skill. |
| Skill activation status emit (UI shows "loaded foo (chained from bar)") | **v1** | Audit-friendly. |
| On-demand skill loading (model decides) [?] | [L] | OpenClaw-style. Ad-hoc tool calls to load skill body. Adds latency. |
| Skillforge (agent learns skills from successful traces) | [L] | ZeroClaw research idea; Hermes-flavoured "self-evolution" (Gemini). Defer. |

## 5. Tools

| Feature | Tier | Notes |
|---|---|---|
| Built-in tool: `file_system` (read, write, list, glob) | **v1** | Workspace-bound. Markdown/json/yaml/html aware. |
| Built-in tool: `shell` | **v1** | Sandboxed; allow/deny lists; validator. Subprocess wrapper [v2]. |
| Built-in tool: `http` (RESTful APIs) | **v1** | Allowlisted domains. SSRF-safe. |
| Built-in tool: `web_search` | **v1** | `WebSearchProvider` trait. v1 ships **Brave + DuckDuckGo** (closed in §1.10). Brave default; DuckDuckGo as the no-key fallback. |
| Built-in tool: `memory_search`, `memory_write`, `memory_read`, `memory_tree` | **v1** | Memory-as-FS interface. |
| Built-in tool: `wiki_read`, `wiki_write` | **v1** | Markdown wiki bound to workspace. Could be just file_system + a convention. [?] |
| Built-in tool: `git` | **v1** | JGit (in-process) for read/diff/clone; shell-out for push and credential helpers. |
| Built-in tool: `github` | **v1** | gh-CLI shell-out OR direct API. Decide per req. |
| Built-in tool: `kubectl` (k3s/k8s cluster ops) | **v1** | Shell-out (or Fabric8 for in-process). Read-only verbs `Medium`; mutating verbs `High` + always-approve. |
| Built-in tool: `schedule` (create routine / cron / SOP) | **v1** | Tool that creates other tools' triggers. |
| Built-in tool: `ask_user` (clarifying question via originating channel) | **v1** | Hermes pattern. |
| Built-in tool: `tool_search` (deferred MCP tool discovery) | **v2** | MiniMax 3.3 — keeps prompt lean when MCP server count grows. Likely earned later if MCP server count grows; not v1. |
| Built-in tool: `job_create` / `job_status` / `job_cancel` | **v1** | Background jobs surface as tools. |
| Tool risk levels — Low / Medium / High / Always-approve | **v1** | Maps to autonomy. |
| `requiresApproval = true` per-tool override | **v1** | E.g. for shell, kubectl, git push. |
| Sensitive-param redaction in logs/UI (`api_key`, etc.) | **v1** | Auto-redact based on a denylist. |
| Tool dispatcher logging (every dispatch → ActionRecord) | **v1** | Single funnel. |
| Per-tool sliding-window rate limiting | **v2** | MiniMax 3.1. |
| Tool versioning + rollback | [L] | Closed in §3.7: not a v1 priority. |

## 6. Plugins (third-party JVM modules via PF4J)

Closed in `hebe-brainstorming-responses.md` §6.2 (PF4J), §6.4 (signature_mode optional), §6.6 (no hot-reload), §6.7 (OCI/ACR distribution).

| Feature | Tier | Notes |
|---|---|---|
| **PF4J as the plugin framework** | **v1** | `PluginManager`, `Plugin`, `Extension` from PF4J; classloader isolation provided. |
| `plugin-api` module (depends on `api` only) hosting `HebePlugin` + `PluginHost` | **v1** | Plugin authors compile against `plugin-api`, not the kernel. |
| Plugin `plugin.properties` (PF4J standard) + `plugin.toml` (hebe-specific) | **v1** | Properties: id/version/provider/plugin.class. TOML: capabilities/permissions/signature/allowlist_domains/hebe_api_version. |
| Capabilities: `tool`, `skill` | **v1** | Initial set. |
| Capabilities: `channel`, `memory`, `observer` | [L] | Reserved; ship when needed. Don't fake-implement. |
| Permissions: `http_client`, `env_read`, `secrets:<name>` | **v1** | Closed in §6.3. Three permissions are enough for v1. |
| Permissions: `file_read`, `file_write`, `memory_read`, `memory_write` | **v2** | Workspace-scoped. |
| Domain allowlist for `http_client` (manifest-declared) | **v1** | Host enforces against `allowlist_domains`. |
| Ed25519 plugin signature verification | **v1** | `signature_mode`: disabled / optional / required. **Default: optional** (§6.4). Required mode in production deployments. |
| **OCI / container-registry distribution** (`oras` Java client) | **v1** | Plugins published as OCI artifacts. ACR auth via `DefaultAzureCredential` chain. |
| `hebe plugin install <oci-ref>` (e.g. `acr.example.com/hebe-plugins/linear:0.3.1`) | **v1** | Pull → verify signature → cache → register with PF4J. |
| `hebe plugin install <local-path>` for local dev | **v1** | Sideloading from a local JAR / directory. |
| Plugin cache at `~/.hebe/cache/oci/<sha256>/` | **v1** | Content-addressed; safe to delete. |
| Plugin namespacing for skills (`plugin:<plugin>/<skill>`) | **v1** | |
| Plugin lifecycle (PF4J: created → resolved → started → stopped → unloaded) | **v1** | We hook `started`/`stopped` for tool registration; `unloaded` closes the classloader. |
| ABI version pinning per plugin | **v1** | TOML `hebe_api_version`; refuse incompatible. |
| Hot reload on plugin file change | [L] | Closed in §6.6: not a priority. Plugin updates → restart hebe. |
| Subprocess plugin runtime (process boundary instead of classloader) | [L] | If we ever need true isolation for a JVM plugin. Otherwise: that's what MCP is for. |
| Plugin marketplace / public registry | [L] | Out of scope for the foreseeable future; plugins are internal-only. |
| ~~WASM (Extism / Chicory)~~ | — | Removed. MCP carries the cross-language story. |
| ~~Custom URLClassLoader hand-roll~~ | — | Closed in favour of PF4J (§6.2). |

## 7. Channels

Closed in `hebe-brainstorming-responses.md` §1.3 + §7: **v1 ships Web Console + CLI + Telegram. Nothing else.**

| Feature | Tier | Notes |
|---|---|---|
| CLI / REPL | **v1** | Local interactive. Build first — fastest debugging surface. |
| Web console (HTTP + SSE/WS) | **v1** | See section 12. Ktor-served. Doubles as primary debugging surface. |
| HTTP webhook (generic inbound) | **v1** | Auth via shared secret. Used for Telegram webhook + future channels. |
| Telegram | **v1** | TelegramBots; webhook + long-poll. Draft updates via editMessageText. **The only chat channel in v1.** |
| Slack | **v2** | bolt-jvm, Events API + Socket Mode. Draft updates. |
| Email | **v2** | Jakarta Mail; SMTP send + IMAP IDLE for inbound. Polling fallback. |
| WhatsApp Cloud API | **v2+** | Requires verified business account. Operationally heavy. |
| Discord, Matrix, IRC, Signal, iMessage, Microsoft Teams | [L] | If asked. |
| ACP (Agent Client Protocol) | [L] | JSON-RPC 2.0 over stdio for IDE integration. Easy to add via MCP overlap. |
| Channel-side `tools_allow` allowlist | **v1** | Restrict tools per channel. |
| Single-operator allowlist on Telegram (no multi-user pairing) | **v1** | Single-user / one-instance scope (§3.2/3.6). The configured operator's Telegram ID is the only sender accepted. |
| `senderId` / `userId` distinction in `IncomingMessage` | **v1** | Kept for hygiene even though `userId` is single-valued. |
| `routingTargetFromMetadata` priority list | **v1** | Out-of-band reply targets. |
| `inject_tx`-style mpsc for background producers | **v1** | Heartbeat / scheduler / SOP push without being a Channel. |
| Recursion guards (`isAgentBroadcast`, `triggeringMissionId`) | **v1** | Prevent echo loops. |
| Hot-add channel at runtime | [L] | Restart-on-config-change is fine for v1. |
| `Channel.healthCheck()` | **v1** | Surfaced in `hebe doctor`. |
| Inline-button approval flow on Telegram | **v2** | Gemini's idea — `[Approve]` / `[Deny]` buttons. |
| Transcription middleware (audio → text) | [L] | OpenAI Whisper / local Whisper.cpp. |
| TTS middleware | [L] | For voice channels. |
| Cross-channel conversation continuity | [L] | Out of scope for single-instance v1. |

## 8. MCP (now the primary cross-language extension path)

| Feature | Tier | Notes |
|---|---|---|
| MCP client (consume external MCP servers) | **v1** | Use MCP Kotlin SDK. |
| MCP server (expose hebe tools to other agents) | **v1** | stdio + SSE/WebSocket via Ktor. |
| MCP tool filter groups (`Always` + `Dynamic` keyword-gated) | **v1** | ZeroClaw pattern. Keeps prompts slim. |
| MCP transports: stdio, SSE, WebSocket, Streamable HTTP | **v1** | All four offered by SDK. |
| MCP-server credential management (per-server config + secrets injection) | **v1** | Plugin-equivalent capability story. |
| OAuth flow for MCP servers | **v2** | Browser-based auth; MiniMax 10.1. |

## 9. Memory

| Feature | Tier | Notes |
|---|---|---|
| `MemoryStore` trait | **v1** | Pluggable backends (Hermes idea). |
| SQLite backend (default) | **v1** | FTS5 + sqlite-vec. |
| PostgreSQL backend (opt-in) | **v2** | pgvector. |
| Markdown workspace (`~/.hebe/workspace/`) | **v1** | Memory-as-FS. `MEMORY.md`, `IDENTITY.md`, `SOUL.md`, `USER.md`, `AGENTS.md`, `HEARTBEAT.md`, `daily/YYYY-MM-DD.md`. |
| Workspace tools surface as memory tools | **v1** | search/write/read/tree. |
| Identity files always read from primary scope | **v1** | Multi-scope security. |
| Hybrid search (FTS + vector + RRF k=60) | **v1** | One screen of code. |
| Embedding providers — OpenAI, Ollama, mock | **v1** | LRU cache wrapper. |
| Chunking — 800 words, 15% overlap, min 50 | **v1** | Sensible defaults; expose as config. |
| Hygiene — sanitise incoming writes | **v1** | Prompt-injection patterns rejected (IronClaw `Sanitizer`). |
| Compaction ladder (workspace → summarize → truncate) | **v1** | See agent loop. |
| Decay — older items lose importance | **v2** | Background job. |
| Consolidation — merge fragments | **v2** | Background job. |
| Conflict detection | **v2** | When new memory contradicts old. |
| Knowledge graph (entity/relation extraction) | [L] | Defer. |
| Snapshot — point-in-time memory captures | **v2** | For backup/debug. |
| Response cache (LRU on identical prompts) | **v1** | Cheap perf win. |
| Group-chat detection (exclude `MEMORY.md` from system prompt) | **v1** | |
| Multi-scope reads (`with_additional_read_scopes`) | **v2** | For team workspaces. |
| Memory category enum: Conversation/Fact/Preference/Skill/Document | **v1** | Useful for filtering. |
| External memory provider adapters (mem0, honcho, supermemory) | [L] | Hermes inspiration. |
| **Five-tier memory documentation** (Live / Transcript / Curated / Derived / Retrieval) | **v1** | GPT framing — informs naming and `/api/memory/*` shapes. |
| **Scheduled internal management jobs** (summarisation, fact extraction, daily digest, stale cleanup, embedding refresh, failed-job detection) | **v1** | The `req.md` requirement made concrete. |

## 10. Persistence (database)

| Feature | Tier | Notes |
|---|---|---|
| SQLite default at `~/.hebe/hebe.db` | **v1** | Closed in §1.8. |
| PostgreSQL opt-in via `DATABASE_URL` | [L] | Single-user/one-instance keeps SQLite sufficient. |
| Flyway migrations | **v1** | Versioned, JVM-standard. |
| Three-layer separation: bootstrap config / DB settings / encrypted secrets | **v1** | Don't collapse. |
| Secrets — AES-256-GCM, master key in OS keychain | **v1** | macOS Keychain / Linux secret-service / Windows Cred Mgr. Passphrase-derived fallback. |
| LLM-data retention invariant (no proactive deletion) | **v1** | Hard rule. Cleanup = evict caches; rows persist. |
| ~~Tenant scoping wrapper (`TenantScope`)~~ | — | Closed in §3.2 + §3.6 + §7: single user, one process. No multi-tenant scoping needed. |

## 11. Security

| Feature | Tier | Notes |
|---|---|---|
| Autonomy levels: ReadOnly / Supervised / Full | **v1** | + YOLO preset (loud, named). |
| Workspace boundary (paths inside workspace only by default) | **v1** | `forbidden_paths` always blocked (e.g. `/etc`, `~/.ssh`). |
| Command policy (allow/deny + validator pre-shell) | **v1** | Block patterns before exec. |
| Domain matcher for outbound HTTP | **v1** | Allowlist + denylist. |
| OS sandbox: subprocess wrapper (`firejail`, `bwrap`, Docker) for shell/browser/kubectl | **v2** | Process-level. JVM can drive these. |
| JVM plugin classloader isolation via PF4J (manifest-declared capabilities) | **v1** | NOT a sandbox — see architecture §8 for the trust model. `signature_mode = optional` default in v1 (closed in §6.4). |
| Tool receipts (Ed25519, hash-chained, on disk) | **v1** | Append-only `~/.hebe/receipts/YYYY-MM.log`. |
| Leak detector (scan outbound for secret patterns) | **v1** | Block on hit. |
| Prompt-injection guard (scan model output before tool call) | **v1** | Pattern-based. |
| OTP gating per-action | **v2** | TOTP via authenticator app. |
| Emergency stop (`hebe estop`) | **v1** | Halts in-flight tool calls. |
| WebAuthn for high-risk approvals | [L] | Yubikey/passkey for Production-class actions. |
| Pairing — channel device pairing | [L] | Single-operator allowlist on Telegram is enough for v1 (single-user/one-instance). |
| Sensitive-params redaction in logs/UI | **v1** | |

## 12. Web console

| Feature | Tier | Notes |
|---|---|---|
| Ktor server hosting | **v1** | |
| Browser UI for chat | **v1** | SSE for streaming; HTMX or small Svelte SPA. |
| Memory browser | **v1** | View workspace, search results. |
| Approval prompts in UI | **v1** | When agent calls a Medium-risk tool. |
| Settings editor | **v2** | |
| Cron / routine / SOP management | **v2** | |
| Tool inspection (last invocations + receipts) | **v1** | Auditable surface. |
| Plugin management (install / enable / disable) | **v2** | |
| Auth (single password by default; OAuth optional) | **v1** | Self-hosted small-team scope. |
| Job/routine monitor | **v1** | Surface scheduler state. |
| Channel / provider / secret status panel | **v2** | Operator surface. |

## 13. SOPs (Standard Operating Procedures)

Closed in §3.1 + §7: **SOPs are v2 only.** v1 ships routines (cron-triggered tool/skill calls) and that's it.

| Feature | Tier | Notes |
|---|---|---|
| SOP definition format (TOML + Markdown) | **v2** | |
| SopEngine (separate execution path from chat loop) | **v2** | |
| Triggers: manual / webhook / cron | **v2** | |
| Triggers: MQTT, peripheral | [L] | Hardware-shaped; defer. |
| Per-step approval | **v2** | |
| Audit log | **v2** | Same Ed25519 receipts pipeline. |
| `hebe sop run/list/validate` | **v2** | |

## 14. Operations

| Feature | Tier | Notes |
|---|---|---|
| `hebe run`, `agent`, `service`, `doctor`, `pairing`, `estop` subcommands | **v1** | |
| `hebe doctor` — config / providers / channels / sandbox detect | **v1** | Self-diagnose env issues. |
| `hebe service install/start/stop` (systemd/launchctl/Windows-Service) | **v1** | Don't make users write unit files. |
| Tunnel for public exposure (cloudflared, ngrok, tailscale, custom cmd) | **v2** | Pluggable; managed child process. |
| Onboarding wizard (`hebe onboard`) | **v1** | Picks LLM provider, wires first channel, generates default config. |
| OpenTelemetry exporters | **v1** | Via koog's built-in support. |
| Structured JSON logs | **v1** | Via kotlin-logging + logback. |
| Graceful shutdown (Ctrl-C, `/quit`) | **v1** | |
| Single binary distribution (fat JAR) | **v1** | |
| Native-image (GraalVM) for thin binary | [L] | Reflection config required; v2+. |
| Docker image | **v2** | |
| Helm chart for k8s | [L] | |
| Daemon mode + PID file + auto-start on boot | **v1** | MiniMax 14.1. |
| Backup / export of memory + config + skills | **v2** | |

## 15. Plugin / extension SDKs

Closed in §6.7: plugins are internal-only for v1, v2, and the foreseeable future, distributed via the user's container registry (ACR). The SDK is therefore an **internal Gradle template repo**, not a public-facing plugin marketplace.

| Feature | Tier | Notes |
|---|---|---|
| Tool development SDK (Kotlin in-tree) | **v1** | `Tool` interface + helpers. |
| **`plugin-template/`** Gradle template (Kotlin) | **v1** | `HebePlugin` skeleton, sample `Tool`, `plugin.toml`, `plugin.properties`, ACR-publish task. |
| ACR publish task in template (Gradle plugin or shell wrapper around `oras push`) | **v1** | One-command publish to internal registry. |
| Skill authoring guide (markdown bundle format) | **v1** | |
| New-channel guide | **v1** | "Add module, impl Channel, register factory." |
| New-provider guide | [L] | Single-provider story for v1; revisit if/when we drop the gateway assumption. |
| Project skeleton generator (`hebe scaffold tool foo`) | **v2** | |
| MCP-server scaffold guide | **v1** | Promote MCP as the cross-language path. |
| Public plugin marketplace / open registry | [L] | Out of scope. |

## 16. Testing / dev experience

| Feature | Tier | Notes |
|---|---|---|
| Mock LLM provider | **v1** | Replay-based. |
| Mock memory backend (in-memory) | **v1** | |
| Mock channel | **v1** | For e2e tests. |
| HTTP recording / replay (for trace tests) | **v1** | IronClaw `HttpInterceptor` analogue. |
| Detekt + ktlint for style | **v1** | |
| Custom Detekt rule for "don't bypass `ToolDispatcher.dispatch`" | **v2** | Pre-commit hook. |
| Integration tests with real DB (Testcontainers) | **v1** | |

## 17. Documentation surface

| Feature | Tier | Notes |
|---|---|---|
| `README.md` | **v1** | One-line install + minimal config. |
| Quickstart guide | **v1** | Up-and-running in 10 minutes. |
| Architecture doc (`docs/plan/hebe-architecture.md`) | **v1** | This already exists. |
| Per-channel setup guides | **v1** | Web console + CLI + Telegram. (Slack/email/WhatsApp added in v2.) |
| **JVM plugin protocol spec** (PF4J + manifest + ACR) | **v1** | Manifest, classloader rules, capability gates, lifecycle, OCI publish/pull flow. |
| MCP integration guide | **v1** | Both as server and client. |
| Security model doc | **v1** | Autonomy, sandbox, receipts, plugin trust posture. |
| RFC process for substantive changes | **v2** | Once contributors arrive. |

---

## What was *removed* relative to Claude's draft + first synthesis

- **All WASM features** (host, manifest format, host functions, fuel metering, WASI Preview 2 / component model).
- **Extism plugin host. Chicory plugin host. Native Wasmtime fallback.** Not relevant under the new direction.
- **WIT / component model.** Closed permanently — MCP carries the cross-language story.
- **Custom URLClassLoader hand-rolled plugin loader.** Replaced by PF4J (closed in §6.2).
- **Tenant scoping wrapper (`TenantScope`).** Closed in §3.2 + §3.6 + §7: single user, one process.
- **Native Anthropic / Bedrock / Gemini / Azure / OpenRouter adapters.** Gateway handles upstream routing; we ship one OpenAI-compatible client.
- **Provider router (per-hint model selection)** and **fallback chain.** Gateway concerns.
- **Slack / Email / WhatsApp channels for v1.** Pushed to v2+.
- **Plugin hot-reload.** Not a priority (§6.6).
- **Tool versioning + rollback for v1.** Not a priority (§3.7).
- **Multi-user pairing / `allowed_users` table.** Single-operator allowlist on Telegram is enough.

## Notes on what was *not* included (and stays out)

- **Hardware (GPIO/I2C/SPI/USB).** Out of scope.
- **Multi-tenant / SaaS.** Out of scope. One hebe instance per human.
- **Tauri desktop app.** Web console is enough.
- **40+ channels.** v1 ships three; pick the rest on demand.
- **Knowledge graph, decay, consolidation, conflict resolution, snapshots, dreaming.** Useful; deferable. v1 ships chunked memory + hybrid search + hygiene + scheduled maintenance jobs.
- **ACP (IDE integration).** MCP-server overlap covers it.
- **Public plugin marketplace.** Out of scope; plugins are internal-only.
- **Hermes-style "agent writes its own Kotlin script tools."** Research-quality; deferred indefinitely.
