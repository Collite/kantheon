# The Librarian (DocWH) — implementation

The Kantheon knowledge-warehouse arc (Stream B): **Pinakes** (pipelines/catalogue) · **Kallimachos** (warehouse + retrieval) · **Kleio** (the NotebookLM agent). Forked + re-scoped from `~/Dev/doc-store` (RAG-store → DocWH).

- [`plan.md`](./plan.md) — the five-phase plan (warehouse core → retrieval planes → wiki-compile → serving/RLS → agent).
- Architecture: [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) · Contracts: [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md).

## Task lists (written 2026-06-25 — all five phases)

Per the planning conventions: each phase has an overview + per-stage TDD task lists (5–7 tasks, tests-first, library references, checkboxes). Branches `feat/docwh-p<n>-s<n.m>-<short>`; tags per [contracts §11](../../../architecture/kleio/contracts.md).

| Phase | Deliverable | Overview | Stages |
|---|---|---|---|
| **P1** — warehouse core + stage | Kallimachos keyword warehouse + Pinakes staging; tags `kallimachos/v0.1.0` + `pinakes/v0.1.0` | [`tasks-p1-overview.md`](./tasks-p1-overview.md) | [1.1 skeleton](./tasks-p1-s1.1-skeleton.md) · [1.2 ingestion+marts](./tasks-p1-s1.2-ingestion-marts.md) · [1.3 pinakes stage](./tasks-p1-s1.3-pinakes-stage.md) |
| **P2** — retrieval planes + getContext | pgvector + AGE + graph-primary cited retrieval; tag `kallimachos/v0.2.0` | [`tasks-p2-overview.md`](./tasks-p2-overview.md) | [2.1 vector+embeddings](./tasks-p2-s2.1-vector-embeddings.md) · [2.2 AGE plane](./tasks-p2-s2.2-age-plane.md) · [2.3 getContext+fusion](./tasks-p2-s2.3-getcontext-fusion.md) |
| **P3** — pipelines + LLM wiki-compile | the compiled wiki (entity/concept pages, links, global resolve); tags `pinakes/v0.2.0` + `kallimachos/v0.3.0` | [`tasks-p3-overview.md`](./tasks-p3-overview.md) | [3.1 stage library](./tasks-p3-s3.1-stage-library.md) · [3.2 LLM compile](./tasks-p3-s3.2-llm-compile.md) · [3.3 hardening+deploy](./tasks-p3-s3.3-hardening-deploy.md) |
| **P4** — serving: MCP + identity + browse | `library.*` MCP, OBO+Argos mart RLS, RAG GA, browse FE; tags `kallimachos-mcp/v0.1.0` + `kallimachos/v0.4.0` | [`tasks-p4-overview.md`](./tasks-p4-overview.md) | [4.1 mcp+registration](./tasks-p4-s4.1-mcp-registration.md) · [4.2 RLS+consumers+browse](./tasks-p4-s4.2-rls-consumers-browse.md) |
| **P5** — Kleio agent (NotebookLM) | grounded cited turns + artifacts + `KNOWLEDGE` routing; tag `kleio/v0.1.0` | [`tasks-p5-overview.md`](./tasks-p5-overview.md) | [5.1 grounded turn](./tasks-p5-s5.1-grounded-turn.md) · [5.2 artifacts+registration](./tasks-p5-s5.2-artifacts-registration.md) · [5.3 Themis+eval+ship](./tasks-p5-s5.3-themis-eval-ship.md) |

> **Stream-B order (master-plan):** Fork P5 → Hebe → **Kleio** (the 3rd/last Body push). Critical path P1→P2→P3→P4→P5 (strictly sequential at phase granularity). **One hard external pre-flight:** Prometheus `EmbedText` (P2). Soft Spine deps (P5 only): Themis `KNOWLEDGE` intent (met, `themis/v0.2.0`) + an Iris notebook surface. RAG (`getContext`) is internally usable at P2 and GA at P4 — the **MK — Knowledge plane** mergepoint — before any Spine dependency bites. Testing is mocked-unit only inside stages (planning-conventions §4); real pgvector/AGE/Prometheus/RLS + in-K3s e2e are the separate integration suite.
