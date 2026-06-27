# ZeroClaw Components

## Crate Inventory

| Crate | Responsibility | Koklyp relevance |
| --- | --- | --- |
| `zeroclaw-runtime` | Agent loop, security, SOP, cron, onboarding, TUI orchestration | Main reference for runtime decomposition |
| `zeroclaw-config` | TOML schema, secrets, autonomy, workspace resolution | Strong model for explicit configuration ownership |
| `zeroclaw-api` | Provider, Channel, Tool interfaces | High-value pattern to reuse almost directly |
| `zeroclaw-providers` | Concrete LLM adapters, routing, fallback, streaming helpers | Relevant for provider-agnostic model layer |
| `zeroclaw-channels` | Messaging platform adapters and orchestration | Good reference for adapter boundaries |
| `zeroclaw-gateway` | REST, WebSocket, dashboard, webhook ingress | Strong hint that koklyp needs a separate gateway slice |
| `zeroclaw-tools` | Agent-callable tools | Useful for built-in tool organization |
| `zeroclaw-memory` | conversation storage, embeddings, consolidation | Relevant for koklyp memory tier design |
| `zeroclaw-tool-call-parser` | provider-specific tool-call normalization | Good idea if provider syntax differences become painful |
| `zeroclaw-plugins` | dynamic out-of-process plugins | Likely later-phase for koklyp |
| `zeroclaw-hardware` | GPIO, I2C, SPI, USB abstraction | Likely out of scope for koklyp MVP |
| `zeroclaw-infra` | tracing, metrics, logging | Should exist in koklyp, likely simpler initially |
| `zeroclaw-macros` | code-generation helpers | Optional convenience, not architectural core |
| `zeroclaw-tui` | terminal UI | Useful if koklyp wants a serious CLI mode |

## Core Runtime Components

### Runtime

The runtime is the real control plane. It owns the request lifecycle, policy enforcement, memory access, and automation.

Koklyp lesson: even with modular APIs, one module still needs to own conversation orchestration.

### Config

Configuration is treated as a product surface, not just a bag of environment variables. Schema and validation are centralized.

Koklyp lesson: if channels, providers, memory, and security are all configurable, the configuration model needs its own module early.

### API Contracts

The API crate is the cleanest reusable pattern in ZeroClaw. It avoids wiring core logic directly to specific integrations.

Koklyp lesson: define small contracts for providers, channels, tools, and perhaps memory backends before building many adapters.

## Integration Components

### Providers

Provider adapters are isolated from the runtime and support fallback and routing. ZeroClaw clearly expects model heterogeneity.

Koklyp lesson: avoid a runtime that assumes a single provider or a single tool-call syntax.

### Channels

Channel adapters handle decode, deduplication, pair-check, outbound streaming, and message splitting. This shows that channels are not thin wrappers around sendMessage APIs; they embody a lot of messaging policy.

Koklyp lesson: keep a small first channel set, but preserve the adapter boundary from day one.

### Gateway

The gateway is a first-class boundary for APIs, streaming, web dashboard, and webhook ingress.

Koklyp lesson: do not bury HTTP concerns inside the core runtime. Give them an adapter layer or dedicated service module.

### Tools

Tools are separate from CLI commands and are described to the model as callable capabilities. This distinction matters.

Koklyp lesson: keep operator commands and LLM-callable tools separate in both API and UX.

## Support Components

### Memory

Memory handles transcript persistence, retrieval, and consolidation. It is intentionally separable from the runtime.

Koklyp lesson: memory should be its own subsystem, not a helper glued to conversation storage.

### Tool-Call Parser

Normalizes vendor-specific tool-call formats. This component exists because providers do not speak one clean protocol.

Koklyp lesson: if the provider abstraction gets messy, isolate parsing rather than polluting the agent loop.

### Plugins

Plugins give ZeroClaw a path to extend tools out of process.

Koklyp lesson: useful long term, probably unnecessary for the first milestone if WASM or MCP already provide extension paths.

### Infra

Centralized tracing and metrics keep the workspace from scattering observability logic.

Koklyp lesson: add structured telemetry early, even if the first version is light.

## Recommended Carry-Over Priority

Carry over in the first koklyp architecture:

- API contracts for provider, channel, and tool.
- Separate gateway module.
- Memory subsystem boundary.
- Streaming-aware runtime interfaces.
- Explicit config model.

Carry over later if needed:

- tool-call parser as a separate module,
- plugin runtime,
- hardware subsystem,
- ACP integration,
- full TUI.