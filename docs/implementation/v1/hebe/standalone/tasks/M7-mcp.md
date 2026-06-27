# M7 — MCP

MCP server exposing hebe tools, MCP client consuming external servers, transports (stdio + SSE/WS via Ktor), tool filter groups, per-server credential injection.

**Done when:** a sample stdio MCP server is consumable from a chat turn AND hebe's `file_system` tool is callable from Claude Desktop.

References: [`../v1-architecture.md`](../v1-architecture.md) §§14, 15.

---

## M7.T1 — MCP Kotlin SDK integration baseline

**Status**: pending  
**Size**: M  
**Depends on**: M0.T2  
**Blocks**: M7.T2 onward

### Goal

A hello-world MCP stdio server using the MCP Kotlin SDK; verify the SDK pinned in `libs.versions.toml` works.

### Files to create

- `modules/mcp-server/build.gradle.kts` (edit)
- `modules/mcp-server/src/main/kotlin/com/hebe/mcp/McpServer.kt` (new — minimal)
- A throwaway test that connects a programmatic client to the server

### Detailed work

1. Deps:

   ```kotlin
   implementation(libs.mcp.kotlin.sdk)
   ```

2. Hello-world server:

   ```kotlin
   class McpServer(name: String, version: String) {
       private val server = Server(serverInfo = ServerInfo(name, version), capabilities = ServerCapabilities(tools = ToolsCapability()))
       fun start(transport: Transport) = server.connect(transport)
       fun registerTool(spec: McpToolSpec, handler: suspend (JsonObject) -> CallToolResult) = server.addTool(...)
   }
   ```

   (Pseudo-code; verify the SDK's exact API. The shape will differ slightly — confirm at PR time.)

3. Smoke test: register one tool, connect a programmatic client, call `tools/list` + `tools/call`, assert the result.

### Tests / verification

- Smoke test passes against the SDK version pinned.

### Acceptance criteria

- ✅ SDK works.
- ✅ Hello-world tool callable.

### Pitfalls

- The MCP Kotlin SDK is young; the API surface may have moved between versions. Pin a specific version in the catalogue and document the contract.

### References

- `v1-architecture.md` §15

---

## M7.T2 — MCP server: expose hebe tools

**Status**: pending  
**Size**: L  
**Depends on**: M2.T6, M7.T1  
**Blocks**: M7.T3

### Goal

Bridge `ToolRegistry` → MCP server. Every tool whose `risk` is `Low` or `Medium` is advertised. `High` tools are gated by `mcp.server.expose_high_risk` (default `false`).

### Files to create

- `modules/mcp-server/src/main/kotlin/com/hebe/mcp/ToolBridge.kt` (new)
- `modules/mcp-server/src/main/kotlin/com/hebe/mcp/ToolFilter.kt` (new)
- Tests

### Detailed work

1. `ToolBridge.bridge(registry: ToolRegistry, server: Server, config: McpServerConfig)`:
   - For each tool in `registry.list()`:
     - If `tool.risk == High` and `!config.exposeHighRisk` → skip.
     - Else `server.addTool(name = tool.spec.name, description = tool.spec.description, inputSchema = tool.spec.schema, handler = bridgeHandler(tool))`.

2. `bridgeHandler(tool)` translates an MCP `CallToolRequest` to `Tool.invoke`:
   - Construct a `ToolContext` for an "mcp:remote-caller" session id (synthetic).
   - Run through the dispatcher (so policies still apply).
   - Translate `ToolResult.Ok` → MCP `CallToolResult.success`; `Err` → `CallToolResult.error`.

3. **Critical**: tool calls from the MCP server still run through `ToolDispatcher` — same security policies, receipts, leak detection. Don't bypass.

### Tests / verification

- Programmatic MCP client lists tools → sees `file_system_read`, etc.
- Calling `file_system_read` via MCP → result returned; receipts written; same policy enforcement as in-process.

### Acceptance criteria

- ✅ Tool bridge works.
- ✅ High-risk gating respected.
- ✅ Dispatcher still in the path.

### References

- `v1-architecture.md` §15

---

## M7.T3 — MCP transports: stdio + SSE/WS via Ktor

**Status**: pending  
**Size**: M  
**Depends on**: M5.T3 (gateway), M7.T2  
**Blocks**: M10.T4 (MCP integration guide)

### Goal

Two transports:
- **stdio**: `hebe mcp serve` reads from stdin, writes to stdout. Used by Claude Desktop / Cursor configs.
- **HTTP/SSE/WS**: routes on the gateway at `/mcp/sse` and `/mcp/ws`.

### Files to create

- `modules/cli-app/src/main/kotlin/com/hebe/cli/commands/Mcp.kt` (edit — implement `serve`)
- `modules/mcp-server/src/main/kotlin/com/hebe/mcp/StdioTransport.kt` (new — wraps SDK's stdio transport)
- `modules/mcp-server/src/main/kotlin/com/hebe/mcp/KtorTransports.kt` (new — registers gateway routes)
- Tests

### Detailed work

1. **Stdio**: `hebe mcp serve` boots minimal AppComponents (no channels, no scheduler — just enough for the dispatcher), connects MCP server to stdin/stdout.

2. **HTTP routes** (registered when `config.mcp.server.http_bind != ""`):

   ```kotlin
   fun Route.mcpRoutes(server: Server) {
       sse("/mcp/sse") { server.connect(SseServerTransport(call)) }
       webSocket("/mcp/ws") { server.connect(WebSocketServerTransport(call)) }
   }
   ```

   Auth: same Basic auth as the rest of the gateway. (For automation with a non-browser client, document the Basic auth headers in the integration guide.)

3. Streamable HTTP (the new MCP transport variant) — confirm SDK support; if available, add at `/mcp/http`.

### Tests / verification

- Stdio: programmatic client over stdio calls `tools/list`.
- SSE: a TestApplication call to `/mcp/sse` with Basic auth establishes a session and lists tools.

### Acceptance criteria

- ✅ Stdio works for desktop clients.
- ✅ SSE + WS routes mounted on the gateway.
- ✅ Auth applied.

### References

- `v1-architecture.md` §15

---

## M7.T4 — MCP client: consume external MCP servers

**Status**: pending  
**Size**: L  
**Depends on**: M7.T1, M2.T6  
**Blocks**: M7.T5, M7.T6

### Goal

Read `[[mcp.client.servers]]` from config; for each server, spawn the configured transport, subscribe to its tools, expose them in `ToolRegistry` with the prefix `mcp_<server>_<tool>`.

### Files to create

- `modules/tools/mcp-client/build.gradle.kts` (edit)
- `modules/tools/mcp-client/src/main/kotlin/com/hebe/tools/mcp/McpClientManager.kt` (new)
- `modules/tools/mcp-client/src/main/kotlin/com/hebe/tools/mcp/RemoteTool.kt` (new — wraps a remote tool as `Tool`)
- `modules/tools/mcp-client/src/main/kotlin/com/hebe/tools/mcp/Transports.kt` (new — stdio/sse/ws)
- Tests

### Detailed work

1. Deps:

   ```kotlin
   implementation(libs.mcp.kotlin.sdk)
   implementation(project(":modules:api"))
   ```

2. `McpClientManager.connect(serverConfigs: List<ServerConfig>)`:
   - For each:
     - Spawn process for `stdio` (`ProcessBuilder` with `command`); pipe stdin/stdout.
     - Or open SSE/WS to a configured URL.
     - Run `tools/list` to fetch the catalogue.
     - For each remote tool, create a `RemoteTool` adapter and register in `ToolRegistry` as `mcp_<serverName>_<toolName>`.

3. `RemoteTool.invoke(args, ctx)` calls `client.callTool(name, args)` and translates the `CallToolResult` into `ToolResult`. Risk inference: external MCP tools default to `Medium` (operator can opt to elevate via per-server config).

4. Lifecycle: graceful shutdown of subprocesses on hebe shutdown; restart on disconnect (capped retries); reflect status in `hebe doctor`.

### Tests / verification

- Spawn a sample stdio MCP server (`@modelcontextprotocol/server-filesystem` or a synthetic Kotlin one), list its tools, call one.
- Disconnect the underlying transport → `RemoteTool.invoke` returns `Err(retriable=true)`.

### Acceptance criteria

- ✅ Stdio transport works.
- ✅ Tools exposed under `mcp_<server>_<tool>`.
- ✅ Disconnect/reconnect handled.

### References

- `v1-architecture.md` §15

---

## M7.T5 — Tool filter groups (`Always` + `Dynamic` + keywords)

**Status**: pending  
**Size**: M  
**Depends on**: M7.T4  
**Blocks**: nothing

### Goal

Per-server filter so we don't advertise hundreds of remote tools every turn. `Always` group exposes tools unconditionally; `Dynamic` exposes only when the user message contains a keyword.

### Files to create

- `modules/tools/mcp-client/src/main/kotlin/com/hebe/tools/mcp/McpToolFilter.kt` (new)
- Tests

### Detailed work

1. Config (per server):

   ```toml
   [[mcp.client.servers]]
   name = "fs"
   always_tools = ["read_file", "write_file"]
   dynamic_tools = ["search_files"]
   dynamic_keywords = ["search", "find", "lookup"]
   ```

2. `McpToolFilter.applicableTools(serverName, allRemoteTools, userMessage): List<String>`:
   - Always include `always_tools` matches.
   - For `dynamic_tools`, include only if `dynamic_keywords` substring-matches the user message (case-insensitive).

3. Wired into `ChatDelegate.callLlm` (M2.T11): before constructing the `tools` list, apply the filter using the latest user message.

### Tests / verification

- `always_tools = ["a"], dynamic_tools = ["b"], dynamic_keywords = ["beta"]` and user msg `"hello"` → `["a"]`.
- Same with user msg `"please search beta"` → `["a", "b"]`.

### Acceptance criteria

- ✅ Two modes implemented.
- ✅ Per-turn filter applied.

### References

- `v1-architecture.md` §15

---

## M7.T6 — Per-server credential injection

**Status**: pending  
**Size**: M  
**Depends on**: M7.T4, M0.T9  
**Blocks**: nothing direct

### Goal

When spawning a stdio MCP server, populate its env from declared secrets so the server can authenticate without us writing creds into config files.

### Files to create / modify

- `modules/tools/mcp-client/src/main/kotlin/com/hebe/tools/mcp/Transports.kt` (edit)
- Tests

### Detailed work

1. Config (per server):

   ```toml
   [[mcp.client.servers]]
   name = "linear"
   transport = "stdio"
   command = ["npx", "@example/linear-mcp"]
   secrets = { LINEAR_API_KEY = "linear.api_key" }
   ```

2. When spawning, build the env by:
   - Inheriting the current process env (filtered through the same denylist as `PluginHost.env` — so other secrets in hebe's env don't leak to the MCP server).
   - Adding entries from `secrets` mapping: `key = name in remote env`, `value = SecretStore.get(value)`.

3. Logging: redact all secret values in any startup logs (`[REDACTED]`).

### Tests / verification

- Spawn a stdio echo server that prints its env on startup; assert the configured secrets are present and other secrets aren't.

### Acceptance criteria

- ✅ Secrets injected at the boundary.
- ✅ Other env vars not leaked.

### Pitfalls

- A misconfigured `secrets` mapping (referring to a non-existent secret name) should fail loudly at boot, not silently launch the server with an empty env var.

### References

- `v1-architecture.md` §15
