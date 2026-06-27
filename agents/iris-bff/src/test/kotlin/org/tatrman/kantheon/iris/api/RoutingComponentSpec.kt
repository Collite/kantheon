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
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.installErrorPages
import org.tatrman.kantheon.iris.routing.AgentLabels
import org.tatrman.kantheon.iris.routing.FakeThemisClient
import org.tatrman.kantheon.iris.routing.RoutingEnvelopes
import org.tatrman.kantheon.iris.routing.ThemisClient
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import java.util.Base64
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }

private fun bearer(sub: String): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$sub"}""".toByteArray())
    return "Bearer h.$payload.s"
}

private fun Application.mountRouting(
    store: InMemorySessionStore,
    golem: FakeGolemV2Client,
    audit: InMemoryAuditStore,
    themis: ThemisClient,
) {
    installKtorServerBase(KtorServerConfig(serviceName = "iris-bff-test", serverPort = 0))
    installErrorPages()
    val agents =
        AgentDispatcher(
            mapOf(
                "golem-v2" to
                    GolemV2AgentClient(
                        store,
                        golem,
                        org.tatrman.kantheon.iris.stream
                            .IrisStreamMux(),
                    ),
            ),
        )
    val dispatcher = ChatDispatcher(store, themis, agents, audit, RoutingEnvelopes(AgentLabels.IDENTITY))
    routing {
        sessionRoutes(store, BearerAuthenticator())
        chatRoutes(store, BearerAuthenticator(), dispatcher)
    }
}

private suspend fun io.ktor.client.HttpClient.newSession(user: String): String {
    val res = post("/v1/session") { header(HttpHeaders.Authorization, bearer(user)) }
    return json
        .parseToJsonElement(res.bodyAsText())
        .jsonObject["sessionId"]!!
        .jsonPrimitive.content
}

class RoutingComponentSpec :
    StringSpec({

        "resolution → dispatch → envelope → done, with a RoutingDecision audit row" {
            val store = InMemorySessionStore()
            val golem = FakeGolemV2Client()
            val audit = InMemoryAuditStore(Ed25519Signer())
            testApplication {
                application { mountRouting(store, golem, audit, FakeThemisClient()) }
                val sid = client.newSession("u1")

                val body =
                    client
                        .post("/v1/chat/stream") {
                            header(HttpHeaders.Authorization, bearer("u1"))
                            contentType(ContentType.Application.Json)
                            setBody("""{"sessionId":"$sid","question":"kolik tržeb?"}""")
                        }.bodyAsText()

                // SSE ordering: step → envelope → done.
                body shouldContain "event: step"
                body shouldContain "event: envelope"
                body shouldContain "event: done"
                (body.indexOf("event: envelope") < body.indexOf("event: done")) shouldBe true

                val turn = store.getTurns(UUID.fromString(sid)).single()
                turn.agentId shouldBe "golem-v2"
                turn.status shouldBe TurnStatus.DONE

                val payload =
                    json
                        .parseToJsonElement(audit.all().first { it.eventKind == "turn" }.payloadJson)
                        .jsonObject
                payload["routingChosenAgentId"]!!.jsonPrimitive.content shouldBe "golem-v2"
            }
        }

        "needs_user_pick → RoutingPickChips → chip-click reissue dispatches to the picked agent" {
            val store = InMemorySessionStore()
            val golem = FakeGolemV2Client()
            val audit = InMemoryAuditStore(Ed25519Signer())
            // First turn (no hint) → ambiguous; a hinted reissue → Layer-0 dispatch.
            val themis =
                FakeThemisClient(
                    responder = { req ->
                        if (req.hasRoutingHint() && req.routingHint.value.isNotEmpty()) {
                            FakeThemisClient.resolutionTo(req.routingHint.value, layer = 0)
                        } else {
                            FakeThemisClient.needsUserPick(listOf("pythia" to "deep", "golem-v2" to "facts"))
                        }
                    },
                )
            testApplication {
                application { mountRouting(store, golem, audit, themis) }
                val sid = client.newSession("u1")

                // Turn 1 → chips, no agent call.
                val first =
                    client
                        .post("/v1/chat/stream") {
                            header(HttpHeaders.Authorization, bearer("u1"))
                            contentType(ContentType.Application.Json)
                            setBody("""{"sessionId":"$sid","question":"proč klesly tržby?"}""")
                        }.bodyAsText()
                first shouldContain "event: envelope"
                first shouldContain "routing"
                first shouldContain "golem-v2"
                golem.createdThreads.size shouldBe 0

                // Turn 2 → chip click reissue with routingHintAgentId → dispatch.
                val second =
                    client
                        .post("/v1/chat/stream") {
                            header(HttpHeaders.Authorization, bearer("u1"))
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"sessionId":"$sid","question":"proč klesly tržby?","routingHintAgentId":"golem-v2"}""",
                            )
                        }.bodyAsText()
                second shouldContain "event: step"
                second shouldContain "event: done"
                golem.createdThreads.size shouldBe 1

                val turns = store.getTurns(UUID.fromString(sid))
                turns.size shouldBe 2
                turns[0].alternatesOffered shouldBe listOf("pythia", "golem-v2")
                turns[1].agentId shouldBe "golem-v2"
            }
        }

        "RefusalWithGaps renders an error envelope over SSE and persists a FAILED turn" {
            val store = InMemorySessionStore()
            val golem = FakeGolemV2Client()
            val audit = InMemoryAuditStore(Ed25519Signer())
            val themis =
                FakeThemisClient(
                    responder = {
                        FakeThemisClient.refusal(
                            listOf(
                                org.tatrman.kantheon.themis.v1.Themis.GapKind.NO_ENTITLED_AGENT to
                                    "HR exists; access denied.",
                            ),
                        )
                    },
                )
            testApplication {
                application { mountRouting(store, golem, audit, themis) }
                val sid = client.newSession("u1")
                val body =
                    client
                        .post("/v1/chat/stream") {
                            header(HttpHeaders.Authorization, bearer("u1"))
                            contentType(ContentType.Application.Json)
                            setBody("""{"sessionId":"$sid","question":"HR platy"}""")
                        }.bodyAsText()
                body shouldContain "NO_ENTITLED_AGENT"
                body shouldContain "event: done"
                golem.createdThreads.size shouldBe 0
                store.getTurns(UUID.fromString(sid)).single().status shouldBe TurnStatus.FAILED
            }
        }
    })
