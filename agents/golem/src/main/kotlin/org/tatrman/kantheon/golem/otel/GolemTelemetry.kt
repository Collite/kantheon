package org.tatrman.kantheon.golem.otel

import com.typesafe.config.Config
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import shared.otel.OtelEndpointConfig
import shared.otel.createOpenTelemetrySdk

// PT Phase 0 · Stage 0.1 T5 — Golem's telemetry wiring.
//
// Golem is the **agent hop** of "one trace per turn: BFF → agent → tools". Before
// this it created an OTel SDK and dropped it on the floor: no server instrumentation
// (so the BFF's `traceparent` was discarded and the turn's trace ended at the BFF)
// and no client instrumentation on the outbound tool calls (so query-mcp appeared
// as an unrelated root).

/**
 * The one SDK per process, or null when telemetry is off. Creating it also installs
 * the Logback `OpenTelemetryAppender` declared in `logback.xml`, which is what puts
 * Golem's log lines into Loki carrying `trace_id`.
 */
fun createGolemOtel(config: Config): OpenTelemetry? =
    if (config.getBoolean("telemetry.enabled")) {
        createOpenTelemetrySdk(
            OtelEndpointConfig(
                serviceName = "golem",
                protocol = System.getenv("GOLEM_OTEL_PROTOCOL") ?: "grpc",
            ),
        ).withW3CPropagators()
    } else {
        null
    }

/** Install before `installKtorServerBase` so the SERVER span wraps the pipeline. */
fun Application.installGolemServerTelemetry(otel: OpenTelemetry?) {
    otel?.let { sdk -> install(KtorServerTelemetry) { setOpenTelemetry(sdk) } }
}

/**
 * **Load-bearing.** The shared `otel-config` lib builds its SDK without
 * `setPropagators(...)`, so `getPropagators()` returns `NoopTextMapPropagator` and
 * anything that asks the SDK to inject trace context injects **nothing** — spans get
 * created, no `traceparent` reaches the wire, and every hop starts a fresh trace.
 * Invisible in review; visible only as fragmented traces in Grafana.
 *
 * Duplicated from iris-bff's `otel/ServerTelemetry.kt` (no shared kantheon OTel lib
 * exists, and these two modules share no library). The proper home is `otel-config`
 * itself — which would also fix the tatrman-server half of the turn path, since
 * those services use the same lib. See PT P0·S0.1 T5 notes.
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

/** Span attribute + header name for the node/step a gateway call belongs to (PT P0·S0.1 T6). */
const val CALL_PURPOSE_ATTRIBUTE: String = "call.purpose"
const val CALL_PURPOSE_HEADER: String = "X-Call-Purpose"

/**
 * PT Phase 0 · Stage 0.1 T6 — label an LLM-gateway call with the node/step that made
 * it. The value later populates `LlmCall.purpose` in the `/protocol` document
 * (contracts §1), read back off the turn's trace.
 *
 * Wraps [block] in a CLIENT span carrying `call.purpose`, so the label survives even
 * though the gateway call itself goes through `org.tatrman:ttr-llm-client`, whose
 * `complete(...)` exposes no per-call header or metadata hook. The matching
 * `X-Call-Purpose` request header therefore cannot be set from this side — it needs a
 * `ttr-llm-client` change (tatrman-server, PT-24). Recorded in the arc's T6 notes.
 *
 * [purpose] must never be empty: a call with no step context uses a stable literal
 * (e.g. `"golem.summarize"`), because an unattributable call is worse than a
 * coarsely-attributed one — it shows up in the protocol as `unattributable_count`.
 */
suspend fun <T> OpenTelemetry?.withCallPurpose(
    purpose: String,
    block: suspend () -> T,
): T {
    require(purpose.isNotBlank()) { "call.purpose must not be blank — use a stable literal instead" }
    val sdk = this ?: return block()
    val span =
        sdk
            .getTracer("golem")
            .spanBuilder(purpose)
            .setSpanKind(io.opentelemetry.api.trace.SpanKind.CLIENT)
            .setAttribute(CALL_PURPOSE_ATTRIBUTE, purpose)
            .startSpan()
    return try {
        span.makeCurrent().use { block() }
    } catch (e: Throwable) {
        span.recordException(e)
        span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR)
        throw e
    } finally {
        span.end()
    }
}
