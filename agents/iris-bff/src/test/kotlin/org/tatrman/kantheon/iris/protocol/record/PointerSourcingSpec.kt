package org.tatrman.kantheon.iris.protocol.record

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.trace.Span
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import java.time.Instant

/**
 * [PointerSourcing] — the ambient half of `RecordPointers`. Uses a real SDK
 * tracer with an in-memory exporter (no collector, no network); the assertions
 * are about what the record captures, not about export.
 */
class PointerSourcingSpec :
    StringSpec({

        fun sdk(): OpenTelemetrySdk =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(
                    SdkTracerProvider
                        .builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
                        .build(),
                ).build()

        "valid current span -> pointers.trace_id = spanContext.traceId" {
            val otel = sdk()
            val span = otel.getTracer("test").spanBuilder("turn").startSpan()
            try {
                span.makeCurrent().use {
                    val expected = span.spanContext.traceId
                    expected.length shouldBe 32
                    PointerSourcing.traceIdOrEmpty() shouldBe expected
                }
            } finally {
                span.end()
                otel.close()
            }
        }

        "invalid/absent span context -> trace_id empty string" {
            // No span made current: Span.current() is the invalid propagated root.
            Span.current().spanContext.isValid shouldBe false
            PointerSourcing.traceIdOrEmpty() shouldBe ""
        }

        "log window = started_at minus 2s .. completed_at plus 2s, ISO-8601 with offset" {
            val started = Instant.parse("2026-07-30T09:00:05Z")
            val completed = Instant.parse("2026-07-30T09:00:11Z")

            val (from, to) = PointerSourcing.logWindow(started, completed)

            from shouldBe "2026-07-30T09:00:03Z"
            to shouldBe "2026-07-30T09:00:13Z"

            // Parseable back to the widened instants — the assembler hands these
            // straight to Loki/Tempo, so they must be real ISO-8601 with an offset.
            Instant.parse(from) shouldBe started.minusSeconds(2)
            Instant.parse(to) shouldBe completed.plusSeconds(2)
        }

        "correlation_id taken from request headers when present, else empty" {
            // The recorder is what applies this rule — correlationId arrives on the
            // turn context from the route's header read, and null must degrade to ""
            // (proto3 strings cannot be null, and "absent" must not read as "null").
            val store = InMemoryProtocolRecordStore()
            val recorder = ProtocolRecorder(store)
            val ctx = turnContext(correlationId = "corr-42")

            recorder.record(ctx)
            store.readByTurnId(ctx.turnId)!!.pointers.correlationId shouldBe "corr-42"

            val without = turnContext(correlationId = null)
            recorder.record(without)
            store.readByTurnId(without.turnId)!!.pointers.correlationId shouldBe ""
        }
    })
