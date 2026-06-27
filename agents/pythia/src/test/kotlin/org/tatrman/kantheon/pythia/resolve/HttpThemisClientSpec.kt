package org.tatrman.kantheon.pythia.resolve

import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.themis.v1.Themis

/**
 * Stage 2.1 T1 — `HttpThemisClient` transport: proto→JSON request with the OBO
 * bearer forwarded; JSON→proto response; 401 → fail-closed `ThemisAuthException`.
 */
class HttpThemisClientSpec :
    StringSpec({

        fun request() =
            Themis.ResolveRequest
                .newBuilder()
                .setProfile(Themis.Profile.INVESTIGATION_DEEP)
                .setFresh(Themis.FreshQuestion.newBuilder().setText("why?"))
                .build()

        "forwards the bearer and parses a RESOLUTION response" {
            runTest {
                var seenAuth: String? = null
                val resolutionJson =
                    JsonFormat.printer().print(
                        Themis.ResolveResponse
                            .newBuilder()
                            .setResolution(Themis.Resolution.newBuilder().setIntentKind(Themis.IntentKind.RCA))
                            .build(),
                    )
                val engine =
                    MockEngine { req ->
                        seenAuth = req.headers[HttpHeaders.Authorization]
                        respond(
                            content = resolutionJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                val client = HttpThemisClient("http://themis", HttpClient(engine))
                val resp = client.understand(request(), "tok-123")
                resp.outcomeCase shouldBe Themis.ResolveResponse.OutcomeCase.RESOLUTION
                seenAuth shouldBe "Bearer tok-123"
            }
        }

        "a 401 raises ThemisAuthException (fail closed)" {
            runTest {
                val engine = MockEngine { respond(content = "", status = HttpStatusCode.Unauthorized) }
                val client = HttpThemisClient("http://themis", HttpClient(engine))
                shouldThrow<ThemisAuthException> { client.understand(request(), "tok") }
            }
        }

        "a 503 raises ThemisUnavailableException" {
            runTest {
                val engine = MockEngine { respond(content = "", status = HttpStatusCode.ServiceUnavailable) }
                val client = HttpThemisClient("http://themis", HttpClient(engine))
                shouldThrow<ThemisUnavailableException> { client.understand(request(), "tok") }
            }
        }
    })
