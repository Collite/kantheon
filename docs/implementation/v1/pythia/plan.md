# Pythia — Phased Implementation Plan (kantheon arc)

> **Scope.** From "Pythia exists as a design + AgentManifest fixture" to "Pythia v1 live: procedural / RCA / forecast / simulation investigations, full HITL, Arrow data plane, constellation-integrated." Five phases, sixteen stages, ~95 tasks. This supersedes the "Pythia is parked" status of 2026-05.
>
> **Companions.** [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md), [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md), [`../../../design/pythia/Pythia-v1-Design.md`](../../../design/pythia/Pythia-v1-Design.md), [`./v1.5-backlog.md`](./v1.5-backlog.md), [`../../planning-conventions.md`](../../planning-conventions.md).
>
> **Arc ordering.** Third arc (after Iris, Golem). Hard prerequisites: Themis Phase 3 (INVESTIGATION_DEEP + routing), envelope-render (Golem Phase 1), envelope/v1 (Iris Phase 1). Phases 1–3 need nothing else; Phase 4 gates on the cross-repo Charon/Metis track.
>
> **Testing.** Per the testing policy ([`../../planning-conventions.md`](../../planning-conventions.md) §4): plans develop against mocked unit/component tests only (MockK, in-memory fakes, Wiremock, scripted-LLM fixtures, mocked clients for Charon/Metis/NATS/PG/LLM-gateway); live-LLM runs and in-cluster e2e are deferred to the separate integration-test suite.

## 1. Phase summary

| Phase | Goal — what deploys at the end | Stages | Estimated effort |
|---|---|---|---|
| **Phase 1 — contract + skeleton + lifecycle** | `pythia/v1` proto; pod in local K3s; lifecycle state machine over all **12** statuses (PD-11 added `AWAITING_BUDGET_DECISION`); checkpointer; event stream (PG log + NATS incl. the lifecycle subject + SSE bridge); `ListInvestigations`; REST control surface answering on fixtures. | 1.1 / 1.2 / 1.3 | ~2 weeks |
| **Phase 2 — procedural investigations e2e** | Themis INVESTIGATION_DEEP; planner; DAG executor; QueryNode via theseus-mcp; rules-first evaluator; budget tracker; synthesizer v0. Nescafe-Maggi worked example green. | 2.1 / 2.2 / 2.3 / 2.4 | ~3 weeks |
| **Phase 3 — hypotheses + HITL complete** | Plan reviser; prioritisation; suspicion; LLM-fallback evaluation; all **five** AWAITING_* + resume; replay/reproduce. RCA worked example green with heuristic explained-variance. | 3.1 / 3.2 / 3.3 | ~2–3 weeks |
| **Phase 4 — data plane + models** | Pythia integration of Charon and Metis (both built as **own arcs** — [`../charon/plan.md`](../charon/plan.md), [`../metis/plan.md`](../metis/plan.md)); DataFrameNode on Polars session DFs; Seaweed/Redis/DB materialisation; ModelNode. Forecast + simulation worked examples green. | 4.1 / 4.2 | ~2–3 weeks |
| **Phase 5 — constellation integration** | Master-of-Golems; Iris investigation UX v1; manifest content; eval gates in CI; tag `pythia/v1.0.0`. | 5.1 / 5.2 / 5.3 | ~2 weeks |

Critical path: P1 → P2 → P3 → P5; P4 parallelises with P3 once the cross-repo track starts. Phases 1–3 alone ship a useful RCA investigator (SQL-only plans).

## 2. Pre-flight — before Phase 1 starts

