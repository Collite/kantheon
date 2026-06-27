# Phase 5 — Constellation integration

> **Reads with.** [`plan.md`](./plan.md) §7, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §5 (master-of-Golems) + §6 (Iris rendering) + §8 (observability) + §9 (testing), [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §8 (eval-corpus schema), [`../../planning-conventions.md`](../../planning-conventions.md) §4.
>
> **Phase deliverable.** Pythia joins the constellation: master-of-Golems Shem reads in the planner; iris-bff `PythiaClient` + investigation UX v1 (standard envelopes + step events); `pythia.yaml` AgentManifest content + Themis routing; eval gates in CI; observability + hardening. Tag `pythia/v1.0.0` — **the constellation is complete.**

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **5.1** — Master-of-Golems + manifest | Shem-read planner context (preferred_queries/terminology/capabilities); cross-domain fixture investigation; `pythia.yaml` content; Themis routing eval; heartbeat registration | [`tasks-p5-s5.1-master-of-golems.md`](./tasks-p5-s5.1-master-of-golems.md) |
| **5.2** — Iris integration | iris-bff `PythiaClient` (submit + SSE bridge); approval/clarification/budget prompts as envelope interactions; synthesizer blocks → envelopes; joint component test with Iris Phase 4 inbox | [`tasks-p5-s5.2-iris-integration.md`](./tasks-p5-s5.2-iris-integration.md) |
| **5.3** — Eval gates + hardening + ship | eval corpus + `just eval-pythia` CI gate; nightly live-LLM bucket; observability completion; load sanity; docs + tag | [`tasks-p5-s5.3-eval-hardening-ship.md`](./tasks-p5-s5.3-eval-hardening-ship.md) |

## Sequencing

```
Stage 5.1 ──► Stage 5.2 ──► Stage 5.3
  master-of-golems   iris        eval + ship
```
5.1 and 5.2 are largely independent (planner-side vs BFF-side) and may overlap; 5.3 closes after both.

## Pre-flight for the phase

- [ ] **Phase 4 DONE** — `pythia/v0.4.0` (all four intent kinds). *(Phase 5 can begin against Phases 1–3 for the SQL-only paths, but the eval corpus in 5.3 covers forecast/simulation buckets, so close Phase 4 first.)*
- [ ] **capabilities-mcp** live with **golem-erp / golem-ucetnictvi ShemManifest content** (lands in Golem Phase 4 — first Golem `golem-ucetnictvi`; Golem arc largely closed per repo state 2026-06-26). Needed for 5.1 master-of-Golems Shem reads.
- [ ] **iris-bff** reachable + **Iris Phase 4 Stage 4.1** (inbox + lifecycle subject + hypothesis tree) available for the 5.2 joint component test.
- [ ] **Themis** corpus available for the joint routing eval (5.1 T4).
- [ ] Bora-owned content: `pythia.yaml` router examples/counter-examples (5.1 T3); eval-corpus question selection ~15/bucket (5.3 T1) — see [`plan.md`](./plan.md) §10.

## Aggregate progress

- [x] **Stage 5.1** — Master-of-Golems + manifest. _(ShemReader area_entities∩entities → planner prompt; cross-domain fixture; pythia.yaml Claude-owned fields; Pythia routing buckets in the joint corpus; PythiaRegistration INVESTIGATOR heartbeat. 201 tests green.)_
- [x] **Stage 5.2** — Iris integration. _(iris-bff LivePythiaClient submit+list; PythiaEventMapper: lifecycle→step, AWAITING_*→envelope interactions (clarification+control chips), synth block→pythia bubble; Pythia synth sets PD-9 Block.provenance; joint inbox+lifecycle+conclusion test. iris-bff 211 / pythia 201 green.)_
- [x] **Stage 5.3** — Eval gates + hardening + ship. _(eval corpus + EvalGateSpec gating plan-validity/verdict/budget/replay; `just eval-pythia`; PythiaMetrics §8 set + /metrics scrape + Grafana dashboard; load-sanity 5-concurrent; README. pythia 204 green. Tag v1.0.0 post-merge.)_

When all three are checked: tag `pythia/v1.0.0`. **Phase 5 DONE — the constellation is complete.** ✅ All three done on `feat/pythia-p4-p5` (2026-06-27); tag deferred to post-merge.

## Up / across

- Up: [`./README.md`](./README.md). Neighbours: [`tasks-p4-overview.md`](./tasks-p4-overview.md).
- Cross-arc: [`../iris/`](../iris/) (Stage 4.1 inbox/lifecycle), [`../golem/`](../golem/) (ShemManifest content), Themis corpus.
