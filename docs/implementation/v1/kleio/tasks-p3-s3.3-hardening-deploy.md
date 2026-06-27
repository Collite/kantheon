# Stage 3.3 — compile hardening + deploy

> **Phase 3, Stage 3.3.** Branch `feat/docwh-p3-s3.3-hardening-deploy`.
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §5 Stage 3.3, [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §6 (compounding) + §13 (metrics) + §14 (compile cost/quality + fragmentation risks), [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §1 (`CONTRADICTS`) + §11 (tags).

## Goal

Harden the compile: contradiction flags, a token-budget + cost-metric guard with degrade-to-mechanical-links on failure, and re-ingest/update **compounding** semantics (a new source updates existing pages/links rather than duplicating). Then deploy Pinakes + a full-pipeline smoke. DONE = tags **`pinakes/v0.2.0`** + **`kallimachos/v0.3.0`** — the corpus is a compiled wiki.

## Tasks (5)

- [ ] **T1 — Contradiction-flag pass (`CONTRADICTS` edges) + spec.**

  Implement a compile pass that detects contradictions between pages/sources and writes `CONTRADICTS` edges (contracts §1 `EdgeKind`). Spec: two sources asserting conflicting facts about one entity produce a `CONTRADICTS` edge (Wiremock'd Prometheus for the detection call).

  Acceptance: contradiction spec green; `CONTRADICTS` edges land.

- [ ] **T2 — Compile token-budget + cost metrics + degrade-to-mechanical-links.**

  Add per-run **token budgets** + `pinakes_compile_llm_calls_total{result}` + per-stage `cost_usd` (already on `StageRecord`). On compile failure (budget exceeded or LLM error), **degrade to mechanical-links-only** — the corpus stays queryable on source parts (architecture §14, the headline cost/quality risk). Spec: a forced compile failure leaves a queryable corpus + a PARTIAL run.

  Acceptance: budget/cost metrics emitted; degrade path spec green.

- [ ] **T3 — Re-ingest/update (compounding) semantics + spec.**

  A new source **updates** existing pages/links rather than duplicating (the wiki compounds — architecture §6). Spec: re-ingesting an overlapping source updates the relevant pages + adds links, and does **not** create duplicate entity pages (pairs with the global resolver, S3.2).

  Acceptance: compounding spec green; no duplicate pages on overlapping re-ingest.

- [ ] **T4 — Deploy Pinakes; live smoke (full pipeline).**

  Deploy Pinakes to local K3s. **Live smoke:** stage an asset → run the **full** pipeline (extract→chunk→embed→compile→link→resolve→load) → the wiki pages + links are queryable via `getContext` (now leading with pages). Per planning-conventions §4 a deployment smoke, not an automated e2e gate.

  Acceptance: full-pipeline smoke produces a queryable wiki.

- [ ] **T5 — Tag.**

  Tag **`pinakes/v0.2.0`** + **`kallimachos/v0.3.0`**; bump `gradle/libs.versions.toml`.

  Acceptance: both tags pushed. PR `[docwh-p3-s3.3] compile hardening + deploy`.

## DONE — Stage 3.3

- [ ] All five tasks checked.
- [ ] `CONTRADICTS` flagging; token-budget + cost metrics; degrade-to-mechanical-links keeps the corpus queryable on compile failure.
- [ ] Re-ingest compounds (updates pages/links, no duplicates).
- [ ] Full-pipeline smoke on K3s produces a queryable wiki.
- [ ] Tags `pinakes/v0.2.0` + `kallimachos/v0.3.0` pushed. **Phase 3 DONE — the corpus is a compiled wiki.**
- [ ] PR merged.

## Library / pattern references

- **architecture.md §6** — compounding. **§13** — the compile/resolve metrics. **§14** — cost/quality + fragmentation mitigations (the authority for T2/T3).
- **contracts.md §1** — `CONTRADICTS` edge; **§11** — tags.

## Out of scope for Stage 3.3

- The `library.*` MCP surface + RLS (Phase 4).
- The Kleio agent (Phase 5).
- Ariadne bridging (v1.x).
- Real Prometheus/AGE/pgvector verification (integration suite).
