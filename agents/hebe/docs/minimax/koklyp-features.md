# Koklyp Features

Comprehensive list of features for the Koklyp autonomous agent, derived from analysis of IronClaw, ZeroClaw, OpenClaw, and Hermes-Agent.

---

## 1. Core Agent

### 1.1 Agent Loop

| Feature | Description | Priority |
|---------|-------------|----------|
| **Turn-based conversation** | Process user messages, generate responses via LLM | Required |
| **Tool-use iteration** | Loop: LLM → tool call → execute → repeat until final response | Required |
| **Streaming responses** | Stream tokens to client as they're generated | Required |
| **Context management** | Maintain conversation history within session | Required |
| **Max iteration limit** | Configurable cap to prevent runaway loops (default: 10) | Required |
| **Cancellation support** | Allow user to interrupt mid-turn | Required |
| **Parallel tool execution** | Execute independent tool calls concurrently | High |
| **Sequential tool execution** | Execute dependent tool calls in order | High |

### 1.2 Session Management

| Feature | Description | Priority |
|---------|-------------|----------|
| **Multi-session support** | Multiple concurrent user sessions | Required |
| **Thread management** | Sessions can have multiple threads (conversations) | High |
| **Turn persistence** | Store all turns in database | Required |
| **Session pruning** | Clean up stale sessions after configurable timeout | Medium |
| **External ID mapping** | Map channel-specific thread IDs to internal UUIDs | High |
| **Session allowlist** | Control which users can access the agent | High |

### 1.3 Scheduler / Jobs

| Feature | Description | Priority |
|---------|-------------|----------|
| **Background job execution** | Run tasks asynchronously | Required |
| **Job state machine** | Pending → InProgress → Completed/Failed/Stuck | Required |
| **Job self-repair** | Detect stuck jobs and attempt recovery | High |
| **Job persistence** | Survive restarts | High |
| **Subtask spawning** | Spawn lightweight subtasks from within a job | Medium |
| **Batch execution** | Run multiple tasks concurrently, return results in order | Medium |

---

## 2. Channels

### 2.1 Channel Framework

| Feature | Description | Priority |
|---------|-------------|----------|
| **Channel trait** | Unified interface for all channel integrations | Required |
| **Channel manager** | Merge multiple channel streams into single input | Required |
| **Typing indicators** | Show "typing" while agent is processing | High |
| **Draft updates** | Progressive message editing (channel-dependent) | Medium |
| **Thread support** | Reply in threads where supported | High |
| **Multi-message delivery** | Stream long responses as multiple messages | Medium |
| **Reactions** | Add/remove emoji reactions to messages | Low |
| **Message pinning** | Pin/unpin messages | Low |
| **Message deletion** | Redact messages from channel | Low |

### 2.2 Supported Channels

| Channel | Status | Notes |
|---------|--------|-------|
| **Web (Browser UI)** | Required | SSE + WebSocket streaming |
| **Telegram** | Required | Bot API |
| **Slack** | Required | Events API + Socket Mode |
| **Discord** | High | Bot API |
| **WhatsApp** | High | WhatsApp Business API |
| **Email (SMTP/IMAP)** | High | Inbound + outbound |
| **Signal** | Medium | Signal CLI |
| **Matrix** | Medium | Matrix protocol |
| **Microsoft Teams** | Medium | Bot Framework |
| **iMessage** | Low | BlueBubbles server |
| **IRC** | Low | IRC protocol |
| **Mattermost** | Low | |
| **Nextcloud Talk** | Low | |
| **Nostr** | Low | |
| **Webhooks (inbound)** | Required | HTTP callback receiver |

### 2.3 Web UI

| Feature | Description | Priority |
|---------|-------------|----------|
| **Real-time streaming** | SSE/WebSocket for live updates | Required |
| **Message history** | Paginated conversation history | Required |
| **Thread switching** | Switch between conversation threads | High |
| **Settings panel** | Configure agent settings | High |
| **Skills management** | Install/remove skills | High |
| **Extension management** | Manage WASM extensions | High |
| **Job monitoring** | View active/pending jobs | Medium |
| **Usage statistics** | Token usage, cost tracking | Medium |
| **Admin dashboard** | User management, system status | Medium |

