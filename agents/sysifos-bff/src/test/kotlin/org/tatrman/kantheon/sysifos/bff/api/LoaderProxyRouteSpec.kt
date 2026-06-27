package org.tatrman.kantheon.sysifos.bff.api

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tatrman.kantheon.sysifos.bff.bearer
import org.tatrman.kantheon.sysifos.bff.module
import org.tatrman.kantheon.sysifos.bff.testDeps

class LoaderProxyRouteSpec :
    StringSpec({

        "GET /loaders/excel/runs/{id} forwards to the loader with the tenant header" {
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(
                    get(urlPathEqualTo("/api/v1/runs/r-1")).willReturn(
                        aResponse()
                            .withHeader(
                                "Content-Type",
                                "application/json",
                            ).withBody("""{"loaderRunId":"r-1"}"""),
                    ),
                )
                testApplication {
                    application { module(testDeps(loaderBaseUrl = wm.baseUrl())) }
                    val res =
                        client.get("/loaders/excel/runs/r-1") {
                            header(HttpHeaders.Authorization, bearer("""{"sub":"u1","tenant":"acme"}"""))
                        }
                    res.status shouldBe HttpStatusCode.OK
                    res.bodyAsText() shouldContain "loaderRunId"
                }
                wm.verify(
                    getRequestedFor(urlPathEqualTo("/api/v1/runs/r-1")).withHeader("X-Tenant-Id", equalTo("acme")),
                )
            } finally {
                wm.stop()
            }
        }

        "POST /loaders/excel/uploads forwards the body with its content type" {
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(
                    post(urlPathEqualTo("/api/v1/uploads")).willReturn(
                        aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody(
                            """{"loaderRunId":"r-2","statusUrl":"/runs/r-2"}""",
                        ),
                    ),
                )
                testApplication {
                    application { module(testDeps(loaderBaseUrl = wm.baseUrl())) }
                    val res =
                        client.post("/loaders/excel/uploads") {
                            header(HttpHeaders.Authorization, bearer("""{"sub":"u1","tenant":"acme"}"""))
                            contentType(ContentType.parse("multipart/form-data; boundary=xyz"))
                            setBody("--xyz--")
                        }
                    res.status shouldBe HttpStatusCode.Accepted
                }
                wm.verify(postRequestedFor(urlPathEqualTo("/api/v1/uploads")))
            } finally {
                wm.stop()
            }
        }

        "GET /loaders/excel/runs/r-1 without a JWT → 401" {
            testApplication {
                application { module(testDeps()) }
                client.get("/loaders/excel/runs/r-1").status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
