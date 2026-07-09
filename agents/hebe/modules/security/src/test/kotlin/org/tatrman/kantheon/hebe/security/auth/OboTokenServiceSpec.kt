package org.tatrman.kantheon.hebe.security.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * The OBO token service (P2 Stage 2.3 T4) over a MockEngine'd Keycloak: both
 * grant paths mint a bound-user bearer; the result is cached within TTL and
 * re-minted on expiry.
 */
class OboTokenServiceSpec :
    StringSpec({

        val tokenUrl = "https://kc.example.com/realms/kantheon/protocol/openid-connect/token"

        fun clientReturning(
            seq: List<String>,
            captured: MutableList<HttpRequestData>,
        ): HttpClient {
            var i = 0
            val engine =
                MockEngine { request ->
                    captured.add(request)
                    val body = seq[minOf(i, seq.size - 1)]
                    i++
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            return HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        }

        fun formOf(req: HttpRequestData): String =
            when (val body = req.body) {
                is io.ktor.http.content.OutgoingContent.ByteArrayContent -> String(body.bytes())
                else -> body.toString()
            }

        "refresh-token grant (personal/server) mints a bound-user bearer" {
            runTest {
                val captured = mutableListOf<HttpRequestData>()
                val http = clientReturning(listOf("""{"access_token":"AT-1","expires_in":300}"""), captured)
                val svc =
                    OboTokenService(
                        RefreshTokenGrant("bora", tokenUrl, "hebe-cli", "RT-seed", http),
                    )
                svc.currentBearer() shouldBe "AT-1"
                svc.boundUser shouldBe "bora"
            }
        }

        "client-credentials → token-exchange (k8s) mints a bound-user bearer in two hops" {
            runTest {
                val captured = mutableListOf<HttpRequestData>()
                val http =
                    clientReturning(
                        listOf(
                            """{"access_token":"SVC","expires_in":300}""", // client-credentials
                            """{"access_token":"OBO","expires_in":300}""", // token-exchange
                        ),
                        captured,
                    )
                val svc =
                    OboTokenService(
                        ClientCredentialsExchangeGrant("bora", tokenUrl, "hebe-k8s", "shh", http),
                    )
                svc.currentBearer() shouldBe "OBO"
                captured.size shouldBe 2
                formOf(captured[1]).contains("token-exchange") shouldBe true
            }
        }

        "a token within TTL is served from cache (no re-hit)" {
            runTest {
                val captured = mutableListOf<HttpRequestData>()
                val http = clientReturning(listOf("""{"access_token":"AT-1","expires_in":300}"""), captured)
                val svc = OboTokenService(RefreshTokenGrant("bora", tokenUrl, "hebe-cli", "RT", http))
                svc.currentBearer()
                svc.currentBearer()
                captured.size shouldBe 1
            }
        }

        "an expired token is re-minted" {
            runTest {
                val captured = mutableListOf<HttpRequestData>()
                val http =
                    clientReturning(
                        listOf(
                            """{"access_token":"AT-1","expires_in":1}""",
                            """{"access_token":"AT-2","expires_in":300}""",
                        ),
                        captured,
                    )
                var clock = 1_000L
                val svc =
                    OboTokenService(
                        RefreshTokenGrant("bora", tokenUrl, "hebe-cli", "RT", http),
                        refreshSkewSeconds = 0,
                        now = { clock },
                    )
                svc.currentBearer() shouldBe "AT-1"
                clock += 10 // past the 1s lifetime
                svc.currentBearer() shouldBe "AT-2"
                captured.size shouldBe 2
            }
        }

        "refresh-token grant adopts the rotated refresh_token on the next mint" {
            runTest {
                val captured = mutableListOf<HttpRequestData>()
                val http =
                    clientReturning(
                        listOf(
                            // first exchange rotates RT-seed → RT-2
                            """{"access_token":"AT-1","expires_in":1,"refresh_token":"RT-2"}""",
                            """{"access_token":"AT-2","expires_in":300,"refresh_token":"RT-3"}""",
                        ),
                        captured,
                    )
                var clock = 1_000L
                val svc =
                    OboTokenService(
                        RefreshTokenGrant("bora", tokenUrl, "hebe-cli", "RT-seed", http),
                        refreshSkewSeconds = 0,
                        now = { clock },
                    )
                svc.currentBearer() shouldBe "AT-1"
                formOf(captured[0]).contains("refresh_token=RT-seed") shouldBe true
                clock += 10 // past the 1s lifetime → re-mint
                svc.currentBearer() shouldBe "AT-2"
                // the second mint must use the rotated token, not the now-invalid seed
                formOf(captured[1]).contains("refresh_token=RT-2") shouldBe true
                formOf(captured[1]).contains("RT-seed") shouldBe false
            }
        }

        "refresh-token grant keeps the previous refresh_token when a response omits one" {
            runTest {
                val captured = mutableListOf<HttpRequestData>()
                val http =
                    clientReturning(
                        listOf(
                            // no refresh_token in the response — keep RT-seed
                            """{"access_token":"AT-1","expires_in":1}""",
                            """{"access_token":"AT-2","expires_in":300}""",
                        ),
                        captured,
                    )
                var clock = 1_000L
                val svc =
                    OboTokenService(
                        RefreshTokenGrant("bora", tokenUrl, "hebe-cli", "RT-seed", http),
                        refreshSkewSeconds = 0,
                        now = { clock },
                    )
                svc.currentBearer()
                clock += 10
                svc.currentBearer() shouldBe "AT-2"
                formOf(captured[1]).contains("refresh_token=RT-seed") shouldBe true
            }
        }
    })
