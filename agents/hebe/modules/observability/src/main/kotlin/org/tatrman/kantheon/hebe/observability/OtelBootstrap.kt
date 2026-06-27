@file:Suppress("TooGenericExceptionCaught", "MagicNumber")

package org.tatrman.kantheon.hebe.observability

import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ObserverEvent
import org.tatrman.kantheon.hebe.api.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import org.slf4j.LoggerFactory

object OtelBootstrap {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Builds the observer driven by the `otel.enabled` axis (P2 Stage 2.4 T3).
     * When [enabled] is `false` (local/personal default) this is a **true no-op**
     * — the log-only observer; the OTel SDK is never touched, so there is no
     * exporter and no collector dependency. When `true`, the SDK initialises with
     * `serviceName = hebe-<instance_id>`; a missing/failed SDK degrades to
     * log-only rather than crashing boot.
     */
    fun createObserver(
        logbackObserver: LogbackObserver,
        enabled: Boolean = !System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT").isNullOrBlank(),
        serviceName: String = "hebe",
    ): Observer {
        if (!enabled) {
            log.debug("otel.enabled=false — log-only observer (no SDK)")
            return logbackObserver
        }
        val sdk =
            try {
                AutoConfiguredOpenTelemetrySdk
                    .builder()
                    .addPropertiesSupplier { mapOf("otel.service.name" to serviceName) }
                    .build()
                    .openTelemetrySdk
            } catch (e: Exception) {
                log.error("Failed to initialise OTel SDK, falling back to log-only: {}", e.message)
                return logbackObserver
            }
        log.info("OTel exporter active, service={}", serviceName)
        return observerFromTracer(logbackObserver, sdk.getTracer("org.tatrman.kantheon.hebe", "1.0.0"))
    }

    /**
     * Wraps a [Tracer] into an [Observer] (forwarding to the log-only delegate).
     * Exposed for the in-memory-exporter test (assert span emission when enabled).
     */
    fun observerFromTracer(
        logbackObserver: LogbackObserver,
        tracer: Tracer,
    ): Observer = OtelObserver(logbackObserver, tracer)
}

private class OtelObserver(
    private val delegate: LogbackObserver,
    private val tracer: Tracer,
) : Observer {
    override fun event(e: ObserverEvent) = delegate.event(e)

    override fun span(
        name: String,
        attrs: Map<String, Any>,
    ): Span {
        val otelSpan =
            tracer
                .spanBuilder(name)
                .apply {
                    attrs.forEach { (k, v) -> setAttribute(k, v.toString()) }
                }.startSpan()
        return object : Span {
            private val delegateSpan = delegate.span(name, attrs)

            override fun setAttribute(
                key: String,
                value: Any,
            ) {
                otelSpan.setAttribute(key, value.toString())
                delegateSpan.setAttribute(key, value)
            }

            override fun recordError(t: Throwable) {
                otelSpan.setStatus(StatusCode.ERROR, t.message ?: "error")
                otelSpan.recordException(t)
                delegateSpan.recordError(t)
            }

            override fun close() {
                otelSpan.end()
                delegateSpan.close()
            }
        }
    }
}
