# Iris — Architecture

Arc planned 2026-06-12. First of the three constellation arcs (Iris → Golem → Pythia).

| File | What |
|---|---|
| [`architecture.md`](./architecture.md) | BFF + FE implementation shape; transitional `/v2` adapter to new-golem; deployment, observability, risks. |
| [`contracts.md`](./contracts.md) | **Owns the constellation-wide `envelope/v1`** (derived from FormatEnvelope v2) and `iris/v1`; BFF REST/SSE surface; persistence DDL; transitional adapter mapping. |

Plan: [`../../implementation/v1/iris/plan.md`](../../implementation/v1/iris/plan.md). Design: [`../../design/iris/`](../../design/iris/) (see reality note). Up: [`../README.md`](../README.md).