| Item | Status (2026-06-12) |
|---|---|
| Themis `themis/v0.2.0` (Profile INVESTIGATION_DEEP honoured; routing live) | pending — Themis Phase 3 |
| envelope/v1 locked (Iris S1.1); envelope-render `v0.1.0` (Golem P1) | pending |
| NATS JetStream reachable from kantheon namespace (fabric-infra middleware) | verified deployed; reachability smoke = P1 task |
| Postgres for pythia | true |
| `capabilities-mcp` live with golem-erp ShemManifest content (for P5 master-of-Golems) | content lands in Golem Phase 4 |
| **Charon arc planned + scheduled** — own three-artefact arc ([`../charon/plan.md`](../charon/plan.md)); `charon/v0.3.0` is the Phase 4 gate here | **planned 2026-06-12**; scheduling slot Bora-call (lean: during Golem arc) |
| **Charon + Metis arcs planned** — own three-artefact arcs; `charon/v0.3.0` + `metis/v0.3.0` gate Phase 4 | **planned 2026-06-12**; scheduling Bora-call |
| **Fork Phase 3 closed** — the in-repo query path + Polars worker gate Phase 4: `theseus/v0.1.0` + `theseus-mcp/v0.1.0` + `steropes/v0.1.0` (+ `kyklop/v0.1.0`) | **closed 2026-06-17** (fork Stage 3.6 sign-off) |
| ~~Cross-repo track kickoff: `aip-v1-gateway-worker-plan.md` coordination doc~~ | **moot post-fork (2026-06-17)** — the LLM gateway + Polars worker are in-repo (Prometheus, Steropes); no ai-platform cross-repo track remains. Worker read-out verified at fork Stage 4.1 T4 |
| ~~llm-gateway tier-routing extension scheduled in ai-platform~~ | **superseded** — the gateway is in-repo **Prometheus**; tier-routing `(modality, tier, task_kind)` is a Prometheus backlog item ([`../../kantheon-v1.1.md`](../../kantheon-v1.1.md) §5), not ai-platform work; GatewayClient shim covers until then. Not blocking |

## 3. Phase 1 — contract + skeleton + lifecycle

### Stage 1.1 — pythia/v1 proto
**Tasks (5).** 1. Write `pythia.proto` field-complete per contracts §1 + design §3 (request, artifact, plan, hypothesis, step, handle incl. `PgResultSnapshot`, conclusion, full event oneof). 2. `just proto`; KT bindings compile. 3. Serialisation round-trip specs incl. unknown-enum tolerance. 4. Proto-doc comments matching design prose. 5. Golden JSON fixtures for the three worked examples' artifacts (hand-built, abbreviated).

### Stage 1.2 — module + persistence + checkpointer
**Tasks (6).** 1. Module skeleton (App.kt, health routes, k8s base, CI). 2. Flyway DDL per contracts §4 + jOOQ. 3. Tests first: repository specs against an in-memory / mocked DB fake (real-PG fidelity deferred to the separate integration-test suite). 4. Repositories. 5. Tests first: `CheckpointerSpec` — snapshot on transition/revision/batch, diff-based, restore round-trip, resume idempotency (status-conditional UPDATE), **per-handle recipe + Arrow-fingerprint recording (PD-5 §3a checkpoint shape — Charon move spec / Metis fit spec; probes consume it in Phase 4)**. 6. Checkpointer.

### Stage 1.3 — lifecycle + events + control surface
**Tasks (8).** 1. Tests first: exhaustive state-machine transition table (legal/illegal per design §3.4 **+ `AWAITING_BUDGET_DECISION` — 12 statuses, five AWAITING_*, PD-11**). 2. `InvestigationOrchestrator` state machine (no real subsystems yet — scripted stage stubs). 3. `EventEmitter`: PG log (sequence-assigning) + NATS publisher + degrade-to-log-only mode, **incl. the coarse lifecycle subject `pythia.lifecycle.{user_id}` on every status transition (PD-2)**; specs against a mocked/in-memory NATS publisher (real-NATS fidelity deferred to the separate integration-test suite). 4. SSE bridge `GET /events?from_seq` (PG replay → live tail). 5. REST control surface per contracts §2 (submit/get/approve/answer/**budget-decision**/halt; 409 semantics; **request admission per PD-8: bearer validation + visibility_roles re-check**). 6. **`GET /v1/investigations` per-user list (PD-2: `InvestigationSummary` paging — the inbox source)**. 7. AWAITING_* TTL expiry sweeper (24 h default). 8. Deploy to K3s; fixture investigation walks SUBMITTED→…→DONE on stubs.

**DONE.** Tag `pythia/v0.1.0`. **Phase 1 DONE.**

## 4. Phase 2 — procedural investigations end-to-end

