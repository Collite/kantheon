package org.tatrman.kantheon.golem.resolution

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.test.runTest
import org.tatrman.resolver.v1.GapKind

/**
 * RV-P5.1 T4 — `golem.callResolutionCore` is a LIVE span (kantheon has a real SDK), so it is
 * asserted rather than assumed.
 */
private fun testSdk(exporter: InMemorySpanExporter): OpenTelemetrySdk =
    OpenTelemetrySdk
        .builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        .build()

private suspend fun traced(
    otel: OpenTelemetry?,
    case: String,
    client: ResolutionCoreClient = RecordedResolutionCore.client(case),
) = otel.tracedResolutionCore {
    callResolutionCoreStep(
        question = "q",
        conversationId = "conv",
        locale = "cs",
        referenceDatetime = "2026-08-06T00:00:00Z",
        tenant = "hartland",
        callerSubject = "user-1",
        client = client,
    )
}

private fun longAttr(name: String) = AttributeKey.longKey(name)

private fun stringAttr(name: String) = AttributeKey.stringKey(name)

class ResolutionTelemetrySpec :
    StringSpec({

        "the span carries the layer tuple and says the node spent no LLM budget" {
            runTest {
                val exporter = InMemorySpanExporter.create()

                traced(testSdk(exporter), "h1-cs")

                val span = exporter.finishedSpanItems.single()
                span.name shouldBe RV_SPAN_CALL_CORE
                span.kind shouldBe SpanKind.CLIENT
                span.attributes.get(longAttr(RV_LLM_INVOCATIONS)) shouldBe 0L
                span.attributes.get(stringAttr(RV_LEXICON_ARTIFACT_HASH)) shouldBe "sha256:h1-lexicon"
                // ABSENT until RV-P6, so absent on the span too — not "".
                span.attributes.get(stringAttr(RV_OVERLAY_VERSION)).shouldBeNull()
                span.attributes.get(longAttr(RV_MENTIONS_TOTAL)) shouldBe 5L
                span.attributes.get(longAttr(RV_VALUES_TOTAL)) shouldBe 2L
                span.attributes.get(longAttr(RV_GAPS_TOTAL)) shouldBe 0L
            }
        }

        "gap counts are broken out by kind — and only kinds that occurred are emitted" {
            runTest {
                val exporter = InMemorySpanExporter.create()

                traced(testSdk(exporter), "h2-cs")

                val span = exporter.finishedSpanItems.single()
                span.attributes.get(longAttr(RV_GAPS_TOTAL)) shouldBe 2L
                span.attributes.get(longAttr(gapAttributeName(GapKind.GAP_KIND_G1_UNBOUND))) shouldBe 1L
                span.attributes.get(longAttr(gapAttributeName(GapKind.GAP_KIND_G3_UNATTRIBUTED))) shouldBe 1L
                // A wall of zeroes costs cardinality and hides the one non-zero.
                span.attributes.get(longAttr(gapAttributeName(GapKind.GAP_KIND_G2_AMBIGUOUS))).shouldBeNull()
                span.attributes.get(longAttr(gapAttributeName(GapKind.GAP_KIND_G4_METHOD_MISS))).shouldBeNull()
            }
        }

        "a degraded turn is an ERROR span carrying the code — it must not look clean" {
            runTest {
                val exporter = InMemorySpanExporter.create()

                val result = traced(testSdk(exporter), "h1-cs", RecordedResolutionCore.failing(code = "UNAVAILABLE"))

                result.degrade shouldBe CoreDegrade("UNAVAILABLE", "resolver unreachable")
                val span = exporter.finishedSpanItems.single()
                span.status.statusCode shouldBe StatusCode.ERROR
                span.attributes.get(stringAttr(RV_DEGRADE_CODE)) shouldBe "UNAVAILABLE"
                // Still zero: a call that failed spent no budget either, and the attribute
                // being present on every span is what makes it queryable as a floor.
                span.attributes.get(longAttr(RV_LLM_INVOCATIONS)) shouldBe 0L
            }
        }

        "telemetry off runs the call and emits nothing" {
            runTest {
                val exporter = InMemorySpanExporter.create()

                val result = traced(null, "h1-cs")

                result.lattice shouldBe RecordedResolutionCore.lattice("h1-cs")
                exporter.finishedSpanItems shouldHaveSize 0
            }
        }
    })
