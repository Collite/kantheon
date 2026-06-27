## MCP Server Communication

Rules for the agent ↔ MCP server channel (Python clients in `agents/*` ↔ Kotlin MCP servers in `tools/*`, streamable-HTTP / SSE transport). The cardinal sin is **no time budget and no exception boundary** — every layer must enforce its own.

### ✅ Do — Client side (Python)
- **Layer the timeouts**: `connect = 5s`, `session.initialize = 10s` (`asyncio.wait_for`), `load_mcp_tools = 15s`, `call_tool` default = `60s` with a **per-tool override map** (e.g. `fuzzy_match=15s`, `free_sql=180s`). Wrap every JSON-RPC exchange in `asyncio.wait_for` — a single global httpx timeout is not enough.
- Keep the SSE **read** timeout long (~300s) but operation-level timeouts short. Streamable-HTTP keeps the channel open by design; the budget belongs on each call, not on the transport.
- Run a periodic **health probe** on long-lived sessions (every ~30s, ping or `list_tools` with a short timeout). On failure, close the persistent session so the next call rebuilds it. Never trust an idle SSE stream to still be alive.
- Add a **circuit breaker**: after N consecutive connect failures, fail fast for a cooldown period instead of paying the retry latency on every request.
- **Parallelise startup** across multiple MCP servers with `asyncio.gather(...)` + per-server `wait_for`. A slow or down server must not block the others.
- Mirror the per-tool timeout map between client and server, so failures surface from whichever side hits the budget first.

### ✅ Do — Server side (Kotlin / Ktor)
- Wrap **every** tool callback in a safe wrapper that combines `withTimeout(...)` + `try/catch` for any `Throwable`, returning `CallToolResult(isError = true, content = listOf(TextContent(message)))`. An uncaught throw in the SDK handler kills the SSE stream silently and the client hangs forever.
- Install Ktor `StatusPages` in the shared MCP base as a backstop for any uncaught throw that escapes a handler.
- Set `connectionIdleTimeoutSeconds = 120` (or similar low value), **never 3600**. Zombie sessions should die in minutes, not hours.
- Always install `HttpTimeout` on every Ktor `HttpClient` used by a tool handler (`connectTimeoutMillis = 5_000`, `requestTimeoutMillis = 20–30_000`, `socketTimeoutMillis = 20–30_000`).
- For gRPC clients in tool handlers: use `stub.withDeadlineAfter(...)` on every call, and add channel keepalive (`keepAliveTime = 30s`, `keepAliveTimeout = 10s`, `keepAliveWithoutCalls = true`). TCP half-closes are otherwise undetected for minutes.
- Keep server **startup non-blocking**: catalog/index/schema loads belong in `CoroutineScope(Dispatchers.IO).launch { ... }` after `embeddedServer.start()`. The MCP endpoint must accept connections within ~2s.
- Expose `/health` (always 200 if the process is up) **and** `/ready` (reflects actual readiness — e.g. catalog loaded). Don't conflate them.
- Defensive arg parsing: a missing required field returns `CallToolResult(isError = true, "missing required argument: X")`, never `throw IllegalArgumentException`.
- For SSE/streamable-HTTP, expose `mcp-session-id` and `mcp-protocol-version` in CORS `exposeHeader` (already handled by `installMcpKtorBase`).
- **Log every tool callback return** with structured INFO (outcome summary with key params trimmed to 100 chars) and DEBUG (full `CallToolResult` object) levels. Example pattern:
  ```kotlin
  val result = CallToolResult(...)
  logger.info(
      "{tool_name} completed | {outcome} | keyParam={} | isError={}",
      keyValue?.take(100),
      result.isError,
  )
  logger.debug("{tool_name} {outcome} result: {}", result)
  return result
  ```
  This ensures agents receive consistent, observable responses via structured logs.

### ❌ Don't
- Don't rely on a single global httpx/transport timeout as your time budget. A 1-hour SSE read timeout means a hung tool call hangs the agent for 1 hour.
- Don't throw uncaught exceptions out of an MCP tool callback — they don't reliably serialise to JSON-RPC errors.
- Don't use `runBlocking { ... }` inside MCP tool callbacks — the callback signature is already `suspend`. `runBlocking` pins a Ktor worker thread and starves the engine under concurrent load.
- Don't use `println` or `e.printStackTrace()` for tool-handler errors — they bypass JSON/OTEL and won't show up in Loki. Use class-level SLF4J with structured fields (`toolName`, `durationMs`, `outcome`).
- Don't load slow downstream state synchronously during MCP server startup — clients (and K8s readiness probes) will time out.
- Don't catch `BaseException` in async Python MCP code — it swallows `CancelledError` / `KeyboardInterrupt` and breaks shutdown. Catch `Exception`.
- Don't keep a single persistent client session as the only connection without a health probe — a silent half-close means the next request waits the full read timeout before failing.