### Stage 2.1 — resolution + planner
**Tasks (7).** 0. **Handoff seeding (PD-1):** investigations seed from `context.handoff` — anchor query/view (`handoff.view`), entities, `source_turn_ref` back-link on the `Conclusion`; tests on a Golem-escalation fixture. 1. `ThemisClient` (INVESTIGATION_DEEP, continuation threading, ClarificationRequest → AWAITING_RESOLUTION_INPUT, RefusalWithGaps → FAILED); Wiremock specs. 2. Planner prompt v1 (`prompts/planner.md`, cs+en) — typed PlanDag tool schema. 3. Tests first: `PlanComposerSpec` on scripted STRONG outputs (valid plan, invalid→feedback-retry, max-3→HALT). 4. `PlanComposer` (Koog StructureFixingParser). 5. `PlanValidator` (typed preconditions, DataDep type compat, capability existence via capabilities-mcp search, depth caps). 6. `plan_drafted` + approval path wired (AWAITING_PLAN_APPROVAL honouring hitl_policy).

### Stage 2.2 — DAG executor
**Tasks (6).** 1. Tests first: frontier computation property tests (DAG shapes, satisfied-dep edge cases). 2. Executor core: frontier → batches, `Semaphore` caps (per-investigation/provider/global), priority launch + promotion. 3. Retry policy (transient backoff w/ jitter; permanent → INCONCLUSIVE; systemic → HALT). 4. Drain semantics + park/resume integration with checkpointer (property tests: no step launches post-drain signal). 5. `batch_*`/`step_*` events. 6. HandleTable v0 (`LiveQueryRef`, `PgResultSnapshot` w/ inline cap; `HandleRef` param binding resolution).

