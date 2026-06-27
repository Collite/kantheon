# IronClaw Overview

Internal reference for the Kotlin port. Source tree: `/Users/bora/Dev/view-only/ironclaw/` (Rust 2024, MSRV 1.92, single binary `ironclaw`, version 0.26.0). Author: NEAR AI.

## What IronClaw is

A self-hosted "secure personal AI assistant" packaged as one Rust binary. Single-tenant by default with a multi-tenant code path; runs on a laptop, VPS, or Railway. Talks to the user over a tower of channels (CLI, REPL, web gateway, HTTP webhook, Signal, plus WASM-sandboxed Telegram/Slack/Discord/WhatsApp/Feishu), runs a tool-calling LLM loop, and persists everything (transcripts, memories, jobs, secrets, settings) in either PostgreSQL or libSQL/Turso.

Differentiating ideas:

- **Capabilities, not blanket permissions.** Tools and channels are sandboxed WASM components (Wasmtime + WIT component model) with explicit per-capability allowlists for HTTP, secrets, workspace reads, sub-tool invocation. Credentials injected at host boundary; WASM never sees secret values (`wit/tool.wit:8-13`).
- **Workspace-as-memory.** Persistent memory is a virtual filesystem of markdown documents indexed with hybrid keyword+vector search (RRF, k=60). Identity files (`AGENTS.md`, `SOUL.md`, `USER.md`, `IDENTITY.md`) are read into the system prompt every turn.
- **Skills as prompt extensions.** A `SKILL.md` file (YAML frontmatter + markdown) declares activation keywords/patterns/tags. A deterministic, **LLM-free** selector (`crates/ironclaw_skills/src/selector.rs`) decides which skills get injected per turn.
- **Everything goes through `ToolDispatcher::dispatch`** (`src/tools/dispatch.rs`). Web UI clicks, CLI commands, scheduled routines, the LLM, and WASM channels all funnel into the same pipeline (validation → safety → audit → execute → sanitize). A pre-commit hook (`scripts/pre-commit-safety.sh`) flags handler code that bypasses dispatch (`CLAUDE.md:268-294`).
- **Two engines coexist.** v1 (`src/agent/`) is the legacy chat-loop. v2 (`crates/ironclaw_engine/`) is a unified Thread/Step/Capability/MemoryDoc/Project model intended to replace v1. The bridge in `src/bridge/` selects per route. Most production paths still go through v1.

## Crate / module map

Workspace declared in `Cargo.toml:1-20`. Six member crates plus the root crate; channel/tool guests are excluded from the workspace.

| Path | Role |
|---|---|
| `src/` (root crate `ironclaw`) | Application crate — agent loop, channels, DB, tools, sandbox, web gateway wiring, CLI |
| `crates/ironclaw_common/` | Shared newtypes (`ExtensionName`, `ExternalThreadId`, `CredentialName`, `JobResultStatus`) |
| `crates/ironclaw_safety/` | Sanitizer, leak detector, validator, policy engine. Pure logic, no I/O |
| `crates/ironclaw_skills/` | `SkillManifest`, SKILL.md parser, deterministic selector, gating, registry, catalog |
| `crates/ironclaw_engine/` | Engine v2 core: Thread/Step/Capability/MemoryDoc/Project + ExecutionLoop. No dependency on root crate |
| `crates/ironclaw_gateway/` | Browser-facing HTTP/WS/SSE gateway, static asset bundler |
| `crates/ironclaw_tui/` | Optional Ratatui terminal UI (feature `tui`) |
| `channels-src/` | WASM channel guests: slack/, telegram/, discord/, whatsapp/, feishu/ (independent crates) |
| `tools-src/` | WASM tool guests: composio, github, gmail, google-{drive,docs,sheets,slides,calendar}, slack, telegram, web-search, portfolio, llm-context |
| `wit/channel.wit`, `wit/tool.wit` | The two host↔guest contracts (component model, WASI Preview 2) |
| `skills/` | Bundled SKILL.md files (~30: coding, commit, code-review, linear, github-workflow, …) |
| `registry/` | Catalog JSON for installable channels/tools/MCP servers + `_bundles.json` |
| `migrations/` | Refinery SQL (Postgres + libSQL — must support both) |

Authoritative spec docs (read before modifying): `src/agent/CLAUDE.md`, `src/channels/web/CLAUDE.md`, `src/db/CLAUDE.md`, `src/llm/CLAUDE.md`, `src/workspace/README.md`, `src/setup/README.md`, `src/tools/README.md`, `crates/ironclaw_engine/CLAUDE.md`.

## Runtime topology

