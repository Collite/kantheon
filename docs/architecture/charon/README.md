# Charon — Architecture

> **⚑ Charon left this repo at the CH-P3 cutover (2026-07-27).** The mover was unified into the open
> [`tatrman-server`](https://github.com/Collite/tatrman-server) (arc **CH**) — one open Charon, which owns the
> engine, the `transfer.v1` contract, the chart and the `ghcr.io/collite/charon` image. kantheon keeps
> **`tools/charon-mcp`** and consumes the rest by version; `services/charon` and the `transfer/v1` proto duplicate
> were removed ([kantheon#17](https://github.com/Collite/kantheon/issues/17)). **These documents are fork-era
> history for the engine** — accurate for the MCP wrapper and for Pythia's data-plane contract, not a place to
> edit the proto. See `collite-gh/project/server/features/charon-unification/`.

Arc planned 2026-06-12. **First platform-grade service migrated into kantheon** (boundary-shift direction: such services gradually move out of ai-platform; package root `org.tatrman.<service>.v1`).

| File | What |
|---|---|
| [`architecture.md`](./architecture.md) | `services/charon` engine (gRPC, ADBC DB edges, Arrow streaming + integrity) + `tools/charon-mcp` thin wrapper; endpoint abstraction; deployment; risks. |
| [`contracts.md`](./contracts.md) | **Authoritative** `org.tatrman.charon.v1` proto (CharonService, Location union); legality matrix; connection-registry schema; Arrow↔DDL type mapping; conventions. |

Plan: [`../../implementation/v1/charon/plan.md`](../../implementation/v1/charon/plan.md). Design origin: `Pythia-v1-Design.md` §6.2 (Mover). Primary consumer: Pythia ([`../pythia/`](../pythia/)). Up: [`../README.md`](../README.md).
