# Koklyp Architecture

To achieve a modular, secure, and extensible autonomous agent in Kotlin, we will structure Koklyp around a clean, layered architecture inspired by IronClaw and ZeroClaw.

## High-Level Architecture Diagram
```
┌────────────────────────────────────────────────────────┐
│                      Interfaces                        │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────────┐  │
│  │   CLI   │ │ Channels│ │ Web API │ │ Web Dashboard│  │
│  └────┬────┘ └────┬────┘ └────┬────┘ └──────┬───────┘  │
│       └───────────┴───────────┴─────────────┘          │
│                          │                             │
│               ┌──────────▼──────────┐                  │
│               │   Agent Orchestrator│                  │
│               └────┬──────────┬─────┘                  │
│                    │          │                        │
│         ┌──────────▼──┐    ┌──▼──────────────┐         │
│         │ Router/Loop │    │ Scheduler/Cron  │         │
│         └──────┬──────┘    └────────┬────────┘         │
│                │                    │                  │
│         ┌──────┼────────────────────┘                  │
│         │      │                                       │
│   ┌─────▼──┐ ┌─▼───────────────┐   ┌────────────────┐  │
│   │ Memory │ │  Security Layer │   │ Provider (LLM) │  │
│   │ DB/RAG │ │  (Auth, Leaks)  │───► (OpenAI, etc.) │  │
│   └────────┘ └────────┬────────┘   └────────────────┘  │
│                       │                                │
│                ┌──────▼──────────┐                     │
│                │ Tool Execution  │                     │
│                ├─────────────────┤                     │
│                │ Built-in (JVM)  │                     │
│                │ WASM Sandbox    │                     │
│                │ MCP Clients     │                     │
│                └─────────────────┘                     │
└────────────────────────────────────────────────────────┘
```

## Module Breakdown

1. **`koklyp-core`**: The kernel. Contains the Agent Loop, Intent Router, Tool/Channel/Provider interfaces, and the core event bus.
2. **`koklyp-providers`**: Implementations for LLM endpoints (OpenAI, Anthropic, Ollama) using Ktor clients.
3. **`koklyp-channels`**: Ktor-based implementations for Slack, WhatsApp, Telegram, Email, and Webhooks.
4. **`koklyp-memory`**: Persistence layer using Exposed or Ktorm. Handles SQLite/PostgreSQL operations and Vector DB embeddings.
5. **`koklyp-tools`**: Tool registry and execution logic. Includes standard filesystem/network tools, Git/K8s integrations, and the WASM runtime sandbox.
6. **`koklyp-gateway`**: Ktor server hosting the Web Gateway API and WebSocket handlers for the frontend dashboard.

## Security & Sandboxing Architecture
- **WASM Isolation:** Untrusted skills will be executed inside a pure-Java/Kotlin WASM interpreter (e.g., Chicory) or via GraalVM WASM. This ensures no memory leaks or unauthorized system calls.
- **Boundary Control:** The `Security Layer` sits between the Agent Loop and the Tool Execution. If the LLM proposes a tool call, the Security Layer validates permissions, scans for secrets, and triggers "User Approval" flows for high-risk actions.
- **Provider Agnosticism:** LLM API interactions are completely abstracted, allowing seamless failover chains.
