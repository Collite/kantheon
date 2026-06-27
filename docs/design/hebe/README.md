# Hebe — design records

Hebe is the personal autonomous agent (per-user; CLI/web/Telegram channels, scheduler + routines, SQLite/PG memory, security/receipts, PF4J plugins, MCP server+client). Source lives at [`agents/hebe/`](../../../agents/hebe/); moved in from the standalone `~/Dev/hebe` repo on 2026-06-12.

These are the **standalone-era design records**, migrated as-is:

- [`hebe-brainstorming.md`](./hebe-brainstorming.md) + [`hebe-brainstorming-responses.md`](./hebe-brainstorming-responses.md) — the original exploration.
- [`hebe-architecture.md`](./hebe-architecture.md) — the high-level blueprint (the "what and why").
- [`hebe-features.md`](./hebe-features.md) — feature inventory.
- [`v1-specs.md`](./v1-specs.md) — standalone v1 scope contract and acceptance criteria.
- [`agent-diff.md`](./agent-diff.md) — comparison vs other agent frameworks.
- [`plan-readme-legacy.md`](./plan-readme-legacy.md) — index of the old `docs/plan/` folder, kept for orientation.

**Kantheon integration** (current): [`/docs/architecture/hebe/architecture.md`](../../architecture/hebe/architecture.md) + [`contracts.md`](../../architecture/hebe/contracts.md) + [`/docs/implementation/v1/hebe/plan.md`](../../implementation/v1/hebe/plan.md). The standalone wiring diagram remains authoritative for Hebe internals: [`standalone-v1-architecture.md`](../../architecture/hebe/standalone-v1-architecture.md).
