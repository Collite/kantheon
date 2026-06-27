# M9 — Operations

`hebe doctor`, `service install`, daemon mode + PID, onboarding wizard, OTel wiring, fat-JAR build via Shadow, shell completion, status command.

**Done when:** a fresh user runs `hebe onboard` → `hebe service install` → hebe comes up under systemd and stays up across a host reboot.

References: [`../v1-architecture.md`](../v1-architecture.md) §17; [`../v1-specs.md`](../v1-specs.md) §2.11.

---

## M9.T1 — `hebe doctor`

**Status**: pending  
**Size**: M  
**Depends on**: M5.T10 (channel health), M6.T11 (plugin status), M0.T9 (keychain)  
**Blocks**: M9.T4 (onboarding refers to doctor for self-test)

### Goal

A single command that prints `Pass | Warn | Fail` for every operational concern with a remediation hint per fail.

### Files to create

- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Doctor.kt` (edit — implement)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/doctor/Checks.kt` (new — composable checks)
- Tests

### Detailed work

1. Checks (each returns `CheckResult(name, status, message, hint?)`):
   - **Config**: file exists, parses, all required keys.
   - **LLM endpoint**: GET `${baseUrl}/models` (or a config-defined ping path) returns 200 with valid bearer token.
   - **Channels**: each enabled channel's `healthCheck()`.
   - **Keychain**: can read/write a synthetic test secret to confirm OS keychain integration.
   - **Plugins**: each loaded plugin reports `started`; flag any in `error` state.
   - **Sandbox detect**: presence of `firejail`/`bwrap`/Docker — informational only in v1 (subprocess sandbox is v2).
   - **DB**: ping `SELECT 1` against SQLite; ensure migrations are at head.
   - **Workspace**: writable; required identity files present.
   - **Receipts signing key**: present in secrets store.

2. Output: pretty table by default; `--json` for machine-readable.

   ```
   Check                  Status   Detail
   ───────────────────────────────────────────────────────────────
   config                 PASS
   llm endpoint           PASS     gpt-4o-mini reachable
   channels: cli          PASS
   channels: web          PASS     listening 127.0.0.1:8765
   channels: telegram     WARN     bot disabled (config.channels.telegram.enabled=false)
   keychain               PASS     macOS Keychain
   plugins                PASS     2 loaded
   sandbox                WARN     no firejail/bwrap/Docker; subprocess sandbox unavailable (v2 feature)
   db                     PASS     V6 head
   workspace              PASS
   receipts signing key   PASS
   ```

3. Exit code: 0 if all `Pass`/`Warn`; 1 if any `Fail`.

4. `--verbose` flag: include the last 50 events from the in-memory ring buffer (M0.T7).

### Tests / verification

- Each check unit-testable.
- Aggregate command outputs the table.

### Acceptance criteria

- ✅ Every check produces remediation hint on Fail.
- ✅ JSON output mode.
- ✅ Exit code semantics correct.

### References

- `v1-specs.md` §2.11

---

## M9.T2 — `hebe service install / start / stop / uninstall`

**Status**: pending  
**Size**: M  
**Depends on**: M9.T6 (fat JAR; the service launches the jar)  
**Blocks**: M10.T7 (soak test depends on running as a service)

### Goal

Generate and install a system service unit. Three platforms in v1:

- macOS: launchd plist at `~/Library/LaunchAgents/com.hebe.agent.plist`.
- Linux: systemd user unit at `~/.config/systemd/user/hebe.service`.
- Windows: a small Service Control Manager registration via `sc.exe` (best-effort; document if it ships fully working).

### Files to create

- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Service.kt` (edit — implement)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/service/MacOsLaunchd.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/service/LinuxSystemd.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/service/WindowsService.kt` (new)
- Tests

### Detailed work

1. Detect platform via `os.name`. Each impl provides `install()`, `start()`, `stop()`, `uninstall()`, `status()`.

2. Linux unit template:

   ```
   [Unit]
   Description=hebe agent
   After=network.target

   [Service]
   Type=simple
   ExecStart=/usr/local/bin/hebe run
   Environment="HOME=%h"
   Restart=on-failure
   RestartSec=5

   [Install]
   WantedBy=default.target
   ```

   Install with `systemctl --user daemon-reload && systemctl --user enable hebe.service`.

