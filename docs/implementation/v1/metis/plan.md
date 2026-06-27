# Metis — Phased Implementation Plan (kantheon arc)

> **Scope.** From "Metis exists only as a capability list in the Pythia design" to "`services/metis` (Python) + `tools/metis-mcp` live in local K3s: fit/diagnose/project/simulate over Arrow series, session workspace, `model.*` registered with benchmarked cost hints — gating Pythia Phase 4 Stage 4.2." Three phases, seven stages, ~42 tasks.
>
> **Companions.** [`../../../architecture/metis/architecture.md`](../../../architecture/metis/architecture.md), [`../../../architecture/metis/contracts.md`](../../../architecture/metis/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md); design origin `Pythia-v1-Design.md` §6.2 + §4.3.
>
> **Testing.** Per the testing policy ([`planning-conventions.md`](../../planning-conventions.md) §4): plans develop against mocked unit/component tests only (pytest with mocked clients + in-memory fakes; golden numerical suites stay); the integration-test suite (live Charon↔Metis round-trip) is separate.
>
> **Arc position.** Independent of Iris/Golem; pairs naturally with the Charon arc (Charon Phase 3 activates `WorkerKind.METIS` against Metis Phase 1's workspace surface — schedule Metis P1 before or alongside Charon P3). **Hard consumer deadline: Pythia Phase 4 Stage 4.2 requires `metis/v0.3.0`.** Python-module conventions established here are a first for kantheon (uv, `just *-py`, CI lane) — Stage 1.1 settles them deliberately.

## 1. Phase summary

| Phase | Goal — what deploys at the end | Stages | Estimated effort |
|---|---|---|---|
| **Phase 1 — proto + skeleton + workspace** | `services/metis` pod in local K3s; session workspace (DFs + model registry, TTL, caps); `Import/Export/Drop/GetStatus` green — Charon can stage in/out. Kantheon Python conventions settled. | 1.1 / 1.2 | ~1 week |
| **Phase 2 — models + golden suite** | `Fit` (LINEAR / ARIMA auto-order / PROPHET), `Diagnose`, `Project` (CI bands), `SimulateScenario`; numerical golden suite pinned. | 2.1 / 2.2 / 2.3 | ~1.5–2 weeks |
| **Phase 3 — wiring + wrapper + ship** | Charon `WorkerKind.METIS` round-trip; `tools/metis-mcp` + `model.*` registration; bench-derived cost hints; observability. Pythia-ready. | 3.1 / 3.2 | ~1 week |

Critical path: P1 → P2 → P3. P2 stages 2.1/2.2 parallelisable in principle (different model families); conservative default sequential.

## 2. Pre-flight — before Phase 1 starts

| Item | Status (2026-06-12) |
|---|---|
| Python-Metis-in-kantheon + package `org.tatrman.metis.v1` | locked 2026-06-12 |
| `just proto` Python output usable for a non-`kantheon.*` root | verify in Stage 1.1 (same check Charon T2 does for Kotlin) |
| uv available in dev + CI (mirrors ai-platform conventions; `just init` already provisions uv) | true; CI lane is Stage 1.1 work |
| prophet/cmdstan builds in the container base image | Stage 1.1 T6 spike — fail-fast on the heaviest dependency |
| Charon arc Phase 1 (workspace conventions + fingerprint fixtures exist) | sequencing note — shared fixtures land wherever first; either order works |

## 3. Phase 1 — proto + skeleton + workspace

### Stage 1.1 — proto + module skeleton + Python conventions

**Goal.** Proto compiles to Python (+ Kotlin for the wrapper); `services/metis` skeleton runs probes; kantheon's Python build lane exists.

**Tasks (7).**
1. Write `org/tatrman/metis/v1/metis.proto` per contracts §1 (full file); `just proto`; Python + Kotlin bindings green (verify second non-kantheon proto root).
2. Module skeleton `services/metis`: pyproject (uv; python 3.13; grpcio, pyarrow, polars, fastapi, statsmodels, prophet, scikit-learn pinned), `main.py` bootstrap (gRPC + probes), telemetry per worker conventions.
3. Justfile recipes `build-py / test-py / lint-py metis` (uv run pytest / ruff / mypy strict); CI lane; module README documents the kantheon-Python conventions (first instance — Midas or future Python modules follow).
4. Tests first: request-validation specs over in-process gRPC (bad params pairing, unknown session, inline cap) → `INVALID_ARGUMENT`/`RESOURCE_EXHAUSTED`; implement validation layer to green.
5. `arrow_io.py`: IPC chunked read/write + schema fingerprint — cross-check fixtures shared with Charon/worker (CI-pinned).
6. **Dockerfile spike (gate):** multi-stage uv build with prophet/cmdstan in a cached base layer; image builds + `import prophet` passes in-container; build time + size recorded.
7. k8s `base/` + `overlays/local/`; lint clean.

**DONE.** Skeleton pod starts; probes green; image spike verdict recorded.

### Stage 1.2 — workspace + staging surface + deploy

**Tasks (6).**
1. Tests first: `test_workspace.py` — port the polars-worker workspace test patterns: keying `(session_id, name)`, DF + model single namespace (`ALREADY_EXISTS` on collision), idle-TTL sweeper, caps → `RESOURCE_EXHAUSTED`.
2. `workspace.py` implementation (DFs + fitted-model entries; bytes accounting; sweeper task).
3. `ImportDataFrame` (client-streaming; header-first chunking per contracts §1.3; fingerprint verify) + `ExportDataFrame` (server-streaming) + `DropWorkspaceEntry` + `GetStatus`.
4. Component tests: import → export round-trip byte-identical schema; concurrent-session isolation.
5. `just deploy` (docker build path — Jib N/A); pod Ready in local K3s; readiness gates (workspace init + prophet importable).
6. Tag.

**DONE.** Tag `metis/v0.1.0`. **Phase 1 DONE — Charon's `WorkerKind.METIS` endpoint has a live target.**

## 4. Phase 2 — models + golden suite

### Stage 2.1 — LINEAR + ARIMA fit + diagnostics

**Tasks (7).**
1. Golden fixtures first: reference notebooks (`tests/golden/notebooks/`) for OLS + SARIMAX on reference series (airline-class + Czech-calendar monthly); pinned values exported with documented tolerances (contracts §3).
2. `models/linear.py` — OLS fit; coefficients/R² in `FitResult`; goldens green.
3. `models/arima.py` fit — explicit-order SARIMAX path; AIC/log-likelihood; goldens green.
4. Auto-order search — bounded stepwise over (p,d,q)(P,D,Q,s) with IC selection; `chosen_order` returned; timeout + `max_order` enforced; pathological-series tests (constant, short, NaN-holed → typed errors).
5. `models/diagnostics.py` — Ljung-Box, ADF, residual normality → `DiagnoseResult` typed checks; goldens for pass/fail cases.
6. Per-kind fit timeouts + process-pool isolation (a hung optimizer can't block the server); `DEADLINE_EXCEEDED` mapping.
7. Seed plumbing (`metis.seed` → all stochastic paths; seed echoed in `messages`).

### Stage 2.2 — Project + PROPHET

**Tasks (6).**
1. Golden fixtures: SARIMAX forecast points + CI bands at 0.90/0.95; prophet forecasts with fixed seed.
2. `Project` for ARIMA — horizon parsing (ISO date / `+N`), CI bands, output frame schema (`ds, yhat, yhat_lower, yhat_upper, kind`) written to workspace; `FAILED_PRECONDITION` on pre-series horizon.
3. `models/prophet_model.py` — fit (yearly/weekly/daily flags) + project; cmdstan invocation hardening (tmpdir, timeout).
4. Prophet goldens green within tolerance.
5. Component: design §4.3 worked example — `marginByMonth`-shaped fixture → fit(seasonality=12) → diagnose PASS → project(2026-12-31, 0.90) → exported frame asserted against goldens.
6. Cross-kind validation (project on a LINEAR model → `INVALID_ARGUMENT`).

### Stage 2.3 — SimulateScenario + hardening

**Tasks (5).**
1. Tests first: scenario delta math — multiplicative/additive deltas over forecast frames, band scaling semantics documented + asserted.
2. `models/scenario.py` + `SimulateScenario` RPC (`FAILED_PRECONDITION` on non-forecast input frame).
3. Memory caps per fit (input-rows bound; `RESOURCE_EXHAUSTED`); concurrent-fit stress sanity (process pool sizing).
4. Full estimation-lifecycle component suite (import→fit→diagnose→project→simulate→export) for all three kinds.
5. Tag.

**DONE.** Tag `metis/v0.2.0`. **Phase 2 DONE — models live, goldens pinned.**

## 5. Phase 3 — wiring + wrapper + ship

**Pre-flight.** Charon arc at Phase 3 (its `WorkerEndpoint` gains the METIS variant against §1.3 — cross-arc task tracked in both plans).

### Stage 3.1 — Charon interop + observability

**Tasks (5).**
1. Charon `WorkerKind.METIS` activation: Charon's `WorkerEndpoint` targets `Import/ExportDataFrame` (Charon-arc task; fixture exchange from here). Component test (mocked Charon endpoint + in-memory stores): Seaweed → Metis session → fit → project → Charon materialise to Seaweed asserts the round-trip wiring. (The live Seaweed↔Charon↔Metis round-trip is deferred to the separate integration-test suite; the interop capability is unchanged.)
2. Metrics per architecture §7 + Grafana panel set.
3. Trace nesting verified from a fixture caller (Pythia-shaped spans).
4. Failure-injection pass: killed fit mid-flight, workspace cap, pod restart with active session (`NOT_FOUND` → re-fit story validated from the caller side).
5. Single-replica + resource-profile tuning recorded (CPU requests/limits from bench data).

### Stage 3.2 — metis-mcp + registration + bench + ship

**Tasks (6).**
1. Tests first: `McpToolsSpec` (Kotlin) — JSON↔proto fidelity for the seven tools; error + `messages` pass-through.
2. `tools/metis-mcp` module (ktor-configurator MCP base; zero logic — review-enforced; charon-mcp twin).
3. `model.*:v1` ToolCapability manifests + heartbeat; visible in capabilities-mcp.
4. Bench: fit/project duration percentiles per kind on 1e2/1e3/1e4-row series → `cost_hints` + README.
5. Docs: kantheon-architecture cross-refs, Pythia plan pre-flight check, module READMEs.
6. Tags.

**DONE.** Tags `metis/v0.3.0`, `metis-mcp/v0.1.0`. **Phase 3 DONE — Pythia Phase 4 Stage 4.2 unblocked.**

## 6. Cross-cutting

| Item | Where |
|---|---|
| Charon `WorkerKind.METIS` endpoint | Charon plan Stage 3.1 consumes Metis contracts §1.3; fixture exchange both ways |
| Fingerprint shared fixtures (worker ↔ Charon ↔ Metis) | CI-pinned from Stage 1.1 |
| Kantheon Python conventions (first instance) | Stage 1.1 T3; documented for future Python modules |
| `model.decompose.variance` | v1.5 — [`../pythia/v1.5-backlog.md`](../pythia/v1.5-backlog.md); lands here when triggered |
| Remaining ai-platform coordination (gateway tier routing + worker read-out) | `aip-v1-gateway-worker-plan.md` — Metis no longer in it |

## 7. Out of scope (v1.x triggers)

- Prophet regressors (trigger: first investigation needing them).
- Model serialisation to Seaweed / reproduce-with-frozen-model (trigger: reproduce() fidelity demand).
- `model.decompose.variance` (v1.5 backlog, trigger documented there).
- ML regressors / classification (`model.fit.logistic`, trees) — resolved Q7 says let real investigations drive.
- Async fit jobs + NATS completion (trigger: first habitual >5 min fit).
- Multi-replica session routing (trigger: Metis throughput bottleneck).
- GPU profiles.

## 8. Open questions / Bora-owned content

| Item | Blocking | Note |
|---|---|---|
| Reference series for goldens (one real anonymised Czech monthly series alongside the classic fixtures) | Stage 2.1 | Bora picks; classic fixtures suffice to start |
| Arc scheduling slot | arc start | Claude lean: Phase 1 alongside Charon Phase 2–3 so Charon 3.1 has a live target; Phases 2–3 before Pythia Phase 4 |
| CPU resource profile defaults | Stage 3.1 | from bench data; config-only |

## 9. Phase progression checklist

- [ ] **Stage 1.1** — proto + skeleton + Python conventions + image spike.
- [ ] **Stage 1.2** — workspace + staging + deploy. **Phase 1 DONE — `metis/v0.1.0`.**
- [ ] **Stage 2.1** — LINEAR + ARIMA + diagnostics.
- [ ] **Stage 2.2** — Project + PROPHET.
- [ ] **Stage 2.3** — SimulateScenario + hardening. **Phase 2 DONE — `metis/v0.2.0`.**
- [ ] **Stage 3.1** — Charon interop + observability.
- [ ] **Stage 3.2** — metis-mcp + bench + ship. **Phase 3 DONE — `metis/v0.3.0` + `metis-mcp/v0.1.0`.**

---

*Plan owner: Bora. Metis arc planned 2026-06-12. Per-stage task lists at `docs/implementation/v1/metis/tasks-p<n>-s<n.m>-*.md` after review.*
