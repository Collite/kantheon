# Themis — Architecture

Implementation architecture and wire contracts for the Themis-in-kantheon arc (Phases 1–3 of the v1 implementation plan).

## Files

| File | What |
|---|---|
| [`architecture.md`](./architecture.md) | Solution architecture: tech stack table, module map (`agents/themis/`, `tools/capabilities-mcp/`, `shared/proto/`, `shared/libs/kotlin/capabilities-client/`), component diagram, Gradle dependency graph, Koog graph at Phase 2 vs Phase 3, capabilities-mcp internal structure, local K3s deployment topology, just-recipe inventory, OTel metrics catalogue, testing strategy, risk register, references to ai-platform `EXAMPLES.md` and locally-cloned libraries. |
| [`contracts.md`](./contracts.md) | All wire contracts: full proto for `capabilities/v1` and `themis/v1`; `envelope/v1` `RoutingPickChip` addition; the six capabilities-mcp MCP tools and REST mirror; YAML manifest schemas with Pythia + Golem-ERP examples; heartbeat client public API; HMAC resume-token payload with Phase 3 additions; eval-corpus shapes; externalised prompts. |

## What's elsewhere

- **What Themis *is* (design)**: [`../../design/themis/`](../../design/themis/).
- **Phased plan + per-stage task lists**: [`../../implementation/v1/themis/`](../../implementation/v1/themis/).
- **The overall constellation architecture this fits inside**: [`../kantheon-architecture.md`](../kantheon-architecture.md) §11 (sequencing — "Resolver → Themis extraction" and "Routing layer added").

## Up / across

- Up: [`../README.md`](../README.md) — architecture entry point.
- Across: [`../../design/themis/`](../../design/themis/) — design history feeding this architecture. [`../../implementation/v1/themis/`](../../implementation/v1/themis/) — phased plan that consumes this.
