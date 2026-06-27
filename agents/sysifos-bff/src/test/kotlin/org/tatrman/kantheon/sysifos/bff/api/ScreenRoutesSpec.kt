package org.tatrman.kantheon.sysifos.bff.api

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tatrman.kantheon.sysifos.bff.bearer
import org.tatrman.kantheon.sysifos.bff.module
import org.tatrman.kantheon.sysifos.bff.testDeps

class ScreenRoutesSpec :
    StringSpec({

        "GET /screens/transactions fans out and nests cash legs under the security leg" {
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(
                    get(urlPathEqualTo("/api/v1/transactions")).willReturn(
                        aResponse().withHeader("Content-Type", "application/json").withBody(
                            """{"transactions":[
                               {"transactionId":"t-1","source":"TX_SRC_MANUAL","correlationId":"c-1"},
                               {"transactionId":"t-2","source":"TX_SRC_DERIVATION","correlationId":"c-1"}
                            ]}""",
                        ),
                    ),
                )
                wm.stubFor(
                    get(urlPathEqualTo("/api/v1/assets")).willReturn(
                        aResponse()
                            .withHeader(
                                "Content-Type",
                                "application/json",
                            ).withBody("""{"assets":[{"assetId":"a-1"}]}"""),
                    ),
                )
                wm.stubFor(
                    get(urlPathEqualTo("/api/v1/portfolios")).willReturn(
                        aResponse()
                            .withHeader(
                                "Content-Type",
                                "application/json",
                            ).withBody("""{"portfolios":[{"portfolioId":"p-1"}]}"""),
                    ),
                )
                testApplication {
                    application { module(testDeps(midasBaseUrl = wm.baseUrl())) }
                    val res =
                        client.get("/screens/transactions?portfolio_id=p-1") {
                            header(HttpHeaders.Authorization, bearer("""{"sub":"u1","tenant":"acme"}"""))
                        }
                    res.status shouldBe HttpStatusCode.OK
                    val body = res.bodyAsText()
                    body shouldContain "cashLegs"
                    body shouldContain "t-2"
                    body shouldContain "\"assets\""
                    body shouldContain "\"portfolios\""
                }
                wm.verify(
                    getRequestedFor(urlPathEqualTo("/api/v1/transactions"))
                        .withHeader("X-Tenant-Id", equalTo("acme")),
                )
            } finally {
                wm.stop()
            }
        }

        "GET /screens/transactions surfaces a Midas-core error on the transactions leg" {
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(
                    get(urlPathEqualTo("/api/v1/transactions")).willReturn(
                        aResponse()
                            .withStatus(
                                403,
                            ).withHeader("Content-Type", "application/json")
                            .withBody("""{"code":"TENANT_MISMATCH"}"""),
                    ),
                )
                testApplication {
                    application { module(testDeps(midasBaseUrl = wm.baseUrl())) }
                    val res =
                        client.get("/screens/transactions?portfolio_id=p-1") {
                            header(HttpHeaders.Authorization, bearer("""{"sub":"u1","tenant":"acme"}"""))
                        }
                    res.status shouldBe HttpStatusCode.Forbidden
                }
            } finally {
                wm.stop()
            }
        }

        "GET /screens/transactions without a JWT → 401" {
            testApplication {
                application { module(testDeps()) }
                client.get("/screens/transactions").status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