3. macOS plist: `<plist><dict><key>Label</key>com.hebe.agent</key><key>ProgramArguments</key>...</dict></plist>`. Install with `launchctl load`.

4. Windows: `sc.exe create hebe binPath= "..\hebe.exe" start= auto`. Document this is best-effort — if it doesn't work cleanly, defer Windows service to v1.1.

5. `hebe service status` shows: `installed | running | stopped | not-installed` per platform.

### Tests / verification

- Linux/macOS install in CI (where possible) — integration tests.

### Acceptance criteria

- ✅ Linux + macOS work end-to-end.
- ✅ Windows path documented.
- ✅ Unit + plist templates committed.

### Pitfalls

- macOS launchd: setting `RunAtLoad` and `KeepAlive` is the auto-restart story; don't conflate.
- systemd user units don't survive a logout unless `loginctl enable-linger <user>` — note in the install output.

### References

- `v1-specs.md` §2.11

---

## M9.T9 — `hebe run` full agent assembly

**Status**: pending  
**Size**: L  
**Depends on**: M2.T6 (dispatcher), M3.T1 (policy chain), M4 (builtin tools), M5.T3 (gateway), M6.T13 (plugin lifecycle), M7.T4 (MCP client), M8.T9 (scheduler)  
**Blocks**: M9.T3 (daemon wrapper goes on top of the assembled agent)

### Goal

Implement `RunCommand` — wire every module's component into a single running agent. This is the composition root: the one place that constructs everything and starts it.

### Files to create / modify

- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Run.kt` (edit — implement)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/AgentFactory.kt` (new — pure assembly, no I/O)

### Detailed work

1. **Load infrastructure** (all from config + `workspaceRoot = ~/.hebe`):
   - `Db.open(workspaceRoot)` → `SqliteMemoryStore`
   - `SigningKey.bootstrap(secretStore)` → shared Ed25519 private key
   - `Receipts(receiptsDir, signingKey)`
   - `WorkspaceSeeder.seedIfMissing(workspaceRoot)`

2. **Build tool stack**:
   - Create `ToolRegistry`; register all builtin tools (`FileSystem*`, `Shell`, `Http`, `WebSearch`, `Memory*`, `Schedule`, `Git`, `AskUser`)
   - `PolicyChain.standard(config, workspaceRoot)` as validators
   - `ApprovalGate` + `PendingApprovalsRepo`
   - `ToolDispatcher(registry, validators, approvalGate, memory, observer, leakDetector, receipts)`

3. **Connect MCP client** (must happen before `HebeAgent` is constructed so remote tools are in the registry):
   ```kotlin
   val mcpClientManager = McpClientManager(registry, secretLookup)
   mcpClientManager.connect(config.mcp.client.servers)
   ```

4. **Build `toolsProvider`** that applies per-turn MCP filter (completes M7.T5):
   ```kotlin
   val toolsProvider: suspend (String) -> List<ToolSpec> = { message ->
       val local = registry.list().map { it.spec }
       val remote = config.mcp.client.servers.flatMap { srv ->
           mcpClientManager.toolsForMessage(srv.name, message)
               .mapNotNull { name -> registry.get(name)?.spec }
       }
       local + remote
   }
   ```

5. **Construct `HebeAgent`** with `toolsProvider` and all other deps.

6. **Load + start plugins** via `HebePluginManager`; plugins may register additional tools into the registry.

7. **Wire channels + Gateway**:
   - Construct enabled channels (`CliChannel`, `WebChannel`, `TelegramChannel` per config)
   - `ChannelWiring.registerChannels(channelManager, ...)`
   - `Gateway.start(config.channels.web, secretStore, mcpServerConfig, registry, dispatcher, configureRoutes = { channelWiring.applyToGateway(this) })`

8. **Start `ChannelManagerImpl`** in a coroutine scope — this begins accepting messages and calling `hebeAgent.handleMessage(msg)` for each.

9. **Wire MCP server** (HTTP): already handled via `Gateway.start(mcpServerConfig = config.mcp.server, ...)`.

### Tests / verification

- Integration test: start the agent with a `MockLlmProvider`, send a message via `InjectChannel`, assert a reply.

### Acceptance criteria

