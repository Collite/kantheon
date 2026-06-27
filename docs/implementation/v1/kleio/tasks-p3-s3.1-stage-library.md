# Stage 3.1 — stage library + pipeline runner + per-source binding

> **Phase 3, Stage 3.1.** Branch `feat/docwh-p3-s3.1-stage-library`.
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §5 Stage 3.1, [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §7 (pipelines & the conformed tail), [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §2 (`Pipeline`/`Stage`/`StageKind`/`EmbedConfig`/`PipelineRun`/`StageRecord`/`Lineage`).

## Goal

The pipeline machinery: a `StageLibrary`, a `Runner` executing a stage DAG with per-stage `StageRecord` + resumability, pipeline definitions from YAML with per-source-feed binding, the `RunPipeline`/`GetRun`/`GetLineage` RPCs + `Lineage` persistence, and the **embed-conformance** check. DONE = named per-source pipelines run the mechanical stages with lineage; embed conformance enforced.

## Tasks (6)

- [ ] **T1 — Tests first: `StageLibrarySpec` + `RunnerSpec`.**

  Spec a DAG of **stage fakes** executing in order; per-stage `StageRecord` (status, items in/out, latency, cost); `PARTIAL`/`FAILED` run status on a mid-DAG failure; **resumable** (a failed run resumes from the failed stage, not from scratch). The head (extract/classify/chunk) varies; the tail (embed/compile/link/resolve/load) is conformed (architecture §7).

  Acceptance: specs written and failing. Commit `[docwh-p3-s3.1] failing stage-library + runner specs`.

- [ ] **T2 — `Pipeline`/`Stage`/`StageLibrary`/`Runner` (contracts §2); YAML defs; per-source binding.**

  Implement the `pipeline/` package (architecture §4): `Pipeline` (a named DAG), `StageLibrary` (extract · classify · chunk · embed · compile · link · resolve · load), `Runner` (executes; per-stage status; resumable). Pipeline definitions load from YAML (`pinakes.pipelines.path`); each **feed** declares its pipeline (per-source binding, not per-document auto-classification — architecture §7).

  Acceptance: T1 runner/library specs green; a YAML pipeline def loads + binds to a feed.

- [ ] **T3 — `EmbedConfig` conformance check.**

  Registering a pipeline whose `EmbedConfig` (model_id/dimensions/version) **disagrees** with the corpus is a **config error at registration** (architecture §11 — "disagreement = two corpora, not one"; the conformed dimension). Spec: a conflicting `EmbedConfig` is rejected; a matching one registers.

  Acceptance: conformance check spec green.

- [ ] **T4 — `RunPipeline`/`GetRun`/`GetLineage` RPCs + `Lineage` persistence.**

  Implement the remaining `PinakesService` RPCs (contracts §2): `RunPipeline` (ingest asset(s) through a named pipeline), `GetRun` (status + stage records), `GetLineage` (asset → run → corpus entries). Persist `Lineage` (Pinakes schema). 

  Acceptance: RPCs answer; a run produces a `PipelineRun` with `StageRecord`s + a `Lineage` row.

- [ ] **T5 — Tests first: `LineageSpec`.**

  Spec lineage: `asset → run_ids → source_ids → page_ids` (contracts §2 `Lineage`). A run records which sources + (later) pages it produced; querying lineage by asset returns the chain.

  Acceptance: `LineageSpec` green.

- [ ] **T6 — Wire the existing extract/chunk/embed/load stages into the library.**

  Wire the already-built mechanical stages (extract/chunk from P1; embed/load from P2) into the `StageLibrary` as concrete `Stage` implementations. `compile`/`link`/`resolve` land as **stubs** here (real in S3.2). A full mechanical run (extract→chunk→embed→load) now runs **through the library/runner** (not the ad-hoc P1.3 runner), with lineage.

  Acceptance: a named pipeline runs the mechanical tail through the runner with per-stage records + lineage. PR `[docwh-p3-s3.1] stage library + runner + per-source binding`.

## DONE — Stage 3.1

- [ ] All six tasks checked.
- [ ] `StageLibrary` + `Runner` (per-stage `StageRecord`, PARTIAL/FAILED, resumable).
- [ ] Pipelines from YAML; per-source-feed binding; `RunPipeline`/`GetRun`/`GetLineage` RPCs.
- [ ] **Embed conformance enforced** at registration.
- [ ] Lineage (asset → run → source/page ids) persisted + queryable.
- [ ] Mechanical stages wired into the library; compile/link/resolve stubbed.
- [ ] PR merged.

## Library / pattern references

- **contracts.md §2** — `Pipeline`/`Stage`/`StageKind`/`EmbedConfig`/`PipelineRun`/`StageRecord`/`Lineage` (the authority).
- **architecture.md §7** — head-varies/tail-conformed; per-source binding; embed is conformed.
- **architecture.md §11** — the conformed-dimension rule the T3 check enforces.

## Out of scope for Stage 3.1

- The LLM compile / link / resolve stage bodies (Stage 3.2 — stubbed here).
- Contradiction flags + cost budgets + re-ingest compounding (Stage 3.3).
