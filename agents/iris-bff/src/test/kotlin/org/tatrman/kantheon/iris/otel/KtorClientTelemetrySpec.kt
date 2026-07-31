package org.tatrman.kantheon.iris.otel

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.tatrman.kantheon.iris.dispatch.golem.golemV1HttpClient
import org.tatrman.kantheon.iris.dispatch.golemv2.golemV2HttpClient
import org.tatrman.kantheon.iris.inbox.pythiaHttpClient
import org.tatrman.kantheon.iris.routing.themisHttpClient
import kotlin.time.Duration.Companion.seconds

/**
 * PT Phase 0 · Stage 0.1 T1/T4 — every outbound iris-bff HttpClient must be
 * `KtorClientTelemetry`-instrumented, so a turn's hops share one trace (GI-7).
 *
 * These drive the **production client factories** rather than a hand-built client,
 * so deleting an install from a factory turns a case red. `MockEngine` stands in
 * for the engine; nothing here touches a network or a collector.
 *
 * Two things this spec has to get right, both learned the hard way:
 *
 * 1. **The CLIENT span ends asynchronously**, after `bodyAsText()` has already
 *    returned — asserting on the exporter straight after the call passes or fails
 *    by luck. Every positive assertion goes through [eventually].
 * 2. **`runBlocking`, not `runTest`.** `runTest`'s virtual clock skips the delays
 *    [eventually] relies on, so its whole budget would burn in zero real time.
 */
private val TRACEPARENT = Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$")

private fun testSdk(exporter: InMemorySpanExporter): OpenTelemetrySdk =
    OpenTelemetrySdk
        .builder()
        .setTracerProvider(
            SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build(),
        ).setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()

private suspend fun call(client: HttpClient): String = client.get("http://upstream/ping").bodyAsText()

/** Asserts exactly one exported CLIENT span, tolerating the async span end. */
private suspend fun shouldHaveOneClientSpan(exporter: InMemorySpanExporter) =
    eventually(5.seconds) {
        exporter.finishedSpanItems shouldHaveSize 1
        exporter.finishedSpanItems
            .single()
            .kind shouldBe SpanKind.CLIENT
    }

class KtorClientTelemetrySpec :
    StringSpec({

        "themis client emits a CLIENT span and traceparent header" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()
                val captured = mutableListOf<HttpRequestData>()
                val client =
                    themisHttpClient(MockEngine, timeoutMs = 10_000, otel = testSdk(exporter)) {
                        addHandler { request ->
                            captured += request
                            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    }

                call(client)

                shouldHaveOneClientSpan(exporter)
                captured.single().headers["traceparent"]!! shouldMatch TRACEPARENT
                client.close()
            }
        }

        "golem v1 and v2 clients emit CLIENT spans" {
            runBlocking {
                val factories =
                    listOf<(OpenTelemetry?) -> HttpClient>(
                        { otel ->
                            golemV1HttpClient(MockEngine, socketIdleMs = 1_000, otel = otel) {
                                addHandler { respond("{}", HttpStatusCode.OK) }
                            }
                        },
                        { otel ->
                            golemV2HttpClient(MockEngine, socketIdleMs = 1_000, otel = otel) {
                                addHandler {
                                    respond(
                                        "{}",
                                        HttpStatusCode.OK,
                                        headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                }
                            }
                        },
                    )

                factories.forEach { factory ->
                    val exporter = InMemorySpanExporter.create()
                    val client = factory(testSdk(exporter))

                    call(client)

                    shouldHaveOneClientSpan(exporter)
                    client.close()
                }
            }
        }

        "pythia client emits a CLIENT span" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()
                val client =
                    pythiaHttpClient(MockEngine, timeoutMs = 15_000, otel = testSdk(exporter)) {
                        addHandler { respond("{}", HttpStatusCode.OK) }
                    }

                call(client)

                shouldHaveOneClientSpan(exporter)
                client.close()
            }
        }

        "clients built with null OpenTelemetry emit no spans and no traceparent" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()
                val captured = mutableListOf<HttpRequestData>()
                val clients =
                    listOf(
                        themisHttpClient(MockEngine, timeoutMs = 10_000, otel = null) {
                            addHandler { request ->
                                captured += request
                                respond("{}", HttpStatusCode.OK)
                            }
                        },
                        golemV1HttpClient(MockEngine, socketIdleMs = 1_000, otel = null) {
                            addHandler { request ->
                                captured += request
                                respond("{}", HttpStatusCode.OK)
                            }
                        },
                        golemV2HttpClient(MockEngine, socketIdleMs = 1_000, otel = null) {
                            addHandler { request ->
                                captured += request
                                respond("{}", HttpStatusCode.OK)
                            }
                        },
                        pythiaHttpClient(MockEngine, timeoutMs = 15_000, otel = null) {
                            addHandler { request ->
                                captured += request
                                respond("{}", HttpStatusCode.OK)
                            }
                        },
                    )

                clients.forEach { call(it) }

                // Telemetry disabled (`telemetry.enabled=false`) must be a true no-op.
                // Safe to assert immediately, unlike the positives: with no plugin
                // installed there is no code path that could produce a span later.
                exporter.finishedSpanItems shouldHaveSize 0
                captured shouldHaveSize 4
                captured.forEach { it.headers["traceparent"].shouldBeNull() }
                clients.forEach { it.close() }
            }
        }
    })
