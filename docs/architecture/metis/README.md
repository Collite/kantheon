# Metis — Architecture

Arc planned 2026-06-12. **Second migrated platform-grade service; kantheon's only Python module** — justified by the classical-forecasting library moat (statsmodels SARIMAX + diagnostics; Prophet has no JVM implementation). Repo rule: "Kotlin unless a library moat says otherwise."

| File | What |
|---|---|
| [`architecture.md`](./architecture.md) | `services/metis` (Python: statsmodels/prophet/sklearn, session workspace for DFs + fitted models) + `tools/metis-mcp` (thin Kotlin wrapper); why-Python rationale; execution model; risks. |
| [`contracts.md`](./contracts.md) | **Authoritative** `org.tatrman.metis.v1` proto (Fit/Diagnose/Project/SimulateScenario + workspace RPCs); `model.*` MCP tools; **numerical-fidelity contract** (golden tolerances); Pythia ModelNode mapping. |

Plan: [`../../implementation/v1/metis/plan.md`](../../implementation/v1/metis/plan.md). Design origin: `Pythia-v1-Design.md` §6.2. Consumers: Pythia ([`../pythia/`](../pythia/)) via gRPC; Charon stages sessions ([`../charon/`](../charon/) `WorkerKind.METIS`). Up: [`../README.md`](../README.md).
