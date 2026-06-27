package org.tatrman.kantheon.iris.api

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
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.dispatch.golemv2.FakeGolemV2Client
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2Client
import org.tatrman.kantheon.iris.dispatch.golemv2.V2ChatRequest
import org.tatrman.kantheon.iris.dispatch.golemv2.V2RefreshRequest
import org.tatrman.kantheon.iris.dispatch.golemv2.V2RefreshResponse
import org.tatrman.kantheon.iris.dispatch.golemv2.V2ResumeRequest
import org.tatrman.kantheon.iris.dispatch.golemv2.V2SessionStartResponse
import org.tatrman.kantheon.iris.dispatch.golemv2.V2StreamEvent
import org.tatrman.kantheon.iris.dispatch.AgentDispatcher
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2AgentClient
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.installErrorPages
import org.tatrman.kantheon.iris.routing.AgentLabels
import org.tatrman.kantheon.iris.routing.FakeThemisClient
import org.tatrman.kantheon.iris.routing.RoutingEnvelopes
import org.tatrman.kantheon.iris.routing.ThemisClient
import org.tatrman.kantheon.iris.stream.IrisStreamMux
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import java.util.Base64

private fun bearer(sub: String): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$sub"}""".toByteArray())
    return "Bearer h.$payload.s"
}

private val json = Json { ignoreUnknownKeys = true }

private fun Application.mount(
    store: SessionStore,
    client: GolemV2Client,
    audit: InMemoryAuditStore = InMemoryAuditStore(Ed25519Signer()),
    themis: ThemisClient = FakeThemisClient(),
) {
    installKtorServerBase(KtorServerConfig(serviceName = "iris-bff-test", serverPort = 0))
    installErrorPages()
    val agents = AgentDispatcher(mapOf("golem-v2" to GolemV2AgentClient(store, client, IrisStreamMux())))
    val dispatcher = ChatDispatcher(store, themis, agents, audit, RoutingEnvelopes(AgentLabels.IDENTITY))
    routing {
        sessionRoutes(store, BearerAuthenticator())
        chatRoutes(store, BearerAuthenticator(), dispatcher)
    }
}

/** Fails partway through the /v2 stream (network drop / deadline). */
private class ThrowingGolemV2Client : GolemV2Client {
    override suspend fun createSession(
        threadId: String,
        userId: String,
        correlationId: String,
        bearer: String,
        locale: String,
    ) = V2SessionStartResponse(thread_id = threadId)

    override fun chatStream(
        req: V2ChatRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ) = flow {
        emit(V2StreamEvent.NodeStart("resolve"))
        throw RuntimeException("upstream exploded")
    }

    override fun reissueAction(
        req: org.tatrman.kantheon.iris.dispatch.golemv2.V2ActionRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ) = flow<V2StreamEvent> {
        throw RuntimeException("upstream exploded")
    }

    override fun resume(
        req: V2ResumeRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ) = flow<V2StreamEvent> {
        throw RuntimeException("upstream exploded")
    }

    override suspend fun refresh(
        req: V2RefreshRequest,
        userId: String,
        correlationId: String,
        bearer: String,
    ): V2RefreshResponse = throw RuntimeException("upstream exploded")
}

private suspend fun io.ktor.client.HttpClient.newSession(user: String): String {
    val res = post("/v1/session") { header(HttpHeaders.Authorization, bearer(user)) }
    return json
        .parseToJsonElement(res.bodyAsText())
        .jsonObject["sessionId"]!!
        .jsonPrimitive.content
}

