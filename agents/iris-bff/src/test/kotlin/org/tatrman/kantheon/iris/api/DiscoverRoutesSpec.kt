package org.tatrman.kantheon.iris.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.kantheon.iris.installErrorPages
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import java.util.Base64

private fun bearer(sub: String): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$sub"}""".toByteArray())
    return "Bearer h.$payload.s"
}

class DiscoverRoutesSpec :
    StringSpec({

        fun Application.mount(caps: CapabilitiesReadClient) {
            installKtorServerBase(KtorServerConfig(serviceName = "iris-bff-test", serverPort = 0))
            installErrorPages()
            routing { discoverRoutes(caps, BearerAuthenticator()) }
        }

        "returns role-filtered domain cards" {
            val caps = mockk<CapabilitiesReadClient>()
            coEvery { caps.listAgents() } returns
                Json.parseToJsonElement(
                    """{"agents":[
                  {"agentId":"golem-erp","displayName":"ERP","descriptionForRouter":"Invoices","exampleQuestions":["Q?"]},
                  {"agentId":"themis","displayName":"Themis","nonRoutable":true}
                ]}""",
                ) as kotlinx.serialization.json.JsonObject

            testApplication {
                application { mount(caps) }
                val res = client.get("/v1/discover") { header(HttpHeaders.Authorization, bearer("u1")) }
                res.status shouldBe HttpStatusCode.OK
                val body = res.bodyAsText()
                body shouldContain "golem-erp"
                body shouldContain "Invoices"
                (body.contains("themis")) shouldBe false // non_routable excluded
            }
        }

        "capabilities-mcp down → empty surface (best-effort)" {
            val caps = mockk<CapabilitiesReadClient>()
            coEvery { caps.listAgents() } throws RuntimeException("registry down")
            testApplication {
                application { mount(caps) }
                val res = client.get("/v1/discover") { header(HttpHeaders.Authorization, bearer("u1")) }
                res.status shouldBe HttpStatusCode.OK
                Json
                    .parseToJsonElement(res.bodyAsText())
                    .jsonObject["domains"]!!
                    .jsonArray
                    .isEmpty() shouldBe true
            }
        }

        "401 without a bearer" {
            val caps = mockk<CapabilitiesReadClient>()
            testApplication {
                application { mount(caps) }
                client.get("/v1/discover").status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
