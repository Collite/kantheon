# Midas — Architecture

Implementation architecture and wire contracts for the Midas arc (the brokerage-domain agent constellation: Midas-core + loaders + Sysifos BFF + FE + report-renderer + Iris dashboard extensions + Golem-Investment ShemManifest).

The arc is **consolidated** — one architecture doc, one contracts doc, one plan covering all the moving pieces because they're tightly interleaved.

## Files

| File | What |
|---|---|
| [`midas-brief.md`](./midas-brief.md) | Originating brief — goals, data requirements, output requirements, loader scope. |
| [`architecture.md`](./architecture.md) | Solution architecture: tech stack, module map for every new piece, component + Gradle dependency diagrams, Midas-core internal structure, operational Postgres design (event-sourced + RLS + materialized views), loader pattern, Sysifos service shape, report-renderer pipeline, Iris dashboard extensions, ai-platform cross-repo dependency, deployment topology, security, observability, test strategy, resolved decisions D1–D13. |
| [`contracts.md`](./contracts.md) | All wire contracts: three new proto packages (`midas/v1`, `sysifos/v1`, `report/v1`), Midas-core REST + MCP tool surfaces, loader REST API, Sysifos-BFF API, Flyway DB schema (DDL), Iris-BFF dashboard schema extensions, report-renderer API, ShemManifest YAML, tool-capability registration payloads, audit log shape, error envelope, error code catalogue. |

## What's elsewhere

- **Phased plan + per-stage task lists**: [`../../implementation/v1/midas/`](../../implementation/v1/midas/).
- **ai-platform cross-repo coordination doc** (Postgres worker): [`../../implementation/v1/_archive/aip-v1-pg-worker-plan.md`](../../implementation/v1/_archive/aip-v1-pg-worker-plan.md).
- **The overall constellation architecture this fits inside**: [`../kantheon-architecture.md`](../kantheon-architecture.md).
- **Themis arc** (the reference implementation of the planning convention this arc mirrors): [`../themis/`](../themis/).

## Up / across

- Up: [`../README.md`](../README.md) — architecture entry point.
- Across: [`../../implementation/v1/midas/`](../../implementation/v1/midas/) — phased plan that consumes this.
