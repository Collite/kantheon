package org.tatrman.kantheon.iris.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.NewTurn
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.inbox.FakePythiaClient
import org.tatrman.kantheon.iris.inbox.InboxService
import org.tatrman.kantheon.iris.inbox.InvestigationSummary
import org.tatrman.kantheon.iris.inbox.LifecycleHub
import org.tatrman.kantheon.iris.installErrorPages
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import java.util.Base64

private fun bearer(sub: String): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$sub"}""".toByteArray())
    return "Bearer h.$payload.s"
}

class InboxRoutesSpec :
    StringSpec({

        fun Application.mount(
            store: InMemorySessionStore,
            byUser: Map<String, List<InvestigationSummary>>,
        ) {
            installKtorServerBase(KtorServerConfig(serviceName = "iris-bff-test", serverPort = 0))
            installErrorPages()
            val service = InboxService(FakePythiaClient(byUser), store)
            routing { inboxRoutes(service, LifecycleHub(), BearerAuthenticator()) }
        }

        "GET /v1/inbox aggregates Pythia state joined with iris_turns + counts" {
            testApplication {
                val store = InMemorySessionStore()
                val s = store.createSession("u1", "t1")
                val turnId =
                    store
                        .appendTurn(
                            NewTurn(
                                sessionId = s.sessionId,
                                agentId = "pythia",
                                question = "Why did margin drop?",
                                status = TurnStatus.DONE,
                            ),
                        ).turnId
                val invs =
                    listOf(
                        InvestigationSummary(
                            "i1",
                            "Why did margin drop?",
                            "EXECUTING",
                            "t0",
                            "t1",
                            "IRIS",
                            turnId.toString(),
                        ),
                        InvestigationSummary("i2", "Forecast Q4", "AWAITING_BUDGET_DECISION", "t0", "t1"),
                    )
                application { mount(store, mapOf("u1" to invs)) }

                val res = client.get("/v1/inbox") { header(HttpHeaders.Authorization, bearer("u1")) }
                res.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
                body["counts"]!!.jsonObject["running"]!!.jsonPrimitive.int shouldBe 1
                body["counts"]!!.jsonObject["needsInput"]!!.jsonPrimitive.int shouldBe 1
                val items = body["items"]!!.jsonArray
                val i1 = items.map { it.jsonObject }.first { it["investigationId"]!!.jsonPrimitive.content == "i1" }
                i1["turnId"]!!.jsonPrimitive.content shouldBe turnId.toString()
                i1["sessionTitle"]!!.jsonPrimitive.content shouldBe "Why did margin drop?"
            }
        }

        "user A does not see user B's investigations" {
            testApplication {
                val store = InMemorySessionStore()
                val invs = listOf(InvestigationSummary("i1", "secret", "EXECUTING", "t0", "t1"))
                application { mount(store, mapOf("ub" to invs)) }

                val res = client.get("/v1/inbox") { header(HttpHeaders.Authorization, bearer("ua")) }
                Json
                    .parseToJsonElement(res.bodyAsText())
                    .jsonObject["items"]!!
                    .jsonArray.size shouldBe 0
            }
        }

        "401 without a bearer" {
            testApplication {
                val store = InMemorySessionStore()
                application { mount(store, emptyMap()) }
                client.get("/v1/inbox").status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
