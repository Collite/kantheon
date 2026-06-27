# Sysifos — design

The data-entry and data-management workbench for the Midas brokerage product. Forms-shaped sibling to Iris (which is chat-shaped): clients, portfolios, assets, manual transaction entry (form + bulk grid), balance entry, statement import, and reconciliation — all writing through Midas-core's append-only API.

Sysifos is its **own arc** (decision S1, 2026-06-13), split out of the Midas arc; it references Midas-core's contracts rather than duplicating them.

## Files

- [`sysifos-brief.md`](./sysifos-brief.md) — the original ask: why Sysifos exists, the two levels of manual input, imports, what it is and isn't.
- [`sysifos-design.md`](./sysifos-design.md) — the locked design: surface, data-entry semantics (incl. derived cash legs), bulk grid, import flow, hybrid write model, validation topology, screens, arc restructuring, resolved decisions S1–S6.
- [`sysifos-brainstorming.md`](./sysifos-brainstorming.md) — process record: the six decisions, alternatives considered, where each locked.

Read order if new: `sysifos-brief.md` → `sysifos-brainstorming.md` → `sysifos-design.md`.

## Across

- [`../../architecture/midas/`](../../architecture/midas/) — the Midas arc: Midas-core (the only writer), the loaders, the operational DB Sysifos writes through.
- The build trio (`architecture/sysifos/` + `implementation/v1/sysifos/`) follows once this design is approved.
