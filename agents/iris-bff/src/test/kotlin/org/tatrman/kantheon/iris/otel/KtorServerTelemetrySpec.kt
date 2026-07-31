package org.tatrman.kantheon.iris.otel

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlin.time.Duration.Companion.seconds

/**
 * PT Phase 0 · Stage 0.1 T2 — iris-bff's server instrumentation. Drives the same
 * [installIrisServerTelemetry] the production `module()` calls, so deleting the
 * install turns these red. No collector, no cluster.
 */
private const val INCOMING_TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
private const val INCOMING_SPAN_ID = "b7ad6b7169203331"
private const val INCOMING_TRACEPARENT = "00-$INCOMING_TRACE_ID-$INCOMING_SPAN_ID-01"

private fun testSdk(exporter: InMemorySpanExporter): OpenTelemetrySdk =
    OpenTelemetrySdk
        .builder()
        .setTracerProvider(
            SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build(),
        ).setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()

class KtorServerTelemetrySpec :
    StringSpec({

        "server request produces a SERVER span" {
            val exporter = InMemorySpanExporter.create()
            testApplication {
                application {
                    installIrisServerTelemetry(testSdk(exporter))
                    routing { get("/ping") { call.respondText("pong") } }
                }

                client.get("/ping")

                eventually(5.seconds) {
                    exporter.finishedSpanItems shouldHaveSize 1
                    exporter.finishedSpanItems
                        .single()
                        .kind shouldBe SpanKind.SERVER
                }
            }
        }

        "server span continues incoming traceparent" {
            val exporter = InMemorySpanExporter.create()
            testApplication {
                application {
                    installIrisServerTelemetry(testSdk(exporter))
                    routing { get("/ping") { call.respondText("pong") } }
                }

                client.get("/ping") { header("traceparent", INCOMING_TRACEPARENT) }

                eventually(5.seconds) {
                    val span = exporter.finishedSpanItems.single()
                    span.traceId shouldBe INCOMING_TRACE_ID
                    span.parentSpanId shouldBe INCOMING_SPAN_ID
                }
            }
        }

        "no incoming traceparent starts a new root trace" {
            val exporter = InMemorySpanExporter.create()
            testApplication {
                application {
                    installIrisServerTelemetry(testSdk(exporter))
                    routing { get("/ping") { call.respondText("pong") } }
                }

                client.get("/ping")

                eventually(5.seconds) {
                    val span = exporter.finishedSpanItems.single()
                    span.parentSpanContext.isValid shouldBe false
                    span.traceId shouldNotBe INCOMING_TRACE_ID
                }
            }
        }

        "telemetry disabled installs nothing and emits no spans" {
            val exporter = InMemorySpanExporter.create()
            testApplication {
                application {
                    installIrisServerTelemetry(null)
                    routing { get("/ping") { call.respondText("pong") } }
                }

                client.get("/ping")

                exporter.finishedSpanItems shouldHaveSize 0
            }
        }

        "packaged application.conf enables CallLogging (PT P0 — probe-filtered by ProbePaths)" {
            // `installKtorServerBase` installs CallLogging only when the config
            // carries a `callLogging` block; iris-bff had none before PT Phase 0,
            // so request logs never reached Loki with a trace_id.
            ConfigFactory.load().hasPath("callLogging") shouldBe true
        }
    })
