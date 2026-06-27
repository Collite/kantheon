package org.tatrman.kantheon.iris.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.tatrman.kantheon.iris.domain.InMemoryFeedbackStore
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.NewTurn
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.installErrorPages
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import java.util.Base64
import java.util.UUID

private fun bearer(sub: String): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$sub"}""".toByteArray())
    return "Bearer h.$payload.s"
}

private fun seedTurn(
    store: InMemorySessionStore,
    user: String,
    agentId: String = "golem-erp",
): UUID {
    val s = store.createSession(user, "t1")
    return store
        .appendTurn(
            NewTurn(sessionId = s.sessionId, agentId = agentId, question = "q", status = TurnStatus.DONE),
        ).turnId
}

class FeedbackRoutesSpec :
    StringSpec({

        fun Application.mount(
            store: InMemorySessionStore,
            feedback: InMemoryFeedbackStore,
        ) {
            installKtorServerBase(KtorServerConfig(serviceName = "iris-bff-test", serverPort = 0))
            installErrorPages()
            routing { feedbackRoutes(store, feedback, BearerAuthenticator()) }
        }

        "POST feedback upserts the verdict against the turn's agent" {
            testApplication {
                val store = InMemorySessionStore()
                val feedback = InMemoryFeedbackStore()
                application { mount(store, feedback) }
                val turnId = seedTurn(store, "u1")

                val res =
                    client.post("/v1/turns/$turnId/feedback") {
                        header(HttpHeaders.Authorization, bearer("u1"))
                        contentType(ContentType.Application.Json)
                        setBody("""{"verdict":"down","reason":"wrong_data"}""")
                    }
                res.status shouldBe HttpStatusCode.OK
                val rec = feedback.get(turnId, "u1")!!
                rec.verdict shouldBe "down"
                rec.reason shouldBe "wrong_data"
                rec.agentId shouldBe "golem-erp"
            }
        }

        "a later verdict overwrites the earlier one (upsert per turn,user)" {
            testApplication {
                val store = InMemorySessionStore()
                val feedback = InMemoryFeedbackStore()
                application { mount(store, feedback) }
                val turnId = seedTurn(store, "u1")

                suspend fun vote(body: String) =
                    client.post("/v1/turns/$turnId/feedback") {
                        header(HttpHeaders.Authorization, bearer("u1"))
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                vote("""{"verdict":"down","reason":"too_slow"}""")
                vote("""{"verdict":"up"}""")
                feedback.get(turnId, "u1")!!.verdict shouldBe "up"
            }
        }

        "invalid verdict / reason is 400" {
            testApplication {
                val store = InMemorySessionStore()
                application { mount(store, InMemoryFeedbackStore()) }
                val turnId = seedTurn(store, "u1")
                client
                    .post("/v1/turns/$turnId/feedback") {
                        header(HttpHeaders.Authorization, bearer("u1"))
                        contentType(ContentType.Application.Json)
                        setBody("""{"verdict":"meh"}""")
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        "a non-owner cannot leave feedback (404)" {
            testApplication {
                val store = InMemorySessionStore()
                application { mount(store, InMemoryFeedbackStore()) }
                val turnId = seedTurn(store, "u1")
                client
                    .post("/v1/turns/$turnId/feedback") {
                        header(HttpHeaders.Authorization, bearer("intruder"))
                        contentType(ContentType.Application.Json)
                        setBody("""{"verdict":"up"}""")
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }
    })
