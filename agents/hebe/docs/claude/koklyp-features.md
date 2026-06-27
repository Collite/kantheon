# koklyp — feature list

Long-list of features, derived from `req.md` + the IronClaw / ZeroClaw / OpenClaw / Hermes analyses (see `docs/claws/`). Organized by subsystem with implementation notes.

Each feature is tagged:
- **[v1]** target the first usable release
- **[v2]** target a follow-up; not on the critical path
- **[L]** long-term / research / nice-to-have
- **[?]** explicitly contested / undecided — see `koklyp-brainstorming.md`

## 1. Kernel ABI

A small, stable set of traits that everything else hangs off. Pick this surface day one and lint for violations.

| Feature | Tier | Notes |
|---|---|---|
| `LlmProvider` trait — `chat(req): Flow<StreamEvent>` | **v1** | Core abstraction. `StreamEvent`: `TextDelta`, `ToolCall`, `Done`, `Error`. Modeled after zeroclaw `Provider`. |
| `Channel` trait — `deliver`, `reply`, `updateDraft`, `supportsDraftUpdates` | **v1** | Zeroclaw-style. Distinguish `senderId` (raw) from `userId` (resolved). |
| `Tool` trait — `spec`, `invoke`, `risk` | **v1** | JSON-schema spec for the LLM; risk used by autonomy gate. |
| `MemoryStore` trait — append, load_context, search, category | **v1** | Hermes-style pluggable. Default impl: SQLite. |
| `Observer` trait — events, metrics, traces | **v1** | OpenTelemetry adapter. |
| One mutation funnel: `ToolDispatcher.dispatch` | **v1** | All channels, web console, scheduler, LLM call dispatch through the same path. Lint-enforced via a custom Detekt rule. |
| Sealed `Submission` type for parsed inbound messages | **v1** | Slash-commands, approvals, user input parsed *before* the dispatcher sees them. Mirror `src/agent/submission.rs`. |

## 2. LLM providers

| Feature | Tier | Notes |
|---|---|---|
| Anthropic | **v1** | Native streaming + tool use. |
| OpenAI | **v1** | Native streaming + tool use. |
| Local (Ollama / OpenAI-compatible) | **v1** | One adapter covers Groq, Mistral, xAI, etc. |
| Provider router (per-hint model selection) | **v2** | Lightweight classifier picks cheap-vs-heavy. Optional. |
| Fallback chain | **v2** | Failover on transport errors / rate limits. |
| Capability check (`streaming?`, `tool_use?`, `multimodal?`) | **v1** | Don't issue requests a provider can't fulfil. |
| Bedrock, Gemini, Azure | [L] | Add when users ask. |
| `ProviderTransport` ABC: format conv. + transport per-provider, retries/cache shared | **v1** | Hermes pattern. Easier to test. |
| Prompt caching (Anthropic) | **v1** | Free 5× speedup on system prompt; trivial to wire. |

## 3. Agent loop

| Feature | Tier | Notes |
|---|---|---|
| Streaming end-to-end | **v1** | Provider streams tokens; channel.update_draft during; full message at Done. |
| Mid-stream tool calls (pause → validate → invoke → resume) | **v1** | Core agent shape. |
| Max-iteration guard | **v1** | Default 10/turn. |
| Loop detector (duplicate tool-call fingerprint) | **v1** | Ironclaw-style: 3-warn / 5-force-text. ~200 LOC. |
| Cost guard (daily $ + per-turn budget) | **v1** | Explicit before/after contract. |
| Approval gate (Supervised + Medium/High) | **v1** | Operator response via originating channel. |
| Auth-mode interception (credential entry never enters chat history) | **v1** | Critical security. |
| `HandleOutcome::Pending` distinction | **v1** | Don't emit `Done` when waiting for approval. |
| Hooks: `BeforeInbound`, `BeforeToolCall`, `BeforeOutbound`, `OnSessionStart/End` | **v1** | Lifecycle pre/post. Fail-open. |
| `ChatDelegate` / `JobDelegate` / `WorkerDelegate` strategy | **v1** | Shared loop, swappable strategy (ironclaw `LoopDelegate`). |
| Path-scoped concurrent tools | **v2** | Hermes idea. Tools targeting independent workspace paths run concurrently. |
| Two-layer context (cheap base + expensive dialectic) | **v2** | Hermes idea. Independent cadences. |
| Compaction ladder (move-to-workspace → summarize → truncate) | **v1** | Three thresholds (50/80/95% configurable). Refuse-to-truncate on summarization failure. |
| Manual `/compact` command | **v1** | |
| Self-repair (stuck-job detection + retry) | **v2** | Ironclaw pattern. |
| Heartbeat (read `HEARTBEAT.md`, run a turn, notify on non-OK) | **v1** | OpenClaw-origin idea. Cheap; high value. |
| Cron-triggered routines | **v1** | Lightweight. Fire a tool/skill on schedule. |
| Cron-triggered SOPs | **v2** | Heavier; deterministic step-driven. See SOPs section. |
| Auto-classification (route message → hint → model) | **v2** | Optional speed-up. Defer if it adds complexity. |

