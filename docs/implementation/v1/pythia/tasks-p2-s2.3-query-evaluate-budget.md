# Stage 2.3 — Query + evaluate + budget

> **Phase 2, Stage 2.3.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4 Stage 2.3, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §5 ("Budget tracker"), [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §1 (`Predicate`) + §5 (GatewayClient), [`../../../architecture/kantheon-security.md`](../../../architecture/kantheon-security.md) §2.1 (OBO + token expiry), [`../../../design/pythia/Pythia-v1-Design.md`](../../../design/pythia/Pythia-v1-Design.md) §3.2/§4.1, [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

QueryNode executes against **theseus-mcp** (compile-before-run, OBO bearer, token-expiry parking); the rules-first `HypothesisEvaluator` scores predicates with **no LLM**; the `BudgetTracker` enforces the 75/90/100/110 ladder and parks AWAITING_BUDGET_DECISION when policy says ASK. **End state:** `PredicateEvaluatorSpec` green; a component test runs plan→execute→evaluate on a trivial-hypothesis fixture.

## Pre-flight

- [ ] Stage 2.2 DONE — executor + HandleTable v0.
- [ ] **theseus-mcp** `theseus-mcp/v0.1.0` reachable (fork Phase 3); tests Wiremock it. QueryNode calls **theseus-mcp** (not ai-platform query-mcp — plan §2).
- [ ] Branch `feat/pythia-p2-s2.3-query-evaluate-budget`.
- [ ] Read the theseus-mcp tool schemas (fork contracts / theseus contracts) — the `compile` + `query` tool shapes, `pipeline_warnings` field. Mirror exactly.

## Tasks (TDD-shaped: T3 written first)

- [ ] **T1 — `QueryMcpClient` (theseus-mcp).**

  Implement `clients/QueryMcpClient.kt`: **compile-before-run** for composed stacks (TransDSL ops on a `QueryNode.stack`), forward `pipeline_warnings` as Rule-6 messages. Every call carries the **user's OBO bearer — never service identity** (PD-8). **Token expiry mid-run** → park **AWAITING_USER_INPUT** and resume under a fresh bearer (kantheon-security §2.1 — fail-closed). Wiremock specs: successful compile+query; a `pipeline_warnings` response (assert forwarded); a 401/expired-token response (assert parks AWAITING_USER_INPUT).

  Acceptance: `QueryMcpClientSpec` green.

- [ ] **T2 — QueryNode executor.**

  Implement the `NodeExecutor` for `QueryNode` (plugs into the Stage 2.2 `DagExecutor`): resolve `params_json` from `HandleRef` projections (Stage 2.2 binding), apply the **IN-list ≤ 500 rule** — inline if ≤500, else **flag for materialise** (a Phase-4 path; here emit a Rule-6 flag + mark the step blocked-pending-materialise). Result → a `HandleTable` entry (`LiveQueryRef` or `PgResultSnapshot` if small). Emit `step_*` events (Stage 2.2 vocabulary).

  Test: a QueryNode with a bound param-list ≤500 executes against Wiremock theseus-mcp and produces a handle; a >500 list flags for materialise.

  Acceptance: `QueryNodeSpec` green.

- [ ] **T3 — `PredicateEvaluatorSpec` (tests first).**

  Create `src/test/kotlin/.../evaluate/PredicateEvaluatorSpec.kt`. One case per predicate kind (contracts §1 `Predicate`): `ROW_COUNT_*` (e.g. `ROW_COUNT_GT`/`_EQ`/`_LT`), `METRIC_DELTA_RATIO`, `NULL_RATE_LT`, `CORRELATION_STRENGTH`. For each: a passing input, a failing input, and a non-applicable input (predicate doesn't match the result shape → returns NON_APPLICABLE, **not** an error — the LLM fallback for NON_APPLICABLE is Phase 3 Stage 3.1).

  Acceptance: spec compiles + fails.

- [ ] **T4 — Rules-first `HypothesisEvaluator` (no LLM).**

  Implement `evaluate/HypothesisEvaluator.kt` + the predicate evaluators: evaluate each hypothesis's `Predicate` against its test steps' result handles, roll up `confidence`, set `HypStatus` (SUPPORTED/REFUTED/INCONCLUSIVE), emit the `hypothesis_*` events (design §3.3 hypotheses group — 6 events). **No LLM in this phase** — NON_APPLICABLE predicates leave the hypothesis INCONCLUSIVE with a note (the CHEAP fallback is Phase 3).

  Acceptance: `PredicateEvaluatorSpec` + a `HypothesisEvaluatorSpec` (confidence rollup + status + events) green.

- [ ] **T5 — `BudgetTracker`.**

  Implement `budget/BudgetTracker.kt`: **four dimensions** (llm_cost_usd, llm_tokens, latency_ms, step_count — confirm against design §3.1 `Constraints`). **Project-and-reserve** per batch from gateway pricing (`(modality, tier) × task_kind token constants × batch size`) via the `GatewayClient` tier-tag shim (contracts §5). Ladder: **75 %** warn (Rule-6) / **90 %** ASK-if-policy / **100 %** HALT_GRACEFULLY (synth runs on current evidence) / **110 %** hard stop. `on_budget_threshold: ASK` → park **AWAITING_BUDGET_DECISION** (PD-11); `/budget-decision` resumes (CONTINUE / HALT_GRACEFULLY / ABANDON).

  Test: injected pricing + a plan that crosses each ladder rung; assert the warn at 75, the park at 90 (policy ASK), graceful halt at 100, hard stop at 110; assert `/budget-decision` CONTINUE raises the ceiling per policy and resumes.

  Acceptance: `BudgetTrackerSpec` green.

- [ ] **T6 — Component test: plan → execute → evaluate.**

  Wire a component test (`testApplication`, Wiremock theseus-mcp + scripted planner from Stage 2.1) over a **trivial-hypothesis fixture**: one hypothesis, one QueryNode, one predicate. Assert the full path SUBMITTED→…→ a hypothesis verdict, with budget tracked and events emitted. This is the spine the Nescafe-Maggi e2e (Stage 2.4) extends.

  Acceptance: component spec green.

## DONE — Stage 2.3

- [ ] All tasks checked; `just test-kt pythia` green.
- [ ] plan→execute→evaluate runs on the trivial fixture with budget + events.
- [ ] Integration carry-overs recorded (live theseus-mcp query, real gateway pricing, token-expiry-resume against real Keycloak).
- [ ] CI green on `[pythia-p2-s2.3] query + evaluate + budget`.

## Library / pattern references

- **theseus-mcp tool schemas** (fork/theseus contracts) — compile + query shapes, `pipeline_warnings`.
- **contracts §1** (`Predicate` kinds), **§5** (GatewayClient tier mapping), **kantheon-security §2.1** (OBO + token-expiry parking).
- **architecture §5** — budget project-and-reserve + ladder.

## Out of scope

- **LLM** evaluation fallback (NON_APPLICABLE / fuzzy) — Phase 3 Stage 3.1.
- Suspicion classification — Phase 3 Stage 3.1.
- Materialise path for IN-list >500 — Phase 4 Stage 4.1 (flagged here only).
- Render / synthesize — Stage 2.4.
