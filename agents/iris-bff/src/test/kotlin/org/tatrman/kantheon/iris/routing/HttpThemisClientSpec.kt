package org.tatrman.kantheon.iris.routing

import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.utils.EmptyContent
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.common.v1.AgentId
import org.tatrman.kantheon.themis.v1.Themis.FreshQuestion
import org.tatrman.kantheon.themis.v1.Themis.Profile
import org.tatrman.kantheon.themis.v1.Themis.ResolveRequest
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse
import org.tatrman.kantheon.themis.v1.Themis.Resolution
import org.tatrman.kantheon.themis.v1.Themis.RoutingDecision

private val printer = JsonFormat.printer().omittingInsignificantWhitespace()

private fun bodyText(req: HttpRequestData): String =
    when (val c = req.body) {
        is TextContent -> c.text
        EmptyContent -> ""
        else -> error("unexpected body type ${c::class}")
    }

/** A MockEngine that records the last request and replies with [responseJson]. */
private fun mockThemis(
    responseJson: String,
    captured: MutableList<HttpRequestData> = mutableListOf(),
): HttpClient =
    HttpClient(
        MockEngine { request ->
            captured.add(request)
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

class HttpThemisClientSpec :
    StringSpec({

        "request rides proto-JSON to /v1/resolve with bearer header and routing fields" {
            runTest {
                val captured = mutableListOf<HttpRequestData>()
                val responseJson =
                    printer.print(
                        ResolveResponse
                            .newBuilder()
                            .setResolution(Resolution.newBuilder().setFunctionId("noop"))
                            .build(),
                    )
                val client = HttpThemisClient("http://themis", httpClient = mockThemis(responseJson, captured))

                val request =
                    ResolveRequest
                        .newBuilder()
                        .setFresh(FreshQuestion.newBuilder().setText("kolik mám tržeb?"))
                        .setProfile(Profile.CHAT_QUICK)
                        .setRoutingHint(AgentId.newBuilder().setValue("golem-erp"))
                        .build()

                client.understand(request, "jwt-token-123")

                val sent = captured.single()
                sent.url.encodedPath shouldBe "/v1/resolve"
                sent.headers[HttpHeaders.Authorization] shouldBe "Bearer jwt-token-123"

                // Body parses back to the same proto (proto-JSON round-trip).
                val echo = ResolveRequest.newBuilder()
                JsonFormat.parser().merge(bodyText(sent), echo)
                val parsed = echo.build()
                parsed.fresh.text shouldBe "kolik mám tržeb?"
                parsed.profile shouldBe Profile.CHAT_QUICK
                parsed.routingHint.value shouldBe "golem-erp"
            }
        }

        "parses a Resolution outcome with routing decision" {
            runTest {
                val responseJson =
                    printer.print(
                        ResolveResponse
                            .newBuilder()
                            .setResolution(
                                Resolution
                                    .newBuilder()
                                    .setFunctionId("erp.invoices")
                                    .setRouting(
                                        RoutingDecision
                                            .newBuilder()
                                            .setChosenAgentId(AgentId.newBuilder().setValue("golem-erp"))
                                            .setConfidence(0.91)
                                            .setLayerHit(1),
                                    ),
                            ).build(),
                    )
                val client = HttpThemisClient("http://themis/", httpClient = mockThemis(responseJson))

                val resp = client.understand(ResolveRequest.getDefaultInstance(), "t")

                resp.hasResolution() shouldBe true
                resp.resolution.routing.chosenAgentId.value shouldBe "golem-erp"
                resp.resolution.routing.layerHit shouldBe 1
            }
        }

        "non-2xx raises ThemisUnavailableException" {
            runTest {
                val client =
                    HttpThemisClient(
                        "http://themis",
                        httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }),
                    )
                shouldThrow<ThemisUnavailableException> {
                    client.understand(ResolveRequest.getDefaultInstance(), "t")
                }
            }
        }

        "401 raises ThemisAuthException (expired/invalid bearer fails closed, not an outage)" {
            runTest {
                val client =
                    HttpThemisClient(
                        "http://themis",
                        httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.Unauthorized) }),
                    )
                shouldThrow<ThemisAuthException> {
                    client.understand(ResolveRequest.getDefaultInstance(), "t")
                }
            }
        }

        "403 raises ThemisAuthException" {
            runTest {
                val client =
                    HttpThemisClient(
                        "http://themis",
                        httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.Forbidden) }),
                    )
                shouldThrow<ThemisAuthException> {
                    client.understand(ResolveRequest.getDefaultInstance(), "t")
                }
            }
        }
    })