---

## 3. Tool System

### 3.1 Tool Framework

| Feature | Description | Priority |
|---------|-------------|----------|
| **Tool trait** | Unified interface for all tool implementations | Required |
| **Tool registry** | Discovery and registration of available tools | Required |
| **Tool dispatcher** | Route tool calls to appropriate executor | Required |
| **Rate limiting** | Per-tool sliding window rate limiting | Required |
| **Tool schema** | JSON Schema for tool parameter validation | Required |
| **Tool versioning** | Track tool versions, rollback capability | Low |

### 3.2 Tool Execution Environments

| Type | Description | Priority |
|------|-------------|----------|
| **Builtin tools (Kotlin)** | Core tools in host language | Required |
| **WASM sandbox** | Isolated tool execution with fuel metering | Required |
| **MCP servers** | Model Context Protocol external servers | High |
| **Process spawn** | Run tools in separate processes | Medium |

### 3.3 Builtin Tools

| Tool | Description | Priority |
|------|-------------|----------|
| **file_read** | Read files from workspace | Required |
| **file_write** | Write files to workspace | Required |
| **list_dir** | List directory contents | Required |
| **shell** | Execute shell commands | Required |
| **http_request** | Make HTTP requests | Required |
| **web_fetch** | Fetch web page content | High |
| **memory_search** | Hybrid FTS + vector search | Required |
| **memory_write** | Write to memory | Required |
| **memory_read** | Read from memory | Required |
| **memory_tree** | View workspace structure | High |
| **json** | Parse and manipulate JSON | High |
| **yaml** | Parse and manipulate YAML | Medium |
| **html** | Parse and manipulate HTML | Medium |
| **tool_search** | Search for MCP tools (deferred loading) | High |
| **job_create** | Create background job | High |
| **job_status** | Check job status | High |
| **job_cancel** | Cancel running job | High |
| **routine_list** | List scheduled routines | Medium |
| **routine_create** | Create new routine | Medium |
| **routine_trigger** | Manually trigger routine | Medium |
| **extension_tools** | Tools from WASM extensions | Required |
| **skill_tools** | Invoke registered skills | High |
| **secrets_tools** | Manage encrypted secrets | High |
| **model_switch** | Switch LLM model mid-conversation | Medium |

### 3.4 WASM Tool System

| Feature | Description | Priority |
|---------|-------------|----------|
| **Fuel metering** | Limit execution based on fuel consumed | Required |
| **Memory limits** | Cap memory usage per tool | Required |
| **Network allowlist** | Restrict outbound HTTP to approved domains | Required |
| **Credential injection** | Inject secrets without exposing to tool code | Required |
| **Output scanning** | Detect and redact leaked secrets | Required |
| **Linear memory persistence** | Persist data across tool calls | Medium |
| **WASM tool discovery** | Auto-discover tools from filesystem | High |

---

## 4. LLM Providers

### 4.1 Provider Framework

| Feature | Description | Priority |
|---------|-------------|----------|
| **Provider trait** | Unified interface for all LLM backends | Required |
| **Multi-provider fallback** | Try alternate provider on failure | Required |
| **Circuit breaker** | Fast-fail after repeated errors | High |
| **Retry with backoff** | Exponential backoff on retryable errors | Required |
| **Response caching** | Cache non-deterministic responses | Medium |
| **Token counting** | Track input/output tokens | Required |
| **Cost tracking** | Track cost per provider/model | High |
| **Streaming** | Support streaming responses | Required |

### 4.2 Supported Providers

| Provider | Status | Notes |
|----------|--------|-------|
| **OpenAI** | Required | Chat Completions API |
| **Anthropic** | Required | Messages API |
| **OpenRouter** | High | 200+ models via unified API |
| **Ollama** | High | Local models |
| **Nous Portal** | Medium | Nous Research |
| **NVIDIA NIM** | Medium | Nemotron models |
| **Kimi/Moonshot** | Medium | |
| **Hugging Face** | Medium | |
| **AWS Bedrock** | Low | Via Converse API |
| **Google Vertex AI** | Low | |
| **Custom OpenAI-compatible** | Required | For self-hosted models |