## 4. Skills

| Feature | Tier | Notes |
|---|---|---|
| Skills as markdown bundles (agentskills.io format) | **v1** | Frontmatter (name, description, activation, tags) + body. |
| Skill registry — bundled, user (`~/.koklyp/skills/`), plugin-bundled | **v1** | Plugin-namespaced IDs `plugin:<name>/<skill>`. |
| Deterministic prefilter (LLM-free scoring; hard caps) | **v1** | Ironclaw selector ported to Kotlin. Pure functions. |
| Skill trust ceiling (Installed < User < Bundled) | **v1** | Attenuates available tools by lowest trust. |
| Skill credentials (frontmatter declares; host registers) | **v1** | OAuth / API key wiring per skill. |
| Skill activation status emit (UI shows "loaded foo (chained from bar)") | **v1** | Audit-friendly. |
| On-demand skill loading (model decides) [?] | [L] | OpenClaw-style. Ad-hoc tool-calls to load skill body. Adds latency. |
| Skillforge (agent learns skills from successful traces) | [L] | Zeroclaw research idea. Defer. |

## 5. Tools

| Feature | Tier | Notes |
|---|---|---|
| Built-in tool: file_system (read, write, list, glob) | **v1** | Workspace-bound. Markdown/json/yaml/html aware. |
| Built-in tool: shell | **v1** | Sandboxed; allow/deny lists; validator. |
| Built-in tool: HTTP client (RESTful APIs) | **v1** | Allowlisted domains. SSRF-safe. |
| Built-in tool: web search | **v1** | Provider-pluggable: Tavily / Brave / DuckDuckGo. |
| Built-in tool: memory_search, memory_write, memory_read, memory_tree | **v1** | Memory-as-FS interface. |
| Built-in tool: wiki_read, wiki_write | **v1** | Markdown wiki bound to workspace. Could be just file_system + a convention. [?] |
| Built-in tool: git (clone, status, diff, branch, commit, push) | **v1** | Wrap libgit2 (jgit) or shell-out to git. |
| Built-in tool: github (PR, issue, repo) | **v1** | Use gh CLI shell-out OR direct API. Decide per req. |
| Built-in tool: kubectl (k3s/k8s cluster ops) | **v1** | Shell-out to kubectl. Risk-tag as High. |
| Built-in tool: schedule (create routine / cron / SOP) | **v1** | Tool that creates other tools' triggers. |
| Built-in tool: ask_user (clarifying question via originating channel) | **v1** | Hermes pattern. |
| Tool risk levels — Low / Medium / High / Always-approve | **v1** | Maps to autonomy. |
| `requiresApproval = true` per-tool override | **v1** | E.g. for shell, kubectl, git push. |
| Sensitive-param redaction in logs/UI (`api_key`, etc.) | **v1** | Auto-redact based on a denylist. |
| Tool dispatcher logging (every dispatch → ActionRecord) | **v1** | Single funnel. |

## 6. Plugins (third-party, sandboxed)

