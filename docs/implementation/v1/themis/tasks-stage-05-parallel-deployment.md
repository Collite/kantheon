# Stage 05 — Parallel deployment alongside Golem

Companion to [docs/v1/resolver-design.md](docs/v1/resolver-design.md).

Goal: run Resolver on production traffic safely, generate empirical comparison
data against Golem's existing in-process logic, and grow the eval corpus from
real disagreements. Per the platform's parallel-deployment principle.

## Entry criteria

- Stage 04 complete: Resolver is deployable, passes the Stage 03 eval corpus
  above the agreed threshold.
- `agents/erp-agent-2` (Golem) is the active analytical agent in production.
- An eval-corpus curation owner is named (could be Bora, could be a
  designated reviewer) — this person triages diff events into corpus
  fixtures.
- Bora confirms the burn-in window (proposed: 2–4 weeks).

## Exit criteria

- A feature flag in Golem controls Resolver invocation: `shadow` (run
  alongside, log results, do not use), `primary` (use Resolver, fall back to
  in-process on error), or `off`.
- Diff harness logs structured comparison events for every request when in
  `shadow` mode.
- Grafana dashboard surfaces divergence rate over time, divergence by
  question shape, and latency comparison.
- After the burn-in window: divergence rate is stable below an agreed
  threshold (specific number set after first week of data); no
  high-severity divergences open.
- Eval corpus has grown to at least 100 questions, with at least 30 added
  organically from observed disagreements.
- Decision documented (cut over to `primary`, or extend burn-in, or roll
  back).

## Tasks

### 1. Feature flag in Golem
- [ ] Add config keys to `agents/erp-agent-2`: `resolver.mode` (`off` /
      `shadow` / `primary`), `resolver.endpoint`, `resolver.timeout_ms`.
- [ ] In `off` mode: no Resolver call.
- [ ] In `shadow` mode: in-process logic runs and is used; Resolver is also
      invoked async, results logged, not used.
- [ ] In `primary` mode: Resolver is called first; in-process logic is the
      fallback when Resolver returns an error or times out.
- [ ] Default for first deploy: `shadow`.

### 2. Diff harness
- [ ] Library inside `agents/erp-agent-2` (or a shared Python lib if
      reusable) that runs in-process AND Resolver, captures both outputs,
      and emits a structured diff event.
- [ ] Diff event fields: `requestId`, `traceId`, `question`, `lang`,
      `inProcessResult` (function-call + entity bindings), `resolverResult`
      (same), `agreementType` (`full` / `partial-args` / `partial-entities`
      / `disagree` / `resolver-error` / `inproc-error`), `latencyMs` for
      each path.
- [ ] Emit the event as a structured log record (JSON), routed via Loki.
      Optionally also publish to a Kafka/NATS topic for offline analysis if
      such infra is available.

### 3. Disagreement curation pipeline
- [ ] Tool (script or small UI) under `agents/resolver/eval/curation/` that
      samples diff events, renders the in-process and Resolver results
      side-by-side, and lets the curator mark each as: in-process correct,
      Resolver correct, both wrong, both right (alternative valid
      interpretations), or skip.
- [ ] Marked cases are appended to the eval corpus
      (`infra/nlp/eval/corpus/seed.jsonl` for parse-only,
      `agents/resolver/eval/corpus/seed.jsonl` for full-resolution).
- [ ] Track per-day curation throughput; aim for at least 10 events
      reviewed per week to keep the corpus growing meaningfully.

### 4. Observability
- [ ] Grafana dashboard: divergence rate over time, divergence broken down
      by `agreementType`, top diverging question shapes (anonymised),
      latency P50/P95/P99 per path.
- [ ] Alert: divergence rate spikes >2× rolling 7-day baseline, or any
      single high-severity disagreement type (`resolver-error`,
      `inproc-error`) exceeds a threshold.
- [ ] Cache-hit rate visible (if Resolver's in-process caches are warming
      adequately).

### 5. Resolver iteration
- [ ] When divergence indicates a Resolver bug → fix in `agents/resolver` and
      add a regression test from the diff event to the eval corpus.
- [ ] When divergence indicates a Golem bug → fix in `agents/erp-agent-2`;
      Bora prioritises (per CLAUDE.md "do only what is asked"; flag the
      finding rather than refactor unilaterally).
- [ ] When divergence is "both right, alternative interpretations" →
      consider whether the question is genuinely ambiguous and should be
      escalated to HITL more aggressively. Tune Resolver's confidence
      threshold if needed.

### 6. Burn-in window
- [ ] Run in `shadow` mode for the agreed window.
- [ ] Weekly review: divergence trend, curation throughput, severity
      breakdown, eval-corpus growth.
- [ ] At end of window: cut-over decision documented in
      `docs/v1/resolver-rollout-decision.md`.

### 7. Documentation
- [ ] Runbook: how to interpret diff events, how to triage, how to feed into
      the eval corpus, how to roll back if `primary` mode misbehaves.
- [ ] Update `docs/overview/Architecture-v1.md` to show Resolver in shadow
      deployment.

## Risks and mitigations

**Latency cost of running both paths.** In `shadow` mode the Resolver call
adds latency relative to in-process; users may notice if it isn't async. Run
Resolver async (fire-and-forget for shadow mode) so user-facing latency is
unaffected. The diff event is emitted whenever Resolver completes, even
after the in-process response has been sent.

**LLM cost during shadow mode.** Resolver fires on every question Golem
receives; LLM token cost doubles for the shadow window. Budget accordingly.
If cost is prohibitive, sample (e.g., shadow on 10% of traffic) instead of
running on every request — but tune the sampling rate so the diff signal is
still meaningful.

**Diff event volume.** A high-traffic deploy generates many diff events.
Sampling can also apply to event emission (emit all disagreements; sample
agreements at 10%). Curation throughput is the bottleneck, not event
storage.

**Burn-in window may need extension.** If divergence rate doesn't stabilise,
do not force the cut-over. Document the extension and continue. Per memory
("don't merge branches" — implies don't force-merge stages either): the
parallel deployment can run indefinitely if needed.

**`agreementType` taxonomy may evolve.** Start with the proposed set; expect
to add categories as edge cases appear (e.g., "Resolver escalated to HITL
appropriately, Golem guessed wrong"). Keep the type field as a string, not an
enum, in the log schema for flexibility.

## Out of scope for Stage 05

- Wrangler integration (Stage 06; depends on Wrangler being ready).
- Pythia integration (Stage 06; gated by Pythia v0).
- Removing in-process logic from Golem (later iteration after Stage 06's
  `primary` mode has proven stable).