### 4.3 LLM Features

| Feature | Description | Priority |
|---------|-------------|----------|
| **Native tool calling** | Use provider's native function calling | Required |
| **Prompt-guided fallback** | Inject tools as text when native unavailable | Required |
| **Vision/image input** | Support multimodal models | Medium |
| **Prompt caching** | Leverage provider caching when available | Medium |
| **Reasoning content** | Preserve thinking/thought process | Medium |
| **Model routing** | Route queries to appropriate model | Medium |

---

## 5. Memory & Workspace

### 5.1 Workspace

| Feature | Description | Priority |
|---------|-------------|----------|
| **Filesystem structure** | Hierarchical directory layout | Required |
| **Identity files** | IDENTITY.md, SOUL.md, AGENTS.md, USER.md | Required |
| **Daily logs** | Auto-create daily log files | High |
| **Project directories** | Arbitrary project structure | Medium |
| **Context files** | .context/ directory for project context | Medium |

### 5.2 Memory Store

| Feature | Description | Priority |
|---------|-------------|----------|
| **Persistent storage** | Store memories across sessions | Required |
| **Full-text search** | Keyword-based search | Required |
| **Vector search** | Semantic similarity search | High |
| **Hybrid search (RRF)** | Combine FTS + vector with Reciprocal Rank Fusion | High |
| **Time decay** | Older memories score lower | Medium |
| **Memory categories** | Core, Daily, Conversation, UserPreference | Medium |
| **Auto-save** | Automatically save conversation turns | High |

### 5.3 Memory Tools

| Tool | Description | Priority |
|------|-------------|----------|
| **memory_search** | Search with query, return relevant entries | Required |
| **memory_write** | Write key-value entry to memory | Required |
| **memory_read** | Read specific entry by key | Required |
| **memory_tree** | View memory structure | High |

---

## 6. Skills System

### 6.1 Skills Framework

| Feature | Description | Priority |
|---------|-------------|----------|
| **SKILL.md format** | File-based skill definition | Required |
| **Skill selection** | Select relevant skills per conversation | Required |
| **Skill trust levels** | Trusted (user-created) vs Installed (registry) | Required |
| **Skill tools** | skill_list, skill_search, skill_install, skill_remove | Required |

### 6.2 Skill Selection Pipeline

| Stage | Description | Priority |
|-------|-------------|----------|
| **Gating** | Check bin/env/config requirements | High |
| **Scoring** | Keyword/pattern/tag matching | Required |
| **Budget** | Fit within token budget | High |
| **Attenuation** | Trust-based tool access limits | Medium |

### 6.3 Skill Features

| Feature | Description | Priority |
|---------|-------------|----------|
| **Skill creation** | Create new skills from experience | Medium |
| **Skill improvement** | Improve skills during use | Medium |
| **Skill marketplace** | Browse/install skills from registry | Medium |
| **Skill auditing** | Log skill execution | High |

---

## 7. Routines & Scheduling

### 7.1 Routine Engine

| Feature | Description | Priority |
|---------|-------------|----------|
| **Cron scheduling** | Time-based trigger (cron expression) | Required |
| **Event triggers** | React to external events | High |
| **Manual trigger** | User-triggered execution | Required |
| **Routine persistence** | Survive restarts | Required |
| **Routine runs history** | Log of routine executions | High |

### 7.2 Heartbeat System

| Feature | Description | Priority |
|---------|-------------|----------|
| **Periodic execution** | Run agent on schedule | Medium |
| **HEARTBEAT.md** | Checklist file read by heartbeat | Medium |
| **Channel notification** | Notify via configured channel | Medium |
| **Silence on OK** | No notification when nothing found | Medium |

### 7.3 Cron Features