- ✅ `hebe run` starts and accepts chat turns on all configured channels.
- ✅ `toolsProvider` includes both local and MCP-filtered remote tools per turn (T5 complete).
- ✅ Shutdown (signal or in-test) disconnects MCP clients and drains the channel manager cleanly.

### Pitfalls

- Plugin loading must happen after `ToolRegistry` is created (plugins register into it) but before `HebeAgent` is constructed (so the agent sees plugin tools).
- MCP client connect is async; call `connect()` with a timeout — don't let a slow external server delay startup indefinitely.

---

## M9.T10 — MCP client lifecycle: reconnect + `hebe doctor` health

**Status**: pending  
**Size**: M  
**Depends on**: M9.T9, M7.T4  
**Blocks**: nothing direct

### Goal

Reconnect with exponential backoff on transport disconnect; surface MCP connection status in `hebe doctor`.

### Files to create / modify

- `modules/tools/mcp-client/src/main/kotlin/com/hebe/tools/mcp/McpClientManager.kt` (edit — add reconnect loop)
- `modules/tools/mcp-client/src/main/kotlin/com/hebe/tools/mcp/McpServerStatus.kt` (new — status type)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/doctor/Checks.kt` (edit — add MCP check)
- Tests

### Detailed work

1. **`McpServerStatus`** sealed type:
   ```kotlin
   sealed interface McpServerStatus {
       data object Connected : McpServerStatus
       data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : McpServerStatus
       data class Failed(val reason: String) : McpServerStatus
   }
   ```
   Store in a `ConcurrentHashMap<String, McpServerStatus>` alongside `connectedClients`.

2. **Reconnect loop** — after `connectServer()` succeeds, launch a background coroutine:
   - Monitor for client disconnection (transport close event or failed `listTools` ping).
   - On disconnect: remove tools from registry; retry with exponential backoff starting at 5 s, doubling up to 5 min cap, giving up after 1 hour total.
   - On reconnect success: re-register tools; update status to `Connected`.
   - On permanent failure: set `Failed`; log at ERROR.

3. **`McpClientManager.connectionStatus(): Map<String, McpServerStatus>`** — public method for health checks.

4. **`hebe doctor` MCP check**: iterate `connectionStatus()`:
   - `Connected` → Pass
   - `Reconnecting` → Warn (include attempt count and next retry)
   - `Failed` → Fail (include reason; hint: check command path and credentials)

### Tests / verification

- Mock a `Client` that closes its transport after connection — assert `McpServerStatus.Reconnecting` transitions to `Connected` on a second mock server.
- After 1-hour backoff exhausted (time-accelerated via `TestCoroutineScheduler`) → `Failed`.
- `hebe doctor` outputs Warn row when server is reconnecting.

### Acceptance criteria

- ✅ Disconnect → reconnect with capped exponential backoff (max 5 min, give up after 1 h).
- ✅ `McpServerStatus` exposed via `connectionStatus()`.
- ✅ `hebe doctor` reflects MCP connection health per server.

### References

- M7 task brief §T4 (`tasks/M7-mcp.md`)

---

## M9.T3 — Daemon mode + PID file + graceful shutdown

**Status**: pending  
**Size**: S  
**Depends on**: M9.T9  
**Blocks**: M9.T2 (service runs in daemon mode)

### Goal

`hebe run` writes a PID file at `~/.hebe/hebe.pid`; SIGTERM/SIGINT triggers graceful shutdown per `v1-architecture.md` §19.

### Files to create

- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Run.kt` (edit — implement)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/daemon/PidFile.kt` (new)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/daemon/Shutdown.kt` (new)
- Tests

### Detailed work

1. `PidFile.acquire(path)`: writes the current PID; uses an exclusive lock (`FileChannel.tryLock`) so a second `hebe run` can't double-launch. On already-locked: print "hebe already running (pid=X)" and exit 1.

2. `Shutdown.installHook(scope, components)`:
   - Registers signal handlers for `SIGTERM`, `SIGINT`.
   - Drains in flight: stop accepting new messages → wait for in-flight turns to finish (deadline 30 s, then force) → flush observer → close DB.

3. Exit code 0 on clean shutdown.

### Tests / verification

