# Koklyp Features List

Based on the research into IronClaw, ZeroClaw, OpenClaw, and Hermes, here is a comprehensive list of features we can implement in the Koklyp Kotlin agent.

## 1. Core Engine & Orchestration
- **Agent Loop & Intent Routing:** Distinct processing for direct conversations, background tasks, and commands.
- **Parallel Job Execution:** Ability to handle multiple tasks concurrently without blocking the main event loop.
- **Self-Evolution (Hermes inspired):** An internal feedback loop where the agent learns from experience, potentially writing or modifying its own Kotlin or WASM skills over time.

## 2. Channels & Interfaces
- **Messaging Integrations:** Telegram, WhatsApp, Slack, Discord, Email.
- **Web Gateway:** REST API and WebSocket endpoints for real-time frontend streaming.
- **Web Console / Dashboard:** A UI to monitor agent logs, approve/deny tool calls, and manage memory/configuration.
- **Terminal UI / CLI:** Fast access from the command line.

## 3. Sandboxing & Security
- **WASM Isolation (IronClaw style):** Run dynamically generated or untrusted skills in a WebAssembly sandbox (e.g., using Chicory or Extism).
- **Capability-based Permissions:** Explicit opt-ins required for tools to access network, filesystem, or secrets.
- **Credential Protection:** Inject secrets at the host boundary, never exposing them to the tool's raw execution environment. Leak scanning on outputs.
- **Supervised Autonomy (ZeroClaw style):** Define medium/high-risk actions that block the event loop and ping the user for approval via their preferred channel before proceeding.

## 4. Skills & Tools
- **Filesystem Tools:** Read/write capabilities across standard formats (Markdown, JSON, YAML, HTML).
- **API & Network Tools:** RESTful API caller, Web Search (Google/Tavily), browser automation.
- **DevOps Tools:** Git/GitHub integration, Kubectl, K3s/K8s cluster management.
- **Model Context Protocol (MCP):** Connect to standard MCP servers to instantly inherit vast capabilities without writing custom Kotlin bindings.

## 5. Memory & Persistence
- **Multi-Tier Memory:**
  - *Short-term:* Current session context.
  - *Long-term:* Vector database for embeddings and semantic search.
  - *Workspace:* Persistent filesystem-based workspace for notes (Wiki) and logs.
- **Auto-Consolidation:** Scheduled routines to summarize older chat sessions and extract key facts about the user.
- **Database Support:** PostgreSQL (with pgvector) for server deployments, or SQLite for local edge deployments.

## 6. Background Routines & Triggers
- **Cron-ish Scheduling:** Define periodic tasks (e.g., "Summarize my emails every morning at 8 AM").
- **Event-Driven SOPs (Standard Operating Procedures):** Trigger complex multi-step workflows based on webhooks, filesystem changes, or external events.
