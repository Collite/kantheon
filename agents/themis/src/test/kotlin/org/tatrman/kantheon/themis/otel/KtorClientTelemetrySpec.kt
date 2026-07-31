package org.tatrman.kantheon.themis.otel

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.instrumentation.ktor.v3_0.KtorClientTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * PT Phase 0 · Stage 0.1 T5 — Themis's outbound hops must be
 * `KtorClientTelemetry`-instrumented so the agent leg joins the turn's single trace.
 *
 * Themis's clients are built inline inside their owning classes, so rather than
 * reaching into them these cases pin the **install shape** those sites use, plus the
 * propagator wiring that makes it actually emit a header. Note the SDK here is built
 * the way the shared `otel-config` lib builds it (no `setPropagators`) — with the raw
 * SDK a span is created but NO `traceparent` is written, which is exactly the trap
 * `withW3CPropagators()` exists to close.
 */
private val TRACEPARENT = Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$")

/** Exactly how `shared.otel.createOpenTelemetrySdk` builds it: providers, no propagators. */
private fun productionShapedSdk(exporter: InMemorySpanExporter): OpenTelemetrySdk =
    OpenTelemetrySdk
        .builder()
        .setTracerProvider(
            SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build(),
        ).build()

/** The install shape every instrumented outbound site in this module uses. */
private fun instrumentedClient(
    otel: OpenTelemetry?,
    captured: MutableList<HttpRequestData>,
): HttpClient =
    HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                captured += request
                respond("{}", HttpStatusCode.OK)
            }
        }
        install(
            createClientPlugin("Obo") {
                onRequest { request, _ -> request.header("Authorization", "Bearer t") }
            },
        )
        otel?.let { sdk -> install(KtorClientTelemetry) { setOpenTelemetry(sdk) } }
    }

class KtorClientTelemetrySpec :
    StringSpec({

        "outbound call emits CLIENT span and traceparent" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()
                val captured = mutableListOf<HttpRequestData>()
                val client =
                    instrumentedClient(
                        productionShapedSdk(exporter).let {
                            with(org.tatrman.kantheon.themis.ResolverOtel) { it.withW3CPropagators() }
                        },
                        captured,
                    )

                client.get("http://upstream/ping").bodyAsText()

                eventually(5.seconds) {
                    exporter.finishedSpanItems shouldHaveSize 1
                    exporter.finishedSpanItems
                        .single()
                        .kind shouldBe SpanKind.CLIENT
                }
                captured.single().headers["traceparent"]!! shouldMatch TRACEPARENT
                client.close()
            }
        }

        "null OpenTelemetry emits nothing" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()
                val captured = mutableListOf<HttpRequestData>()
                val client = instrumentedClient(null, captured)

                client.get("http://upstream/ping").bodyAsText()

                exporter.finishedSpanItems shouldHaveSize 0
                captured.single().headers["traceparent"].shouldBeNull()
                client.close()
            }
        }

        "the raw shared-lib SDK writes no traceparent — why withW3CPropagators exists" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()
                val captured = mutableListOf<HttpRequestData>()
                val client = instrumentedClient(productionShapedSdk(exporter), captured)

                client.get("http://upstream/ping").bodyAsText()

                // The span still appears — which is what makes this so easy to miss.
                eventually(5.seconds) { exporter.finishedSpanItems shouldHaveSize 1 }
                captured.single().headers["traceparent"].shouldBeNull()
                client.close()
            }
        }

        "withW3CPropagators keeps the SDK's real providers" {
            val exporter = InMemorySpanExporter.create()
            val sdk = productionShapedSdk(exporter)
            val wrapped = sdk.let { with(org.tatrman.kantheon.themis.ResolverOtel) { it.withW3CPropagators() } }

            wrapped.tracerProvider shouldBe sdk.tracerProvider
            wrapped.meterProvider shouldBe sdk.meterProvider
            wrapped.propagators.textMapPropagator.fields() shouldBe
                W3CTraceContextPropagator.getInstance().fields()
            ContextPropagators
                .noop()
                .textMapPropagator
                .fields()
                .isEmpty() shouldBe true
        }
    })
