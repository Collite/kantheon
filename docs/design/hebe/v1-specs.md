# hebe — v1 specs (scope)

This is the **scope contract** for hebe v1. It is the document we point at when deciding "is this in or out?"

Companion docs:

- [`v1-architecture.md`](v1-architecture.md) — concrete contracts, schemas, lifecycle.
- [`v1-tasks.md`](v1-tasks.md) — ordered, dependency-aware task list.

Source of upstream decisions: [`hebe-brainstorming-responses.md`](hebe-brainstorming-responses.md), [`hebe-architecture.md`](hebe-architecture.md), [`hebe-features.md`](hebe-features.md).

---

## 1. v1 thesis (what success looks like)

A single human can run `hebe run` and end up with **a personal autonomous agent** that:

1. Talks to them via **CLI, a self-hosted web console, or Telegram**.
2. Reasons via the **user's LLM Gateway** (or any OpenAI-compatible endpoint).
3. Reads and writes a **markdown workspace** that is the agent's persistent memory.
4. Calls a **curated set of built-in tools** (filesystem, shell, HTTP, web search, git, kubectl, memory, scheduling) under a strict autonomy/approval policy.
5. Surfaces a **tamper-evident receipts log** of everything it did.
6. Can be extended with **PF4J plugins pulled from a container registry** without recompiling hebe.
7. Speaks **MCP** as both server and client so external tools and IDEs can plug in.
8. Runs continuously with **scheduled routines + memory-maintenance jobs** without manual prodding.

If a human can use hebe daily for two weeks without us having to restart it, debug it from the JVM, or hand-edit the database — v1 is done.

## 2. In scope (must ship)

### 2.1 Runtime + agent loop

- One agent process per human; single-user, no `TenantScope` wrapper.
- koog wrapped behind hebe's `LlmProvider` / `HebeAgent` facade.
- Single mutation funnel `ToolDispatcher.dispatch`, lint-enforced (Detekt rule).
- `Submission` sealed-type parser before the dispatcher (slash-commands, approvals, raw input, auth-mode credential entry).
- `LoopDelegate` strategy split: **`ChatDelegate` v1**, `JobDelegate` minimal (used by scheduler), `WorkerDelegate` deferred.
- Hooks: `BeforeInbound`, `BeforeToolCall`, `BeforeOutbound`, `OnSessionStart/End`. Fail-open.
- Loop detector (3-warn / 5-force-text on duplicate tool-call fingerprint).
- Cost guard (per-turn + daily token-cost caps).
- Compaction ladder: workspace-promote → summarise → refuse-to-truncate. Trigger threshold default 60% of context, configurable.
- Preemptive history pruning (trim before overflow).
- Streaming end-to-end: provider → channel `updateDraft` (where supported) → final reply at `Done`.
- Mid-stream tool-call pause → validate → invoke → resume.
- Max-iteration guard (default 10 / turn).
- `HandleOutcome::Pending` distinct from `NoResponse`.
- Auth-mode interception (credential entry never enters chat history).

### 2.2 LLM provider

- One `LlmProvider` impl: **OpenAI-compatible client** (BYOK).
- Configured by `base_url` + `api_key` + `default_model`; works against the user's LLM Gateway, OpenAI proper, Ollama, OpenRouter, Groq.
- Streaming + tool use + capability check (`streaming?`, `tool_use?`, `multimodal?`).
- Token counting + per-call cost record into `llm_calls` table.

### 2.3 Memory

