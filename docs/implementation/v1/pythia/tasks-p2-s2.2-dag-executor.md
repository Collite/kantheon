# Stage 2.2 — DAG executor

> **Phase 2, Stage 2.2.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4 Stage 2.2, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §5 ("DAG executor", "Handle table"), [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §1 (`Handle` oneof), [`../../../design/pythia/Pythia-v1-Design.md`](../../../design/pythia/Pythia-v1-Design.md) §3.2/§5.2, [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

A custom-coroutine DAG executor: frontier computation, batches under three `Semaphore` caps with priority launch + promotion, tiered retry, drain + park/resume integrated with the Stage 1.2 `Checkpointer`, `batch_*`/`step_*` events, and `HandleTable` v0 (`LiveQueryRef` + `PgResultSnapshot`, with `HandleRef` param binding). **No real query execution yet** — node executors are mock/stub here (QueryNode lands in Stage 2.3). **End state:** frontier + drain property tests green; executor drives a fixture plan of stub nodes to completion under caps.

## Pre-flight

- [ ] Stage 2.1 DONE — `PlanDag` contract frozen.
- [ ] Branch `feat/pythia-p2-s2.2-dag-executor`.
- [ ] Confirm Kotlin structured-concurrency primitives: `kotlinx.coroutines` `Semaphore`, `supervisorScope`, `CompletableDeferred`. (No external workflow lib — custom executor is the locked decision, architecture §2.)

## Tasks (TDD-shaped: T1 + T4 are property tests written first)

- [ ] **T1 — Frontier computation property tests (tests first).**

  Create `src/test/kotlin/.../executor/FrontierSpec.kt`. Property tests (Kotest property) over generated DAG shapes: frontier = nodes whose `DataDep`s are all satisfied; assert no node enters the frontier before its deps complete; assert a linear chain, a fan-out, a fan-in, and a diamond all schedule in topologically valid order; assert a cyclic input is rejected at validation (cross-ref Stage 2.1 `PlanValidator` — executor assumes acyclic, asserts defensively).

  Acceptance: spec compiles + fails (no executor).

- [ ] **T2 — Executor core (frontier → batches, caps, priority).**

  Implement `executor/DagExecutor.kt`: compute frontier → launch a batch; three `Semaphore` caps — per-investigation (default 5), per-provider, global (architecture §5). Priority order from hypothesis scoring (placeholder scoring here; real prioritisation is Phase 3 Stage 3.2 — accept an injected `priorityOf(node)` comparator). Promotion: as a slot frees, launch the next-highest-priority ready node. Node execution dispatches to an injected `NodeExecutor` interface (stubbed in this stage; QueryNode impl in 2.3).

  Acceptance: `FrontierSpec` green; an integration-style unit test (all collaborators mocked) runs a diamond plan to completion respecting a cap of 2.

- [ ] **T3 — Retry policy.**

  Implement tiered failure handling (architecture §5): **transient** → retry with jittered exponential backoff (cap attempts); **permanent** → mark the tested hypothesis INCONCLUSIVE + continue; **systemic** (e.g. provider down) → HALT the investigation. Classification via a `FailureKind` mapping on the node error (StepRecord `error{ recoverable }`, contracts §1). Inject the clock/jitter for deterministic tests.

  Test: a node that fails transiently-then-succeeds retries and succeeds; a permanent failure marks its hypothesis INCONCLUSIVE; a systemic failure HALTs.

  Acceptance: `RetryPolicySpec` green.

- [ ] **T4 — Drain semantics + park/resume (property tests first).**

  Create `executor/DrainSpec.kt`: on a drain signal (entering any AWAITING_*), assert **no new step launches** after the signal (property test over interleavings), in-flight steps are awaited, then `scheduler_drained` is emitted and the `Checkpointer` snapshot is taken. Resume reconstructs the frontier from the checkpoint and continues — assert no step is double-executed (idempotency via the Stage 1.2 `tryResume`). 

  Implement the drain + resume integration in `DagExecutor` to make the spec pass.

  Acceptance: `DrainSpec` green; no post-drain launches in any generated interleaving.

- [ ] **T5 — `batch_*` / `step_*` events.**

  Emit the execution event vocabulary (design §3.3 execution group, 6 events) via the Stage 1.3 `EventEmitter`: `batch_started`/`batch_completed`, `step_started`/`step_completed`/`step_failed`/`step_retried` (confirm exact names against design §3.3). Each `StepRecord` persisted via the Stage 1.2 `StepRepository` with `cost` + `error` fields.

  Test: a fixture plan produces the expected ordered event trace; assert step/batch pairing.

  Acceptance: execution-events spec green.

- [ ] **T6 — `HandleTable` v0.**

  Implement `handles/HandleTable.kt` + the v0 handle kinds: `LiveQueryRef` (a reference to a query result, not yet materialised) and `PgResultSnapshot` (small results inlined to Pythia's PG as Arrow IPC, capped by `pythia.handles.inline-max-bytes` — divergence 1). Implement **`HandleRef` param binding resolution**: a downstream node's `params_json` can reference an upstream handle's projection (e.g. an id list from a prior result); resolve the binding at launch time. (Phase 4 adds `WorkerSessionDF`/`SeaweedArrowBlob`/`RedisArrowEntry`/`DbTableRef`.)

  Test: produce a `PgResultSnapshot` (assert the cap is enforced — oversize → flagged for materialise, a Phase-4 path, here just a Rule-6 flag), bind a downstream node's param from its projection, assert the resolved param value.

  Acceptance: `HandleTableSpec` green.

## DONE — Stage 2.2

- [ ] All tasks checked; `just test-kt pythia` green; frontier + drain property tests green.
- [ ] Executor drives a stub-node fixture plan to completion under caps, with drain/resume round-tripping through the checkpointer.
- [ ] Integration carry-overs recorded (executor under real concurrency + real PG checkpoint, large-result materialise path → Phase 4).
- [ ] CI green on `[pythia-p2-s2.2] DAG executor`.

## Library / pattern references

- **`kotlinx.coroutines`** — `Semaphore`, `supervisorScope`, structured concurrency. **architecture §5** — the executor contract (frontier/caps/priority/drain/affinity).
- **contracts §1** — `Handle` oneof (v0 kinds), `DataDep`, `StepRecord.cost`/`error`.
- **Kotest property testing** — for `FrontierSpec`/`DrainSpec` (generated DAG shapes + interleavings).

## Out of scope

- Real QueryNode execution + theseus-mcp — Stage 2.3.
- Sticky affinity for `WorkerSessionDF` parents — Phase 4 (no worker handles yet).
- Hypothesis-score-driven priority — Phase 3 Stage 3.2 (injected comparator placeholder here).
