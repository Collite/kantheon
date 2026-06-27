# Pythia — autonomous analytical investigator

Pythia (Kotlin + Koog + a custom DAG executor) runs RCA / forecast / simulation /
cross-domain investigations: it resolves the question (Themis), plans a typed
`PlanDag`, executes it over a data plane (theseus-mcp queries, Charon-staged
DataFrames, Metis models), evaluates hypotheses, and synthesises an envelope/v1
conclusion — all under full HITL with a 12-status lifecycle.

Authoritative docs: [`docs/architecture/pythia/architecture.md`](../../docs/architecture/pythia/architecture.md),
[`contracts.md`](../../docs/architecture/pythia/contracts.md),
[`docs/implementation/v1/pythia/plan.md`](../../docs/implementation/v1/pythia/plan.md).

## Shape

- **Lifecycle** (`orchestrator/`) — one re-entrant coroutine per investigation over
  the 12 statuses (5 `AWAITING_*` pauses, each resumed by one control endpoint);
  diff-based checkpointer; idempotent resume (status-conditional UPDATE).
- **Plan** (`plan/`) — `PlanComposer` (Koog STRONG) → `PlanDagCodec` → `PlanValidator`
  with a bounded feedback-retry loop; master-of-Golems Shem reads (`ShemReader`).
- **Execute** (`executor/`) — the custom DAG executor (frontier + `Semaphore` caps +
  tiered failure handling); `QueryNode` (theseus-mcp), `DataFrameNode` (Polars
  worker), `ModelNode` (Metis), `ReasoningNode`/`RenderNode`.
- **Data plane** (`dataplane/`) — gRPC-direct `CharonClient` / `WorkerClient` /
  `MetisClient`; handle↔Location mapping; the materialisation policy engine
  (cross-engine staging, evidence-persist, TTL-approach); PD-5 resume prober.
- **Evaluate / revise** (`evaluate/`, `revise/`, `suspicion/`) — rules-first +
  CHEAP-fallback hypothesis evaluation; prioritisation; PRUNE/PIVOT/DECOMPOSE/HALT
  reviser; stop-condition spine.
- **Synthesize** (`synth/`) — block streaming, PD-9 provenance, honest stop reasons.
- **Events** (`events/`, `api/`) — PG event log (authoritative) + NATS + the SSE
  bridge (`GET /v1/investigations/{id}/events?from_seq`); REST control surface.

## Build / test / eval

```bash
just build pythia            # compile
just test-kt pythia          # the mocked unit/component suite (planning-conventions §4)
just eval-pythia             # the scripted investigation eval gate (Stage 5.3)
```

The unit/component gate runs entirely against mocked clients — in-process gRPC
fixture-servers (Charon/Metis), worker/query fakes, scripted-LLM fixtures. Live
Charon/Metis/Steropes/theseus, real NATS/PG, and live-LLM runs are the **integration
suite** (deferred per planning-conventions §4).

### Eval gate (`just eval-pythia`)

`agents/pythia/eval/corpus/{procedural,rca,forecast,simulation}.jsonl` (contracts §8)
drives the deterministic `EvalGateSpec`, which gates CI on the architecture §9 metrics:
**plan-validity rate**, **verdict accuracy**, **budget adherence**, **replay
determinism**. Question selection is Bora-owned (~15/bucket); the scripted fixtures
make it deterministic. A small **live-LLM bucket runs nightly only** (the `pythia`
nightly context — drift reporting, non-blocking).

## Observability

The architecture §8 metric set emits under `pythia_*` names (`obs/PythiaMetrics`),
scraped at `GET /metrics`; the dashboard is `k8s/grafana-dashboard.json`. Span-per-step
tracing carries the investigation id as trace baggage.

## Config

`pythia.{themis,llm-gateway,capabilities,theseus,charon,worker,metis}.*` wire the
collaborators; a blank host degrades that capability (SQL-only when the data plane is
off; the scripted stubs when Themis/LLM are off). Identity discipline: every downstream
call forwards the user's OBO bearer — never service identity (kantheon-security §2.1).
