# hebe — v1 task list

Ordered, dependency-aware task breakdown for v1. Each task is small enough that one engineer should be able to land it in 0.5–3 days. The PF4J spike is task **M6.T1**, not a prelude.

Reading the table:

- **ID** — `M{milestone}.T{n}`. Milestones in `v1-specs.md` §6.
- **Deps** — predecessor task IDs. A task with no deps in its milestone may also depend on the previous milestone being effectively complete unless noted.
- **Size** — S (≤0.5 d), M (0.5–1 d), L (1–3 d), XL (>3 d; should be split if it stays XL).
- **Acceptance** — what makes this task done.

Companion docs: [`v1-specs.md`](v1-specs.md) (scope) and [`v1-architecture.md`](v1-architecture.md) (contracts/schemas).

---

## M0 — Foundations

| ID | Title | Deps | Size | Acceptance |
|---|---|---|---|---|
| M0.T1 | Gradle multi-module skeleton | — | M | `settings.gradle.kts` declares all modules from `v1-architecture.md` §1; root `build.gradle.kts` applies common conventions; `./gradlew build` succeeds with all modules empty. |
| M0.T2 | `gradle/libs.versions.toml` aligned with stack table | M0.T1 | S | All versions from `v1-architecture.md` §2 listed; resolved correctly on `./gradlew dependencies`. |
| M0.T3 | Detekt + ktlint baseline | M0.T1 | S | `./gradlew detekt ktlintCheck` clean on empty project. |
| M0.T4 | CI pipeline (GitHub Actions) | M0.T2, M0.T3 | M | PR CI: `./gradlew check`; main CI: same + `./gradlew shadowJar`; cache enabled. |
| M0.T5 | `api` module — Kotlin types from `v1-architecture.md` §3 | M0.T1 | L | All interfaces compile; `kotlinx-serialization` annotations on data classes; no other deps. |
| M0.T6 | `plugin-api` module — `HebePlugin`, `PluginHost`, `Capability`, `Permission`, `SecretHandle`, `GatedHttpClient` | M0.T5 | M | Compiles with only `api` + `pf4j` as deps; `HebePlugin` extends `org.pf4j.Plugin`. |
| M0.T7 | `observability` module — kotlin-logging + logback JSON encoder + `Observer` impl | M0.T5 | M | `LogbackObserver` produces structured JSON with required fields (§21 of arch); ring buffer for `doctor`. |
| M0.T8 | `config` module — TOML schema + loader + validation diagnostics | M0.T5 | L | `HebeConfig.load(path) → Result<HebeConfig, ConfigErrors>`; bad config produces row/col-pinpointed errors. |
| M0.T9 | `config` — secrets store (AES-256-GCM + OS keychain + passphrase fallback) | M0.T8 | L | `SecretStore.put/get/delete/list` round-trips on macOS, Linux (secret-service or passphrase fallback), Windows. Master key rotation deferred. |
| M0.T10 | `detekt-rules` — custom rule "no direct mutation outside `// dispatch-exempt:` lines" | M0.T1, M0.T3 | M | Rule fires on a synthetic positive test; passes on `// dispatch-exempt: <reason>`-annotated calls. |
| M0.T11 | Minimum-viable `cli-app` skeleton (clikt-based subcommand parser) | M0.T1 | S | `./hebe --help` lists all v1 subcommands as stubs; each prints "not yet implemented". |

**M0 done when:** project builds end-to-end, CI is green, `api` + `plugin-api` + `config` + `observability` are usable from other modules.

---

## M1 — Memory

