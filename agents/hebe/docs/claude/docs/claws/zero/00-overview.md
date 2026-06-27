# ZeroClaw Overview

Internal reference for the Kotlin port. Source tree: `/Users/bora/Dev/view-only/zeroclaw/` (Rust 2024, MSRV 1.87, version 0.7.3, single binary `zeroclaw`). Author: ZeroClaw Labs (community).

## What ZeroClaw is

A self-hostable, single-binary agent runtime. Talks to ~20 LLM providers, reaches the user through 30+ channels (Discord, Slack, Telegram, Matrix, email, voice/telephony, IRC, Bluesky, Nostr, Reddit, Twitter, WhatsApp, iMessage, …), executes tools (shell, browser, HTTP, hardware GPIO/I2C/SPI/USB, MCP), and persists memory locally (SQLite, Postgres, optional Qdrant).

Marketing one-liner: *"Zero overhead. Zero compromise. 100% Rust."* — and as of v0.7 the project is mid-migration from a monolithic runtime crate to a **microkernel** topology (RFC #5574).

Compared to IronClaw, ZeroClaw makes very different bets:

- **Public stable ABI in `zeroclaw-api`** — three traits (`Provider`, `Channel`, `Tool`) define the kernel interface. Everything else is feature-flagged and pluggable at compile time.
- **Channels are native Rust crates, not WASM.** ZeroClaw has 40+ channel adapters as in-tree code. WASM is reserved for *plugins* (third-party tools), via **Extism** (extism-pdk + extism host) — a much lighter sandbox than Wasmtime+component-model.
- **OS-level sandboxes do the heavy lifting** for tool isolation. Landlock, Bubblewrap, Firejail, Seatbelt, Docker, Windows AppContainer — auto-detected at runtime.
- **Tool receipts** are cryptographically chained (each receipt hashes the previous), so the audit log is tamper-evident.
- **SOP engine** — Standard Operating Procedures, deterministic event-triggered runbooks with approval gates. Triggers: manual / webhook / MQTT / cron / hardware-peripheral. This is a first-class subsystem, not a routine bolt-on.
- **Hardware-first.** `Peripheral` trait, support for Raspberry Pi, STM32 Nucleo, Arduino Uno Q, ESP32; firmware module included; an "aardvark-sys" + "robot-kit" exist as separate crates.
- **Three autonomy levels** (`ReadOnly` / `Supervised` / `Full`) plus a `YOLO` preset that disables the gates, deliberately loud and obvious.
- **Provider router with fallback chains** — declarative `[providers.models.<name>]` blocks, hint-based routing (lightweight keyword routing to cheaper models), automatic failover when a provider flakes.
- **Streaming end-to-end with draft updates.** Channels that support edit-in-place (Discord, Slack, Telegram) render the agent's reply token-by-token by editing a sent message. Tool calls happen mid-stream — the runtime pauses the stream, validates, invokes, feeds back, and resumes.
- **ACP (Agent Client Protocol)** — JSON-RPC 2.0 over stdio for IDE integration. Editor plugins talk to the agent like a language server.
- **Companion Tauri desktop app** under `apps/tauri/`.
- **Sophisticated memory subsystem.** SQLite + Postgres + Qdrant backends; decay, importance scoring, consolidation, hygiene, conflict resolution, knowledge-graph (general + Postgres), RAG, snapshots, response cache.

## Crate / module map

Workspace from `Cargo.toml:1-20` (16 in-workspace crates + apps/tools):

| Path | Role |
|---|---|
| `crates/zeroclaw-api` | **Public ABI** — `Provider`, `Channel`, `Tool` traits, `StreamEvent`, `TurnEvent`. The kernel interface |
| `crates/zeroclaw-runtime` | The agent loop, security, SOP engine, cron, onboarding, skills, skillforge, integrations. The "kernel" today, shrinking per #5574 |
| `crates/zeroclaw-config` | TOML schema, secrets encryption, autonomy levels, workspace resolution |
| `crates/zeroclaw-providers` | LLM client implementations + router + fallback wrapper. Anthropic, OpenAI, Ollama, Bedrock, Gemini, Azure, OpenRouter, Groq, Mistral, xAI, … |
| `crates/zeroclaw-channels` | 40+ channel adapters (see below) + transcription, TTS, link enrichment, orchestrator |
| `crates/zeroclaw-tools` | Built-in tools: browser, calculator, canvas, ask_user, backup, … |
| `crates/zeroclaw-tool-call-parser` | Tool-call syntax parser (XML and native), shared between runtime and providers |
| `crates/zeroclaw-memory` | Memory backends + retrieval; SQLite, Postgres, Qdrant, knowledge graph, embeddings, decay, consolidation, hygiene |
| `crates/zeroclaw-plugins` | Extism-based WASM plugin host: `wasm_tool`, `wasm_channel`, `signature` (Ed25519 verification), runtime |
| `crates/zeroclaw-gateway` | HTTP / WebSocket gateway, web dashboard, webhook ingress |
| `crates/zeroclaw-hardware` | HAL: GPIO / I2C / SPI / USB |
| `crates/zeroclaw-tui` | Terminal UI |
| `crates/zeroclaw-infra` | Tracing, metrics, structured logging |
| `crates/zeroclaw-macros` | Derive macros for config, tool registration |
| `crates/aardvark-sys`, `crates/robot-kit` | Specialised hardware support |
| `apps/tauri` | Desktop app (Tauri) |
| `plugins/image-gen-fal` | Example out-of-tree plugin (excluded from workspace) |
| `tools/fill-translations`, `xtask` | Build/devops tooling |

Authoritative docs live under `docs/book/src/` (mdBook). Read `philosophy.md` (the four opinions), `architecture/overview.md`, `architecture/request-lifecycle.md`, `security/overview.md`, `developing/plugin-protocol.md`, and `sop/index.md` before designing for parity.

### Runtime submodules (the "kernel" being split)

```
crates/zeroclaw-runtime/src/
├── agent/              ← agent loop, dispatcher, classifier, prompt, system_prompt,
│                          history, tool_execution, tool_receipts, loop_detector, eval, …
├── approval/           ← approval flow (operator gating)
├── cost/               ← per-turn budget tracking
├── cron/               ← cron-driven scheduler
├── daemon/, service/   ← systemd / launchctl / Windows-Service registration
├── doctor/             ← `zeroclaw doctor` health check
├── firmware/           ← firmware uploads to MCU boards
├── health/             ← liveness/readiness probes
├── heartbeat/          ← periodic agent self-check
├── hooks/              ← pre/post hooks for tool calls
├── identity.rs         ← agent identity (name, persona)
├── integrations/       ← provider integrations beyond LLM (e.g. cloud APIs)
├── nodes/              ← multi-node coordination (early)
├── observability/      ← tracing, metrics, structured events
├── onboard/            ← interactive setup wizard
├── platform/           ← OS-specific shims
├── rag/                ← retrieval-augmented generation
├── routines/           ← lightweight scheduled actions (vs full SOPs)
├── security/           ← autonomy, sandboxing, tool-receipts, leak detector,
│                          policy, prompt_guard, OTP, WebAuthn, e-stop, pairing
├── skillforge/         ← skill creation pipeline (scout → evaluate → integrate)
├── skills/             ← skill registry, audit, creator, improver, http+tool wrappers
├── sop/                ← SOP engine: types, condition, dispatch, engine, audit, metrics
├── tools/              ← runtime-side tool plumbing (registry, builtin, executor)
├── trust/              ← explicit trust ledger
├── tunnel/             ← public-internet exposure
└── verifiable_intent/  ← cryptographic intent receipts
```

### Channels in `crates/zeroclaw-channels/src/`

`bluesky`, `clawdtalk`, `cli`, `dingtalk`, `discord` (+ `discord_history`), `email_channel`, `gmail_push`, `imessage`, `irc`, `lark`, `line`, `linq`, `matrix`, `mattermost`, `mochat`, `nextcloud_talk`, `nostr`, `notion`, `qq`, `reddit`, `signal`, `slack`, `telegram`, `twitter`, `voice_call`, `voice_wake`, `wati`, `webhook`, `wechat`, `wecom`, `whatsapp` (+ `whatsapp_storage`, `whatsapp_web`).

Plus utility modules: `transcription` (audio→text middleware), `tts` (text-to-speech), `link_enricher` (URL preview/expansion), `orchestrator/` (channel orchestration glue), `util.rs`.

### Memory submodules in `crates/zeroclaw-memory/src/`

`audit`, `backend`, `chunker`, `conflict`, `consolidation`, `decay`, `embeddings`, `hygiene`, `importance`, `knowledge_graph`, `knowledge_graph_pg`, `lucid`, `markdown`, `namespaced`, `none` (no-op backend), `policy`, `postgres`, `qdrant`, `response_cache`, `retrieval`, `snapshot`, `sqlite`, `traits`, `vector`.

## Runtime topology

```
                  ┌────────────────────────────────────────────────┐
                  │         single OS process · tokio RT            │
                  └────────────────────────────────────────────────┘
                                       │
   ┌────────────┬────────────┬─────────┴──────────┬─────────────┬───────────┐
   ▼            ▼            ▼                    ▼             ▼           ▼
┌────────┐ ┌──────────┐ ┌────────────┐    ┌─────────────┐  ┌─────────┐ ┌─────────┐
│Channels│ │ Gateway  │ │   ACP      │    │ SOP engine  │  │  Cron   │ │Heartbeat│
│40+     │ │HTTP/WS+  │ │JSON-RPC/   │    │ webhook/    │  │ tasks   │ │self-    │
│native  │ │dashboard │ │stdio (IDE) │    │ mqtt/cron/  │  └────┬────┘ │check    │
│adapters│ └────┬─────┘ └──────┬─────┘    │ peripheral  │       │      └────┬────┘
└────┬───┘      │              │          └──────┬──────┘       │           │
     │          │              │                 │              │           │
     └─────────►├◄────────────►├◄────────────────┼◄─────────────┼───────────┘
                ▼              ▼                 ▼              ▼
        ┌──────────────────────────────────────────────────────────────┐
        │                    AGENT LOOP (runtime)                       │
        │  classifier → memory_loader → prompt → provider.chat()        │
        │  ↓ stream  ↓ tool_call  ↓ security gate  ↓ tool exec ↓ loop   │
        │  cost guard · approval · loop detector · receipts · hooks     │
        └────┬─────────────────────────────────────────────────┬───────┘
             │                                                 │
             ▼                                                 ▼
       ┌──────────────┐                            ┌─────────────────────┐
       │  Providers   │                            │ Tools + Plugins     │
       │  (Anthropic, │                            │ builtin · MCP ·     │
       │   OpenAI,    │                            │ Extism WASM         │
       │   Ollama, …) │                            │ + sandbox (LL/BW/   │
       │  router +    │                            │  Seatbelt/Docker)   │
       │  fallback    │                            └─────────┬───────────┘
       └──────┬───────┘                                      │
              │                                              ▼
              ▼                                       ┌─────────────┐
        ┌──────────┐                                  │   Memory    │
        │ external │                                  │ SQLite/PG/  │
        │ HTTP/SSE │                                  │ Qdrant +    │
        └──────────┘                                  │ KG + RAG    │
                                                      └─────────────┘
```

The agent loop is in `crates/zeroclaw-runtime/src/agent/loop_.rs`. The shared `Agent` struct (`agent.rs`) carries provider, tools, memory, observer, prompt builder, dispatcher, hook runner, autonomy level, security policy summary, response cache, etc.

Concurrency: tokio multi-threaded runtime, async I/O throughout. Streaming uses `StreamEvent` from `zeroclaw-providers` (`TextDelta`, `ToolCall`, …). The runtime pauses the stream on each tool call, runs the security gate, optionally awaits operator approval, invokes the tool, appends the result to the chat request, and resumes.

## Persistence

Three orthogonal storage layers:

1. **Config** — `~/.zeroclaw/config.toml`, single source of truth. Loaded once at startup. Secrets section is encrypted at rest.
2. **Memory backend** — pluggable. Default `sqlite` at `~/.zeroclaw/memory.db`. Postgres for shared deployments. Qdrant for high-volume vector retrieval. The `Memory` trait (`zeroclaw-memory::traits`) is what's load-bearing.
3. **Tool receipts** — append-only chained log on disk. Each receipt links to the previous via hash. Independent of the memory DB.

The memory subsystem is sophisticated: `decay` (older items lose importance), `importance` (scored entries), `consolidation` (merges related fragments), `conflict` (handles contradictions), `hygiene` (sanitization), `knowledge_graph` (entity extraction and edges), `snapshot` (point-in-time captures). Most personal-agent ports won't need all of this — but the `traits.rs` API is worth studying.

## Boot sequence

1. `src/main.rs` parses CLI (subcommands: `onboard`, `agent`, `gateway`, `service`, `sop`, `cron`, `plugin`, `doctor`, `estop`, `pairing`, …).
2. Loads `~/.zeroclaw/config.toml` via `zeroclaw-config`.
3. Builds the agent stack via `AgentBuilder` (`runtime/src/agent/agent.rs`): provider, tools, memory, observer, hooks, dispatcher, autonomy.
4. Registers channel factories (each channel's `register()` function), gateway routes, ACP server (if `acp` feature on), SOP engine, cron scheduler, heartbeat, doctor.
5. `agent` subcommand runs interactive in the terminal; `service install/start` registers as systemd/launchctl/Windows-Service.
6. Inbound events flow into the agent loop (see `30-channels.md` and `50-agent-loop.md`).

## Deployment

- One static binary (or with feature flags, *very* small — `--minimal` in install.sh ships a ~6.6 MB kernel).
- `install.sh` script: prebuilt or source build, `--features <list>` for custom feature set, `--list-features` to enumerate.
- `zeroclaw service install` registers a service: systemd on Linux, launchctl on macOS, Windows Service on Windows.
- Docker image; Tauri desktop bundle (`apps/tauri`).
- Public exposure via tunnel (`runtime/src/tunnel/`) — same idea as ironclaw but less elaborated.

## Notable known limits / caveats

- Workspace is mid-migration: `[package] publish = false` because the multi-crate publish topology is unresolved (RFC #5579, comment in `Cargo.toml`).
- WASM plugin capabilities other than `tool` and `skill` are listed but **not yet implemented**: `channel`, `memory`, `observer` (`docs/book/src/developing/plugin-protocol.md`).
- WASM host-fn permissions other than `http_client` and `env_read` are listed but not implemented: `file_read`, `file_write`, `memory_read`, `memory_write`.
- `nodes/` (multi-node coordination) is described as early.
- AppContainer (Windows sandbox) is marked experimental.
