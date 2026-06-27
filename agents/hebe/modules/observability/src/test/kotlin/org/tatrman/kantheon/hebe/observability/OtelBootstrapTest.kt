package org.tatrman.kantheon.hebe.observability

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `otel.enabled` axis gate (P2 Stage 2.4 T3): enabled ⇒ spans are emitted;
 * disabled ⇒ a true no-op (the log-only observer, no SDK). Asserted with an
 * in-memory SpanExporter.
 */
class OtelBootstrapTest {
    @Test
    fun `enabled tracer emits spans with attributes`() {
        val exporter = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        val observer = OtelBootstrap.observerFromTracer(LogbackObserver(), provider.get("test"))

        observer.span("routine.fire", mapOf("routine.id" to "daily")).use {
            it.setAttribute("job.id", "j-1")
        }

        val spans = exporter.finishedSpanItems
        assertEquals(1, spans.size)
        assertEquals("routine.fire", spans[0].name)
        assertTrue(spans[0].attributes.asMap().keys.any { it.key == "job.id" })
        provider.close()
    }

    @Test
    fun `disabled is a true no-op (returns the log-only observer, no SDK)`() {
        val logback = LogbackObserver()
        val observer = OtelBootstrap.createObserver(logback, enabled = false)
        // The exact log-only observer is returned — the SDK is never built.
        assertSame(logback, observer)
    }
}
