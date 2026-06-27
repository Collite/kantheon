# ZeroClaw Architecture

## Overview
ZeroClaw is a layered, single-binary Rust runtime designed for autonomous agents. It focuses on being provider-agnostic, supporting a wide array of input channels, and strictly enforcing security boundaries while allowing for hardware and standard operating procedure (SOP) integrations.

## Core Architecture Layers
1. **External World:** UIs (CLI, chat platforms), LLMs (Anthropic, OpenAI, Ollama), Filesystems, and Network.
2. **Edge Crates:** 
   - `zeroclaw-channels`: 30+ messaging integrations (Discord, Matrix, Email, etc.).
   - `zeroclaw-gateway`: REST, WebSockets, and dashboard ingress.
   - `zeroclaw-providers`: Plug-and-play LLM clients with fallback and routing capabilities.
   - `zeroclaw-tools`: Callable tools (browser, HTTP, hardware).
3. **Core Layer:**
   - `zeroclaw-runtime`: Agent loop, security policy enforcement, SOP engine, and cron scheduler.
   - `zeroclaw-memory`: Conversation memory, embeddings, and consolidation via SQLite.
   - `zeroclaw-config`: TOML schemas, autonomy levels, and secrets encryption.
   - `zeroclaw-api`: Public traits (Kernel ABI) for Providers, Channels, and Tools.

## Key Features
- **Security & Sandboxing:** Default autonomy is "supervised" (medium/high-risk actions require user approval). Employs OS-level sandboxes (Landlock, Bubblewrap, Docker) and generates cryptographic "tool receipts" on every action.
- **Hardware Integrations:** Can run on edge devices like Raspberry Pi, supporting GPIO, I2C, SPI, and USB.
- **SOP Engine:** Standard Operating Procedures triggered by events (cron, MQTT, webhooks) with resumable execution.
- **Agent Client Protocol (ACP):** IDE and editor integrations via JSON-RPC.

## Request Lifecycle
When a message arrives via a Channel, the Runtime delivers it to the Agent Loop. The runtime queries the Provider, which streams text or tool calls. Any tool call is intercepted by the Security policy for approval. Once approved, the Tool executes, and results are fed back to the Provider to generate a final response to the user.