| Feature | Tier | Notes |
|---|---|---|
| Extism plugin host | **v1** | Chicory or native runtime; JSON in/out. |
| Plugin manifest.toml | **v1** | name, version, capabilities, permissions, signature, publisher_key. |
| Capabilities: `tool`, `skill` | **v1** | Initial set. |
| Capabilities: `channel`, `memory`, `observer` | [L] | Reserved; ship when needed. Don't fake-implement. |
| Permissions: `http_client`, `env_read` | **v1** | Host-fn gates. |
| Permissions: `file_read`, `file_write`, `memory_read`, `memory_write` | **v2** | Workspace-scoped. |
| Ed25519 plugin signature verification | **v1** | `signature_mode`: disabled / optional / required. |
| Plugin discovery — `~/.koklyp/plugins/<name>/` | **v1** | |
| `koklyp plugin install <path|url>` CLI | **v1** | |
| Plugin namespacing for skills (`plugin:<plugin>/<skill>`) | **v1** | |
| Plugin sandbox via Chicory (zero native deps) | **v1** | |
| Native Wasmtime fallback for performance | [L] | When Chicory perf becomes a bottleneck. |
| WASI Preview 2 / component model | [L] | When Chicory ships preview-2. |

## 7. Channels

| Feature | Tier | Notes |
|---|---|---|
| CLI / REPL | **v1** | Local interactive. |
| Web console (HTTP+WS) | **v1** | See section 12. Ktor-served. |
| HTTP webhook (generic inbound) | **v1** | Auth via shared secret. |
| Slack | **v1** | bolt-jvm, Events API + Socket Mode. Draft updates. |
| Telegram | **v1** | TelegramBots; webhook + long-poll. Draft updates via editMessageText. |
| WhatsApp Cloud API | **v1** | Raw HTTP via Ktor. Webhook ingress. |
| Email | **v1** | Jakarta Mail; SMTP send + IMAP IDLE for inbound. |
| Discord | **v2** | If a user asks. |
| Matrix, IRC, Signal | [L] | Federated/legacy; nice but not on critical path. |
| ACP (Agent Client Protocol) | **v2** | JSON-RPC 2.0 over stdio for IDE integration. Easy to add via MCP overlap. |
| Channel-side `tools_allow` allowlist | **v1** | Restrict tools per channel. |
| Channel pairing + `allowed_users` | **v1** | Allowlist before runtime sees the event. |
| `senderId` / `userId` distinction in `IncomingMessage` | **v1** | Pairing flow loadbearing. |
| `routingTargetFromMetadata` priority list | **v1** | Out-of-band reply targets. |
| `inject_tx`-style mpsc for background producers | **v1** | Heartbeat / scheduler / SOP push without being a Channel. |
| Recursion guards (`isAgentBroadcast`, `triggeringMissionId`) | **v1** | Prevent echo loops. |
| Hot-add channel at runtime | **v2** | Useful when user installs Slack mid-session. |
| `Channel.healthCheck()` | **v1** | Surfaced in `koklyp doctor`. |
| Transcription middleware (audio → text) | **v2** | OpenAI Whisper / local Whisper.cpp. |
| TTS middleware | [L] | For voice channels. |

## 8. MCP

| Feature | Tier | Notes |
|---|---|---|
| MCP client (consume external MCP servers) | **v1** | Use MCP Kotlin SDK. |
| MCP server (expose koklyp tools to other agents) | **v1** | stdio + SSE/WebSocket via Ktor. |
| MCP tool filter groups (`Always` + `Dynamic` keyword-gated) | **v1** | Zeroclaw pattern. Keeps prompts slim. |
| MCP transports: stdio, SSE, WebSocket, Streamable HTTP | **v1** | All four offered by SDK. |

## 9. Memory