| ID | Title | Deps | Size | Acceptance |
|---|---|---|---|---|
| M1.T1 | SQLite open + Flyway runner | M0.T8 | M | `Db.open(path)` runs migrations V1–V5 (§5 of arch); idempotent on re-open. |
| M1.T2 | Migration files V1–V5 | M1.T1 | M | DDL from §5 verbatim; `./gradlew test` boots a temp DB with all tables. |
| M1.T3 | sqlite-vec extension loader | M1.T1 | M | Native extension loaded via JDBC URL on macOS+Linux; vector inserts/selects work in a smoke test. |
| M1.T4 | `WorkspaceFs` API (read/write/list/append) confined to `~/.hebe/workspace/` | M0.T8 | M | Bounds violations throw `HebeException.Security`; `read/write` round-trip; markdown autoinference. |
| M1.T5 | Workspace seeding (BOOTSTRAP/IDENTITY/MEMORY/HEARTBEAT/README) | M1.T4 | S | First-run creates the layout from §6 of arch; idempotent. |
| M1.T6 | Chunker (800 words, 15% overlap, min 50) | M1.T4 | M | Pure function with property tests; deterministic output. |
| M1.T7 | `EmbeddingProvider` trait + mock + OpenAI-compat impl | M1.T1 | M | Mock returns deterministic vectors; OpenAI-compat impl reads `embedding_model` from config; unit tests use mock. |
| M1.T8 | LRU `CachedEmbeddingProvider` | M1.T7 | S | Cache hit / miss / eviction tests pass. |
| M1.T9 | Indexer: doc → chunks → FTS + vec rows | M1.T2, M1.T6, M1.T7 | L | Writing a doc populates `memory_chunks`, `memory_chunks_fts`, `memory_chunks_vec` consistently; idempotent on re-index. |
| M1.T10 | RRF retrieval (`MemoryStore.search`) | M1.T9 | M | `search(q, k)` fuses FTS + vector lists with `k₀ = 60`; returns `MemoryHit` with `source = Fts | Vector | Both`. |
| M1.T11 | Identity-files loader for `systemPrompt()` | M1.T4 | S | Always reads from `Default` scope; v1 list = IDENTITY.md, MEMORY.md, HEARTBEAT.md (group-chat detection stub). |
| M1.T12 | Hygiene scanner (regex set for prompt-injection patterns) | M1.T9 | M | `scanInbound(content)` returns `Severity` + match list; high-severity rejects writes. |
| M1.T13 | Group-chat detection stub | M1.T11 | S | `Conversation.metadata.group = true` causes `MEMORY.md` to be omitted from system prompt. |
| M1.T14 | Memory category enum + storage column | M1.T2, M1.T9 | S | New column on `memory_docs` (V2 already includes `scope`; category is added in a follow-up V6 if needed; otherwise reuse `scope`). |
| M1.T15 | LRU response cache | M0.T7 | S | Keyed on `(model, system, messages)`; hit/miss tests. |
| M1.T16 | `MemoryStore` integration tests with Testcontainers (or in-memory SQLite) | M1.T10, M1.T12 | L | Round-trip: write doc → search → retrieve. Uses real sqlite-vec. |

**M1 done when:** writing/reading workspace + memory works end-to-end; `MemoryStore.search` returns sensible results on a 100-doc corpus; hygiene blocks a known prompt-injection sample.

---

## M2 — LLM + agent loop