```
                              ┌─────────────────────────────────┐
                              │        single OS process         │
                              │     (tokio multi-thread RT)      │
                              └─────────────────────────────────┘
                                              │
   ┌─────────────────┬────────────────┬───────┴───────┬───────────────┬──────────────┐
   ▼                 ▼                ▼               ▼               ▼              ▼
┌────────┐     ┌──────────┐     ┌────────────┐  ┌─────────────┐  ┌─────────┐   ┌──────────┐
│Channels│     │  Agent / │     │ Scheduler  │  │ Workspace + │  │ Sandbox │   │ Webhook  │
│CLI/REPL│ ──▶ │ Dispatch │───▶ │  (jobs +   │  │   memory    │  │ Docker  │   │  axum    │
│web/HTTP│     │ chat loop│     │  subtasks) │  │ (FTS+vector)│  │ per job │   │  server  │
│ /WASM  │ ◀── │          │ ◀── │            │  └──────┬──────┘  │ +bridge │   └──────────┘
└────────┘     └────┬─────┘     └─────┬──────┘         │         └────┬────┘
                    │                 │                ▼              │
                    ▼                 ▼          ┌──────────┐         │
              ┌─────────────┐  ┌────────────┐    │    DB    │◀────────┘
              │ToolRegistry │  │ Heartbeat  │    │ pg/lsql  │
              │(builtin +   │  │ Routine    │    └──────────┘
              │ WASM + MCP) │  │ engine     │
              └─────┬───────┘  └────────────┘
                    │
                    ▼  in-process: wasmtime · MCP-over-stdio/http · in-proc Rust tools
```

Concurrency model: every I/O path is async tokio. Shared state is `Arc<RwLock<…>>` or `Arc<Mutex<…>>`. The agent loop is single-threaded **per session**; parallelism happens at the scheduler level (`spawn_subtask`, `spawn_batch`, full background jobs) — see `src/agent/agent_loop.rs:1145-1308` for the main `run()` select-loop.

The scheduler maintains two `Arc<RwLock<HashMap<Uuid, _>>>`s (`src/agent/scheduler.rs:73-77`):

- `jobs` — full LLM-driven background jobs, each owning an `mpsc<WorkerMessage>` channel
- `subtasks` — fire-and-forget `ToolExec` or `Background` tasks

The Docker sandbox (`src/sandbox/`) runs a per-project container with `sandbox_daemon` (a binary in `src/bin/`) speaking NDJSON over `docker exec -i`. Used **only** for the five filesystem/shell tools (`file_read`, `file_write`, `list_dir`, `apply_patch`, `shell`) when `SANDBOX_ENABLED=true` (`CLAUDE.md:296-308`). The orchestrator (`src/orchestrator/`) is an internal HTTP API the sandbox container calls back into.

## Boot sequence

`src/main.rs:38-51`:

1. Sync prelude — load `.env`, resolve `~/.ironclaw/`, set env vars **before** tokio starts (avoids `set_var` data races).
2. Build tokio multi-thread runtime, hand off to `async_main`.
3. Parse `Cli` (clap, `src/cli/`). Subcommands: `run | onboard | config | tool | registry | mcp | memory | pairing | service | doctor | status | completion`.
4. For `run`: `AppBuilder::new(...).init_database().init_secrets().init_llm().init_tools().init_extensions().build_all()` returns `AppComponents` (`src/app.rs:33-81, 132-1370`).
5. `Agent::new(...)` constructed; channels (CLI/REPL/web/HTTP/Signal + every loaded WASM channel) added to `ChannelManager`.
6. `agent.run()` blocks until shutdown (Ctrl-C, `/quit`, or all channel streams close). See `src/agent/agent_loop.rs:741-1308`.

The five `init_*` phases are mechanical and explicit. Rule from `AGENTS.md:36-41`: module-owned init lives in module factories, called from `app.rs`; do not move it into entrypoints.

## Persistence story

Dual-backend by hard rule. Every persistence feature must work on both PostgreSQL (default, feature `postgres`, refinery migrations, deadpool-postgres pool, pgvector for embeddings) **and** libSQL/Turso (feature `libsql`, embedded or remote, FTS5 + `libsql_vector_idx`). See `src/db/CLAUDE.md` and `CLAUDE.md:218-220`.

Three persistence layers, never collapsed:

1. **Bootstrap config** — env vars + TOML, loaded before DB exists.
2. **DB-backed settings** — `settings` table; dual-write to workspace `.system/settings/**` via `WorkspaceSettingsAdapter` (`src/workspace/settings_adapter.rs`).
3. **Encrypted secrets** — AES-256-GCM, master key in OS keychain (macOS Keychain, Linux secret-service, Windows). See `src/secrets/`.

Critical invariant from `CLAUDE.md:74-77`: **LLM data is never deleted.** Reasoning, tool calls, messages, steps — all retained. "Cleanup" means evicting in-memory caches; DB rows stay.

## Deployment story

cargo-dist targets (`Cargo.toml:288-296`): macOS arm64/x86_64, Linux gnu/musl arm64/x86_64, Windows x86_64. Installers: shell, PowerShell, npm, MSI.

Containers: `Dockerfile`, `Dockerfile.worker`, `Dockerfile.test`, `crates/Dockerfile.sandbox`. `docker-compose.yml` and `railway.toml` exist for one-line deploy.

Public-internet exposure: `Tunnel` trait (`src/tunnel/mod.rs`) with implementations for cloudflared, ngrok, Tailscale (serve/funnel), arbitrary command (`{host}/{port}` template), and `none`. Launched as managed child process; URL echoed to channels that need webhook URLs.

## Known stubs / limits (`CLAUDE.md:322-330`)

- Domain-specific tools (`marketplace.rs`, `restaurant.rs`) are stubs.
- WIT bindgen auto-extract of tool schema is partial.
- Built (auto-generated) tools start with empty capabilities — no UX yet for granting access.
- No tool versioning/rollback.
- Observability: only `noop` and `log` backends; no OpenTelemetry.
- MCP transports (stdio/HTTP/Unix) are all request-response; no streaming.
