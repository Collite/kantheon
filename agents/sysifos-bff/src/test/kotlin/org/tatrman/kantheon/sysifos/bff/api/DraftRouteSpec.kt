package org.tatrman.kantheon.sysifos.bff.api

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.tatrman.kantheon.sysifos.bff.bearer
import org.tatrman.kantheon.sysifos.bff.module
import org.tatrman.kantheon.sysifos.bff.testDeps

class DraftRouteSpec :
    StringSpec({

        "POST /drafts with a DRAFT_CLIENT round-trips to Midas-core via the async path" {
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(
                    post(urlPathEqualTo("/api/v1/clients")).willReturn(
                        aResponse()
                            .withStatus(201)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"client":{"clientId":"client-123"}}"""),
                    ),
                )
                testApplication {
                    application { module(testDeps(midasBaseUrl = wm.baseUrl())) }
                    val res =
                        client.post("/drafts") {
                            header(HttpHeaders.Authorization, bearer("""{"sub":"u1","tenant":"acme"}"""))
                            contentType(ContentType.Application.Json)
                            setBody("""{"kind":"DRAFT_CLIENT","payloadJson":"{\"name\":\"Acme Corp\"}"}""")
                        }
                    res.status shouldBe HttpStatusCode.Accepted
                    res.bodyAsText() shouldContain "draft_id"

                    // The commit runs off-thread; wait for Midas-core to receive the POST.
                    withTimeout(3000) {
                        while (wm.findAll(postRequestedFor(urlPathEqualTo("/api/v1/clients"))).isEmpty()) {
                            delay(25)
                        }
                    }
                }
                val posts = wm.findAll(postRequestedFor(urlPathEqualTo("/api/v1/clients")))
                posts.first().getHeader("X-Tenant-Id") shouldBe "acme"
                posts.first().bodyAsString shouldContain "Acme Corp"
            } finally {
                wm.stop()
            }
        }

        "POST /drafts without a JWT → 401" {
            testApplication {
                application { module(testDeps()) }
                client.post("/drafts").status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