| Feature | Tier | Notes |
|---|---|---|
| `Memory` trait | **v1** | Pluggable backends (Hermes idea). |
| SQLite backend (default) | **v1** | FTS5 + sqlite-vec. |
| PostgreSQL backend (opt-in) | **v2** | pgvector. |
| Markdown workspace (`~/.koklyp/workspace/`) | **v1** | Memory-as-FS. `MEMORY.md`, `IDENTITY.md`, `SOUL.md`, `USER.md`, `AGENTS.md`, `HEARTBEAT.md`, `daily/YYYY-MM-DD.md`. |
| Workspace tools surface as memory tools | **v1** | search/write/read/tree. |
| Identity files always read from primary scope | **v1** | Multi-scope security. |
| Hybrid search (FTS + vector + RRF k=60) | **v1** | One screen of code. |
| Embedding providers — OpenAI, Ollama, mock | **v1** | LRU cache wrapper. |
| Chunking — 800 words, 15% overlap, min 50 | **v1** | Sensible defaults; expose as config. |
| Hygiene — sanitize incoming writes | **v1** | Prompt-injection patterns rejected. |
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
| External memory provider adapters (mem0, honcho, supermemory) | [L] | Hermes inspiration. Trait makes this trivial later. |

## 10. Persistence (database)

| Feature | Tier | Notes |
|---|---|---|
| SQLite default at `~/.koklyp/koklyp.db` | **v1** | |
| PostgreSQL opt-in via `DATABASE_URL` | **v2** | |
| Flyway migrations | **v1** | Versioned, JVM-standard. |
| Three-layer separation: bootstrap config / DB settings / encrypted secrets | **v1** | Don't collapse. |
| Secrets — AES-256-GCM, master key in OS keychain | **v1** | macOS Keychain / Linux secret-service / Windows Cred Mgr. |
| Tenant scoping wrapper (`TenantScope`) for multi-user | **v1** | Auto-binds `userId` on every operation. |
| LLM-data retention invariant (no proactive deletion) | **v1** | Hard rule. Cleanup = evict caches; rows persist. |

## 11. Security

| Feature | Tier | Notes |
|---|---|---|
| Autonomy levels: ReadOnly / Supervised / Full | **v1** | + YOLO preset (loud, named). |
| Workspace boundary (paths inside workspace only by default) | **v1** | `forbidden_paths` always blocked (e.g. `/etc`, `~/.ssh`). |
| Command policy (allow/deny + validator pre-shell) | **v1** | Block patterns before exec. |
| Domain matcher for outbound HTTP | **v1** | Allowlist + denylist. |
| OS sandbox: subprocess wrapper (`firejail`, `bwrap`, Docker) | **v2** | Process-level. JVM can drive these via subprocess. |
| Tool receipts (Ed25519, hash-chained, on disk) | **v1** | Append-only `~/.koklyp/receipts/YYYY-MM.log`. |
| Leak detector (scan outbound for secret patterns) | **v1** | Block on hit. |
| Prompt-injection guard (scan model output before tool call) | **v1** | Pattern-based. |
| OTP gating per-action | **v2** | TOTP via authenticator app. |
| Emergency stop (`koklyp estop`) | **v1** | Halts in-flight tool calls. |
| WebAuthn for high-risk approvals | [L] | Yubikey/passkey for Production-class actions. |
| Pairing — channel device pairing | **v2** | Prevent stolen creds from working on a new device. |
| Sensitive-params redaction in logs/UI | **v1** | |

## 12. Web console

| Feature | Tier | Notes |
|---|---|---|
| Ktor server hosting | **v1** | |
| Browser UI for chat | **v1** | SSE for streaming; basic HTML+JS or a small Svelte/React SPA. |
| Memory browser | **v1** | View workspace, search results. |
| Approval prompts in UI | **v1** | When agent calls a Medium-risk tool. |
| Settings editor | **v2** | |
| Cron / routine / SOP management | **v2** | |
| Tool inspection (last invocations + receipts) | **v1** | Auditable surface. |
| Plugin management (install / enable / disable) | **v2** | |
| Auth (single password by default; OAuth optional) | **v1** | Self-hosted small-team scope. |

## 13. SOPs (Standard Operating Procedures) [?]

| Feature | Tier | Notes |
|---|---|---|
| SOP definition format (TOML + Markdown) | [?] | Open question whether to ship in v1. See brainstorming. |
| SopEngine (separate execution path from chat loop) | [?] | |
| Triggers: manual / webhook / cron | [?] | |
| Triggers: MQTT, peripheral | [L] | Hardware-shaped; defer. |
| Per-step approval | [?] | |
| Audit log | [?] | |
| `koklyp sop run/list/validate` | [?] | |

