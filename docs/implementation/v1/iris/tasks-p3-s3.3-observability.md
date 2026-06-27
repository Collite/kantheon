# Iris Phase 3 Stage 3.3 — observability + hardening + docs

> **Goal (plan §5 Stage 3.3).** Make the routing path observable and ship it: BFF metrics per architecture §10.1, a Grafana `iris-bff` dashboard, a single SPA→BFF→Themis→agent trace, a load sanity check on concurrent streams, doc fold-ins, and the Phase-3 tags. **Phase 3 DONE — routing UX shipped.**
>
> **Companions.** [`tasks-p3-overview.md`](./tasks-p3-overview.md) · [`plan.md`](./plan.md) §5 · [`../../../architecture/iris/architecture.md`](../../../architecture/iris/architecture.md) §10 (metrics §10.1, tracing §10.2) · [`../../../design/iris/iris-design.md`](../../../design/iris/iris-design.md) (the fold-in target) · `ai-platform/EXAMPLES.md` §8 (OTel patterns).

## Grounding

- BFF uses `cz.dfpartner:otel-config` (architecture §2) for OTel; metrics are emitted via the OTel/Micrometer registry that `ktor-configurator` wires. The metric *names + labels* are specified in architecture §10.1 — this stage emits them at the call sites added in 3.1/3.2.
- No `iris-bff` Grafana dashboard exists yet; ai-platform's service dashboards are the JSON-model reference.
- `iris-design.md` carries a 2026-06-12 reality note (agents-fe heritage) flagged for full revision *here* (plan §6 cross-cutting + §5 Stage 3.3 T5).

## Pre-flight

- [ ] **Stages 3.1 + 3.2 closed** (the call sites the metrics/traces instrument exist).
- [ ] Branch `feat/iris-p3-s3.3-observability`.
- [ ] Local Grafana/Prometheus (or the olymp observability stack) reachable for dashboard import — dashboard JSON is committed regardless.

## Tasks

- [ ] **T1 — Metrics emission (architecture §10.1).** Emit, at the 3.1/3.2 call sites: `iris_turns_total{agent_id, outcome}`, `iris_turn_duration_ms` (histogram, dispatch-inclusive), `iris_dispatch_total{client, result}`, `iris_routing_pick_shown_total` / `iris_routing_pick_clicked_total`, `iris_stream_open_gauge`, `iris_session_active_gauge`, `iris_typed_action_total{action}`, `iris_excerpt_build_duration_ms`, `iris_investigate_chip_shown_total` / `_clicked_total`, `iris_audit_write_total` / `iris_audit_chain_verify_failures_total`. **Tests first:** a metrics unit/component spec asserting counter/gauge increments on a dispatched turn, a shown+clicked routing pick, and a typed action (use the registry's test exporter; assert metric name + labels). *(The `feedback_total`, `iris_inbox_*`, `iris_lifecycle_nats_connected_gauge`, `iris_artifact_refresh_total` metrics are Phase 4 — out of scope here.)*
- [ ] **T2 — Grafana dashboard `iris-bff`.** Commit a dashboard JSON model at `agents/iris-bff/observability/iris-bff-dashboard.json`: rows for turn latency (p50/p95/p99 from the histogram), dispatch breakdown by client+result, routing pick-rate (`clicked/shown`), active stream gauge, session gauge, typed-action mix, audit write/verify-failure. Document the panels + the manual import steps in the BFF README. *(No automated test — this is a committed JSON artifact; validity is confirmed by importing it once into the local Grafana per the README steps.)*
- [ ] **T3 — Single-trace audit (architecture §10.2).** Verify the SPA `traceparent` (OTel web) propagates SPA → BFF → Themis → agent → `/v2` adapter as one trace; `X-Correlation-Id` carries through for `/v2` parity. The BFF turn span is the root for turn-shaped traces. **Tests first:** a propagation unit/component spec — incoming `traceparent` is honoured and the outbound Themis + agent calls carry the same trace id; correlation id propagated to the `/v2` client.
- [ ] **T4 — Load sanity (component, bounded).** A component test driving ~20 concurrent `/v1/chat/stream` sessions against Wiremock-Themis + FakeGolemV2: assert no event loss/reordering and that the mux's bounded buffers hold (no unbounded growth, heartbeats still emitted). This is a mocked-collaborator concurrency test (planning-conventions §4) — **not** an in-cluster load test (that's the integration suite).
- [ ] **T5 — Doc fold-ins.** Update `docs/design/iris/iris-design.md` (fold in: agents-fe heritage, the transitional `/v2` adapter, the `current_display` (BFF) vs `current_view` (agent) pin from architecture §6.1, the routing-UX surfaces from 3.1/3.2). Refresh `agents/iris-bff/README.md` + `frontends/iris/README.md` (routing path, typed-action surface, metrics). Note any contract deltas discovered during 3.1/3.2 back into `contracts.md` (field-level changes require the contract doc to change first, per its footer).
- [ ] **T6 — Tags + plan tick.** Confirm green (`just test-kt iris-bff` + ktlint; FE vitest/tsc/lint). Tag `iris-bff/v0.3.0` + `iris/v0.2.0` (coordinate `gradle/libs.versions.toml`). Tick plan §9 Stage 3.3 and mark **Phase 3 DONE**.

## DONE

Metrics emitted + asserted; Grafana dashboard committed; single-trace propagation verified; 20-stream concurrency sanity green; `iris-design.md` + READMEs folded in; tags `iris-bff/v0.3.0`, `iris/v0.2.0`. **Phase 3 DONE — routing UX shipped.** In-cluster load + live end-to-end trace verification → integration suite.

## Out of scope

- Phase-4 metrics (feedback, inbox, lifecycle-nats gauge, artifact refresh) — emitted in their Phase-4 stages.
- Real-cluster load/perf testing — integration suite.
