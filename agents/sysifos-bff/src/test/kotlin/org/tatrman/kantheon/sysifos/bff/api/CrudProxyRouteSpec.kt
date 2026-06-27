package org.tatrman.kantheon.sysifos.bff.api

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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

class CrudProxyRouteSpec :
    StringSpec({

        "GET /midas/clients forwards to Midas-core with the tenant header" {
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(
                    get(urlPathEqualTo("/api/v1/clients")).willReturn(
                        aResponse().withHeader("Content-Type", "application/json").withBody("""{"clients":[]}"""),
                    ),
                )
                testApplication {
                    application { module(testDeps(midasBaseUrl = wm.baseUrl())) }
                    val res =
                        client.get("/midas/clients") {
                            header(HttpHeaders.Authorization, bearer("""{"sub":"u1","tenant":"acme"}"""))
                        }
                    res.status shouldBe HttpStatusCode.OK
                    res.bodyAsText() shouldContain "clients"
                }
                wm.verify(
                    getRequestedFor(urlPathEqualTo("/api/v1/clients")).withHeader("X-Tenant-Id", equalTo("acme")),
                )
            } finally {
                wm.stop()
            }
        }

        "POST /midas/clients passes the 201 through" {
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(
                    post(urlPathEqualTo("/api/v1/clients")).willReturn(
                        aResponse()
                            .withStatus(201)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"client":{"clientId":"c-1"}}"""),
                    ),
                )
                testApplication {
                    application { module(testDeps(midasBaseUrl = wm.baseUrl())) }
                    val res =
                        client.post("/midas/clients") {
                            header(HttpHeaders.Authorization, bearer("""{"sub":"u1","tenant":"acme"}"""))
                            contentType(ContentType.Application.Json)
                            setBody("""{"client":{"name":"Acme"}}""")
                        }
                    res.status shouldBe HttpStatusCode.Created
                }
            } finally {
                wm.stop()
            }
        }

        "GET /midas/clients without a JWT → 401 (and never reaches Midas)" {
            testApplication {
                application { module(testDeps()) }
                client.get("/midas/clients").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "GET /midas/audit without midas:admin → 403 (server-side gate, never reaches Midas)" {
            testApplication {
                application { module(testDeps()) }
                val res =
                    client.get("/midas/audit") {
                        header(HttpHeaders.Authorization, bearer("""{"sub":"u1","tenant":"acme"}"""))
                    }
                res.status shouldBe HttpStatusCode.Forbidden
            }
        }

        "GET /midas/audit with midas:admin forwards to Midas-core" {
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(
                    get(urlPathEqualTo("/api/v1/audit")).willReturn(
                        aResponse().withHeader("Content-Type", "application/json").withBody("""{"entries":[]}"""),
                    ),
                )
                testApplication {
                    application { module(testDeps(midasBaseUrl = wm.baseUrl())) }
                    val res =
                        client.get("/midas/audit") {
                            header(
                                HttpHeaders.Authorization,
                                bearer("""{"sub":"u1","tenant":"acme","realm_access":{"roles":["midas:admin"]}}"""),
                            )
                        }
                    res.status shouldBe HttpStatusCode.OK
                }
            } finally {
                wm.stop()
            }
        }

        "PATCH /midas/assets without midas:admin → 403 (asset edits are admin-only)" {
            testApplication {
                application { module(testDeps()) }
                val res =
                    client.patch("/midas/assets/a-1") {
                        header(HttpHeaders.Authorization, bearer("""{"sub":"u1","tenant":"acme"}"""))
                        contentType(ContentType.Application.Json)
                        setBody("""{"asset":{"name":"x"}}""")
                    }
                res.status shouldBe HttpStatusCode.Forbidden
            }
        }
    })