| Feature | Description | Priority |
|---------|-------------|----------|
| **Cron expressions** | Standard cron syntax | Required |
| **Timezone support** | Configurable timezone | High |
| **Jitter/randomization** | Add randomness to execution times | Low |
| **Catchup execution** | Run missed executions on startup | Medium |

---

## 8. Security

### 8.1 Secrets Management

| Feature | Description | Priority |
|---------|-------------|----------|
| **Encryption at rest** | AES-256-GCM encryption | Required |
| **OS keychain integration** | Use system keychain for master key | Required |
| **Secret injection** | Inject into tools without exposure | Required |
| **Secret usage audit** | Log credential access | High |

### 8.2 Sandbox Isolation

| Feature | Description | Priority |
|---------|-------------|----------|
| **Docker sandbox** | Run untrusted tools in containers | High |
| **Process isolation** | Run tools in separate processes | Medium |
| **Filesystem boundaries** | Prevent access outside workspace | Required |
| **Network restrictions** | Allowlist permitted endpoints | High |
| **Resource limits** | CPU, memory, time limits | High |

### 8.3 DM Security

| Feature | Description | Priority |
|---------|-------------|----------|
| **DM pairing** | Unknown senders must pair first | Required |
| **Pairing code** | Short code for approval | Required |
| **Allowlist** | Pre-approved sender list | High |
| **Blocking** | Block specific senders | Medium |

### 8.4 Safety Layer

| Feature | Description | Priority |
|---------|-------------|----------|
| **Input sanitization** | Sanitize user input | Required |
| **Output sanitization** | Sanitize tool output | Required |
| **Prompt injection detection** | Detect malicious input patterns | High |
| **Secret leak detection** | Scan output for credentials | Required |
| **Output redaction** | Redact detected secrets | Required |

---

## 9. Hooks & Observability

### 9.1 Lifecycle Hooks

| Hook | Description | Priority |
|------|-------------|----------|
| **BeforeInbound** | Pre-process incoming messages | Medium |
| **BeforeToolCall** | Pre-validate tool execution | Medium |
| **BeforeOutbound** | Pre-process outgoing responses | Medium |
| **OnSessionStart** | Session initialization | Medium |
| **OnSessionEnd** | Session cleanup | Low |
| **TransformResponse** | Modify response before delivery | Low |

### 9.2 Observability

| Feature | Description | Priority |
|---------|-------------|----------|
| **Observer trait** | Pluggable event recording | Required |
| **Event types** | LLM requests/responses, tool calls, etc. | Required |
| **Log streaming** | SSE endpoint for live logs | High |
| **Runtime log level** | Adjust log level at runtime | Medium |
| **Tracing** | Distributed tracing support | Low |
| **Metrics** | Prometheus-compatible metrics | Medium |

---

## 10. Extensions

### 10.1 Extension System

| Feature | Description | Priority |
|---------|-------------|----------|
| **Extension manifest** | Metadata + capabilities declaration | Required |
| **Extension registry** | Local + remote registry discovery | High |
| **Extension installation** | Download, verify, install | Required |
| **Extension activation** | Load and register extension | Required |
| **OAuth flow** | Browser-based OAuth for extension auth | High |

### 10.2 Extension Types

| Type | Description | Priority |
|------|-------------|----------|
| **WASM channels** | Custom channel implementations | Medium |
| **WASM tools** | New tool capabilities | High |
| **MCP servers** | External MCP server connections | High |

---

## 11. Configuration & Management

### 11.1 Configuration

| Feature | Description | Priority |
|---------|-------------|----------|
| **YAML configuration** | File-based configuration | Required |
| **Environment variables** | Override config via env vars | Required |
| **Secret references** | `${SECRET_NAME}` syntax | Required |
| **Config validation** | Validate on startup | High |
| **Config export/import** | Export/import configuration | Medium |

### 11.2 CLI Commands

| Command | Description | Priority |
|---------|-------------|----------|
| **run** | Start the agent | Required |
| **onboard** | First-time setup wizard | High |
| **config** | View/edit configuration | High |
| **tool** | Tool management (install, list, auth) | High |
| **registry** | Extension registry commands | Medium |
| **memory** | Memory management | Medium |
| **pairing** | Manage paired devices | Medium |
| **service** | Install/uninstall as system service | Medium |
| **doctor** | Diagnose issues | High |
| **status** | Show current status | Medium |
| **completion** | Shell completion scripts | Low |

