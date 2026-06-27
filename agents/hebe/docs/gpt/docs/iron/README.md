# IronClaw Internal Notes

This document pack summarizes IronClaw as a source system for koklyp. It is not a user guide; it is an internal architecture digest meant to answer three questions:

1. How is IronClaw structured?
2. Which design ideas are worth carrying into a Kotlin implementation?
3. Which parts are powerful but expensive to port?

## Document Map

- [architecture.md](./architecture.md) - system structure, core abstractions, invariants
- [components.md](./components.md) - subsystem responsibilities and integration points
- [logic-flow.md](./logic-flow.md) - request lifecycle, approvals, memory compaction, jobs

## Quick Profile

| Area | IronClaw takeaway |
| --- | --- |
| Product shape | Personal assistant with many channels, tools, background execution, and a strong operator surface |
| Core execution model | Session -> thread -> turn, backed by a shared agentic loop engine and scheduler |
| Tool model | Native built-ins plus sandboxed WASM tools plus MCP, all exposed behind a common Tool abstraction |
| Security model | Defense in depth: safety layer, approval gates, credential separation, WASM capability model, container sandbox |
| Memory model | Workspace-as-durable-memory backed by database storage and hybrid search |
| Persistence | Dual backend: PostgreSQL and libSQL/Turso |
| Operations | Gateway, TUI, service management, onboarding, routines, heartbeat, self-repair |
| Portability risk | Large surface area, Rust-first WASM/runtime decisions, many background subsystems |

## What Matters Most For Koklyp

IronClaw contributes the strongest ideas in five areas:

1. A clean conversation model with session, thread, and turn boundaries.
2. A unified agentic loop shared by interactive chat, jobs, and container workers.
3. Memory treated as explicit persisted workspace data rather than implicit RAM state.
4. Sandboxed tools with declarative capabilities and host-side credential injection.
5. Background autonomy with routines, heartbeat checks, and self-repair.

## Reuse Bias

The best IronClaw ideas to reuse are the ones that define boundaries, not the ones that depend on Rust implementation details:

- Keep the session and job model.
- Keep the explicit tool abstraction and approval points.
- Keep durable memory and hybrid retrieval.
- Keep the distinction between extension identity and credential identity.
- Avoid copying the exact crate and migration strategy unless the Kotlin platform needs the same complexity.

## Porting Notes

IronClaw assumes a mature async Rust runtime, deep Wasmtime integration, and a dual-database persistence strategy. Koklyp should borrow the architecture, but it should not assume that every IronClaw implementation choice maps cleanly to Kotlin.

The biggest translation questions are:

- How much of the sandbox should be in-process versus sidecar-based?
- Should dual-backend persistence exist on day one?
- Which IronClaw channels and tools justify first-class support in the first milestone?
- How much self-repair and background automation belongs in the MVP versus phase two?