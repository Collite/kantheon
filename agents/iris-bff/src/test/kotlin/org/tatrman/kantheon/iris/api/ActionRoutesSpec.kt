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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.dispatch.AgentDispatcher
import org.tatrman.kantheon.iris.dispatch.golemv2.FakeGolemV2Client
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2AgentClient
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2Client
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.installErrorPages
import org.tatrman.kantheon.iris.routing.AgentLabels
import org.tatrman.kantheon.iris.routing.FakeThemisClient
import org.tatrman.kantheon.iris.routing.RoutingEnvelopes
import org.tatrman.kantheon.iris.stream.IrisStreamMux
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import java.util.Base64
import java.util.UUID

private fun bearer(sub: String): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$sub"}""".toByteArray())
    return "Bearer h.$payload.s"
}

private val json = Json { ignoreUnknownKeys = true }

private fun Application.mount(
    store: SessionStore,
    client: GolemV2Client,
) {
    installKtorServerBase(KtorServerConfig(serviceName = "iris-bff-test", serverPort = 0))
    installErrorPages()
    val agents = AgentDispatcher(mapOf("golem-v2" to GolemV2AgentClient(store, client, IrisStreamMux())))
    val dispatcher =
        ChatDispatcher(
            store,
            FakeThemisClient(),
            agents,
            InMemoryAuditStore(Ed25519Signer()),
            RoutingEnvelopes(AgentLabels.IDENTITY),
        )
    val typedActions =
        org.tatrman.kantheon.iris.action
            .TypedActionDispatcher(store, client, InMemoryAuditStore(Ed25519Signer()))
    val reask =
        org.tatrman.kantheon.iris.action
            .ReaskHandler(
                org.tatrman.kantheon.iris.domain
                    .InMemoryFeedbackStore(),
                InMemoryAuditStore(Ed25519Signer()),
            )
    val escalation =
        org.tatrman.kantheon.iris.action
            .EscalationHandler(InMemoryAuditStore(Ed25519Signer()))
    routing {
        sessionRoutes(store, BearerAuthenticator())
        chatRoutes(store, BearerAuthenticator(), dispatcher)
        actionRoutes(store, BearerAuthenticator(), dispatcher, typedActions, reask, escalation)
    }
}

private suspend fun io.ktor.client.HttpClient.newSession(user: String): String {
    val res = post("/v1/session") { header(HttpHeaders.Authorization, bearer(user)) }
    return json
        .parseToJsonElement(res.bodyAsText())
        .jsonObject["sessionId"]!!
        .jsonPrimitive.content
}

/** Drive one normal turn so there is a turn to edit; returns its turnId. */
private suspend fun io.ktor.client.HttpClient.firstTurn(
    sid: String,
    user: String,
    question: String,
): String {
    val res =
        post("/v1/chat/turn") {
            header(HttpHeaders.Authorization, bearer(user))
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"$sid","question":"$question"}""")
        }
    return json
        .parseToJsonElement(res.bodyAsText())
        .jsonObject["turnId"]!!
        .jsonPrimitive.content
}

class ActionRoutesSpec :
    StringSpec({

        "edit_resend discards the turns after the anchor and re-runs the edited question" {
            val store = InMemorySessionStore()
            testApplication {
                application { mount(store, FakeGolemV2Client()) }
                val sid = client.newSession("u1")
                val t1 = client.firstTurn(sid, "u1", "kolik mam trzeb v lednu?")
                client.firstTurn(sid, "u1", "a v unoru?") // the turn we will discard

                val sessionUuid = UUID.fromString(sid)
                store.getTurns(sessionUuid).size shouldBe 2

                val body =
                    client
                        .post("/v1/action") {
                            header(HttpHeaders.Authorization, bearer("u1"))
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"sessionId":"$sid","bubbleId":"b1","action":{"kind":"edit_resend",""" +
                                    """"payloadJson":"{\"editedQuestion\":\"kolik mam trzeb v breznu?\",""" +
                                    """\"fromTurnId\":\"$t1\"}"}}""",
                            )
                        }.bodyAsText()

                body shouldContain "event: envelope"
                body shouldContain "event: done"
                // t1 kept; the February turn discarded; a fresh March turn appended.
                val visible = store.getTurns(sessionUuid)
                visible.map { it.question } shouldBe listOf("kolik mam trzeb v lednu?", "kolik mam trzeb v breznu?")
                // a snapshot with reason edit_resend was taken
                store.snapshots(sessionUuid).any { it.reason == "edit_resend" } shouldBe true
            }
        }

        "an unknown action kind streams a terminal NOT_IMPLEMENTED error" {
            val store = InMemorySessionStore()
            testApplication {
                application { mount(store, FakeGolemV2Client()) }
                val sid = client.newSession("u1")
                val body =
                    client
                        .post("/v1/action") {
                            header(HttpHeaders.Authorization, bearer("u1"))
                            contentType(ContentType.Application.Json)
                            setBody("""{"sessionId":"$sid","action":{"kind":"bogus_kind","payloadJson":"{}"}}""")
                        }.bodyAsText()
                body shouldContain "event: error"
                body shouldContain "NOT_IMPLEMENTED"
                body shouldContain "event: done"
            }
        }

        "edit_resend with a malformed payload is a 400" {
            val store = InMemorySessionStore()
            testApplication {
                application { mount(store, FakeGolemV2Client()) }
                val sid = client.newSession("u1")
                client
                    .post("/v1/action") {
                        header(HttpHeaders.Authorization, bearer("u1"))
                        contentType(ContentType.Application.Json)
                        setBody("""{"sessionId":"$sid","action":{"kind":"edit_resend","payloadJson":"{}"}}""")
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        "an action on a session owned by another user is a 404" {
            val store = InMemorySessionStore()
            testApplication {
                application { mount(store, FakeGolemV2Client()) }
                val sid = client.newSession("u1")
                client
                    .post("/v1/action") {
                        header(HttpHeaders.Authorization, bearer("intruder"))
                        contentType(ContentType.Application.Json)
                        setBody("""{"sessionId":"$sid","action":{"kind":"edit_resend","payloadJson":"{}"}}""")
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        "the discarded turn survives for undo (status-flipped, not deleted)" {
            val store = InMemorySessionStore()
            testApplication {
                application { mount(store, FakeGolemV2Client()) }
                val sid = client.newSession("u1")
                val t1 = client.firstTurn(sid, "u1", "first")
                client.firstTurn(sid, "u1", "second")
                client.post("/v1/action") {
                    header(HttpHeaders.Authorization, bearer("u1"))
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"sessionId":"$sid","action":{"kind":"edit_resend",""" +
                            """"payloadJson":"{\"editedQuestion\":\"second-edited\",\"fromTurnId\":\"$t1\"}"}}""",
                    )
                }
                val sessionUuid = UUID.fromString(sid)
                store.getTurns(sessionUuid, includeDiscarded = true).any {
                    it.question == "second" && it.status == TurnStatus.DISCARDED
                } shouldBe true
            }
        }
    })
