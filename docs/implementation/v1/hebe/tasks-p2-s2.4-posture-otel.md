# Stage 2.4 — Tool posture + OTel

> **Phase 2, Stage 2.4.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §"Stage 2.4", [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §8 (observability) + §6 layer 3 (`tools.posture`), [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §5.2 (`[tools]` + `[otel]` blocks), [`../../../../EXAMPLES.md`](../../../../EXAMPLES.md) §8 (OTel SDK init).

## Goal

`tools.posture = restricted` (the `k8s` default) blocks the dangerous tool families (shell/kubectl/git/filesystem) at the dispatcher and emits **refusal receipts**; `enable`/`disable` lists provide per-instance opt-ins. Observability moves from Hebe's bespoke `observability` module internals to a thin adapter over ai-platform's `otel-config` (`createOpenTelemetrySdk()`); `otel.enabled = false` is a true no-op (local). W3C trace-context propagates on outbound HTTP. Spans + metrics cover routine fire, job run, tool call, channel delivery.

## Pre-flight

- [x] **Stage 2.1 DONE** (axes); **2.2/2.3** provide the gateway/Keycloak HTTP clients that get trace-context propagation in T4.
- [x] **Branch**: `feat/hebe-p2-s2.4-posture-otel`.
- [x] Local Grafana/Alloy/Loki/Tempo stack reachable for T6 (`just debug-tunnel`); unit work asserts span emission via an in-memory `SpanExporter`.

## Tasks

- [x] **T1 — Tests first: posture matrix.**

  Create `agents/hebe/modules/tools/dispatch/src/test/kotlin/.../PostureSpec.kt`:

  - `restricted` blocks the dangerous families: shell, kubectl, git, filesystem → dispatch refuses with a typed `PostureDenied` outcome.
  - `restricted` **allows** the safe families: memory, http, web-search, scheduling, kantheon (per contracts §5.2 comment).
  - `enable = ["git"]` opt-in flips git to allowed under `restricted`; `disable = [...]` removes a family under `full`.
  - Every refusal writes a **refusal receipt** through the receipts log (the dispatcher mutation-funnel still applies — refusals are receipted state changes).
  - `full` posture (local default) behaves as today.

  Acceptance: specs written and failing. Commit `[hebe-p2-s2.4] failing posture specs`.

- [x] **T2 — Posture resolution in `tools/dispatch`.**

  Implement posture enforcement in the `ToolDispatcher` from `tools.posture` + `enable`/`disable` (resolved `Axes`, not profile name). Tag each built-in tool with a family; the dispatcher consults the resolved allow-set before invoking. Refusals route through the receipt path (do not bypass the mutation-funnel — the detekt rule guards this).

  Acceptance: T1 specs pass; the mutation-funnel detekt rule stays green.

- [x] **T3 — Replace `observability` internals with an `otel-config` adapter.**

  Rework `:agents:hebe:modules:observability` so it is a **thin adapter** over ai-platform's `otel-config` (`createOpenTelemetrySdk(OtelEndpointConfig(serviceName = "hebe-<instance_id>", ...))`, EXAMPLES.md §8) rather than its own OTLP plumbing. Driven by the `otel.enabled` axis: `false` ⇒ a true no-op (no exporter, no collector dependency — `local`/`personal` default). Keep Hebe's domain span names stable.

  Acceptance: `otel.enabled = false` starts with zero OTel dependency active; `true` initialises the SDK; unit test with an in-memory exporter asserts a span is emitted when enabled and none when disabled.

- [x] **T4 — W3C trace-context propagation on outbound HTTP.**

  Inject W3C `traceparent`/`tracestate` headers on outbound calls from the gateway client (2.2) and — wired but exercised in Phase 4 — the future iris-bff client. This is what later lets a single trace span cron-tick → iris-bff → agent → delivery (architecture §8). Unit-test header injection with the in-memory exporter + a stub server capturing headers.

  Acceptance: outbound gateway requests carry `traceparent`; spec green.

- [x] **T5 — Span + metric set.**

  Emit spans for: routine fire, job run, tool call, channel delivery; and the matching metrics (counters/histograms — routine fires, job durations, tool-call counts by family, delivery successes/failures). Names follow Hebe's existing convention (keep stable for dashboards). Unit-test span/metric emission with the in-memory exporter/reader.

  Acceptance: the four span types + metrics emit under `otel.enabled = true`; spec green.

- [x] **T6 — Verify in the local Grafana stack + doctor OTel check.**

  Run a `k8s`-profile Hebe (still SQLite — PG is Phase 3) locally with gateway + Keycloak + OTel against `deployment/local`; confirm traces in Tempo and logs in Loki via `just debug-tunnel`. Register the `doctor` OTel check (OTLP endpoint reachable when `otel.enabled = true`). Per planning-conventions §4 the Grafana confirmation is a deploy-time smoke, not an automated CI gate.

  Acceptance: traces/logs visible locally; `doctor` OTel check registered. PR `[hebe-p2-s2.4] tool posture + otel`.

## DONE — Stage 2.4

- [x] All six tasks checked.
- [x] Restricted posture blocks shell/kubectl/git/fs with refusal receipts; opt-in lists work.
- [x] `observability` is an `otel-config` adapter; `otel.enabled=false` is a true no-op.
- [x] W3C trace-context propagates outbound; the four span types + metrics emit.
- [x] `k8s`-profile Hebe (SQLite) runs locally with gateway+Keycloak+OTel against local-infra.
- [x] PR merged.

## Library / pattern references

- **EXAMPLES.md §8** — `createOpenTelemetrySdk(...)` init; call before serving.
- **architecture.md §8** — span chain + W3C propagation; `otel` axis semantics.
- ai-platform `otel-config` lib (in-repo `shared/libs/kotlin` equivalent) — the adapter target.
- **detekt mutation-funnel rule** (`:agents:hebe:modules:detekt-rules`) — refusals must still flow through the dispatcher/receipt path.

## Out of scope for Stage 2.4

- PG-backed receipts (Phase 3 Stage 3.2) — refusal receipts here use the file NDJSON log.
- The full constellation trace through iris-bff (Phase 4) — propagation is wired, the iris-bff hop lands later.
