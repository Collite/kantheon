# Charon — Architecture

Arc planned 2026-06-12. **First platform-grade service migrated into kantheon** (boundary-shift direction: such services gradually move out of ai-platform; package root `org.tatrman.<service>.v1`).

| File | What |
|---|---|
| [`architecture.md`](./architecture.md) | `services/charon` engine (gRPC, ADBC DB edges, Arrow streaming + integrity) + `tools/charon-mcp` thin wrapper; endpoint abstraction; deployment; risks. |
| [`contracts.md`](./contracts.md) | **Authoritative** `org.tatrman.charon.v1` proto (CharonService, Location union); legality matrix; connection-registry schema; Arrow↔DDL type mapping; conventions. |

Plan: [`../../implementation/v1/charon/plan.md`](../../implementation/v1/charon/plan.md). Design origin: `Pythia-v1-Design.md` §6.2 (Mover). Primary consumer: Pythia ([`../pythia/`](../pythia/)). Up: [`../README.md`](../README.md).
