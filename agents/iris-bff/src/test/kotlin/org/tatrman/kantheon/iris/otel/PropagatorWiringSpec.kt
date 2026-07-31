package org.tatrman.kantheon.iris.otel

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.tatrman.kantheon.iris.routing.themisHttpClient
import kotlin.time.Duration.Companion.seconds

/**
 * PT Phase 0 · Stage 0.1 — regression cover for the subtlest failure this arc found.
 *
 * The shared `otel-config` lib builds its SDK without `setPropagators(...)`, so the
 * SDK's propagators are **noop**. Installing `KtorClientTelemetry` with that SDK
 * produces spans but injects no `traceparent` — code that looks fully instrumented,
 * traces that fragment at every hop, and nothing red anywhere. `withW3CPropagators()`
 * is the fix; these cases pin both halves so a future refactor cannot quietly drop it.
 *
 * The first case deliberately reconstructs the *production* SDK shape rather than a
 * convenient test SDK — a test SDK that sets its own propagators would hide the bug.
 */
private val TRACEPARENT = Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$")

/** Exactly how `shared.otel.createOpenTelemetrySdk` builds it: providers, no propagators. */
private fun productionShapedSdk(exporter: InMemorySpanExporter): OpenTelemetrySdk =
    OpenTelemetrySdk
        .builder()
        .setTracerProvider(
            SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build(),
        ).build()

private suspend fun traceparentOf(otel: io.opentelemetry.api.OpenTelemetry): String? {
    val captured = mutableListOf<HttpRequestData>()
    val client =
        themisHttpClient(MockEngine, timeoutMs = 5_000, otel = otel) {
            addHandler { request ->
                captured += request
                respond("{}", HttpStatusCode.OK)
            }
        }
    client.get("http://upstream/ping").bodyAsText()
    client.close()
    return captured.single().headers["traceparent"]
}

class PropagatorWiringSpec :
    StringSpec({

        "the raw shared-lib SDK injects NO traceparent — this is why the wrapper exists" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()
                val raw = productionShapedSdk(exporter)

                raw.propagators.textMapPropagator
                    .fields()
                    .isEmpty() shouldBe true
                traceparentOf(raw).shouldBeNull()

                // The span is still created — which is exactly what makes this so easy to miss.
                eventually(5.seconds) { exporter.finishedSpanItems.size shouldBe 1 }
            }
        }

        "withW3CPropagators makes the same SDK inject a valid traceparent" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()
                val wrapped = productionShapedSdk(exporter).withW3CPropagators()

                val traceparent = traceparentOf(wrapped)

                traceparent.shouldNotBeNull()
                traceparent shouldMatch TRACEPARENT
            }
        }

        "withW3CPropagators keeps the SDK's real providers (spans still export)" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()
                val sdk = productionShapedSdk(exporter)
                val wrapped = sdk.withW3CPropagators()

                wrapped.tracerProvider shouldBe sdk.tracerProvider
                wrapped.meterProvider shouldBe sdk.meterProvider
                wrapped.logsBridge shouldBe sdk.logsBridge
                wrapped.propagators.textMapPropagator.fields() shouldBe
                    W3CTraceContextPropagator.getInstance().fields()

                traceparentOf(wrapped)
                eventually(5.seconds) { exporter.finishedSpanItems.size shouldBe 1 }
            }
        }
    })
