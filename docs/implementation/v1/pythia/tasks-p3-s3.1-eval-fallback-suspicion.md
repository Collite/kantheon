# Stage 3.1 — Evaluation LLM fallback + suspicion

> **Phase 3, Stage 3.1.**
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §5 Stage 3.1, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §3 (`evaluate/`, `suspicion/`) + §5, [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §5 (GatewayClient), [`../../../design/pythia/Pythia-v1-Design.md`](../../../design/pythia/Pythia-v1-Design.md) §3 (evaluation + suspicion), [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

The evaluator gains a **CHEAP-tier LLM fallback** for predicates that are NON_APPLICABLE or absent (the Phase 2 rules-first path stays primary); a `SuspicionClassifier` flags dodgy results via a rules checklist + CHEAP fallback; `on_suspicious_result` policy drives CONTINUE/WARN/HALT actions (HALT → AWAITING_USER_INPUT). **End state:** mock-executor evaluator-fallback specs green; suspicion specs green; policy-action specs green.

## Pre-flight

- [ ] Phase 2 DONE — rules-first `HypothesisEvaluator` (Stage 2.3) is the primary path this stage falls back from.
- [ ] Branch `feat/pythia-p3-s3.1-eval-fallback-suspicion`.
- [ ] Scripted-LLM fixture harness extended with CHEAP verdict scripts.

## Tasks (TDD-shaped)

- [ ] **T1 — Evaluator CHEAP-tier fallback.**

  Extend `evaluate/HypothesisEvaluator.kt`: when a hypothesis has **no predicate** or its predicate returns **NON_APPLICABLE** (Stage 2.3), issue a **CHEAP-tier** structured-verdict call (`GatewayClient` CHEAP tag, task_kind EVALUATION) returning `{ verdict: SUPPORTED|REFUTED|INCONCLUSIVE, confidence, rationale }`. Rules-first stays primary — fallback only fires for the gap. Author `prompts/evaluator.md` (cs + en) with a typed structured-output schema.

  Test (mock-executor): a no-predicate hypothesis gets a CHEAP verdict; a predicate-applicable hypothesis does **not** call the LLM (assert zero gateway calls); a NON_APPLICABLE predicate falls back.

  Acceptance: `EvaluatorFallbackSpec` green.

- [ ] **T2 — `SuspicionClassifier` rules checklist.**

  Implement `suspicion/SuspicionClassifier.kt` — a rules checklist over a step result (design §3): empty-result-where-rows-expected, **10× / 0.1×** row-count anomalies vs expectation, high NULL-rate, schema mismatch (columns differ from the query's projection), and **security flags** forwarded from theseus-mcp (`pipeline_warnings`). Returns a `SuspicionVerdict { suspicious: Bool, reasons: [...] }`.

  Test: one fixture per rule trips exactly that reason; a clean result trips none.

  Acceptance: `SuspicionClassifierSpec` green.

- [ ] **T3 — CHEAP fallback for fuzzy suspicion.**

  For cases the rules can't decide (e.g. "is this distribution plausible?"), add a CHEAP-tier fuzzy-suspicion call gated behind the rules (only when rules are inconclusive *and* the result feeds a load-bearing hypothesis). Reuse the evaluator prompt family or a small `prompts/suspicion.md` (cs + en).

  Test (mock-executor): a rules-inconclusive load-bearing result triggers the CHEAP call; a rules-clear result does not.

  Acceptance: `FuzzySuspicionSpec` green.

- [ ] **T4 — `on_suspicious_result` policy actions.**

  Wire the `HitlPolicy.on_suspicious_result` actions (design §3.1): **CONTINUE** (record + proceed), **WARN** (Rule-6 message + proceed), **HALT** → park **AWAITING_USER_INPUT** (resume via `/answer`). Emit a `finding`/suspicion event (design §3.3 suspicion/findings group, 2 events).

  Test (`testApplication` + mocked classifier): each policy value produces its action; HALT parks AWAITING_USER_INPUT and `/answer` resumes.

  Acceptance: `SuspicionPolicySpec` green.

- [ ] **T5 — Events + metrics.**

  Emit the suspicion/findings events via the `EventEmitter`; add the architecture §8 metrics touched here (`pythia_hypotheses_total{terminal_status}` rollup contribution; a suspicion counter if not already present — keep to the §8 list, don't invent metric names). 

  Test: a suspicious-then-halted fixture emits the expected event + increments the expected counter (assert via a test meter registry).

  Acceptance: `SuspicionEventsSpec` green; `just test-kt pythia` green.

## DONE — Stage 3.1

- [ ] All tasks checked; suite green.
- [ ] Rules-first remains primary (LLM only on the gap — asserted by zero-call tests).
- [ ] Integration carry-overs recorded (live CHEAP-tier verdict quality → feeds Phase 5 eval gate).
- [ ] CI green on `[pythia-p3-s3.1] eval fallback + suspicion`.

## Library / pattern references

- **contracts §5** (GatewayClient CHEAP tag), **design §3** (suspicion checklist + evaluation fallback), **architecture §8** (metric names — use only those).
- **Koog** structured output for the CHEAP verdict schema (same pattern as Stage 2.1 planner).

## Out of scope

- Plan revision / prioritisation — Stage 3.2.
- Replay/reproduce / RCA e2e — Stage 3.3.
- Honest `model.decompose.variance` — v1.5 backlog (Stage 3.3 uses a heuristic).
