# Stage 3.1 — Additional contexts

> **Phase 3, Stage 3.1.** Grow the nightly run-set beyond `theseus-runquery`. Each context is an increment: kantheon owns specs + fixtures + the context *name*; olymp owns the context manifest + values (olymp Stage 7.6).
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`../../../architecture/testing/architecture.md`](../../../architecture/testing/architecture.md) §4 (the named context), [`../../../architecture/testing/contracts.md`](../../../architecture/testing/contracts.md) §1–§4, olymp [`docs/test-harness.md`](../../../../../collite-gh/olymp/docs/test-harness.md) Stage 7.6.
>
> **Status (2026-06-24): T1 kantheon-side landed ahead-of-cluster; live bring-up upstream-gated.** The `golem-erp` chain is **not yet chart-complete** — its hard model dependency **Ariadne** (`GetModel`) and the **Prometheus** LLM gateway have no D3′ Helm chart (only `golem` + the `theseus` query chain + `brontes` do; the pre-flight's "golem-erp: yes per olymp Phase 5" is **wrong** — golem has a chart but its deps don't). So no `golem-erp` context can stand up live yet. Following the Stage 2.2 precedent, T1's kantheon side ships gated: `GolemErpIntegrationSpec` (`@RequiresContext("golem-erp")`) + `GolemAnswerDriver` + the LLM WireMock fixture **compile + skip**, with the answer-turn assertions behind `liveContext = false` and only the bearer-fail-closed (`unauthorized`/401) assertion active. The olymp `test-contexts/golem-erp/context.yaml` is authored as **DRAFT — not runnable**, registering the cross-repo name (so the `ContextNameRegistrySpec` drift guard stays green) and documenting the exact gate. **`themis-routing` (T2) is blocked the same way** — Themis/Kadmos/Echo have no charts either. **Flip `liveContext` + add `golem-erp` to the nightly run-set** once Ariadne (+ a `golem-erp` Shem and a seed-aligned model) reaches the cluster.

## Goal

Nightly runs ≥3 contexts (`theseus-runquery` + `golem-erp` + `themis-routing`, with `pythia-rca` added when its arc reaches the cluster), each green and isolated by namespace-per-run. The "add a context" path is a documented, repeatable runbook.

## Pre-flight

- [x] Phase 2 closed.
- [~] For each context to add: the target chain builds + has a D3′ Helm chart — **golem-erp: PARTIAL** (golem + theseus chain + brontes have charts; **Ariadne + Prometheus do not** → live bring-up blocked); **themis-routing: NO** (Themis/Kadmos/Echo have no charts); pythia-rca: gated on Charon+Metis on-cluster.
- [~] olymp Stage 7.6 adds the matching `test-contexts/<name>/` manifests + values — `test-contexts/golem-erp/context.yaml` authored as **DRAFT** (name registered; values + ariadne/prometheus charts pending).
- [x] Branch per context — using the stage branch `feat/p3-s3.1-contexts` for the ahead-of-cluster spec landing.

## Tasks

- [x] **T1 — `golem-erp` integration specs + fixtures.** *(kantheon side done 2026-06-24; "passes live" deferred — see Status.)*

  `@RequiresContext("golem-erp")` spec: a domain Q&A turn through Golem + its deps (Ariadne model via `GetModel` + `ResolveArea`; **prompts from the mounted Shem, not Ariadne** — `get_prompts` was removed in Golem P4 S4.2; the query chain) end-to-end; assert the conversational envelope. WireMock fixtures for the LLM upstream + any modeler fetch under `.../wiremock/golem-erp/`. Reference the golem arc contracts for the request/response shapes. **Acceptance:** compiles + gated; passes against the live `golem-erp` context. — **Done:** `agents/golem/src/integrationTest/.../GolemErpIntegrationSpec.kt` (drives `POST /v1/answer/sync`, `GolemRequest` → `ConversationalResponse`) + `GolemAnswerDriver.kt` + `resources/wiremock/golem-erp/llm/mappings.json`; `:agents:golem:compileIntegrationTestKotlin` + ktlint green; `:agents:golem:integrationTest` skips with no `-Pcontext`. The "passes live" half is gated `liveContext = false` pending the Ariadne/Prometheus charts.