### 11.3 Onboarding

| Feature | Description | Priority |
|---------|-------------|----------|
| **Setup wizard** | TUI-guided first-time setup | High |
| **Provider selection** | Choose and configure LLM provider | Required |
| **Channel setup** | Configure messaging channels | High |
| **Credential setup** | Enter API keys, OAuth flows | Required |
| **Workspace initialization** | Create initial workspace structure | Required |

---

## 12. Database & Persistence

### 12.1 Database Backends

| Backend | Description | Priority |
|---------|-------------|----------|
| **SQLite** | Local file-based database | Required |
| **PostgreSQL** | Server-grade relational database | High |

### 12.2 Data Stored

| Data | Storage | Priority |
|------|---------|----------|
| **Sessions** | Database | Required |
| **Messages/Turns** | Database | Required |
| **Jobs** | Database | Required |
| **Routines** | Database | Required |
| **Settings** | Database | High |
| **Tool failures** | Database | Medium |
| **Leak detection events** | Database | High |
| **Secret usage log** | Database | Medium |
| **Rate limit state** | Database | Medium |
| **LLM call logs** | Database | Medium |

---

## 13. Infrastructure Tools

### 13.1 Git & GitHub

| Feature | Description | Priority |
|---------|-------------|----------|
| **Git operations** | clone, pull, push, branch, commit | High |
| **GitHub API** | Issues, PRs, repos, actions | High |
| **GitHub authentication** | OAuth or token-based | Required |

### 13.2 Kubernetes

| Feature | Description | Priority |
|---------|-------------|----------|
| **kubectl integration** | Execute kubectl commands | High |
| **Cluster context** | Multi-cluster support | Medium |
| **Pod logs** | Read pod logs | Medium |
| **Deployment status** | Check deployment health | Medium |
| **Secret management** | Read/create k8s secrets | Medium |

### 13.3 Web & Network

| Feature | Description | Priority |
|---------|-------------|----------|
| **REST API calls** | Generic HTTP client | Required |
| **Web scraping** | Fetch and parse web pages | High |
| **Web search** | Search the web | Medium |
| **Webhook delivery** | Send webhook callbacks | High |

---

## 14. System Integration

### 14.1 Service Management

| Feature | Description | Priority |
|---------|-------------|----------|
| **Daemon mode** | Run as background service | Required |
| **Auto-start** | Start on system boot | High |
| **Graceful shutdown** | Handle SIGTERM properly | Required |
| **PID file** | Track process ID | Medium |

### 14.2 Tunneling

| Feature | Description | Priority |
|---------|-------------|----------|
| **Cloudflare tunnel** | Expose via cloudflared | Medium |
| **Ngrok** | Expose via ngrok | Medium |
| **Tailscale** | Expose via Tailscale | Medium |
| **Custom tunnel** | Arbitrary tunnel command | Low |

---

## Priority Summary

### Required (MVP)
- Agent loop with tool execution
- Session management with persistence
- Web UI (SSE + WebSocket)
- Telegram + Slack channels
- Builtin tools (file, shell, http)
- Memory search + workspace
- SQLite persistence
- Secret encryption
- LLM provider abstraction (OpenAI + Anthropic)
- CLI with basic commands

### High Priority (v1.0)
- MCP server support
- WASM sandbox
- WhatsApp + Email channels
- Git + GitHub tools
- Routine scheduling
- Skill system
- PostgreSQL support
- Self-repair for stuck jobs

### Medium Priority (v1.1+)
- Kubernetes tools
- Web search
- Skill creation/improvement
- Discord + Signal channels
- Tailscale tunneling
- Observability/metrics
- Tool versioning

### Low Priority (Future)
- Hardware/peripherals
- Mobile companion
- Team collaboration
- Plugin marketplace
- TEE integration