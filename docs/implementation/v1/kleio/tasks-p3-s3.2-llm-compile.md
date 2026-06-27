# Stage 3.2 — the LLM compile + linking

> **Phase 3, Stage 3.2.** Branch `feat/docwh-p3-s3.2-llm-compile`. **The DocWH differentiator.**
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §5 Stage 3.2, [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §6 (the wiki) + §7 (compile/resolve are conformed tail) + §12 (the Ariadne `concept_ref` seam), [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §1 (`Page`/`PageKind`/`ConceptRef`/`EdgeKind`/`LoadPagesRequest`) + §6 (concept identity).

## Goal

The `COMPILE` stage turns source parts into LLM-authored wiki pages (ENTITY/CONCEPT/SUMMARY), the `LINK` stage materialises page↔page / page↔source edges in AGE, and the `RESOLVE` stage does **global** entity resolution against the whole corpus graph (populating `concept_ref`, wiki-local at v1). After this stage, `getContext` seeding prefers ENTITY/CONCEPT pages — the graph truly leads. DONE = a pipeline run compiles sources into linked wiki pages with globally-resolved entities.

## Tasks (7)

- [ ] **T1 — Tests first: `WikiCompilerSpec` (Wiremock Prometheus).**

  Spec the compiler against a Wiremock'd Prometheus: source parts → ENTITY/CONCEPT/SUMMARY `Page` drafts; **prompt shaping** (the prompts live in `prompts/`, Bora-owned content); output **parse + fallback** (a malformed LLM response degrades gracefully, doesn't crash the run). Assert the produced `Page.kind` + `derived_from_parts` provenance.

  Acceptance: spec written and failing. Commit `[docwh-p3-s3.2] failing wiki-compiler spec`.

- [ ] **T2 — `WikiCompiler` (COMPILE stage; prompts in `prompts/`); `Page` creation via `LoadPagesRequest`.**

  Implement `compile/WikiCompiler.kt` (architecture §4) as the `COMPILE` stage: Prometheus-driven entity/concept extraction + page synthesis; prompts externalised in `prompts/`. Pages are written via the Kallimachos internal `LoadPagesRequest` (contracts §1 — `LoadApi` is the only corpus writer). Batch/offline (never on the query path — architecture §7).

  Acceptance: T1 compiler spec green; a run produces `Page`s with provenance via `LoadApi`.

- [ ] **T3 — Tests first: `LinkStageSpec`.**

  Spec the linking: page→source `DERIVED_FROM` (provenance), page↔page `MENTIONS`/`ABOUT`/`RELATED`/`CONTRADICTS` edges (contracts §1 `EdgeKind`). The edges are what graph-primary retrieval walks (architecture §6).

  Acceptance: spec written and failing. Commit.

- [ ] **T4 — `LINK` stage → AGE wiki edges.**

  Implement the `LINK` stage writing the content edges into AGE (the `AgeGraphAdapter` from P2.2). `DERIVED_FROM` + the page↔page content links.

  Acceptance: T3 `LinkStageSpec` green; edges land in the graph plane.

- [ ] **T5 — Tests first: `EntityResolverSpec` — GLOBAL resolution.**

  Spec the resolver (architecture §7 "resolve is GLOBAL and conformed"): resolution against the **whole corpus graph** (so "Kaufland" is one node across feeds); new vs merged outcomes (`pinakes_entities_resolved_total{outcome}`); `concept_ref` populated **wiki-local** (`entity_type`+`entity_id`+label; `ariadne_qname` empty — the §6/§12 seam, not yet bridged).

  Acceptance: spec written and failing. Commit.

- [ ] **T6 — `RESOLVE` stage (conformed, global); `pinakes_entities_resolved_total`.**

  Implement `resolve/EntityResolver.kt` as the global `RESOLVE` stage — conformed tail, **never per-pipeline** (else the wiki fragments — risks §14). Populate `concept_ref` (wiki-local). Emit `pinakes_entities_resolved_total{outcome="new|merged"}` (the wiki-coherence signal). Reserve the Ariadne grounding hook (additive, `bearer | whois` pattern — architecture §12; **no Ariadne dependency in v1**).

  Acceptance: T5 resolver spec green; "Kaufland" across two feeds resolves to one node.

- [ ] **T7 — `getContext` seeding prefers ENTITY/CONCEPT pages; `HybridFusion` reweight.**

  Now that pages exist, update `getContext` seeding (S2.3) to **prefer** ENTITY/CONCEPT pages as seeds (the graph truly leads — architecture §8). Reweight `HybridFusion` accordingly and update its spec (pages outrank raw parts when present; degrades to parts when the wiki is thin).

  Acceptance: fusion spec updated + green; `getContext` leads with pages when available. PR `[docwh-p3-s3.2] llm compile + linking + global resolution`.

## DONE — Stage 3.2

- [ ] All seven tasks checked.
- [ ] `COMPILE` produces ENTITY/CONCEPT/SUMMARY pages via `LoadApi` with `derived_from_parts` provenance; prompts in `prompts/`; parse-fallback safe.
- [ ] `LINK` materialises `DERIVED_FROM` + page↔page content edges in AGE.
- [ ] `RESOLVE` is global + conformed; `concept_ref` populated wiki-local (`ariadne_qname` empty); merged/new metric emitted.
- [ ] `getContext` leads with ENTITY/CONCEPT pages; fusion reweighted.
- [ ] PR merged.

## Library / pattern references

- **architecture.md §6/§7/§12** — the wiki model, conformed-tail compile/resolve, the Ariadne `concept_ref` seam (reserved, not coupled).
- **contracts.md §1** — `Page`/`PageKind`/`ConceptRef`/`EdgeKind`/`LoadPagesRequest`. **§6** — concept identity + the bridge seam.
- **EXAMPLES.md §9** — Wiremock Prometheus stub. Koog/Prometheus prompt patterns (`agents/golem` precedent).

## Out of scope for Stage 3.2

- Contradiction-flag pass hardening + cost budgets + re-ingest compounding (Stage 3.3).
- The Ariadne `ariadne_qname` fill + cross-graph `SAME_AS` (v1.x — architecture §12; only the empty seam here).
- Real Prometheus / real-AGE verification (integration suite).
