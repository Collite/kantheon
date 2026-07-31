package org.tatrman.kantheon.iris.otel

import com.typesafe.config.Config
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

/**
 * PT Phase 0 · Stage 0.1 T2 — the BFF's own server instrumentation.
 *
 * Until this landed, iris-bff created an OTel SDK and then never told Ktor about
 * it: no SERVER span, so an incoming `traceparent` was dropped and every outbound
 * hop started its own root. The BFF is the root span owner for turn-shaped traces
 * (`docs/architecture/iris/architecture.md` §10.2), which only works if the server
 * side is instrumented too.
 *
 * Install this **before** `installKtorServerBase` so the server span wraps the
 * rest of the pipeline (CallLogging included) rather than nesting inside it.
 */
fun Application.installIrisServerTelemetry(otel: OpenTelemetry?) {
    // Null when `telemetry.enabled=false` — then nothing is installed at all,
    // rather than a noop SDK being threaded through the pipeline.
    otel?.let { sdk -> install(KtorServerTelemetry) { setOpenTelemetry(sdk) } }
}

/**
 * The one SDK per process, or null when telemetry is off. Creating it also installs
 * the Logback `OpenTelemetryAppender` (the shared `otel-config` lib does that), which
 * is what puts this service's log lines into Loki carrying `trace_id`.
 */
fun createIrisOtel(config: Config): OpenTelemetry? =
    if (config.getBoolean("telemetry.enabled")) {
        createOpenTelemetrySdk(
            OtelEndpointConfig(
                serviceName = "iris-bff",
                protocol = System.getenv("IRIS_BFF_OTEL_PROTOCOL") ?: "grpc",
            ),
        ).withW3CPropagators()
    } else {
        null
    }

/**
 * **Load-bearing, and not optional.** The shared `otel-config` lib builds its SDK
 * with `OpenTelemetrySdk.builder()...build()` and never calls `setPropagators(...)`,
 * so `getPropagators()` returns `NoopTextMapPropagator`. Ktor's telemetry plugins
 * take their propagators from the `OpenTelemetry` instance handed to them — so with
 * the raw SDK they happily create spans and inject **nothing**: no `traceparent` on
 * the wire, every hop a fresh root trace.
 *
 * That failure is invisible in code review and in any test that supplies its own
 * propagator-configured SDK; it only shows up as fragmented traces in Grafana. This
 * wrapper returns the SDK's real tracer/meter/logger providers and substitutes W3C
 * trace-context propagation.
 *
 * The proper home for this is `otel-config` itself (which would also fix the
 * tatrman-server half of the turn path, since those services use the same lib) —
 * see `project/kantheon/features/protocol` PT P0·S0.1 T5 notes.
 */
fun OpenTelemetry.withW3CPropagators(): OpenTelemetry {
    val delegate = this
    val w3c = ContextPropagators.create(W3CTraceContextPropagator.getInstance())
    return object : OpenTelemetry {
        override fun getTracerProvider() = delegate.tracerProvider

        override fun getMeterProvider() = delegate.meterProvider

        override fun getLogsBridge() = delegate.logsBridge

        override fun getPropagators() = w3c
    }
}