- `hebe run` writes PID; second `run` fails.
- `kill -TERM <pid>` triggers graceful drain; in-flight tool call gets cancelled cleanly.

### Acceptance criteria

- ✅ PID file with exclusive lock.
- ✅ SIGTERM/SIGINT handled.
- ✅ Drain deadline respected.

### References

- `v1-architecture.md` §19 (boot/shutdown sequence)

---

## M9.T4 — Onboarding wizard (`hebe onboard`)

**Status**: pending  
**Size**: L  
**Depends on**: M0.T8, M0.T9, M5.T8, M9.T1  
**Blocks**: M10.T2 (quickstart guide demonstrates onboarding)

### Goal

Interactive wizard that walks through LLM endpoint + Telegram setup + admin password, generates `~/.hebe/config.toml` + populates `secrets.db`, deletes `BOOTSTRAP.md`. After completion, `hebe doctor` reports green.

### Files to create

- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Onboard.kt` (edit — implement)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/onboard/Steps.kt` (new — pure functions)
- `modules/cli-app/src/main/kotlin/com/hebe/cli/onboard/Prompts.kt` (new — TUI helpers via clikt)
- Tests with scripted input

### Detailed work

1. Steps:
   1. **LLM endpoint**: ask `base_url`. Default suggestion: the user's gateway. Validate with a `GET /models` ping; on failure, ask again.
   2. **API key**: prompt for the key (echo off); store under `llm.api_key` in secrets.
   3. **Default model + embedding model**: query `/models`, present a picker, default to `gpt-4o-mini` and `text-embedding-3-small` if available.
   4. **Admin password (web)**: prompt + confirm; store SHA-256 in `web.password` secret.
   5. **Telegram (optional)**: ask `enable Telegram? [y/N]`. If yes:
      - Prompt for bot token; ping `getMe`; store under `telegram.bot_token`.
      - Ask the user to message the bot from their personal Telegram; auto-detect operator id from the next inbound message (5-minute window) or accept manual input.
   6. **Generate config**: write `~/.hebe/config.toml` from `HebeConfig.minimal(...)` populated with the answers.
   7. **Seed workspace**: `WorkspaceSeeder.seedIfMissing(...)`.
   8. **Generate receipts signing key**: `SigningKey.bootstrap(...)`.
   9. **Finalise**: delete `BOOTSTRAP.md`; print "All set. Run `hebe doctor` to verify, then `hebe run` to start."

2. The wizard is **idempotent**: rerunning skips already-completed steps unless `--force`.

3. `--non-interactive` flag with answers via env vars (for scripted setups in containers).

### Tests / verification

- Scripted-input integration test runs all steps.
- Doctor reports green after onboarding.

### Acceptance criteria

- ✅ Interactive flow.
- ✅ Idempotent.
- ✅ Non-interactive variant.
- ✅ Doctor green after.

### References

- `v1-specs.md` §5 (acceptance criterion 1)

---

## M9.T5 — OTel exporter wiring + spans

**Status**: pending  
**Size**: M  
**Depends on**: M0.T7  
**Blocks**: nothing direct

### Goal

