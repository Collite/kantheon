# IronClaw Architecture

## Overview
IronClaw is a secure, personal AI assistant built in Rust. It emphasizes security, local data ownership, and defense-in-depth mechanisms. The core principle is running untrusted tools in isolated WebAssembly (WASM) containers, protecting the user's host environment and credentials.

## Philosophy
- **Local Data:** Data is stored locally, encrypted, and remains under user control.
- **Transparency:** Open source, auditable, no telemetry.
- **Security-First:** Multiple layers of defense against prompt injection and data exfiltration.

## Core Components
- **Agent Loop:** Manages message handling, turn coordination, and tool execution loops.
- **Router:** Classifies user intent (commands vs queries vs background tasks).
- **Scheduler & Workers:** Manages parallel job execution with isolated contexts.
- **Orchestrator:** Handles Docker sandbox containers for more complex untrusted executions.
- **Web Gateway:** Provides a browser UI with real-time SSE/WebSocket streaming.
- **Routines Engine:** Manages scheduled (cron) and reactive (webhooks/events) background tasks.
- **Workspace:** Persistent memory utilizing hybrid search (full-text + vector embeddings via pgvector).
- **Safety Layer:** Filters prompt injections and manages content sanitization.

## Security Mechanisms
1. **WASM Sandboxing:** Untrusted skills are compiled to WASM. They run with capability-based permissions.
2. **Credential Protection:** Secrets are never passed directly to WASM tools. They are injected at the host boundary, and requests/responses are scanned for leaks.
3. **Endpoint Allowlisting:** Tools can only reach explicitly approved HTTP endpoints.

## Channels & Extensibility
- Supports multiple input channels (REPL, HTTP, Telegram, Slack).
- Tools can be added dynamically, either as WASM modules, Docker containers, or via the Model Context Protocol (MCP).