- [ ] **T1b — golem live legs received from the Golem arc (Golem P4 §6 Handoff, 2026-06-25).** The Golem (build) arc closed at its Stage 4.4 (bundle + Helm Shem-mount ship in-repo); the **live** legs moved here. Land them on the golem context (rename `golem-erp` → **`golem-ucetnictvi`** — the first Kantheon Golem, `agent_id=golem-ucetnictvi`, role `kantheon-area-accounting`; the bundle is `agents/golem/shems/golem-ucetnictvi/`):
  - **Deploy + readiness:** ConfigMap from the bundle dir → mount at `/etc/golem/shem` (chart `shem.configMapName`); `/ready` gates on Shem-assembled + PackageContext (model) + PromptStore (mounted).
  - **Registration:** golem-ucetnictvi visible in capabilities-mcp (`AREA_QA`, `visibility_roles=[kantheon-area-accounting]`, the assembled `area_*`/`description_for_router`).
  - **Routing:** Themis Layer-1 routes PROCEDURAL/accounting fixtures to golem-ucetnictvi; counter-examples (RCA/HR) do not. Joint with the `themis-routing` eval.
  - **Latency/cost:** measure p50/p95 + cost on the corpus; fill the Shem's `typical_latency_ms`/`typical_cost_usd`.
  - **Side-by-side soak:** per-session golem-v2 vs golem-ucetnictvi flag; one-week divergence log (envelope diffs auto-captured by the Stage 3.3 diff-harness on live traffic); Bora's cs prompt-quality review; perf vs v2 baseline; go/no-go → feeds the release/cutover (Bora-owned, Golem §6 Handoff).
  - **⚠ Pre-deploy:** the live ai-models model must be on 0.7.0 TTR syntax (`binding:` / `schema binding`) — Ariadne bumped to modeler 0.7.0 in Golem P4 S4.2.

- [ ] **T2 — `themis-routing` integration specs + fixtures.**

  `@RequiresContext("themis-routing")` spec: a route classification end-to-end against **real** Kadmos (NLP) + Echo (fuzzy) + the capabilities registry; assert the `RoutingDecision`/`Resolution` for representative queries (a clean route, an `AwaitingClarification`, a `RefusalWithGaps`). WireMock for the LLM upstream only. **Acceptance:** the three routing scenarios pass against the live context.

- [ ] **T3 — `pythia-rca` integration specs + fixtures (gated on arc readiness).**

  `@RequiresContext("pythia-rca")` spec: a minimal investigation DAG (a small RCA) against **real** Charon (data movement) + Metis (estimation); assert a `Conclusion` artifact + provenance. **Only land when** Charon `charon/v0.3.0` + Metis `metis/v0.3.0` are on-cluster (master-plan M4). Mark clearly as deferred until then. **Acceptance:** when the deps are on-cluster, the minimal investigation completes end-to-end.

- [ ] **T4 — Per-context WireMock fixtures + readiness definitions.**

  For each context, fixtures under `.../wiremock/<context>/<scenario>/` and a documented readiness expectation mirrored by olymp's `context.yaml` `readiness`. Keep the externals stubbed minimal (LLM + modeler); everything in-chain is real. **Acceptance:** each context's fixtures load + reset cleanly; readiness lists match olymp.

- [ ] **T5 — Extend the nightly run-set; prove isolation.**

  Add the new contexts to the nightly run set (olymp `_nightly.txt` or enumerate-all per the ratified top-level `test-contexts/` choice). Run two contexts concurrently and assert namespace-per-run keeps them isolated (no shared-namespace bleed, no WireMock cross-talk). **Acceptance:** a multi-context nightly runs each green in its own `kantheon-<ctx>-<run>` namespace.

- [ ] **T6 — "Add a context" runbook.**

  Document the kantheon side (write specs, add fixtures, annotate `@RequiresContext`, add to run-set) with a pointer to olymp's context-authoring side (manifest + values + readiness). Land it in `docs/architecture/testing/` or the testing `plan.md` appendix. **Acceptance:** a new context can be added by following the runbook without re-deriving the harness.

## DONE criteria

- [ ] Nightly runs ≥3 contexts green (pythia-rca deferred until its deps are on-cluster), each namespace-isolated.
- [ ] Per-context fixtures + readiness defined and matched to olymp.
- [ ] The add-a-context runbook exists.

## Notes for the executor

- Land contexts **incrementally** — don't block this stage on pythia-rca's cross-arc dependency. golem-erp first (chart exists), themis-routing next, pythia-rca when M4 clears.
- Reuse the harness lib + WireMock loader from Phase 2; new contexts should be mostly spec + fixture work, not new plumbing.