## 14. Operations

| Feature | Tier | Notes |
|---|---|---|
| `koklyp run`, `agent`, `service`, `doctor`, `pairing`, `estop` subcommands | **v1** | |
| `koklyp doctor` — config / providers / channels / sandbox detect | **v1** | Self-diagnose env issues. |
| `koklyp service install/start/stop` (systemd/launchctl/Windows-Service) | **v1** | Don't make users write unit files. |
| Tunnel for public exposure (cloudflared, ngrok, tailscale, custom cmd) | **v2** | Pluggable; managed child process. |
| Onboarding wizard (`koklyp onboard`) | **v1** | Picks LLM provider, wires first channel, generates default config. |
| OpenTelemetry exporters | **v1** | Via koog's built-in support. |
| Structured JSON logs | **v1** | Via kotlin-logging + logback. |
| Graceful shutdown (Ctrl-C, `/quit`) | **v1** | |
| Single binary distribution (fat JAR) | **v1** | |
| Native-image (GraalVM) for thin binary | [L] | Reflection config required; v2+. |
| Docker image | **v2** | |
| Helm chart for k8s | [L] | |

## 15. Plugin / extension SDKs (for users to develop new things)

| Feature | Tier | Notes |
|---|---|---|
| Tool development SDK (Kotlin in-tree) | **v1** | `Tool` interface + helpers. |
| Plugin development SDK (WASM via Extism) | **v1** | Rust/Go/TinyGo plugin templates. |
| Skill authoring guide (markdown bundle format) | **v1** | |
| New-channel guide | **v1** | "Add file, impl Channel, register factory." |
| New-provider guide | **v2** | |
| Project skeleton generator | **v2** | `koklyp scaffold tool foo` etc. |

## 16. Testing / dev experience

| Feature | Tier | Notes |
|---|---|---|
| Mock LLM provider | **v1** | Replay-based. |
| Mock memory backend (in-memory) | **v1** | |
| Mock channel | **v1** | For e2e tests. |
| HTTP recording / replay (for trace tests) | **v1** | Ironclaw `HttpInterceptor` analogue. |
| Detekt + ktlint for style | **v1** | |
| Custom Detekt rule for "don't bypass `ToolDispatcher.dispatch`" | **v2** | Pre-commit hook. |
| Integration tests with real DB (Testcontainers) | **v1** | |

## 17. Documentation surface

| Feature | Tier | Notes |
|---|---|---|
| `README.md` | **v1** | One-line install + minimal config. |
| Quickstart guide | **v1** | Up-and-running in 10 minutes. |
| Architecture doc (`docs/architecture.md`) | **v1** | This already exists as `koklyp-architecture.md`. |
| Per-channel setup guides | **v1** | Slack/Telegram/WhatsApp/email. |
| Plugin protocol spec | **v1** | Extism manifest + host fns. |
| MCP integration guide | **v1** | Both as server and client. |
| Security model doc | **v1** | Autonomy, sandbox, receipts. |
| RFC process for substantive changes | **v2** | Once contributors arrive. |

---

## Notes on what was *not* included

These are explicitly out of scope for v1, even though they're in the source claws:

- **Hardware (GPIO/I2C/SPI/USB).** Zeroclaw's `Peripheral` trait + firmware module. Not relevant to the koklyp brief.
- **Multi-tenant / SaaS.** Brief says self-hosted small-team. Don't design for multi-tenancy on day one; the `TenantScope` wrapper leaves a seam if needed.
- **Tauri desktop app.** Web console is enough.
- **40+ channels.** Pick the ones in the brief; defer the rest.
- **Knowledge graph, decay, consolidation, conflict resolution, snapshots, response cache, dreaming.** All useful; all deferable. v1 ships chunked memory + hybrid search + hygiene.
- **WIT / component-model plugins.** Not stable on JVM; Extism is enough.
- **ACP (IDE integration).** Useful but optional; MCP-server overlap covers most of the same use case.
