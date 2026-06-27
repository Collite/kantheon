# The forked constellation — observability (Stage 4.1 T3)

> Status: landed with Fork Phase 4 Stage 4.1 (2026-06-17). Companion to
> [`architecture.md`](./architecture.md). Covers the **client-side** telemetry
> wiring that ships in-repo. Dashboards and alerts are **fabric-infra-owned**
> (inherited decision #8) — the wishlist below is filed for that team, not added
> as server-side manifests here.

## 1. Client config — every forked module emits OTLP

All forked Kotlin services/wrappers wire the in-repo `shared/libs/kotlin/otel-config`
(`createOpenTelemetrySdk()`), and the two Python modules wire `shared/libs/python/otel-config`.
One `telemetry.enabled` knob silences OTel everywhere (returns a no-op SDK).

| Module | Lane | Telemetry wiring | `service.name` |
|---|---|---|---|
| theseus | Kotlin/Ktor | otel-config (SDK + log bridge) **+ manual orchestration spans** | `theseus` |
| theseus-mcp | Kotlin/Ktor MCP | otel-config via `McpTelemetry` **+ manual root tool span** | `theseus-mcp` |
| argos, kyklop, ariadne, echo, proteus | Kotlin/Ktor | otel-config (SDK + log bridge); leaf spans via gRPC auto-instrumentation | `argos` / `kyklop` / `ariadne` / `echo` / `proteus` |
| brontes | Kotlin worker | otel-config (SDK + log bridge); leaf spans via gRPC auto-instrumentation | `brontes` |
| ariadne-mcp, echo-mcp, kadmos-mcp | Kotlin MCP | otel-config via `McpTelemetry` | `ariadne-mcp` / `echo-mcp` / `kadmos-mcp` |
| kadmos, steropes | Python | Python `otel-config` (`setup_opentelemetry`) | `kadmos` / `steropes` |
| **prometheus** | **Spring Boot** | **`opentelemetry-spring-boot-starter`** (OTLP exporter + auto-instrumentation), **not** the Ktor `otel-config` lib | `prometheus` |

**Why Prometheus differs (deliberate).** Prometheus forked as a Spring Boot app.
Its idiomatic OTel wiring is the OpenTelemetry Spring Boot starter, which it
already carries — it exports over OTLP (`OTEL_EXPORTER_OTLP_HOST`), tags
`OTEL_SERVICE_NAME=prometheus`, and **auto-instruments HTTP/gRPC/client spans**.
Forcing the Ktor-flavoured `otel-config` into a Spring app would double-initialise
the SDK. So "every module wires otel-config" holds for the Ktor/Python lanes; the
Spring lane wires the equivalent first-class integration. Net effect is identical:
OTLP out, persona `service.name`, spans + metrics + logs.

**Service-name convention (note vs the pre-write).** The 2026-06-12 pre-write
suggested `kantheon-<persona>` (`kantheon-theseus`, …). Every forked module
actually landed with the **bare persona name** (`theseus`, `argos`, …), uniformly,
in both code (`OtelEndpointConfig.serviceName`) and k8s (`OTEL_SERVICE_NAME`).
Bare names are unambiguous (the personas don't collide with ai-platform's
`query-runner`/`validator`/… even in a shared backend), so we keep the landed
convention rather than introduce a prefix. Documented here so the pre-write isn't
mistaken for a defect.

## 2. The `run_query` trace

Manual spans (Stage 4.1 T3) make the in-process orchestration legible; cross-pod
nesting is delivered by gRPC auto-instrumentation (the deploy-time mechanism the
line has always relied on).

```
mcp.tool.query                      (theseus-mcp — root, SpanKind.SERVER)
└── theseus.run                     (Theseus orchestration)
    ├── theseus.detect_schema       → Proteus
    ├── theseus.parse               → Proteus
    ├── theseus.translate           → Proteus   (ER→DB path)
    ├── theseus.validate            → Argos      (RLS / rules)
    └── theseus.dispatch            → Kyklop → a worker (Brontes / Steropes)
```

- **In-repo:** the `mcp.tool.<name>` root span (theseus-mcp `InstrumentedTool`)
  and the `theseus.run` + per-stage spans (Theseus, via tracing decorators around
  each downstream client). The shared helper is `shared.otel.withSpan` /
  `Flow<T>.tracedFlow` in `otel-config` (coroutine-safe: `withSpan` via
  `asContextElement`, `tracedFlow` via `flowOn` so the Flow context-preservation
  invariant holds).
- **Across pods:** theseus-mcp → Theseus → Proteus/Argos/Kyklop → Brontes/Steropes
  are gRPC hops; trace context propagates through gRPC headers via
  auto-instrumentation. The leaf services therefore need no manual spans for the
  trace to nest end-to-end on a live cluster.
- **Verified by:** `RunQueryTracingComponentSpec` (theseus-mcp) — one trace, root
  `mcp.tool.query` → `theseus.run` → the four stage spans, asserted via an
  in-memory span exporter with the in-process chain sharing one SDK. The live
  "traces land in the fabric-infra stack" e2e is deferred to the integration-test
  suite (planning-conventions §4).

## 3. Fabric-infra wishlist (panels + alerts to define on their side)

Per-service **RED** dashboards (Rate / Errors / Duration), driven by the metrics
each module already emits:

- **Per service** (theseus, argos, kyklop, brontes, steropes, ariadne, echo,
  proteus, prometheus, the four MCP wrappers): request rate, error rate, p50/p95/p99
  latency.
- **`run_query` SLO panel:** end-to-end `mcp.tool.query` latency (p50/p95/p99) and
  error rate, plus the per-stage breakdown from the `theseus.*` spans (where time
  goes: parse vs validate vs dispatch).
- **Worker throughput:** rows/s for Brontes and Steropes (see Stage 4.1 T4
  `cost_hints` baselines).
- **Alerts:** `run_query` p95 > SLO; per-service error-rate > threshold; OTLP
  export failures; worker dispatch failures (`no_worker_for_connection`).

These are **definitions for fabric-infra to own**; no server-side dashboard/alert
manifests live in kantheon.