- `MemoryStore` trait + SQLite backend (`~/.hebe/hebe.db`).
- FTS5 + sqlite-vec; Reciprocal Rank Fusion at `k₀ = 60`.
- Markdown workspace (`~/.hebe/workspace/`) with `MEMORY.md`, `IDENTITY.md`, `HEARTBEAT.md`, `daily/YYYY-MM-DD.md`.
- Workspace tools: `memory_search`, `memory_write`, `memory_read`, `memory_tree`, `wiki_read`, `wiki_write`.
- Embedding provider abstraction. v1 ships: OpenAI-compat embeddings (via the same gateway) + a mock provider for tests. Local Ollama embeddings count as OpenAI-compat.
- Chunking: 800 words, 15% overlap, min 50.
- LRU response cache.
- Hygiene scanner (sanitise inbound writes for prompt-injection patterns).
- Group-chat detection (exclude `MEMORY.md` from the system prompt for non-1:1 contexts — applicable when Telegram group support lands).
- Memory category enum (`Conversation | Fact | Preference | Skill | Document`).
- Five tiers documented (Live / Transcript / Curated / Derived / Retrieval).

### 2.4 Scheduled internal management (memory + ops)

Implemented as `Routine` rows owned by the scheduler. v1 ships:

- Transcript summarisation (rolling window).
- Fact / preference extraction into `MEMORY.md`.
- Daily digest (`workspace/daily/YYYY-MM-DD.md`).
- Stale-task cleanup.
- Embedding refresh / reindex.
- Failed-job / failed-tool detection.
- Heartbeat (`HEARTBEAT.md` driven; silence-on-OK).

### 2.5 Channels

**v1 channel set: Web Console + CLI + Telegram. Nothing else.**

- **CLI** — local interactive REPL, builds against `Channel`. Slash-commands (`/quit`, `/compact`, `/approve`, `/help`).
- **Web Console** — Ktor server with SSE for streaming, basic HTML/HTMX or small Svelte SPA. Routes:
  - `GET /` — chat UI
  - `POST /api/messages` — submit message
  - `GET /api/sessions/{id}/events` — SSE stream
  - `POST /api/approval/{id}` — resolve approval
  - `GET /api/memory/search?q=…`
  - `GET /api/receipts?since=…`
  - `POST /api/webhooks/<channel>/<endpoint>` — channel webhook ingress
  - HTTP Basic auth over TLS, single-password.
