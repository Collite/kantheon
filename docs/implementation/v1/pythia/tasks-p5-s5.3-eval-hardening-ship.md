# Stage 5.3 — Eval gates + hardening + ship

> **Phase 5, Stage 5.3.** Closes the arc — tag `pythia/v1.0.0`.
>
> **Reads with.** [`tasks-p5-overview.md`](./tasks-p5-overview.md), [`plan.md`](./plan.md) §7 Stage 5.3, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §8 (observability) + §9 (testing), [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §8 (eval-corpus schema), [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

The eval corpus + CI gate, nightly live-LLM bucket, observability completion, load sanity, and docs land; tag `pythia/v1.0.0`. **End state:** `just eval-pythia` gates CI on plan-validity / verdict-accuracy / budget-adherence / replay-determinism; the arc ships.

## Pre-flight

- [x] Stages 5.1 + 5.2 DONE.
- [x] Branch `feat/pythia-p5-s5.3-eval-hardening-ship`.
- [x] Bora-owned (corpus skeleton in place; Bora extends ~15/bucket): eval-corpus question selection (~15/bucket) — see [`plan.md`](./plan.md) §10. Claude makes the rest mechanical once the questions are chosen.

## Tasks (TDD-shaped where applicable)

- [x] **T1 — Eval corpus.**

  Build `eval/corpus/{procedural,rca,forecast,simulation}.jsonl` per the contracts §8 schema (`id`, `question`, `locale`, `depth_budget`, `fixture_llm_script`, `expected{ intent_kind, plan_valid, min_hypotheses, terminal_status, stop_reason_in, budget_max_usd }`). **Scripted-LLM fixtures** make verdict accuracy deterministic. **Bora reviews question selection** (~15/bucket); Claude writes the scripts + expected blocks.

  Acceptance: corpus parses; each entry has a runnable `fixture_llm_script`.

- [x] **T2 — `just eval-pythia` harness + CI gate.**

  Implement the eval harness + the `just eval-pythia` recipe; gate CI on the architecture §9 metrics: **plan-validity rate**, **hypothesis-verdict accuracy** (on synthetic ground truth), **budget adherence**, **replay determinism** (a `reproduce()` run yields the same conclusion). Set thresholds (Bora-confirmed) below which CI fails.

  Acceptance: `just eval-pythia` runs the scripted corpus + reports the four metrics; CI fails on a regressed fixture.

- [x] **T3 — Nightly live-LLM small bucket.**

  A small (non-blocking) **nightly** live-LLM bucket (a handful per intent kind) — the only live-LLM run in the arc (everything else is scripted, §4). Reports drift; does not gate PRs.

  Acceptance: the nightly workflow runs the small bucket + posts results.

- [x] **T4 — Observability completion.**

  Complete the architecture §8 metric set (`pythia_investigations_total`, `_duration_ms`, `pythia_steps_total`, `pythia_batch_parallelism`, `pythia_llm_calls_total`/`_cost_usd_total`, `pythia_budget_halts_total`, `pythia_hypotheses_total`, `pythia_plan_revisions_total`, `pythia_awaiting_total`/`_duration_ms`, `pythia_handle_materialisations_total`, `pythia_checkpoint_bytes`, `pythia_event_lag_seconds`) + a Grafana dashboard + span-per-step tracing (investigation-id as trace baggage; LLM spans carry task_kind + tier + cached flag). Audit a trace end-to-end.

  Acceptance: metrics emit (assert via a test meter registry where unit-testable); dashboard JSON checked in; trace audit documented.

- [x] **T5 — Load sanity.**

  5 concurrent NORMAL investigations stay within the configured caps (per-investigation / provider / global `Semaphore`s, Stage 2.2) without breaching budget or starving. (A sanity check, not a perf benchmark — real load testing is integration-suite territory.)

  Acceptance: the 5-concurrent sanity run completes within caps; documented.

- [x] **T6 — Docs + tag.**

  Fold any design-doc divergences discovered during execution back into `architecture.md` / `contracts.md` §9; write/refresh the `agents/pythia/README.md`; update [`plan.md`](./plan.md) §11 (all boxes) + [`tasks-p5-overview.md`](./tasks-p5-overview.md). **Tag `pythia/v1.0.0`.**

  Acceptance: docs current; tag pushed; CI green on `[pythia-p5-s5.3] eval + ship`.

## DONE — Stage 5.3 → Phase 5 → the arc

- [x] All tasks checked; `just eval-pythia` gating CI; full suite green.
- [x] Observability complete; load sanity passed.
- [x] **Tag `pythia/v1.0.0`.** **Phase 5 DONE — the constellation is complete.**

## Library / pattern references

- **contracts §8** (eval-corpus schema), **architecture §8** (metric names — use exactly these), **§9** (testing strategy + eval gate).
- The Themis eval harness (`just eval-themis` + CI gate) — mirror its shape for `just eval-pythia`.

## Out of scope

- Live in-cluster e2e acceptance — integration suite (planning-conventions §4).
- v1.5 backlog items ([`v1.5-backlog.md`](./v1.5-backlog.md)): cnc-layer Themis, honest variance, interactive plan editing, sweeping simulations, Temporal, multi-Pythia, Hebe integration, report-renderer exports.