| ID | Title | Deps | Size | Acceptance |
|---|---|---|---|---|
| M2.T1 | `OpenAiCompatProvider` — Ktor client, streaming, tool-use, capability check | M0.T5, M0.T9 | L | Streams `StreamEvent` correctly against a recorded fixture (real call against the user's gateway as a manual smoke test). |
| M2.T2 | Mock `LlmProvider` (replay-based) | M0.T5 | M | Replays a recorded transcript; deterministic; used by all loop tests. |
| M2.T3 | `KoogLlmProvider` adapter (wraps koog) | M0.T5 | L | `LlmProvider` impl backed by koog; the only file in `core/` that imports `ai.koog.*`. |
| M2.T4 | `SubmissionParser` (slash-commands, approvals, auth-mode, raw input) | M0.T5 | M | Sealed-type parse with property tests on each variant. |
| M2.T5 | `ApprovalGate` (request, resolve, expire) | M1.T2 | M | Rows in `pending_approvals`; resume on resolve; expiry job. |
| M2.T6 | `ToolDispatcher` skeleton + state machine wiring | M2.T4, M2.T5 | L | All steps in §9 of arch run in order; pluggable validators; receipts-append stub. |
| M2.T7 | Loop detector (fingerprint per turn; 3-warn / 5-force-text) | M2.T6 | M | Repeated identical tool calls trigger warnings; sixth identical call returns force-text outcome. |
| M2.T8 | Cost guard (per-turn + daily budget; reads `llm_calls`) | M1.T2, M2.T1 | M | Rejects new turns past daily cap; emits `Failed("budget")`. |
| M2.T9 | Compaction ladder (workspace-promote → summarise → refuse-to-truncate) | M1.T11, M2.T1 | L | Triggers at 60% of `maxContextTokens`; refuses to truncate when summarisation fails. |
| M2.T10 | Preemptive history pruning | M2.T9 | S | Trim runs before context overflow; never produces an over-budget request. |
| M2.T11 | `ChatDelegate` — implements `LoopDelegate` | M2.T3, M2.T6, M2.T7, M2.T8 | L | Drives `runAgenticLoop`; produces `LoopOutcome` variants correctly in mocked tests. |
| M2.T12 | `JobDelegate` minimal (used by scheduler later) | M2.T11 | M | Same loop, no draft updates, sequential tools. |
| M2.T13 | `HebeAgent` facade with `handleMessage(IncomingMessage): HandleOutcome` | M2.T11 | M | All `HandleOutcome` variants wired; `Pending` distinguishable from `NoResponse`. |
| M2.T14 | Hooks: `BeforeInbound`, `BeforeToolCall`, `BeforeOutbound`, `OnSessionStart/End` | M2.T13 | M | Fail-open semantics; tested. |
| M2.T15 | Auth-mode interception (credential entry never reaches transcript) | M2.T4, M2.T13 | M | Auth-mode input goes to `SecretStore.put`; nothing in `messages`. |

**M2 done when:** a mock-LLM end-to-end test runs a multi-tool turn through the dispatcher, with receipts written and memory appended.

---

## M3 — Security

| ID | Title | Deps | Size | Acceptance |
|---|---|---|---|---|
| M3.T1 | `AutonomyLevel` enum + per-tool `risk` validation | M2.T6 | S | `Supervised` blocks `High` without approval; `Full` allows; `ReadOnly` blocks any side-effect tool. |
| M3.T2 | Workspace boundary validator | M1.T4 | S | `forbidden_paths` always blocks; outside-workspace paths blocked unless explicitly allowed. |
| M3.T3 | Command-policy validator (allow/deny globs + pattern checks) | M0.T8 | M | Pre-shell match; explicit forbidden globs override allow globs; tests for known footguns. |
| M3.T4 | Domain matcher for outbound HTTP | M0.T8 | S | Allowlist + denylist; SSRF-safe IP checks. |
| M3.T5 | Prompt-injection guard | M0.T7 | M | Pattern set; cached per turn; severity report. |
| M3.T6 | Leak detector (regex set for known secret formats) | M0.T7 | M | Detects: AWS keys, OpenAI keys, GitHub PATs, Stripe keys, generic high-entropy tokens. |
| M3.T7 | Sensitive-param redaction | M0.T7, M2.T6 | S | `args_redacted` masks values for known key names; receipts + UI use redacted form. |
| M3.T8 | Ed25519 receipts log writer (chain + sig) | M1.T2, M0.T9 | L | NDJSON file at `~/.hebe/receipts/YYYY-MM.log` per §13 of arch; signing key stored in `secrets.db`. |
| M3.T9 | Receipts verifier (`hebe memory show receipts/<file> --verify`) | M3.T8 | M | Walks the file, checks chain + signatures; reports first divergence. |
| M3.T10 | Wire policy chain into `ToolDispatcher` | M3.T1–M3.T6, M2.T6 | M | All checks in §22 of arch run in order; tested via golden cases. |
| M3.T11 | Emergency stop (`hebe estop` IPC + dispatcher honors it) | M2.T13 | M | Stop fires inside an in-flight tool call; receipts log records `{aborted: true}`. |

**M3 done when:** running a tool that violates each policy returns a structured `ToolResult.Err`; a known-malicious LLM output is blocked; receipts verify clean on a 100-call sample.

---

## M4 — Built-in tools

Each tool is a small task + tests + risk-tagging + sensitive-param config.

| ID | Title | Deps | Size | Acceptance |
|---|---|---|---|---|
| M4.T1 | `file_system` (read/write/list/glob) | M3.T2, M3.T10 | M | Workspace-bounded; markdown/json/yaml/html-aware metadata. |
| M4.T2 | `shell` | M3.T3, M3.T10 | M | Subprocess via `ProcessBuilder`; allow/deny + validator; `requiresApproval = true`; receipts. |
| M4.T3 | `http` | M3.T4, M3.T10 | M | Ktor client; allowlist enforced; SSRF rejection; timeouts. |
| M4.T4 | `web_search` provider trait + Brave + DuckDuckGo impls | M4.T3 | M | Brave default when API key present; DDG fallback; result shape unified. |
| M4.T5 | `memory_search`, `memory_read`, `memory_write`, `memory_tree` | M1.T10, M1.T4 | M | Hygiene scan on writes; reads return paginated results. |
| M4.T6 | `wiki_read`, `wiki_write` | M4.T1, M4.T5 | S | Convention layer over `file_system`; v1 just enforces a prefix and links. |
| M4.T7 | `git` (JGit) | M3.T10 | M | clone/status/diff/log/branch/commit; push deferred to M4.T8. |
| M4.T8 | `git push` (shell-out for credential helpers) | M4.T7, M4.T2 | S | Tagged `High` + always-approve. |
| M4.T9 | `github` (PAT-auth API) | M4.T3 | M | issues / PRs / repo metadata; PAT in `secrets.db`. |
| M4.T10 | `kubectl` | M4.T2 | M | Read-only verbs `Medium`; mutating verbs `High` + always-approve. Verb-discovery tests. |
| M4.T11 | `ask_user` | M2.T13 | S | Sends question via originating channel; resumes turn on reply. |
| M4.T12 | `schedule` (creates routine entries) | M1.T2 | S | Inserts into `routines`; cron expression validated on insert. |
| M4.T13 | `job_create` / `job_status` / `job_cancel` | M1.T2 | M | CRUD against `jobs`; cancellation cooperative. |

**M4 done when:** every v1 tool has a unit test, a receipts entry on success, a `ToolResult.Err` on policy violation, and `hebe tool list` enumerates them with their risk.

---

## M5 — Channels

| ID | Title | Deps | Size | Acceptance |
|---|---|---|---|---|
| M5.T1 | `channels/api` — `ChannelManager`, `injectChannel`, recursion guards | M0.T5 | M | Merges N flows; injectChannel capacity 64; `is_agent_broadcast` filtered. |
| M5.T2 | CLI channel — REPL with slash-commands | M2.T13, M5.T1 | M | `/quit`, `/compact`, `/approve`, `/help`; Ctrl-C cancels turn, double Ctrl-C exits. |
| M5.T3 | Web `gateway` skeleton (Ktor + HTTP Basic) | M0.T8 | M | Server starts on configured bind/port; auth challenges work. |
| M5.T4 | Web SSE `/api/sessions/{id}/events` | M5.T3, M2.T13 | M | Streams `StreamEvent` per §14 of arch; reconnect via `Last-Event-ID`. |
| M5.T5 | Web chat UI (HTMX or small Svelte SPA) | M5.T4 | L | Sends + receives messages; resolves approvals; renders streaming text. |
| M5.T6 | Web memory browser (`/api/memory/search`, `/api/memory/tree`, `/api/memory/doc`) | M5.T3, M1.T10 | M | UI exposes search box + workspace tree; opens docs read-only. |
| M5.T7 | Web receipts viewer (`/api/receipts`, `/api/receipts/verify`) | M5.T3, M3.T8 | M | Tabular view of recent receipts; verify button; per-row "show full args". |
| M5.T8 | Telegram channel — long-poll + draft updates + operator gate | M5.T1 | L | Bot token from secrets; rejects non-operator senders at adapter; `editMessageText` throttled. |
| M5.T9 | Telegram webhook variant | M5.T8, M5.T3 | M | Webhook payload routed to `ChannelManager`; signature validation. |
| M5.T10 | Channel `healthCheck()` exposed in `/api/status` and `hebe doctor` | M5.T2, M5.T5, M5.T8 | S | Each channel reports `Up | Degraded | Down`. |

**M5 done when:** a chat works end-to-end through CLI + web + Telegram; an approval prompt round-trips on each.

---

## M6 — PF4J spike → production loader

The PF4J spike is the first task here. It's deliberately scoped to "load a hello-world plugin from a local JAR and call its tool", so it derisks the plugin model before the rest of the loader is built.

| ID | Title | Deps | Size | Acceptance |
|---|---|---|---|---|
| **M6.T1** | **PF4J spike — hello-world plugin loaded from a local JAR** | **M0.T6** | **L** | **A `plugin-template/`-derived hello-world plugin contributes a `say_hello` Tool. `hebe plugin install <local-path>` loads it; calling `say_hello` from a chat returns the expected output. No manifest validation, no signature, no ACR yet — just the loader path.** |
| M6.T2 | `PluginManagerWrapper` (PF4J `DefaultPluginManager` subclass + classloader rules) | M6.T1 | M | Plugin classloader exposes `api` + `plugin-api` only (verified by negative test: plugin importing `com.hebe.core.*` fails to load). |
| M6.T3 | `plugin.toml` parser + manifest model (`PluginManifest`) | M0.T8, M6.T2 | M | Errors point to row/col; missing required fields rejected. |
| M6.T4 | `PluginHost` impl with capability gates (`http_client`, `env_read`, `secrets:<name>`) | M6.T3, M3.T4 | L | Calling `host.http()` without `http_client` throws `PluginCapabilityException`; allowlist enforced. |
| M6.T5 | Ed25519 signature verification (`signature_mode = optional/required/disabled`) | M0.T9, M6.T3 | M | Signed plugin loads under `required`; unsigned + `required` rejected; unsigned + `optional` warns + loads. |
| M6.T6 | ABI compatibility check (`hebe_api_version`) | M6.T3 | S | Incompatible plugin rejected with a clear diagnostic. |
| M6.T7 | Plugin lifecycle wiring into `ToolRegistry` | M6.T2, M2.T6 | M | `plugin.tools(host)` results registered as `<plugin>:<tool>`; deregistered on stop. |
| M6.T8 | OCI client (ORAS Java SDK) wrapper | M0.T9 | L | `OciClient.pull(ref) → tarball at cache path`; auth via `DefaultAzureCredential` chain; non-Azure registries via docker config / ORAS auth file. |
| M6.T9 | `hebe plugin install <oci-ref>` flow (pull → verify → extract → load) | M6.T7, M6.T8 | M | Round-trip: publish hello-world to a local OCI registry (via `oras push`), then install via hebe; tool callable. |
| M6.T10 | `hebe plugin install <local-path>` (sideload) | M6.T2 | S | Used by dev workflow; mirrors the OCI path's last steps. |
| M6.T11 | `hebe plugin list` / `hebe plugin remove` | M6.T7 | M | List shows status (loaded/error) + capabilities; remove stops + deletes; install records persisted in `settings`. |
| M6.T12 | `auto_pull` on boot | M6.T9 | S | Configured plugins pulled at startup if missing; failures non-fatal. |
| M6.T13 | `plugin-template/` Gradle template repo | M6.T1, M6.T3 | M | One-command publish via `oras push` Gradle task; sample Tool + manifest + properties + tests. |

**M6 done when:** the PF4J spike passes (M6.T1) AND the production loader pulls, verifies, and runs a signed plugin from ACR end-to-end (M6.T9).

---

## M7 — MCP

| ID | Title | Deps | Size | Acceptance |
|---|---|---|---|---|
| M7.T1 | MCP Kotlin SDK integration baseline | M0.T2 | M | Hello-world stdio server compiles; spec-compliant initialise/list_tools/call_tool. |
| M7.T2 | `mcp-server` — expose hebe tools as MCP server | M2.T6, M7.T1 | L | All `Low|Medium`-risk tools advertised; `High` gated by `expose_high_risk` flag. |
| M7.T3 | `mcp-server` transports — stdio (subcommand) + SSE/WS via Ktor | M5.T3, M7.T2 | M | Claude Desktop / Cursor can call `file_system_read` over stdio. |
| M7.T4 | `tools/mcp-client` — consume external MCP servers | M7.T1, M2.T6 | L | Tools imported with `mcp_<server>_<tool>` names; spawn via configured transport. |
| M7.T5 | Tool filter groups (`Always` + `Dynamic` + keywords) | M7.T4 | M | Per-server filter applied per turn before context build. |
| M7.T6 | Per-server credential injection | M7.T4, M0.T9 | M | Stdio servers spawned with declared env populated from secrets store. |

**M7 done when:** a sample stdio MCP server (e.g. `@modelcontextprotocol/server-filesystem`) is consumable from a chat turn AND hebe's `file_system` tool is callable from Claude Desktop.

---

## M8 — Scheduler + heartbeat

| ID | Title | Deps | Size | Acceptance |
|---|---|---|---|---|
| M8.T1 | Cron parser + next-fire calculator | — | S | Standard 5-field cron + `@hourly|@daily|@every <duration>` shortcuts. |
| M8.T2 | Job loop (single-threaded; reads `jobs` ordered by `trigger_at`) | M1.T2 | M | At-least-once execution; idempotency required for retryable kinds. |
| M8.T3 | Routines engine (cron → insert `jobs`; `JobDelegate` runs body) | M8.T1, M8.T2, M2.T12 | M | Routines from `routines` table fire at the right time; bodies = skill or tool ref. |
| M8.T4 | Maintenance: transcript summarisation | M2.T9 | M | Rolling-window summary appended to `MEMORY.md` when threshold crossed. |
| M8.T5 | Maintenance: fact / preference extraction | M2.T1 | M | Promoted facts written to `MEMORY.md` with provenance line. |
| M8.T6 | Maintenance: daily digest | M8.T2 | M | `daily/YYYY-MM-DD.md` generated end of day from transcript + receipts. |
| M8.T7 | Maintenance: embedding refresh | M1.T9 | S | Chunks with NULL embedding batch-indexed. |
| M8.T8 | Maintenance: stuck-job detection + retry-once | M8.T2 | S | Jobs in `running` past deadline marked `stuck`; retried once if marked retryable. |
| M8.T9 | Heartbeat routine (HEARTBEAT.md → run a turn → silence-on-OK) | M8.T3, M2.T13 | M | Cron-driven; emits to `notify_channel` if non-OK; quiet otherwise. |

**M8 done when:** a 24-hour soak fires the heartbeat 4 times, summarises transcripts twice, generates a daily digest, and refreshes embeddings on demand.

---

## M9 — Operations

| ID | Title | Deps | Size | Acceptance |
|---|---|---|---|---|
| M9.T1 | `hebe doctor` — config / LLM / channels / keychain / plugins / sandbox detect | M5.T10, M6.T11 | M | Each check returns `Pass | Warn | Fail` with a remediation hint. |
| M9.T2 | `hebe service install/start/stop/uninstall` | M0.T11 | M | systemd unit / launchctl plist / Windows-Service definition generated and installed. |
| M9.T3 | Daemon mode + PID file + graceful shutdown | M0.T11 | S | SIGTERM drains turns within a deadline, then exits 0. |
| M9.T4 | Onboarding wizard (`hebe onboard`) | M0.T8, M5.T8 | L | Walks through LLM endpoint + Telegram + admin password; generates `config.toml` + secrets; deletes `BOOTSTRAP.md`. |
| M9.T5 | OTel exporter wiring + spans for `dispatch.<tool>`, `memory.search`, `plugin.start`, `channel.reply` | M0.T7 | M | Spans visible against an OTLP collector. |
| M9.T6 | Fat-JAR build via Gradle Shadow + `./hebe` shell wrapper | M0.T1 | S | `./gradlew shadowJar` produces a single jar; wrapper runs it. |
| M9.T7 | `hebe completion bash/zsh/fish` | M0.T11 | S | Shell completion for subcommands. |
| M9.T8 | `hebe status --recent` | M3.T8 | S | Prints recent receipts, last LLM call, channel health. |

**M9 done when:** a fresh user runs `hebe onboard` → `hebe service install` → hebe comes up under systemd and stays up across a host reboot.

---

## M10 — Hardening + docs

| ID | Title | Deps | Size | Acceptance |
|---|---|---|---|---|
| M10.T1 | README.md (install + minimal config) | M9.T4 | S | 10-line quickstart works on a fresh machine. |
| M10.T2 | Quickstart guide (10-minute happy path) | M10.T1 | M | New user reaches first chat in <10 min, end-to-end. |
| M10.T3 | Plugin protocol spec doc | M6.T11, M6.T13 | M | Manifest, classloader rules, capability gates, lifecycle, OCI publish/pull flow. |
| M10.T4 | MCP integration guide (server + client) | M7.T6 | M | Setup instructions + sample configs for stdio, SSE, WS. |
| M10.T5 | Security model doc | M3.T11 | M | Autonomy, sandbox posture, receipts, plugin trust posture, leak detector, prompt-injection guard. |
| M10.T6 | Per-channel setup guide — Telegram | M5.T9 | S | BotFather → token → operator id → wired up. |
| M10.T7 | Soak test (7-day continuous run) | M8.T9, M9.T2 | L | No JVM crash, no DB corruption, at least one routine fires daily, heartbeat 4×/day. |
| M10.T8 | Security review checklist (self-audit) | M3.T11 | M | Each item in §22 of arch verified against the running system. |
| M10.T9 | RFC process scaffold (`docs/rfcs/0000-template.md`) | M10.T1 | S | Template + README explaining when an RFC is needed. |
| M10.T10 | Internal acceptance run-through against `v1-specs.md` §5 | All | L | Every numbered acceptance item passes manually. |

**M10 done when:** all v1-specs.md §5 acceptance items pass and a soak run completes without intervention.

---

## Cross-cutting (parallelisable, claim anytime)

These don't belong to a milestone but should be picked up opportunistically.

| ID | Title | Deps | Size | Acceptance |
|---|---|---|---|---|
| X.T1 | HTTP record/replay infrastructure (IronClaw `HttpInterceptor` analogue) | M0.T2 | M | Used by `OpenAiCompatProvider` tests + `github` tool tests. |
| X.T2 | Mock channel for e2e tests | M5.T1 | S | Submits scripted `IncomingMessage`s; captures `OutboundMessage`s. |
| X.T3 | Mock memory backend (in-memory) | M0.T5 | S | For unit tests that don't need SQLite. |
| X.T4 | jacoco coverage gate ≥ 70% on `core`, `memory`, `security`, `plugins` | M0.T4 | S | CI fails below threshold. |
| X.T5 | Bundled starter skills (3–5 markdown files) | M1.T11 | M | `daily-briefing`, `code-review-prep`, `wiki-organiser` (TBD by content); pass deterministic-prefilter unit tests. |
| X.T6 | `MockLlmProvider` recording tool (`./gradlew :tests:recordTrace`) | X.T1, M2.T1 | M | Easy capture of a real-LLM trace for replay-based tests. |
| X.T7 | Pre-commit hook installer | M0.T3 | S | `./scripts/install-hooks.sh` wires Detekt + ktlint locally. |
| X.T8 | Migration check (no rewriting V1–V5 once shipped) | M1.T2 | S | CI lint that fails if a shipped migration file's content changes. |

---

## Dependency-respecting build order

A reasonable execution order if a single engineer is driving:

1. **M0 entirely** (foundations, no shortcuts).
2. **M1 in parallel with M2 (mock provider track first)** — memory and the agent loop can both be built against mocks.
3. **M3** layers on top of M2's dispatcher.
4. **M4 (built-in tools)** — most can land independently in any order; `shell` and `kubectl` last because they're the highest-blast-radius.
5. **M6.T1 (PF4J spike)** can start as soon as M0 is done; finish the spike before the rest of M6.
6. **M5 (channels)** — CLI first, then web, then Telegram. Each picks up `HebeAgent` from M2.
7. **M7 (MCP)** can start after M2 + M6.T1; client side benefits from having M4 tools to expose.
8. **M8 (scheduler)** after M2 + M4.
9. **M9 (operations)** runs alongside M5–M8 as features stabilise.
10. **M10 (hardening)** is continuous; soak test only meaningful at the end.

If two people are working: split M1 (memory) and M2 (loop) in parallel; one person owns M6 (PF4J + ACR) end-to-end since it's a coherent slice.

## Cut lines

If we're behind schedule, the recommended cut order (least painful first):

1. M4.T9 (`github` tool) — defer to v1.1.
2. M4.T10 (`kubectl`) — defer if no pressing use case.
3. M5.T9 (Telegram webhook variant) — keep long-poll only.
4. M7.T3 SSE/WS (keep stdio).
5. M9.T4 onboarding wizard → minimal `hebe init` that writes a starter `config.toml`.
6. M10.T7 soak shortened from 7 days to 48 hours.
7. M6.T8/T9 (OCI/ACR) — keep sideload only; defer ACR pull to v1.1.

Dropping anything past this list reshapes v1 enough that the spec needs revising.