### Stage 2.3 — query + evaluate + budget
**Tasks (6).** 1. `QueryMcpClient` (compile-before-run for composed stacks; pipeline_warnings forwarded; **user's OBO bearer on every call — never service identity, PD-8; token expiry mid-run → park AWAITING_USER_INPUT per kantheon-security §2.1**). 2. QueryNode executor (params from HandleRef projections; IN-list ≤500 rule, else flag for materialise — Phase 4). 3. Tests first: `PredicateEvaluatorSpec` (ROW_COUNT_*, METRIC_DELTA_RATIO, NULL_RATE_LT, CORRELATION_STRENGTH). 4. Rules-first `HypothesisEvaluator` (no LLM in this phase) + confidence rollup + `hypothesis_*` events. 5. `BudgetTracker` (4 dimensions, project-and-reserve from gateway pricing, ladder 75/90/100/110, **`on_budget_threshold: ASK` parks `AWAITING_BUDGET_DECISION` (PD-11)**, `GatewayClient` tier-tag shim per contracts §5). 6. Component test: plan→execute→evaluate on the trivial-hypothesis fixture.

### Stage 2.4 — render + synthesize + e2e
**Tasks (6).** 1. RenderNode (TABLE/NARRATIVE_FRAGMENT) via envelope-render; ChartIntent path stubbed to Phase 4 data. 2. Synthesizer v0 (STRONG, block streaming via `synthesizer_block_*`, Conclusion assembly with stop_reason honesty). 3. NARRATIVE_FRAGMENT per-fragment CHEAP calls. 4. Nescafe-Maggi fixture e2e (scripted/mocked LLM): full event trace asserted against design §4.1. 5. ~~Live-LLM manual run vs real platform (dev).~~ Deferred to the separate integration-test suite (the scripted-LLM e2e in task 4 satisfies DONE). 6. Tag.

**DONE.** Tag `pythia/v0.2.0`. **Phase 2 DONE — procedural investigations ship.**

## 5. Phase 3 — hypotheses + HITL complete

### Stage 3.1 — evaluation LLM fallback + suspicion
**Tasks (5).** 1. Evaluator CHEAP-tier fallback (no predicate / non-applicable) with structured verdicts; mock-executor specs. 2. `SuspicionClassifier` rules checklist (empty-result, 10×/0.1× row counts, NULL-rate, schema mismatch, security flags). 3. CHEAP fallback for fuzzy suspicion. 4. `on_suspicious_result` policy actions (CONTINUE/WARN/HALT→AWAITING_USER_INPUT). 5. Events + metrics.

### Stage 3.2 — reviser + prioritisation
**Tasks (6).** 1. Tests first: scoring formula spec (`confidence × explanatory × 1/cost × diagnostic × novelty`; tie-break only top-2 within 10%). 2. Prioritisation + `deepening_decision` events. 3. Reviser prompt + `PlanReviserSpec` on scripted outputs (PRUNE/PIVOT/DECOMPOSE/HALT). 4. `PlanReviser` (STRONG; revision caps per depth_budget; `on_plan_revision` policy → AWAITING_PLAN_REVISION_APPROVAL). 5. Loose-end derivation (PLANNING_TIME + EXECUTION_TIME orphan sweep; suggested_followup). 6. Stop-condition spine (5 reasons + per-type completion criteria; four RCA brakes).

### Stage 3.3 — replay/reproduce + RCA e2e
**Tasks (5).** 1. `replay` (re-resolve relative params) + `reproduce` (frozen resolved_params; blob reuse check) per design §3.6; parent/child lineage. 2. Heuristic explained-variance (capped sum) into ConfidenceInfo caveats. 3. RCA worked example (design §4.2) as scripted fixture — full revision/deepening trace asserted (satisfies DONE). 4. ~~Live-LLM RCA dev run + prompt iteration.~~ Deferred to the separate integration-test suite. 5. Tag.

**DONE.** Tag `pythia/v0.3.0`. **Phase 3 DONE — RCA ships.**

## 6. Phase 4 — data plane + models

**Pre-flight.** **Charon arc Phase 3 closed (`charon/v0.3.0` + `charon-mcp/v0.1.0`)** — [`../charon/plan.md`](../charon/plan.md); **Metis arc Phase 3 closed (`metis/v0.3.0` + `metis-mcp/v0.1.0`)** — [`../metis/plan.md`](../metis/plan.md) (gates Stage 4.2); **Fork Phase 3 closed — the in-repo query path + Polars worker the data plane now runs on: `theseus/v0.1.0` + `theseus-mcp/v0.1.0` (QueryNode/DataFrameNode call theseus-mcp, not ai-platform query-mcp) + `steropes/v0.1.0` (the Polars worker — DataFrameNode session DFs) + `kyklop/v0.1.0` (dispatch)** — [`../fork/plan.md`](../fork/plan.md) Phase 3, signed off Stage 3.6 (2026-06-17); `aip-v1-gateway-worker-plan.md` closed (gateway tier routing; worker workspace read-out — also a Charon-arc pre-flight); Polars Worker (Steropes) reachable; `pythia-evidence` bucket + Charon connection registry provisioned (Charon arc Stages 1.3/2.3).

### Stage 4.1 — Pythia integration of Charon + DataFrameNode
**Tasks (8).** 0. **PD-5 resume semantics (contracts §3a):** resume-time liveness probes (Charon `Describe`) + lazy re-materialization of dead handles from checkpointed recipes; fingerprint drift → Rule-6 warning + `LooseEnd`, never hard-fail; tests on a kill-TTL-resume fixture. 1. `CharonClient` — gRPC against `org.tatrman.charon.v1.CharonService` ([`../../../architecture/charon/contracts.md`](../../../architecture/charon/contracts.md) §1); fixture-server specs. 2. Handle kinds activate: `WorkerSessionDF` (df_name keying), `SeaweedArrowBlob`, `RedisArrowEntry`, `DbTableRef` (mapping per Charon contracts §7). 3. Materialisation policy engine (evidence-persist, cross-engine, TTL-approach triggers per design §6.2 policies). 4. Sticky-affinity scheduler hints from WorkerSessionDF parents; evidence persistence at finalisation (→ Seaweed; retention tags); GC/evict on completion. 5. `WorkerClient` (`WorkerService.Execute` streaming, session reuse) + DataFrameNode executor (dfdsl; session DF chaining). 6. Planner gains DataFrame composition (capability lights up via capabilities-mcp; SQL-only degradation tested both ways). 7. IN-list >500 → materialise path live; worked-example variant: Nescafe-Maggi N3 as DataFrameNode.

### Stage 4.2 — Metis integration + ModelNode + forecast/simulation e2e
**Tasks (6).** 1. `MetisClient` — gRPC against `org.tatrman.metis.v1.MetisService` ([`../../../architecture/metis/contracts.md`](../../../architecture/metis/contracts.md) §1); fixture-server specs **incl. the `GetStatus` resume probe (PD-5 — workspace dead → re-fit from checkpointed fit spec)**. 2. ModelNode executor (Charon-staged session DFs → Fit/Project/Simulate; `Diagnose` + LLM-interpreted ReasoningNode per Metis contracts §4; `NOT_FOUND` model → re-fit). 3. Forecast worked example (design §4.3) scripted against a mocked Metis fixture, asserted on the pinned Metis goldens (live-Metis run deferred to the separate integration-test suite). 4. Simulation variant (scenario_params → `SimulateScenario` insertion). 5. ChartBlock rendering for forecast CI bands (envelope-render chart kinds extended if needed — coordinate with Golem arc). 6. Tag.

**DONE.** Tag `pythia/v0.4.0`. **Phase 4 DONE — all four intent kinds ship.**

## 7. Phase 5 — constellation integration

### Stage 5.1 — master-of-Golems + manifest
**Tasks (5).** 1. Shem-read planner context (preferred_queries/terminology/capabilities of relevant Golems via CapabilitiesReadClient; relevance = domain_entities ∩ resolved entities). 2. Cross-domain fixture investigation (ERP + second synthetic Shem). 3. `pythia.yaml` AgentManifest content fill (Bora: description_for_router, example/counter questions; Claude: endpoints/latency/cost from measurements). 4. Themis routing eval: RCA/FORECAST/SIMULATION/cross-domain buckets route to Pythia (joint with Themis corpus). 5. Heartbeat registration replaces fixture.

### Stage 5.2 — Iris integration
**Tasks (5).** 1. iris-bff `PythiaClient` (submit + SSE bridge consumption; event→IrisStreamEvent mapping per Iris contracts). 2. Approval/clarification/budget prompts as envelope interactions (PendingClarification + PromptChips; plan approval as chip pair; budget decision → the `AWAITING_BUDGET_DECISION` flow). 3. Synthesizer blocks → envelopes (block-per-bubble; agent_id pythia; executor refs added to `Block.provenance` — step/hypothesis/model, PD-9). 4. Component test (mocked Pythia + iris-bff testApplication): chat-submitted SHALLOW investigation renders through the Iris envelope path **+ joint mocked test with Iris Stage 4.1 (inbox + lifecycle subject + hypothesis tree)** (the live in-cluster Pythia→Iris e2e is deferred to the separate integration-test suite; the render capability is unchanged). 5. ~~Investigation-UI backlog notes (v1.5)~~ **superseded by PD-2 (2026-06-12): inbox + hypothesis tree are Iris Phase 4 Stage 4.1 (v1); only the plan-DAG pane stays deferred (`kantheon-v1.1.md` §2)**.

### Stage 5.3 — eval gates + hardening + ship
**Tasks (6).** 1. Eval corpus per contracts §8 (scripted buckets; Bora reviews question selection). 2. `just eval-pythia` harness + CI gate (plan-validity, verdict accuracy, budget adherence, replay determinism). 3. Nightly live-LLM small bucket. 4. Observability completion (architecture §8 metrics + Grafana dashboard + trace audit). 5. Load sanity: 5 concurrent NORMAL investigations within caps. 6. Docs (design-doc divergence fold-in, READMEs) + tag.

**DONE.** Tag `pythia/v1.0.0`. **Phase 5 DONE — the constellation is complete.**

## 8. In-repo coordination (gateway tiers / worker read-out)

> **Post-fork (2026-06-17):** there is **no ai-platform cross-repo track left** — the LLM gateway and the workers are all in-repo. The former `aip-v1-gateway-worker-plan.md` coordination doc is **moot** (it was never written; the fork absorbed its contents).

Charon, Metis, **and the forked gateway/workers are all kantheon-side** — no cross-repo coordination beyond fixture exchange. The residual items live in-repo: **gateway tier-routing** (`(modality, tier, task_kind)` — additive) is a **Prometheus** backlog item ([`../../kantheon-v1.1.md`](../../kantheon-v1.1.md) §5; GatewayClient shim covers until then); **Polars worker workspace read-out** was verified at **fork Stage 4.1 T4** (Steropes baseline) and against the Charon arc (`ReadWorkspace` RPC); deployment/secrets coordination stays in fabric-infra (Charon's Seaweed bucket + DB connection secrets; Metis + Steropes resource profiles).

## 9. Out of scope (v1.5+ — see [`v1.5-backlog.md`](./v1.5-backlog.md))

cnc-layer Themis; self-hosted CHEAP embeddings; `model.decompose.variance` (honest RCA variance); per-engine IN-list thresholds; learned token estimates; interactive plan editing; one-click follow-up UX; sweeping simulations; Temporal; multi-Pythia; mid-flight tier escalation; MCP exposure of Pythia itself; Hebe integration; plan-node-level Golem delegation; Report-Renderer-rendered investigation exports (lands when Midas `services/report-renderer` ships — consumer wiring only).

## 10. Open questions / Bora-owned content

| Item | Blocking | Note |
|---|---|---|
| ~~Charon placement~~ **locked 2026-06-12**: kantheon `services/charon` + `tools/charon-mcp`, pkg `org.tatrman.charon.v1`, DB sources/targets, ADBC | — | first migrated platform-grade service |
| ~~Metis placement~~ **locked 2026-06-12**: kantheon `services/metis` (Python — library moat) + `tools/metis-mcp`, pkg `org.tatrman.metis.v1` | — | own arc: [`../metis/plan.md`](../metis/plan.md); second migrated service |
| Charon named-connection registry content (which DBs at v1) | Stage 4.1 | Bora; config + sealed secrets in fabric-infra |
| AgentManifest router content (examples/counter-examples) | Stage 5.1 | same pattern as golem-erp fill |
| Eval-corpus question selection (~15/bucket) | Stage 5.3 | scripted fixtures make the rest mechanical |
| Seaweed bucket provisioning + retention config on df-test | Stage 4.1 | fabric-infra change |
| depth_budget default $ caps confirmation (0.20/2/10) | Phase 2 config | design defaults; config-only |

## 11. Phase progression checklist

- [x] **Stage 1.1** — proto. — [x] **1.2** — persistence + checkpointer. — [x] **1.3** — lifecycle + events + API. **P1 DONE — `pythia/v0.1.0`** (built on branch `pythia-p1-p3`, 2026-06-26; tag deferred to post-merge).
- [x] **Stage 2.1** — resolution + planner. — [x] **2.2** — executor. — [x] **2.3** — query/evaluate/budget. — [x] **2.4** — synth + e2e. **P2 DONE — `v0.2.0`** (tag post-merge).
- [x] **Stage 3.1** — LLM eval + suspicion. — [x] **3.2** — reviser + prioritisation. — [x] **3.3** — replay + RCA e2e. **P3 DONE — `v0.3.0`** (tag post-merge). _Phases 1–3 ship a self-contained RCA investigator (SQL-only). Built against mocked unit/component tests (planning-conventions §4); kantheon persistence is Exposed+Flyway (not jOOQ as the task lists assumed)._
- [ ] **Stage 4.1** — Pythia integration of Charon + DataFrameNode. — [ ] **4.2** — Metis integration + forecast/sim. **P4 DONE — `v0.4.0`.** _(Charon + Metis themselves: separate arc checklists in [`../charon/plan.md`](../charon/plan.md), [`../metis/plan.md`](../metis/plan.md).)_
- [x] **Stage 5.1** — master-of-Golems. — [x] **5.2** — Iris. — [x] **5.3** — eval + ship. **P5 DONE — `pythia/v1.0.0`** (built on branch `feat/pythia-p4-p5`, 2026-06-27; tag deferred to post-merge). _The constellation is complete: Pythia is discoverable + routable, drives Iris, and gates CI on the scripted eval corpus._

---

*Plan owner: Bora. Pythia arc planned 2026-06-12. Per-stage task lists at `docs/implementation/v1/pythia/tasks-p<n>-s<n.m>-*.md` after review.*
