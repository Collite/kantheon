# MCP Integration Guide

hebe speaks the [Model Context Protocol](https://modelcontextprotocol.io) in both directions:

- **Server**: hebe exposes its built-in tools to Claude Desktop, Cursor, Windsurf, or any other MCP-capable client.
- **Client**: hebe consumes external MCP servers as an additional tool source during agent turns.

---

## Part A: hebe as an MCP Server

### stdio transport (recommended for Claude Desktop / Cursor)

Add hebe to your MCP client's config. For Claude Desktop, edit `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "hebe": {
      "command": "/usr/local/bin/hebe",
      "args": ["mcp", "serve"]
    }
  }
}
```

Restart Claude Desktop. You should see hebe's tools appear in the tool list.

To start manually for debugging:

```bash
hebe mcp serve
# reads JSON-RPC from stdin, writes to stdout
```

### SSE transport (remote or LAN access)

Enable HTTP transport in `~/.hebe/config.toml`:

```toml
[mcp.server]
enabled   = true
stdio     = true
http_bind = "127.0.0.1"
http_port = 8766
```

The SSE endpoint is at `http://127.0.0.1:8766/mcp/sse`.

```json
{
  "mcpServers": {
    "hebe": {
      "url": "http://127.0.0.1:8766/mcp/sse"
    }
  }
}
```

### WebSocket transport

Same config block — the WebSocket endpoint is at `ws://127.0.0.1:8766/mcp/ws`. Not all MCP clients support WebSocket yet; SSE is the safer choice.

### Which tools are advertised?

By default, hebe advertises all tools with `risk = Low` or `risk = Medium`. `High`-risk tools (e.g. `shell`, `kubectl` mutating verbs) are hidden unless you opt in:

```toml
[mcp.server]
expose_high_risk = true   # default: false
```

### HTTP auth

When using SSE or WebSocket transports, the endpoint is protected by the same HTTP Basic auth as the web console. Set the `Authorization` header:

```
Authorization: Basic <base64(admin:<password>)>
```

---

## Part B: hebe as an MCP Client

### Adding an external MCP server

Each external server is a `[[mcp.client.servers]]` block in `~/.hebe/config.toml`.

**Example: local filesystem server (stdio)**

```toml
[[mcp.client.servers]]
name      = "filesystem"
transport = "stdio"
command   = ["npx", "@modelcontextprotocol/server-filesystem", "/Users/me/Documents"]
always_tools = ["read_file", "write_file", "list_directory"]
```

**Example: remote HTTP/SSE server**

```toml
[[mcp.client.servers]]
name      = "my-remote-mcp"
transport = "sse"
url       = "https://mcp.example.com/sse"
always_tools = []
dynamic_keywords = ["database", "query", "sql"]
```

### Tool filter groups

Each server entry has two filter strategies that control when its tools are injected into the agent's tool list:

| Field | Type | Behaviour |
|---|---|---|
| `always_tools` | `list[string]` | These tool names are always included in the tool list, every turn. |
| `dynamic_tools` | `list[string]` | Included only when at least one `dynamic_keywords` keyword matches the user's incoming message. |
| `dynamic_keywords` | `list[string]` | Keywords that trigger `dynamic_tools`. Case-insensitive substring match. |

Tools not listed in either group are never forwarded to the LLM. Use this to keep the context clean and tool counts low.

**Tool naming:** imported tools are prefixed: `mcp_<server_name>_<original_tool_name>`. A tool named `read_file` on a server named `filesystem` becomes `mcp_filesystem_read_file`.

### Per-server credential injection

For stdio servers that need env vars (API keys, tokens):

```toml
[[mcp.client.servers]]
name      = "linear"
transport = "stdio"
command   = ["npx", "@linear/mcp-server"]
secrets   = { LINEAR_API_KEY = "linear.api_key" }
```

`LINEAR_API_KEY` will be set in the subprocess environment with the value resolved from `secrets.db` under the key `linear.api_key`. The plugin or MCP server never sees the raw key in config files.

---

## Common errors and remediation

| Error | Likely cause | Fix |
|---|---|---|
| `mcp server not found` in Claude Desktop | Wrong path in `command` | Run `which hebe` and use the absolute path |
| `tool call failed: not in tool list` | Tool filtered by `always_tools` / `dynamic_tools` | Add tool name to `always_tools`, or include a keyword in the message |
| `transport disconnect` on SSE | hebe restarted or crashed | Check `hebe doctor`; restart hebe |
| `tool name collision` warning in logs | Two servers export the same tool name | Rename one server (`name = "..."`) so the prefix differs |
| Auth failure on SSE | Missing or wrong `Authorization` header | Verify the password set during `hebe onboard`; re-run to reset |
| `connection refused` | HTTP transport not enabled | Set `mcp.server.http_bind` and `http_port` in config |

---

## Reference: config schema (MCP blocks)

```toml
[mcp.server]
enabled          = true
stdio            = true          # bind mcp serve to stdin/stdout when subcommand is 'mcp serve'
http_bind        = ""            # empty disables HTTP transport
http_port        = 0
expose_high_risk = false         # advertise High-risk tools (default: false)

# Zero or more:
[[mcp.client.servers]]
name             = "my-server"
transport        = "stdio"       # stdio | sse | ws
command          = ["..."]       # stdio only
url              = ""            # sse | ws only
always_tools     = []            # tool names always in the tool list
dynamic_tools    = []            # tool names injected when a keyword matches
dynamic_keywords = []            # triggers for dynamic_tools
secrets          = {}            # { ENV_NAME = "secret_handle" }
```
