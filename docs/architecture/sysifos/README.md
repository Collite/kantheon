# Sysifos — architecture

The data-entry + data-management workbench for the Midas brokerage product (Sysifos-BFF + Vue FE). Own arc, split from Midas per decision **S1** (2026-06-13); references Midas-core contracts rather than duplicating them.

## Files

- [`architecture.md`](./architecture.md) — solution architecture: tech stack, module map, component + dependency diagrams, the hybrid write model, three-layer validation, deployment, observability, the Midas-arc cash-leg dependency, resolved decisions S1–S6.
- [`contracts.md`](./contracts.md) — wire contracts: `sysifos/v1` proto (owned here), the Sysifos-BFF API, SSE stream protocol, dictionaries, the shared validation rule manifest, and a pointer to the Midas cash-leg amendment.

## Across

- [`../midas/`](../midas/) — the Midas arc: Midas-core (the only writer), loaders, operational DB. Source of truth for `midas/v1`, Midas-core REST/MCP, and the cash-leg amendment Sysifos consumes.
- [`../../design/sysifos/`](../../design/sysifos/) — *what* Sysifos is and why.
- [`../../implementation/v1/sysifos/`](../../implementation/v1/sysifos/) — the phased plan + per-stage task lists.
