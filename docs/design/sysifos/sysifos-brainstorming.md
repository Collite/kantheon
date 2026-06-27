# Sysifos — Brainstorming record

> Process record for the 2026-06-13 Sysifos design session. Captures the questions that were on the table, the alternatives considered, and where each decision locked. The locked design lives in [`sysifos-design.md`](./sysifos-design.md); this is the "how we got there."

## Starting point

Sysifos already existed as a *structural* citizen of the Midas arc: the Midas architecture (§9), contracts (`sysifos/v1` §1.2, Sysifos-BFF API §5), and plan (P1 S1.6 + all of Phase 2) gave it plumbing — a forms-shaped BFF, a Vue/PrimeVue/Pinia FE, eight screens, a `Draft`/SSE write surface. The Midas brief, though, explicitly promised Sysifos "its own design and brief." So the gap wasn't plumbing — it was **the interaction and data-entry semantics**: what manual input actually *means*, how cash is handled, how imports are corrected, how validation is layered.

The session deliberately did **not** relitigate what the Midas arc had locked (separate BFF/FE per D4, all-writes-through-Midas-core, no charts per D6, event-sourced transactions per D5). It took those as given and went deep on the open space.

## Decisions

### S1 — Sysifos becomes its own arc

**On the table:** (a) design doc only, contracts/plan stay in Midas; (b) full own arc; (c) extend the Midas docs in place.

**Considered:** The consolidation instinct argued for (a) — Sysifos already had contracts inside Midas, and splitting risks duplicating them. But Bora chose **(b) full own arc**.

**Resolution + the consolidation guard:** Because the worry with (b) is duplication, the design adds an explicit **de-duplication rule** (§10): the Sysifos contracts doc *cites* Midas contract sections and never restates a `midas/v1` message or a Midas-core endpoint; Midas keeps core/loaders/reports/Golem/dashboards; Sysifos picks up only `sysifos/v1`, the Sysifos-BFF API, the BFF/FE build, and the screen stages. Dependency direction is one-way: Sysifos → Midas-core. **Locked 2026-06-13.**

### S2 — Cash legs derived in Midas-core

**On the table:** the biggest unresolved manual-input semantic — a trade consumes/produces cash, but nothing said whether that cash side is modeled. Options for *who* derives: (a) derive in core, Sysifos sends security leg only; (b) single-entry, no cash in v1; (c) user enters both legs.

**Considered:** (b) is simplest but leaves NAV and statement reconciliation incomplete — fatal for a brokerage book. (c) maximises friction and error. **(a) derive in core** won: minimal typing, correct book, and it respects "Midas-core is the only writer / owns derivation."

**Then, how to represent the cash leg in the event log** (a second fork): (a) new `TX_CASH_CREDIT`/`TX_CASH_DEBIT` kinds, cash leg is its own row against a cash asset; (b) reuse `TRANSFER_IN/OUT`; (c) a cash-amount field on the security row.

**Considered:** (b) conflates external transfers with trade-settlement cash; (c) breaks one-asset-per-row and complicates cash-position queries. **(a) new cash kinds** won — clean, explicit, queryable.

**Consequence:** this is a **Midas-arc amendment**, not a Sysifos one — `TransactionKind` gains the two kinds, a `track_cash` per-portfolio toggle is added, and `derivation/CashLegDerivation.kt` emits the counter-leg in the same commit. Sysifos consumes it (renders both legs, exposes the toggle). **Locked 2026-06-13.**

### S3 — Form + bulk grid

**On the table:** (a) single-record form + spreadsheet-style bulk grid; (b) form only, grid v1.x; (c) grid-first.

**Considered:** Back-office entry lives or dies on speed; form-only (b) is too slow for heavy days, grid-first (c) is awkward for casual single edits. **(a)** gives both, with the grid as the bulk path and the form as the single path — no third entry concept. The grid is paste-friendly, keyboard-first, per-cell validated, and commits as a batch over the async path (ties to S5). **Locked 2026-06-13.**

### S4 — Broker templates in v1, mapping → v1.x

**On the table:** (a) predefined broker templates only; (b) interactive column-mapping in v1 too; (c) mapping that saves as reusable templates.

**Considered:** The brief says "Excel files with predefined templates," so (a) matches scope and covers known brokers. Interactive mapping (b/c) is real value but real surface. **(a)** locked for v1; the import screen keeps a seam (the broker-template selector) so v1.x can add a "custom mapping" option that persists as a named template. The Sysifos-specific value in v1 is the **inline correction** of ERROR rows in preview, not arbitrary-file mapping. **Locked 2026-06-13.**

### S5 — Hybrid write model

**On the table:** (a) hybrid — sync for single records, async `Draft`+SSE for long ops; (b) sync only, Draft dormant; (c) optimistic everywhere.

**Considered:** Optimistic-everywhere (c) adds ack/commit/SSE failure modes to writes that are already sub-second — ceremony for no felt gain. Sync-only (b) leaves bulk/import progress to polling. **(a) hybrid** matches where latency actually is: single writes go sync; the bulk grid and statement import — genuinely multi-second — use `Draft` + SSE for honest progress and per-row outcomes. The `Draft`/`SysifosStreamEvent` types stay in the contract as the async seam. **Locked 2026-06-13.**

### S6 — Asset quick-create modal

**On the table:** unknown symbol during entry/import → (a) auto-create a stub flagged `NEEDS_ENRICHMENT`; (b) inline quick-create modal; (c) block until created via the Assets screen.

**Considered:** Stubs (a) keep ops flowing but litter the dictionary with half-assets and a worklist nobody clears. Blocking (c) is correct but high-friction, especially on import. **(b) quick-create modal** is the middle: master data stays clean (real asset, minimal fields), but no context-switch — the new asset drops back into the originating cell and entry resumes. In bulk grid / import preview, multiple unknowns queue into one modal pass. **Locked 2026-06-13.**

## Open items carried forward

Listed in `sysifos-design.md` §12 — the notable ones: `bff-base` extraction trigger, draft-scratch durability across refresh (leaning session-memory in v1), cash-asset identity (`per-portfolio` vs `per-tenant`, leaning per-portfolio), bulk-grid paste column-mapping heuristic, and reconcile granularity (transaction-level in v1).

## Next step

With the design locked, the build trio follows: `docs/architecture/sysifos/architecture.md` + `contracts.md` and `docs/implementation/v1/sysifos/plan.md`, plus the matching Midas-arc amendment (cash legs) recorded in the Midas contracts. Per planning-conventions, those three precede any per-stage task lists.
