package org.tatrman.kantheon.capabilities.client

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * PT Phase 0 · Stage 0.1 T1 — the capabilities read client is an outbound hop on
 * the BFF's turn path, so it carries `KtorClientTelemetry` like every other one.
 */
private val TRACEPARENT = Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$")

private fun testSdk(exporter: InMemorySpanExporter): OpenTelemetrySdk =
    OpenTelemetrySdk
        .builder()
        .setTracerProvider(
            SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build(),
        ).setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()

class CapabilitiesClientTelemetrySpec :
    StringSpec({

        "capabilities client emits a CLIENT span and traceparent header" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()
                val captured = mutableListOf<HttpRequestData>()
                val client =
                    capabilitiesHttpClient(MockEngine, otel = testSdk(exporter)) {
                        addHandler { request ->
                            captured += request
                            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    }

                client.get("http://capabilities/v1/capabilities").bodyAsText()

                // The CLIENT span ends asynchronously, after the body read returns.
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

        "capabilities client built with null OpenTelemetry emits no spans and no traceparent" {
            runBlocking {
                val exporter = InMemorySpanExporter.create()
                val captured = mutableListOf<HttpRequestData>()
                val client =
                    capabilitiesHttpClient(MockEngine, otel = null) {
                        addHandler { request ->
                            captured += request
                            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    }

                client.get("http://capabilities/v1/capabilities").bodyAsText()

                exporter.finishedSpanItems shouldHaveSize 0
                captured.single().headers["traceparent"].shouldBeNull()
                client.close()
            }
        }
    })