class ChatRoutesSpec :
    StringSpec({

        "POST /v1/chat/turn drives a turn and returns the terminal envelope" {
            val store = InMemorySessionStore()
            testApplication {
                application { mount(store, FakeGolemV2Client()) }
                val sid = client.newSession("u1")
                val res =
                    client.post("/v1/chat/turn") {
                        header(HttpHeaders.Authorization, bearer("u1"))
                        contentType(ContentType.Application.Json)
                        setBody("""{"sessionId":"$sid","question":"kolik mám tržeb?"}""")
                    }
                res.status shouldBe HttpStatusCode.OK
                val env = json.parseToJsonElement(res.bodyAsText()).jsonObject["envelope"]!!.jsonObject
                env["agentId"]!!.jsonPrimitive.content shouldBe "golem-v2"
            }
        }

        "POST /v1/chat/stream emits step, envelope and a synthesised done over SSE" {
            val store = InMemorySessionStore()
            testApplication {
                application { mount(store, FakeGolemV2Client()) }
                val sid = client.newSession("u1")
                val body =
                    client
                        .post("/v1/chat/stream") {
                            header(HttpHeaders.Authorization, bearer("u1"))
                            contentType(ContentType.Application.Json)
                            setBody("""{"sessionId":"$sid","question":"tržby"}""")
                        }.bodyAsText()
                body shouldContain "event: step"
                body shouldContain "event: envelope"
                body shouldContain "event: done"
            }
        }

        "a turn is persisted with the streamed terminal envelope" {
            val store = InMemorySessionStore()
            testApplication {
                application { mount(store, FakeGolemV2Client()) }
                val sid = client.newSession("u1")
                client.post("/v1/chat/turn") {
                    header(HttpHeaders.Authorization, bearer("u1"))
                    contentType(ContentType.Application.Json)
                    setBody("""{"sessionId":"$sid","question":"tržby"}""")
                }
                val turns = store.getTurns(java.util.UUID.fromString(sid))
                turns.size shouldBe 1
                turns.single().status shouldBe TurnStatus.DONE
                turns.single().agentId shouldBe "golem-v2"
            }
        }

        "clarification → resume round-trip" {
            val store = InMemorySessionStore()
            val client = FakeGolemV2Client(fixtureForQuestion = { "clarification.sse" })
            testApplication {
                application { mount(store, client) }
                val sid = this@testApplication.client.newSession("u1")

                // Turn 1 → clarification.
                val first =
                    this@testApplication
                        .client
                        .post("/v1/chat/turn") {
                            header(HttpHeaders.Authorization, bearer("u1"))
                            contentType(ContentType.Application.Json)
                            setBody("""{"sessionId":"$sid","question":"kdo?"}""")
                        }.bodyAsText()
                // /chat/turn returns proto-canonical camelCase JSON.
                first shouldContain "pendingClarification"
                first shouldContain "rt-abc"

                // The clarification turn is persisted with the resume token.
                val clTurn = store.getTurns(java.util.UUID.fromString(sid), includeDiscarded = true).single()
                clTurn.status shouldBe TurnStatus.CLARIFICATION
                clTurn.pendingResumeToken shouldBe "rt-abc"

                // Turn 2 → resume routed to the issuer.
                val resumed =
                    this@testApplication
                        .client
                        .post("/v1/chat/resume") {
                            header(HttpHeaders.Authorization, bearer("u1"))
                            contentType(ContentType.Application.Json)
                            setBody("""{"sessionId":"$sid","resumeToken":"rt-abc","selectedOptionId":"c-1"}""")
                        }
                resumed.status shouldBe HttpStatusCode.OK
                val body = resumed.bodyAsText()
                body shouldContain "event: envelope"
                body shouldContain "Vybráno"
                body shouldContain "event: done"
            }
        }

        "resume with an unknown token is a 404" {
            val store = InMemorySessionStore()
            testApplication {
                application { mount(store, FakeGolemV2Client()) }
                val sid = client.newSession("u1")
                client
                    .post("/v1/chat/resume") {
                        header(HttpHeaders.Authorization, bearer("u1"))
                        contentType(ContentType.Application.Json)
                        setBody("""{"sessionId":"$sid","resumeToken":"nope"}""")
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        "chat on a session owned by another user is a 404" {
            val store = InMemorySessionStore()
            testApplication {
                application { mount(store, FakeGolemV2Client()) }
                val sid = client.newSession("u1")
                client
                    .post("/v1/chat/turn") {
                        header(HttpHeaders.Authorization, bearer("intruder"))
                        contentType(ContentType.Application.Json)
                        setBody("""{"sessionId":"$sid","question":"x"}""")
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        "the caller's OBO bearer is forwarded downstream to /v2" {
            val store = InMemorySessionStore()
            val fake = FakeGolemV2Client()
            testApplication {
                application { mount(store, fake) }
                val sid = client.newSession("u1")
                client.post("/v1/chat/turn") {
                    header(HttpHeaders.Authorization, bearer("u1"))
                    contentType(ContentType.Application.Json)
                    setBody("""{"sessionId":"$sid","question":"tržby"}""")
                }
                // The exact JWT the client sent (createSession + chatStream both get it).
                val token = bearer("u1").removePrefix("Bearer ")
                fake.receivedBearers.all { it == token } shouldBe true
                fake.receivedBearers.isEmpty() shouldBe false
            }
        }

        "an upstream stream failure still persists a FAILED turn, audits it, and emits a terminal frame" {
            val store = InMemorySessionStore()
            val audit = InMemoryAuditStore(Ed25519Signer())
            testApplication {
                application { mount(store, ThrowingGolemV2Client(), audit) }
                val sid = client.newSession("u1")
                val body =
                    client
                        .post("/v1/chat/stream") {
                            header(HttpHeaders.Authorization, bearer("u1"))
                            contentType(ContentType.Application.Json)
                            setBody("""{"sessionId":"$sid","question":"boom"}""")
                        }.bodyAsText()
                // The wire stays well-formed: a terminal error + synthesised done.
                body shouldContain "event: error"
                body shouldContain "event: done"
                body shouldContain "failed"
                // The turn is persisted FAILED (not dropped) and audited.
                val turns = store.getTurns(java.util.UUID.fromString(sid))
                turns.single().status shouldBe TurnStatus.FAILED
                audit.all().any { it.eventKind == "turn" } shouldBe true
            }
        }

        "the SSE response carries no-cache + no-buffer headers" {
            val store = InMemorySessionStore()
            testApplication {
                application { mount(store, FakeGolemV2Client()) }
                val sid = client.newSession("u1")
                val res =
                    client.post("/v1/chat/stream") {
                        header(HttpHeaders.Authorization, bearer("u1"))
                        contentType(ContentType.Application.Json)
                        setBody("""{"sessionId":"$sid","question":"tržby"}""")
                    }
                res.headers[HttpHeaders.CacheControl] shouldBe "no-cache"
                res.headers["X-Accel-Buffering"] shouldBe "no"
            }
        }
    })