- **Telegram** — TelegramBots library. Webhook + long-poll. Single-operator allowlist (configured operator's Telegram ID is the only sender accepted). Draft updates via `editMessageText`.
- `ChannelManager` merging + `injectChannel` (capacity 64) for background producers (heartbeat, scheduler, MCP server).
- Channel `healthCheck()` surfaced by `hebe doctor`.
- Recursion guards (`isAgentBroadcast`).

### 2.6 Built-in tools

| Tool | Risk | Notes |
|---|---|---|
| `file_system` (read/write/list/glob) | Low | Workspace-bound. Markdown/json/yaml/html-aware. |
| `shell` | High + always-approve | Allow/deny lists; pre-exec validator. No subprocess sandbox in v1. |
| `http` (RESTful APIs) | Medium | Allowlist of domains; SSRF-safe. |
| `web_search` | Low | `WebSearchProvider` trait. v1 providers: **Brave** (default if API key present) + **DuckDuckGo** (free fallback). |
| `memory_search` / `memory_read` / `memory_write` / `memory_tree` | Low / Medium write | Workspace-as-FS. |
| `wiki_read` / `wiki_write` | Low / Medium | Convention on top of `file_system`. |
| `git` | Medium read / High write | JGit in-process for read/diff/clone; shell-out for push + credential helpers. |
| `github` | Medium / High | API client; auth via PAT in secrets store. |
| `kubectl` | High + always-approve for mutating verbs | Shell-out. Read-only verbs (`get`, `describe`, `logs`, `top`, `events`, `version`) are Medium. Mutating verbs (`apply`, `delete`, `exec`, `scale`, `patch`, `replace`, `port-forward`, `rollout`, `cordon`, `drain`, `uncordon`, `taint`, `label`) are High + always-approve. |
| `ask_user` | Low | Clarifying question via originating channel. |
| `schedule` | Medium | Creates routine entries. |
| `job_create` / `job_status` / `job_cancel` | Low | Background-job control. |

All tools go through `ToolDispatcher`. Sensitive parameter names (`api_key`, `token`, `password`, `secret`, etc.) auto-redacted in receipts and UI.

### 2.7 MCP

- **MCP client**: consume external MCP servers as a tool source. Tools imported as `mcp_<server>_<tool>`.
- **MCP server**: expose hebe's built-in tools to other agents.
- Transports: stdio (default for both directions), SSE + WebSocket via Ktor.
- Tool filter groups: `Always` (advertise unconditionally) + `Dynamic` (advertise only when user message contains a keyword).
- Per-server credential injection at boundary; plugin/MCP-server never sees raw secret.

### 2.8 Plugin host (PF4J + ACR)

The PF4J spike is part of v1 — not a prelude. The first delivery is a "hello-world Tool plugin loaded from a local JAR"; the second extends to OCI pull from ACR with signature verification.

- PF4J `PluginManager` driving a `hebe` PluginManager wrapper.
- `plugin-api` module exposing `HebePlugin` + `PluginHost` + capability interfaces. `koog`, `slack-bolt` (when added), JDBC, etc. are *not* visible to plugins — only `api` + `plugin-api`.
- Plugin layout: directory or fat-JAR; `plugin.properties` (PF4J) + `plugin.toml` (hebe manifest).
- v1 capabilities exposed via `PluginHost`:
  - `http_client` — domain-allowlisted Ktor client.
  - `env_read` — curated env subset (filtered against `*_TOKEN | *_SECRET | *_KEY` patterns).
  - `secrets:<name>` — host-injected; plugin sees a handle, not the value.
- Ed25519 signature verification with `signature_mode = optional` default (warn on unsigned, load).
- Distribution: OCI pull from a container registry (Azure Container Registry first; the OCI client is generic so any registry works). Auth: Azure `DefaultAzureCredential` chain.
- `hebe plugin install <oci-ref>` and `hebe plugin install <path>` (sideload) and `hebe plugin list` and `hebe plugin remove <name>`.
- Plugin namespacing for skills: `plugin:<plugin>/<skill>`.
- ABI version pinned in manifest (`hebe_api_version`); incompatible plugins refuse to load with a clear error.

### 2.9 Skills

- Markdown bundles in agentskills.io format under `~/.hebe/skills/`.
- Frontmatter (name, description, activation keywords/patterns/tags, max_context_tokens) + body.
- Deterministic prefilter (LLM-free scoring) — IronClaw port.
- Skill trust ceiling (Installed < User < Bundled) attenuating the tool list.
- Bundled set in v1: 3–5 starter skills (TBD by content; e.g. `daily-briefing`, `code-review-prep`, `wiki-organiser`).

### 2.10 Security

- Autonomy levels: `ReadOnly | Supervised | Full` (+ `YOLO` preset, named loudly).
- Workspace boundary (paths outside the workspace blocked by default; `forbidden_paths` always blocked).
- Command policy (allow/deny + pre-shell pattern validator).
- Domain matcher for outbound HTTP.
- Tool receipts: Ed25519-signed, hash-chained, append-only on disk at `~/.hebe/receipts/YYYY-MM.log`.
- Leak detector on outbound (block on hit).
- Prompt-injection guard on model output before tool dispatch.
- Sensitive-param redaction in logs/UI.
- Emergency stop (`hebe estop`).
- Secrets at rest: AES-256-GCM in `secrets.db`, master key in OS keychain (macOS / secret-service / Windows Cred Mgr); passphrase-derived fallback.
- Three-layer separation: bootstrap config / DB settings / encrypted secrets — never collapsed.
- LLM-data retention invariant: nothing is proactively deleted.

### 2.11 Operations

- CLI subcommands: `run`, `onboard`, `service install/start/stop/uninstall`, `doctor`, `tool list`, `plugin install/list/remove`, `mcp serve`, `memory search/tree/show`, `pairing` (single-operator pairing), `estop`, `status`, `completion`.
- `hebe doctor` checks: config validity, LLM endpoint reachability, channel health, plugin manifests, sandbox (no-op in v1, presence detection), keychain access.
- `hebe service install` generates and installs systemd unit / launchctl plist / Windows-Service definition. Daemon mode + PID file.
- Onboarding wizard: pick LLM endpoint, paste API key, configure Telegram (optional), generate default config.
- OpenTelemetry exporters via koog.
- Structured JSON logs via kotlin-logging + logback.
- Graceful shutdown (SIGTERM, Ctrl-C, `/quit`).

### 2.12 Distribution + dev experience

- Single fat JAR via Gradle Shadow (`hebe.jar`), shell wrapper `./hebe`.
- Detekt + ktlint baseline + custom Detekt rule for `// dispatch-exempt:` discipline.
- Mock LLM provider (replay-based), mock memory, mock channel for tests.
- HTTP record/replay for trace tests (IronClaw `HttpInterceptor` analogue).
- Testcontainers integration tests where useful (Postgres deferred but Telegram bot + simulated MCP server fits here).

### 2.13 Documentation (v1)

- `README.md` — install + minimal config in 10 lines.
- Quickstart (10-minute happy path).
- `v1-specs.md` (this), `v1-architecture.md`, `v1-tasks.md`.
- Per-channel setup guide for Telegram.
- JVM plugin protocol spec (PF4J + manifest + ACR publish/pull flow).
- MCP integration guide (server + client).
- Security model doc.

## 3. Out of scope (explicit)

These ship in v2 or later. They are documented here so we don't accidentally pull them in.

- **Channels**: Slack, Email, WhatsApp, Discord, Matrix, Signal, iMessage, Microsoft Teams, IRC.
- **LLM**: native Anthropic / Bedrock / Gemini / Azure / OpenRouter adapters. Provider router. Fallback chain.
- **Memory**: PostgreSQL backend. Decay. Consolidation. Conflict detection. Snapshots. Knowledge graph. Multi-scope reads. External providers (mem0/honcho/supermemory).
- **Plugins**: hot-reload. Public marketplace. Capabilities `channel`/`memory`/`observer` (reserved). Permissions `file_read`/`file_write`/`memory_read`/`memory_write`. Tool versioning + rollback.
- **SOPs** entirely. (Routines yes, SOPs no.)
- **OS sandbox** (`firejail`/`bwrap`/Docker) for shell/browser/kubectl. Subprocess wrapper deferred.
- **OTP** gating, **WebAuthn**, channel device pairing.
- **Multi-tenant / multi-user**. `TenantScope`. Per-user pairings.
- **ACP** (IDE integration via JSON-RPC over stdio). MCP overlap covers it.
- **Skillforge** (agent learns its own skills). Hermes-style self-evolution.
- **Native-image** distribution (GraalVM). **Docker** image. Helm chart.
- **Tunnel management** (cloudflared/ngrok/tailscale).
- **Web console**: settings editor, plugin management UI, cron/routine management UI, channel/provider/secret status panel.
- **Hardware** (`Peripheral` trait, GPIO/I2C/SPI/USB).
- **Path-scoped concurrent tools**, **two-layer context**, **transcription/TTS middleware**.
- **Inline approval buttons** in Telegram.

## 4. Non-functional requirements

| NFR | Target | Verification |
|---|---|---|
| Cold start | < 5 s on a developer laptop (warm JVM cache) | Stopwatch in CI on a Mac mini reference machine |
| Per-turn latency overhead | < 250 ms hebe-side (excludes LLM) | OTel span budgets |
| Memory footprint (idle) | < 400 MB RSS | `ps` snapshot in `hebe doctor` |
| Memory footprint (active turn) | < 800 MB RSS | Same |
| SQLite size growth | Acceptable to indefinitely append; no proactive deletion | Manual review at end of v1 against a 30-day soak |
| Test coverage | > 70% line for `core`, `memory`, `security`, `plugins` modules | jacoco gate in CI |
| Detekt | Zero warnings on `main` | CI gate |
| Plugin load (cached, local) | < 500 ms | Bench in `hebe plugin install --dry-run` |
| Plugin pull (ACR cold) | < 10 s for a 5 MB artifact | Manual benchmark |
| Tool receipts append | Sub-millisecond append; fsync per N calls (configurable) | Bench |
| Workspace integrity | No corrupted writes after kill -9 mid-session | Stress test |

## 5. Acceptance criteria (definition of done)

v1 is shippable when **all** of the following pass on a clean machine:

1. `./hebe onboard` walks me through an LLM endpoint + Telegram setup and produces a working `~/.hebe/config.toml`.
2. `./hebe run` boots; `./hebe doctor` reports green for config / LLM / channels / keychain / plugins.
3. From the **CLI**, I can have a multi-turn chat that calls `file_system.read`, `web_search`, and `http` tools, with receipts written to disk and visible via `./hebe status --recent`.
4. From the **web console**, the same chat works with streaming over SSE, an approval prompt appears for `shell`, and I can resolve it from the UI.
5. From **Telegram**, the same chat works as the configured operator; messages from any other Telegram user are rejected.
6. Memory: `MEMORY.md` is loaded into the system prompt; an explicit "remember that I prefer X" produces a write; a follow-up question retrieves the fact via hybrid search.
7. **Plugin**: I can publish the in-tree `hello-world` plugin to a local OCI registry, then `hebe plugin install <ref>`, then call its tool from a chat turn. Same plugin loaded with `signature_mode = required` + a valid Ed25519 signature works; loaded without a signature it refuses.
8. **MCP server**: `hebe mcp serve --stdio` from Claude Desktop / Cursor lets that client call hebe's `file_system` tool.
9. **MCP client**: configuring an external MCP server (e.g. a stdio echo server) makes its tools available with `mcp_<server>_<tool>` names.
10. **Routines**: a cron-defined routine fires at the scheduled time, runs an `ask_user` round-trip, and writes its output to `daily/YYYY-MM-DD.md`.
11. **Heartbeat**: `HEARTBEAT.md` content drives a periodic turn; silence-on-OK is observed.
12. **Receipts**: every tool call is in `~/.hebe/receipts/YYYY-MM.log`, the chain hash verifies, and `hebe memory show receipts/2026-04.log --verify` returns OK.
13. **Service**: `./hebe service install --systemd` generates a unit; the unit starts hebe; `systemctl restart` cleanly stops and resumes (in-flight turns terminate cleanly, pending approvals are restored).
14. **Estop**: `./hebe estop` mid-tool-call halts the loop, leaves no zombie processes, and the receipts log records the abort.
15. Soak test: a 7-day continuous run with at least one routine firing daily, no manual intervention, no JVM crash, no DB corruption.

## 6. v1 milestones (high-level)

The detailed task breakdown is in `v1-tasks.md`. The milestone shape:

| # | Milestone | Brief |
|---|---|---|
| M0 | **Foundations** | Gradle multi-module skeleton, `api`, `plugin-api`, observability, config, Detekt rules, CI. |
| M1 | **Memory** | SQLite + Flyway, workspace, embeddings (mock + OpenAI-compat), chunker, RRF retrieval, hygiene, compaction, response cache. |
| M2 | **LLM + agent loop** | OpenAI-compat client wrapped behind `LlmProvider`, koog wrapped, dispatcher, hooks, `ChatDelegate`, loop detector, cost guard, submission parser. |
| M3 | **Security** | Autonomy levels, workspace boundary, command policy, leak detector, prompt-injection guard, approval gate, Ed25519 receipts, sensitive-param redaction, secrets store, OS keychain. |
| M4 | **Built-in tools** | `file_system`, `shell`, `http`, `web_search` (Brave + DDG), `memory_*`, `wiki_*`, `git` (JGit), `github`, `kubectl`, `ask_user`, `schedule`, `job_*`. |
| M5 | **Channels** | `Channel` API + manager + `injectChannel`. CLI channel + REPL. Web console (Ktor + SSE + chat UI + receipts viewer + memory browser). Telegram channel (webhook + long-poll + draft updates + single-operator allowlist). |
| M6 | **PF4J spike → production loader** | Spike: load a hello-world plugin from a local JAR. Production: PF4J `PluginManager` wrapper, manifest parser, capability gates, signature verification, OCI/ACR pull. CLI subcommands. ABI versioning. |
| M7 | **MCP** | Server (stdio + SSE/WS via Ktor). Client (consume external MCP, filter groups). Per-server credential injection. |
| M8 | **Scheduler + heartbeat** | Cron engine, routine table, scheduled-maintenance jobs (transcript summary, fact extraction, daily digest, embedding refresh, failed-job detection), heartbeat. |
| M9 | **Operations** | `hebe doctor`, `hebe service install`, `hebe onboard`, fat-JAR build via Shadow, structured logs, OTel wiring. |
| M10 | **Hardening + docs** | Soak test, security review, README + quickstart + plugin protocol spec + MCP integration guide + per-channel setup + security model doc. |

Milestones are loosely sequential — M2 depends on M1; M4 depends on M2; M5 channels can start once M2 has a working dispatcher; M6 PF4J can start as early as M0 once `plugin-api` exists; M7 can start after M2; M8 after M4. M10 is continuous.

The first three months target M0–M5. PF4J spike (M6's first deliverable) starts in parallel after M0. The next two months bring M6–M9 to completion. M10 runs throughout the last month.

## 7. Risks tracked for v1

(Pulled from `hebe-brainstorming.md` §4 and updated after the responses.)

1. **Koog v1.0 may not have shipped.** Mitigation: facade pattern.
2. **Gateway latency.** The user's LLM Gateway is an extra hop. Mitigation: support direct OpenAI / Ollama configurations as fallback; OpenAI-compat client doesn't care.
3. **Embedding API costs through the gateway.** Aggressive `CachedEmbeddingProvider`; recommend Ollama-local embeddings as the default in onboarding.
4. **Tool-call thrash loops.** Loop detector.
5. **Prompt injection via memory writes.** Hygiene scanner with high-severity rejection.
6. **OS keychain on Linux** is finicky. Passphrase fallback.
7. **PF4J classloader leaks on plugin update.** Document the "restart for prod" guidance; warn loudly on update.
8. **OCI artifact format churn.** Pin to OCI distribution-spec 1.1; abstract the registry client behind a small interface so we can swap implementations.
9. **kubectl is dangerous.** Aggressive risk-tagging + always-approve on mutating verbs.
10. **Plugin signature UX friction.** `signature_mode = optional` default makes the trade-off explicit; docs make `required` the recommendation for prod.

## 8. What we explicitly bet on (and what falsifies the bet)

- **Bet: koog is the right agent runtime.** Falsified if we hit two or more cases where koog's history compression / tool-use protocol normalisation conflicts with our needs. Mitigation: facade so swapping is one-week work.
- **Bet: PF4J is enough.** Falsified if classloader bugs (resource visibility, transitive deps) consume more than a week of debugging. Mitigation: drop back to a thin URLClassLoader wrapper (~600 LOC, pre-designed in the first synthesis).
- **Bet: SQLite is enough.** Falsified if transcript writes start blocking under realistic load (this would be surprising). Mitigation: Postgres path is documented and the schema is written compatibly; DDL changes in Flyway are dialect-aware where they need to be.
- **Bet: One OpenAI-compatible client is enough.** Falsified if a v1 user wants Anthropic-native features (e.g. extended thinking, prompt caching beyond what the gateway exposes). Mitigation: trait stays multi-provider-ready; second adapter is a few hundred LOC.
- **Bet: Three channels is enough for v1.** Falsified if a stakeholder demands Slack or email before v1 ships. Mitigation: `Channel` API is the same; add a fourth channel module without touching core.
- **Bet: Plugins are internal-only.** Falsified if we suddenly want to onboard external plugin authors. Mitigation: signature_mode → required, add public registry semantics, write a public plugin protocol spec — none of this is in v1, but the `plugin-api` module is shaped to accommodate it.
