package org.tatrman.kantheon.iris.protocol.sources

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.iris.protocol.FixtureLoader

/**
 * The three HTTP source clients, MockEngine-backed (house pattern — no live HTTP
 * in CI). Payloads come from the golden corpus, so a client and the fixtures it
 * feeds cannot drift apart.
 *
 * The property every case shares: **a failure is a Degraded outcome, never a
 * throw.** These clients sit behind a user-facing request, and an exception here
 * would turn one flaky log query into a failed protocol.
 */
class SourceClientsSpec :
    StringSpec({

        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

        fun client(handler: MockRequestHandler) = HttpClient(MockEngine(handler))

        // ---- gateway ----

        "gateway: GET /v1/prompt-logs with turn_ref + trace_id + limit, bearer attached" {
            runTest {
                var seen: io.ktor.http.Url? = null
                var auth: String? = null
                val http =
                    client { req ->
                        seen = req.url
                        auth = req.headers[HttpHeaders.Authorization]
                        respond("""{"items":[]}""", HttpStatusCode.OK, jsonHeaders)
                    }

                GatewayLogsClient("http://gw:8080/", http)
                    .fetch(turnRef = "turn-A", traceId = "trace-A", limit = 50, bearer = "jwt-1")

                seen!!.encodedPath shouldBe "/v1/prompt-logs"
                seen!!.parameters["turn_ref"] shouldBe "turn-A"
                seen!!.parameters["trace_id"] shouldBe "trace-A"
                seen!!.parameters["limit"] shouldBe "50"
                // The caller's OBO bearer, never a service identity (architecture §7).
                auth shouldBe "Bearer jwt-1"
            }
        }

        "gateway: 200 items parse into typed rows from the H1-full fixture" {
            runTest {
                val body = FixtureLoader.dir("H1-full").resolve("sources/gateway.json").readText()
                // The fixture is the normalised source shape; re-wrap its items as the
                // endpoint's page shape so the client parses exactly what the wire sends.
                val page = """{"items":${body.substringAfter("\"items\":").substringBeforeLast("}")}}"""
                val http = client { respond(page, HttpStatusCode.OK, jsonHeaders) }

                val out = GatewayLogsClient("http://gw", http).fetch("turn-A", "trace-A", 50, "jwt")

                out as SourceOutcome.Ok
                out.payload.status shouldBe SourceStatus.OK
                out.payload.items
                    .first()
                    .id shouldBe "gw-100"
                out.payload.items
                    .first()
                    .servedProvider shouldBe "azure"
            }
        }

        "gateway: 5xx -> Degraded, never throws" {
            runTest {
                val http = client { respondError(HttpStatusCode.InternalServerError) }
                val out = GatewayLogsClient("http://gw", http).fetch("turn-A", "", 50, "jwt")

                (out as SourceOutcome.Degraded).reason shouldContain "llm-gateway"
                out.reason shouldContain "500"
            }
        }

        "gateway: no base url -> SkippedByConfig; no correlation key -> Degraded" {
            runTest {
                val http = client { respond("{}", HttpStatusCode.OK, jsonHeaders) }
                GatewayLogsClient("", http).fetch("t", "x", 1, "j") shouldBe SourceOutcome.SkippedByConfig

                val out = GatewayLogsClient("http://gw", http).fetch("", "", 50, "jwt")
                (out as SourceOutcome.Degraded).reason shouldContain "no turn_ref or trace_id"
            }
        }

        // ---- loki ----

        "loki: query_range built by trace id with nanosecond window bounds" {
            runTest {
                var seen: io.ktor.http.Url? = null
                val http =
                    client { req ->
                        seen = req.url
                        respond("""{"data":{"result":[]}}""", HttpStatusCode.OK, jsonHeaders)
                    }

                LokiClient("http://loki:3100", http)
                    .fetch(
                        "0af7651916cd43dd8448eb211c80319c",
                        "2026-07-30T09:00:00Z",
                        "2026-07-30T09:00:05Z",
                        200,
                        "jwt",
                    )

                seen!!.encodedPath shouldBe "/loki/api/v1/query_range"
                seen!!.parameters["query"] shouldBe """{trace_id="0af7651916cd43dd8448eb211c80319c"}"""
                // Nanoseconds, not millis or seconds — Loki's unit for start/end.
                seen!!.parameters["start"] shouldBe "1785402000000000000"
                seen!!.parameters["end"] shouldBe "1785402005000000000"
                seen!!.parameters["limit"] shouldBe "200"
            }
        }

        "loki: ISO window converts to nanoseconds exactly" {
            LokiClient("http://loki", HttpClient(MockEngine { respond("") }))
                .nanos("2026-07-30T09:00:00Z") shouldBe "1785402000000000000"
        }

        "loki: streams parse into ServiceLogGroup lines, over-cap counted" {
            runTest {
                val body =
                    """
                    {"data":{"result":[
                      {"stream":{"service_name":"golem-finance","trace_id":"t1"},
                       "values":[["1785402002000000000","INFO plan composed"],
                                 ["1785402003000000000","WARN param defaulted"],
                                 ["1785402004000000000","ERROR boom"]]}
                    ]}}
                    """.trimIndent()
                val http = client { respond(body, HttpStatusCode.OK, jsonHeaders) }

                val out =
                    LokiClient(
                        "http://loki",
                        http,
                    ).fetch("t1", "2026-07-30T09:00:00Z", "2026-07-30T09:00:05Z", 2, "jwt")

                out as SourceOutcome.Ok
                val g = out.payload.groups.single()
                g.serviceName shouldBe "golem-finance"
                g.lines.size shouldBe 2
                // The cap bit, and the overflow is COUNTED rather than dropped silently.
                g.droppedByCap shouldBe 1
                // Level is recovered from the line so the summary filter has something
                // to work with; Loki carries no level label by default.
                g.lines.map { it.level } shouldBe listOf("INFO", "WARN")
            }
        }

        "loki: error -> Degraded; no trace id -> Degraded with the reason spelled out" {
            runTest {
                val http = client { respondError(HttpStatusCode.GatewayTimeout) }
                (
                    LokiClient("http://loki", http).fetch("t1", "2026-07-30T09:00:00Z", "2026-07-30T09:00:05Z", 10, "j")
                        as SourceOutcome.Degraded
                ).reason shouldContain "loki"

                val noTrace =
                    LokiClient("http://loki", client { respond("{}", HttpStatusCode.OK, jsonHeaders) })
                        .fetch("", "2026-07-30T09:00:00Z", "2026-07-30T09:00:05Z", 10, "j")
                (noTrace as SourceOutcome.Degraded).reason shouldContain "no trace_id"
            }
        }

        // ---- tempo ----

        "tempo: GET /api/traces/<id> parses OTLP JSON into spans with attributes" {
            runTest {
                val body =
                    """
                    {
                      "batches": [
                        {
                          "resource": {
                            "attributes": [
                              {
                                "key": "service.name",
                                "value": {
                                  "stringValue": "ttr-query"
                                }
                              }
                            ]
                          },
                          "scopeSpans": [
                            {
                              "spans": [
                                {
                                  "spanId": "aaaa000000000002",
                                  "name": "query.run",
                                  "startTimeUnixNano": "1785402002000000000",
                                  "endTimeUnixNano": "1785402002180000000",
                                  "attributes": [
                                    {
                                      "key": "dispatch.target",
                                      "value": {
                                        "stringValue": "pg-hartland"
                                      }
                                    },
                                    {
                                      "key": "result.row_count",
                                      "value": {
                                        "intValue": "8"
                                      }
                                    }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
                var seen: io.ktor.http.Url? = null
                val http =
                    client { req ->
                        seen = req.url
                        respond(body, HttpStatusCode.OK, jsonHeaders)
                    }

                val out = TempoClient("http://tempo:3200", http).fetch("abc123", "jwt")

                seen!!.encodedPath shouldBe "/api/traces/abc123"
                if (out is SourceOutcome.Degraded) error("expected Ok, got Degraded: ${out.reason}")
                out as SourceOutcome.Ok
                val span = out.payload.spans.single()
                span.serviceName shouldBe "ttr-query"
                span.durationMs shouldBe 180
                span.attributes["dispatch.target"] shouldBe "pg-hartland"
                // OTLP AnyValue: an intValue is a STRING on the JSON wire.
                span.attributes["result.row_count"] shouldBe "8"
            }
        }

        "tempo: 404 -> Degraded saying the trace expired or never exported (not 'nothing ran')" {
            runTest {
                val http = client { respondError(HttpStatusCode.NotFound) }
                val out = TempoClient("http://tempo", http).fetch("gone", "jwt")

                (out as SourceOutcome.Degraded).reason shouldContain "not found"
                out.reason shouldContain "expired or never exported"
            }
        }
    })
