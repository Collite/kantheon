package org.tatrman.kantheon.iris.otel

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import org.tatrman.kantheon.iris.routing.themisHttpClient
import kotlin.time.Duration.Companion.seconds

/**
 * PT Phase 0 · Stage 0.1 T4 — the actual deliverable of Phase 0, in miniature:
 * **one turn = one trace**. A request arriving at the BFF opens a SERVER span, and
 * the outbound hop it makes must continue *that* trace rather than starting its own.
 *
 * This is the case that was structurally impossible before T1/T2: with no client
 * instrumentation nothing was injected, and with no server instrumentation there
 * was no context to inherit. Comment out either install and this spec goes red.
 *
 * Assertions read the exported span tree, never logs. No collector, no cluster.
 */
private const val INCOMING_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
private const val INCOMING_SPAN_ID = "00f067aa0ba902b7"
private const val INCOMING_TRACEPARENT = "00-$INCOMING_TRACE_ID-$INCOMING_SPAN_ID-01"

private fun testSdk(exporter: InMemorySpanExporter): OpenTelemetrySdk =
    OpenTelemetrySdk
        .builder()
        .setTracerProvider(
            SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build(),
        ).setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()

class TracePropagationSpec :
    StringSpec({

        "outbound client call inside a server span carries the server's traceId in traceparent" {
            val exporter = InMemorySpanExporter.create()
            val sdk = testSdk(exporter)
            val outbound = mutableListOf<HttpRequestData>()
            testApplication {
                application {
                    installIrisServerTelemetry(sdk)
                    val upstream =
                        themisHttpClient(MockEngine, timeoutMs = 10_000, otel = sdk) {
                            addHandler { request ->
                                outbound += request
                                respond("{}", HttpStatusCode.OK)
                            }
                        }
                    routing {
                        get("/turn") {
                            // The hop a turn makes: BFF route → Themis.
                            upstream.get("http://themis/v1/resolve").bodyAsText()
                            call.respondText("ok")
                        }
                    }
                }

                client.get("/turn")

                eventually(5.seconds) {
                    val server = exporter.finishedSpanItems.single { it.kind == SpanKind.SERVER }
                    val client = exporter.finishedSpanItems.single { it.kind == SpanKind.CLIENT }

                    // One trace across both hops — the whole point of Phase 0.
                    client.traceId shouldBe server.traceId
                    client.parentSpanId shouldBe server.spanId

                    // And the wire carries it, so the *next* service can continue it too.
                    val traceparent = outbound.single().headers["traceparent"]!!
                    traceparent.split("-")[1] shouldBe server.traceId
                }
            }
        }

        "server continues context from incoming traceparent" {
            val exporter = InMemorySpanExporter.create()
            testApplication {
                application {
                    installIrisServerTelemetry(testSdk(exporter))
                    routing { get("/turn") { call.respondText("ok") } }
                }

                client.get("/turn") { header("traceparent", INCOMING_TRACEPARENT) }

                eventually(5.seconds) {
                    val server = exporter.finishedSpanItems.single { it.kind == SpanKind.SERVER }
                    server.traceId shouldBe INCOMING_TRACE_ID
                    server.parentSpanId shouldBe INCOMING_SPAN_ID
                }
            }
        }

        "no incoming traceparent starts a new root trace" {
            val exporter = InMemorySpanExporter.create()
            testApplication {
                application {
                    installIrisServerTelemetry(testSdk(exporter))
                    routing { get("/turn") { call.respondText("ok") } }
                }

                client.get("/turn")

                eventually(5.seconds) {
                    val server = exporter.finishedSpanItems.single { it.kind == SpanKind.SERVER }
                    server.parentSpanContext.isRemote shouldBe false
                    server.parentSpanContext.isValid shouldBe false
                    server.traceId shouldNotBe INCOMING_TRACE_ID
                }
            }
        }
    })
