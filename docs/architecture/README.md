# Architecture

"How it is built." Technical architecture, wire contracts, deployment topology, security, and other technical artefacts. These docs describe the *implementation shape* — module layouts, proto definitions, dependency graphs, runtime topology — distinct from the *outward design* (which lives in [`../design/`](../design/)).

## Cross-cutting

- **[`kantheon-architecture.md`](./kantheon-architecture.md)** — the overall constellation architecture: vision, top-level repo layout, proto packaging, shared libs, module dependency graph, conversation state model, routing model, cross-repo coupling with ai-platform, sequencing, resolved decisions. **Read first.**

## Per agent

- **[`iris/`](./iris/)** — Iris architecture + contracts (arc planned 2026-06-12). Owns the constellation-wide `envelope/v1` definition (contracts §1.1) and `iris/v1`. Dashboard-system extensions remain under the Midas arc (Phase 3 Stage 3.5).
- **[`themis/`](./themis/)** — Themis-in-kantheon architecture and wire contracts. Active (mid-Stage 2.4).
- **[`golem/`](./golem/)** — Golem template architecture + contracts (arc planned 2026-06-12). Ports new-golem v2 semantics to Kotlin + Koog; includes the `envelope-render` shared lib.
- **[`pythia/`](./pythia/)** — Pythia architecture + contracts (arc planned 2026-06-12). Full v1 design retained; Metis stays a cross-repo deliverable.
- **[`charon/`](./charon/)** — Charon architecture + contracts (arc planned 2026-06-12). Arrow data mover: `services/charon` (gRPC, `org.tatrman.charon.v1`) + thin `tools/charon-mcp`. **First platform-grade service migrated into kantheon.**
- **[`metis/`](./metis/)** — Metis architecture + contracts (arc planned 2026-06-12). Model estimation: `services/metis` (**Python** — statsmodels/Prophet library moat; gRPC `org.tatrman.metis.v1`) + thin Kotlin `tools/metis-mcp`. Second migrated service.
- **[`midas/`](./midas/)** — Midas arc: brokerage-domain agent constellation (Midas-core + loaders + report-renderer + Iris dashboard extensions + Golem-Investment Shem). Consolidated arc; active. Includes brief, architecture, contracts. **Sysifos split out to its own arc (S1, 2026-06-13)** — see below.
- **[`sysifos/`](./sysifos/)** — Sysifos arc: the data-entry + data-management workbench for Midas (Sysifos-BFF + Vue FE). Own arc since 2026-06-13; owns `sysifos/v1` + the Sysifos-BFF API; references Midas-core contracts. Architecture + contracts.
- **[`arges/`](./arges/)** — Arges arc (planned 2026-06-23, fork-now). Postgres read worker: `workers/arges` (Kotlin, implements `org.tatrman.worker.v1`), mirrors Brontes + adds the `SET LOCAL app.tenant_id` RLS contract. Third Kyklops; gates Midas P3 S3.2. Architecture + contracts.
- **[`capabilities-mcp/`](./capabilities-mcp/)** — `capabilities-mcp` design rationale (one registry for tools + agents, push-from-tools heartbeat, source-controlled fixtures, warn-and-continue). Phase 1.

## What kinds of docs go here

| File pattern | What it contains |
|---|---|
| `architecture.md` (per agent) | Module map, component diagram, dependency graph, deployment topology, tech-stack table, observability. |
| `contracts.md` (per agent) | Wire contracts: protobuf packages, MCP tool surfaces, REST endpoints, manifest YAML schemas, persistence shapes. |
| `<topic>.md` cross-cutting | Topics that span multiple agents — e.g. security model, deployment-cluster topology, OTel conventions, shared envelope-rendering library design. |

## Up / across

- Up: [`../README.md`](../README.md) — top-level docs index.
- Across: [`../design/`](../design/) — *what* each agent is. [`../implementation/`](../implementation/) — phased plans and task lists.