When `OTEL_EXPORTER_OTLP_ENDPOINT` is set, hebe exports spans for `dispatch.<tool>`, `memory.search`, `plugin.start`, `channel.reply` (and koog's spans). Otherwise no-op.

### Files to create

- `modules/observability/src/main/kotlin/com/hebe/observability/OtelBootstrap.kt` (new)
- Tests

### Detailed work

1. Use the OTel Java SDK (the `opentelemetry-sdk-extension-autoconfigure` module) for env-driven configuration.

2. Spans created via `Observer.span(name, attrs)`:
   - `dispatch.<tool>` — wraps the dispatcher's invoke step. Attrs: `tool.name`, `risk`, `ok`.
   - `memory.search` — wraps the FTS+vec query. Attrs: `query.length`, `k`.
   - `plugin.start` — wraps PF4J start. Attrs: `plugin.id`, `plugin.version`.
   - `channel.reply` — wraps each outbound. Attrs: `channel.name`.

3. Verify: against a local OTel collector (Jaeger UI or equivalent), spans are visible after a chat turn.

### Tests / verification

- Spans emitted via an in-memory exporter (OTel SDK testing utility).
- Endpoint env var unset → no-op exporter.

### Acceptance criteria

- ✅ Env-driven.
- ✅ Four span families.
- ✅ koog's spans exported alongside.

### References

- `v1-architecture.md` §21

---

## M9.T6 — Fat-JAR via Gradle Shadow + `./hebe` shell wrapper

**Status**: pending  
**Size**: S  
**Depends on**: M0.T1  
**Blocks**: M9.T2 (service runs the jar)

### Goal

`./gradlew shadowJar` produces a single JAR; a shell wrapper `./hebe` runs it.

### Files to create / modify

- `modules/cli-app/build.gradle.kts` (edit — finalise Shadow config)
- `hebe` (edit — production wrapper, replacing M0.T11's dev wrapper)
- Tests via CI

### Detailed work

1. `cli-app/build.gradle.kts`:

   ```kotlin
   tasks.shadowJar {
       archiveBaseName.set("hebe")
       archiveClassifier.set("")
       archiveVersion.set(project.version.toString())
       manifest {
           attributes["Main-Class"] = "com.hebe.cli.MainKt"
       }
       mergeServiceFiles()                  // important for ServiceLoader (e.g. Detekt / SLF4J)
       isZip64 = true                       // dependency count may exceed 65k entries
       // Logback's `Configurator` SPI — make sure it's in mergeServiceFiles
   }
   ```

2. Production wrapper:

   ```bash
   #!/usr/bin/env bash
   set -euo pipefail
   JAR_DIR="${KOKLYP_HOME:-/usr/local/lib/hebe}"
   JAR="$JAR_DIR/hebe.jar"
   if [ ! -f "$JAR" ]; then
       # dev fallback: run from build output
       JAR="$(dirname "$0")/modules/cli-app/build/libs/hebe.jar"
   fi
   exec java -jar "$JAR" "$@"
   ```

3. Document install: `mkdir -p /usr/local/lib/hebe && cp build/libs/hebe.jar /usr/local/lib/hebe/ && cp hebe /usr/local/bin/`.

### Tests / verification

- `./gradlew shadowJar` succeeds.
- `java -jar build/libs/hebe.jar --help` works.

### Acceptance criteria

- ✅ Single JAR built.
- ✅ Wrapper script installed.
- ✅ Service files merged correctly (Logback works in the JAR).

### Pitfalls

- Some libs use `provider-configuration files` (`META-INF/services/...`); without `mergeServiceFiles`, only the last copy wins and you'll have weird "no implementation" errors.
- Logback's auto-config requires `META-INF/services/ch.qos.logback.classic.spi.Configurator`; verify after Shadow.

### References

- `v1-specs.md` §2.12

---

## M9.T7 — `hebe completion bash/zsh/fish`

**Status**: pending  
**Size**: S  
**Depends on**: M0.T11  
**Blocks**: nothing

### Goal

Shell completion for subcommands.

### Files to create / modify

- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Completion.kt` (edit — implement)
- Tests

### Detailed work

1. Use clikt's built-in `installCompletion()` support (Clikt 5 has this baked in). Wire as:

   ```
   hebe completion bash > ~/.local/share/bash-completion/completions/hebe
   hebe completion zsh > ~/.zfunc/_hebe
   hebe completion fish > ~/.config/fish/completions/hebe.fish
   ```

2. The command writes the script to stdout; users redirect.

### Tests / verification

- Generated script syntactically valid (`bash -n`).

### Acceptance criteria

- ✅ Three shells supported.

### References

- `v1-specs.md` §2.11

---

## M9.T8 — `hebe status [--recent]`

**Status**: pending  
**Size**: S  
**Depends on**: M3.T8 (receipts), M5.T10 (channel health)  
**Blocks**: nothing

### Goal

Print recent receipts + last LLM call + channel health in a compact table.

### Files to create / modify

- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Status.kt` (edit — implement)
- Tests

### Detailed work

1. Default output: same as `/api/status` JSON, formatted as a table.

2. `--recent`: show the last 20 receipts.

3. `--watch`: refresh every 2 s (TUI-light; `clear` + redraw).

### Acceptance criteria

- ✅ Default summary.
- ✅ `--recent` shows receipts.
- ✅ `--watch` mode.

### References

- `v1-specs.md` §5 (acceptance criterion 3)
