package org.tatrman.kantheon.golem.otel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking

/**
 * PT Phase 0 · Stage 0.1 T6 — gateway calls carry the node/step that made them, so the
 * protocol's LLM-calls section can attribute each call instead of counting it as
 * `unattributable_count` (contracts §1 `LlmCall.purpose`).
 *
 * Scope note: only the **span attribute** half is testable — and implementable — here.
 * The `X-Call-Purpose` request header cannot be set from Golem: the gateway call goes
 * through `org.tatrman:llm-client`, whose `complete(...)` takes only
 * (prompt, model, systemPrompt, temperature, maxTokens) and exposes no header or
 * metadata hook. That half needs a tatrman-server change (PT-24).
 */
private fun testSdk(exporter: InMemorySpanExporter): OpenTelemetrySdk =
    OpenTelemetrySdk
        .builder()
        .setTracerProvider(
            SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build(),
        ).build()

class CallPurposeSpec :
    StringSpec({

        "gateway call span has the purpose attribute" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()

                val result = testSdk(exporter).withCallPurpose("golem.format.chip-topup") { "answer" }

                result shouldBe "answer"
                exporter.finishedSpanItems shouldHaveSize 1
                val span = exporter.finishedSpanItems.single()
                span.kind shouldBe SpanKind.CLIENT
                span.name shouldBe "golem.format.chip-topup"
                span.attributes.get(AttributeKey.stringKey(CALL_PURPOSE_ATTRIBUTE)) shouldBe
                    "golem.format.chip-topup"
            }
        }

        "a failing call records the exception and ends the span" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()

                shouldThrow<IllegalStateException> {
                    testSdk(exporter).withCallPurpose<Unit>("golem.summarize") { error("gateway down") }
                }

                val span = exporter.finishedSpanItems.single()
                span.status.statusCode shouldBe StatusCode.ERROR
                span.events shouldHaveSize 1
            }
        }

        "blank purpose is rejected — an unlabelled call is worse than a coarse one" {
            runBlocking {
                shouldThrow<IllegalArgumentException> {
                    testSdk(InMemorySpanExporter.create()).withCallPurpose("  ") { Unit }
                }
            }
        }

        "telemetry disabled runs the call and emits nothing" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()

                val result = (null as io.opentelemetry.api.OpenTelemetry?).withCallPurpose("golem.x") { 42 }

                result shouldBe 42
                exporter.finishedSpanItems shouldHaveSize 0
            }
        }
    })
