# ZeroClaw Internal Notes

This document pack summarizes ZeroClaw as a source system for koklyp. The focus is not feature catalog completeness. The focus is architecture, runtime model, and the design ideas most relevant to a Kotlin implementation.

## Document Map

- [architecture.md](./architecture.md) - layered workspace structure, traits, extension points
- [components.md](./components.md) - crate responsibilities and subsystem roles
- [logic-flow.md](./logic-flow.md) - inbound lifecycle, streaming tool calls, approvals, memory, SOP flow

## Quick Profile

| Area | ZeroClaw takeaway |
| --- | --- |
| Product shape | Single Rust runtime with many optional crates for channels, providers, tools, gateway, memory, and hardware |
| Core architectural style | Trait-driven layered workspace with strong compile-time feature gating |
| Runtime character | Streaming-first agent loop with security checks around tool calls |
| Extensibility | Clear Provider, Channel, and Tool interfaces in a dedicated API crate |
| Memory | Persistent conversations and optional embeddings plus consolidation |
| Security | Autonomy levels, approvals, sandboxing, and signed tool receipts |
| Product breadth | Messaging gateway, dashboard, SOP engine, cron, ACP, hardware |
| Portability risk | Wide integration surface, strong dependency on Rust trait and feature-flag idioms |

## What Matters Most For Koklyp

ZeroClaw contributes the strongest ideas in four areas:

1. A very clear separation between the runtime core and the concrete integrations.
2. Trait-based extension points for providers, channels, and tools.
3. End-to-end streaming through channels, runtime, providers, and tools.
4. A security model that mixes autonomy levels, approvals, and sandboxing.

## Reuse Bias

For koklyp, the highest-value ZeroClaw ideas are conceptual:

- stable API boundaries for integrations,
- layered workspace organization,
- a streaming-aware request lifecycle,
- event-driven automation through SOP and cron,
- generated or centralized configuration metadata.

The lower-value parts to copy literally are Rust-specific feature-flag and crate-splitting choices.

## Porting Notes

ZeroClaw feels closer to a microkernel than IronClaw. That makes it especially useful for deciding how koklyp should separate runtime, APIs, channels, tools, memory, and gateway code.

The main translation questions are:

- how strict the module boundaries should be in Kotlin,
- whether every integration should remain compile-time pluggable,
- how much of the SOP and hardware surface belongs in the first koklyp release,
- whether Kotlin's multiplatform story helps or hurts the desired operational feature set.