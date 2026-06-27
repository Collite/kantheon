package org.tatrman.kantheon.iris.api

import com.google.protobuf.util.JsonFormat
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.envelope.v1.TableDetails
import org.tatrman.kantheon.envelope.v1.TableHeader
import org.tatrman.kantheon.envelope.v1.TablePagingInfo
import org.tatrman.kantheon.iris.action.TypedActionDispatcher
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.dispatch.AgentDispatcher
import org.tatrman.kantheon.iris.dispatch.golemv2.FakeGolemV2Client
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2AgentClient
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.NewTurn
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

private val printer = JsonFormat.printer().omittingInsignificantWhitespace()

/** Seed a DONE turn whose envelope is a table of `rows` with the given bubble id. */
private fun seedTableTurn(
    store: InMemorySessionStore,
    userId: String,
    bubbleId: String,
    rows: List<Pair<String, Int>>,
    totalRows: Long = rows.size.toLong(),
    withDrilldown: Boolean = false,
): UUID {
    val session = store.createSession(userId, "t1")
    val content = rows.joinToString(prefix = "[", postfix = "]", separator = ",") { (m, r) -> """{"m":"$m","r":$r}""" }
    val builder =
        FormatEnvelope
            .newBuilder()
            .setBubbleId(bubbleId)
            .setTurnId("v2t-1")
            .setThreadId(session.sessionId.toString())
            .setContentJson(content)
    if (withDrilldown) {
        builder.addDrilldowns(
            org.tatrman.kantheon.envelope.v1.Drilldown
                .newBuilder()
                .setId("d-1")
                .setDisplay("Detail za měsíc")
                .setTargetPatternId("revenue-detail")
                .putArgMapping("month", "m")
                .setScope("row"),
        )
    }
    val env =
        builder
            .setFormat(
                FormatSpec
                    .newBuilder()
                    .setKind(FormatKind.TABLE)
                    .setTable(
                        TableDetails
                            .newBuilder()
                            .addHeaders(TableHeader.newBuilder().setName("m").setTitle("Měsíc"))
                            .addHeaders(TableHeader.newBuilder().setName("r").setTitle("Tržby"))
                            .setPaging(
                                TablePagingInfo
                                    .newBuilder()
                                    .setPage(1)
                                    .setPageSize(50)
                                    .setTotalRows(totalRows),
                            ),
                    ),
            ).setAgentId("golem-v2")
            .build()
    store.appendTurn(
        NewTurn(
            sessionId = session.sessionId,
            agentId = "golem-v2",
            question = "tržby",
            status = TurnStatus.DONE,
            envelopeJson = printer.print(env),
            displayedBlockIds = listOf(bubbleId),
        ),
    )
    return session.sessionId
}

private fun Application.mountActions(
    store: InMemorySessionStore,
    golem: FakeGolemV2Client,
    feedback: org.tatrman.kantheon.iris.domain.FeedbackStore =
        org.tatrman.kantheon.iris.domain
            .InMemoryFeedbackStore(),
    escalationAudit: InMemoryAuditStore = InMemoryAuditStore(Ed25519Signer()),
) {
    installKtorServerBase(KtorServerConfig(serviceName = "iris-bff-test", serverPort = 0))
    installErrorPages()
    val agents = AgentDispatcher(mapOf("golem-v2" to GolemV2AgentClient(store, golem, IrisStreamMux())))
    val chat =
        ChatDispatcher(
            store,
            FakeThemisClient(),
            agents,
            InMemoryAuditStore(Ed25519Signer()),
            RoutingEnvelopes(AgentLabels.IDENTITY),
        )
    val typed = TypedActionDispatcher(store, golem, InMemoryAuditStore(Ed25519Signer()))
    val reask =
        org.tatrman.kantheon.iris.action
            .ReaskHandler(feedback, InMemoryAuditStore(Ed25519Signer()))
    val escalation =
        org.tatrman.kantheon.iris.action
            .EscalationHandler(escalationAudit)
    routing {
        sessionRoutes(store, BearerAuthenticator())
        actionRoutes(store, BearerAuthenticator(), chat, typed, reask, escalation)
    }
}

private suspend fun io.ktor.client.HttpClient.action(
    user: String,
    sid: UUID,
    bubbleId: String?,
    kind: String,
    payload: String,
) = post("/v1/action") {
    header(HttpHeaders.Authorization, bearer(user))
    contentType(ContentType.Application.Json)
    val bubble = if (bubbleId != null) ""","bubbleId":"$bubbleId"""" else ""
    setBody("""{"sessionId":"$sid"$bubble,"action":{"kind":"$kind","payloadJson":${jsonStr(payload)}}}""")
}

/** Embed a JSON object as a JSON string value (payloadJson is Rule-7 stringly). */
private fun jsonStr(raw: String): String = "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private val sseJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** Parse the rows of the first `event: envelope` frame's envelope.contentJson. */
private fun envelopeRows(body: String): List<kotlinx.serialization.json.JsonObject> {
    val dataLine =
        body
            .lineSequence()
            .dropWhile { it != "event: envelope" }
            .first { it.startsWith("data:") }
            .removePrefix("data:")
            .trim()
    val event = sseJson.parseToJsonElement(dataLine).jsonObject
    val content =
        event["envelope"]!!
            .jsonObject["contentJson"]!!
            .jsonPrimitive.content
    return sseJson.parseToJsonElement(content).jsonArray.map { it.jsonObject }
}

private fun kotlinx.serialization.json.JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

class TypedActionRoutesSpec :
    StringSpec({

        "sort emits a replacing envelope with the same bubble_id, reshaped rows" {
            val store = InMemorySessionStore()
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120, "2026-02" to 98, "2026-03" to 140))
            testApplication {
                application { mountActions(store, FakeGolemV2Client()) }
                val body =
                    client.action("u1", sid, "b-1", "sort", """{"column":"r","direction":"desc"}""").bodyAsText()
                body shouldContain "event: envelope"
                body shouldContain "b-1"
                body shouldContain "event: done"
                // desc by r → 140, 120, 98.
                envelopeRows(body).map { it.str("r") } shouldBe listOf("140", "120", "98")
                // current_display persisted.
                store.getSession(sid)!!.currentDisplayJson shouldContain "\"sort\""
            }
        }

        "filter narrows the cached rows" {
            val store = InMemorySessionStore()
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120, "2026-02" to 98, "2026-03" to 140))
            testApplication {
                application { mountActions(store, FakeGolemV2Client()) }
                val body =
                    client
                        .action(
                            "u1",
                            sid,
                            "b-1",
                            "filter",
                            """{"column":"r","operator":"gte","value":120}""",
                        ).bodyAsText()
                val months = envelopeRows(body).map { it.str("m") }
                months shouldBe listOf("2026-01", "2026-03")
            }
        }

        "paginate within the cached page slices BFF-side (no refetch)" {
            val store = InMemorySessionStore()
            val golem = FakeGolemV2Client()
            val sid =
                seedTableTurn(
                    store,
                    "u1",
                    "b-1",
                    listOf("2026-01" to 120, "2026-02" to 98, "2026-03" to 140, "2026-04" to 110),
                )
            testApplication {
                application { mountActions(store, golem) }
                val body =
                    client.action("u1", sid, "b-1", "paginate", """{"page":2,"pageSize":2}""").bodyAsText()
                envelopeRows(body).map { it.str("m") } shouldBe listOf("2026-03", "2026-04")
                golem.reissuedActions.size shouldBe 0
            }
        }

        "paginate beyond the cached page (more rows exist) refetches via the agent" {
            val store = InMemorySessionStore()
            val golem = FakeGolemV2Client()
            // 2 cached rows but total 10 → page 3 (rows 5-6) is beyond cache.
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120, "2026-02" to 98), totalRows = 10)
            store.putV2Thread(sid, sid.toString())
            testApplication {
                application { mountActions(store, golem) }
                val body =
                    client.action("u1", sid, "b-1", "paginate", """{"page":3,"pageSize":2}""").bodyAsText()
                golem.reissuedActions.size shouldBe 1
                golem.reissuedActions.single().kind shouldBe "paginate"
                body shouldContain "event: envelope"
            }
        }

        "a bad payload is a 400" {
            val store = InMemorySessionStore()
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120))
            testApplication {
                application { mountActions(store, FakeGolemV2Client()) }
                client.action("u1", sid, "b-1", "sort", """{"nope":true}""").status shouldBe HttpStatusCode.BadRequest
            }
        }

        "a missing bubbleId is a 400" {
            val store = InMemorySessionStore()
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120))
            testApplication {
                application { mountActions(store, FakeGolemV2Client()) }
                client.action("u1", sid, null, "sort", """{"column":"r","direction":"asc"}""").status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        "an action on another user's session is a 404" {
            val store = InMemorySessionStore()
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120))
            testApplication {
                application { mountActions(store, FakeGolemV2Client()) }
                client.action("intruder", sid, "b-1", "sort", """{"column":"r","direction":"asc"}""").status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        "reask_agent re-issues the turn with the hint and upserts corrected_agent_id" {
            val store = InMemorySessionStore()
            val feedback =
                org.tatrman.kantheon.iris.domain
                    .InMemoryFeedbackStore()
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120))
            val turnId = store.getTurns(sid).single().turnId
            testApplication {
                application { mountActions(store, FakeGolemV2Client(), feedback) }
                val body =
                    client
                        .action(
                            "u1",
                            sid,
                            null,
                            "reask_agent",
                            """{"turnId":"$turnId","targetAgentId":"golem-v2"}""",
                        ).bodyAsText()
                body shouldContain "event: done"
                feedback.get(turnId, "u1")!!.correctedAgentId shouldBe "golem-v2"
                feedback.get(turnId, "u1")!!.verdict shouldBe "down"
                feedback.get(turnId, "u1")!!.reason shouldBe "wrong_agent"
            }
        }

        "reask_agent for an unknown turn is a 404" {
            val store = InMemorySessionStore()
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120))
            testApplication {
                application { mountActions(store, FakeGolemV2Client()) }
                client
                    .action(
                        "u1",
                        sid,
                        null,
                        "reask_agent",
                        """{"turnId":"${UUID.randomUUID()}","targetAgentId":"golem-v2"}""",
                    ).status shouldBe HttpStatusCode.NotFound
            }
        }

        "chip_invocation re-submits the prompt as a normal turn" {
            val store = InMemorySessionStore()
            val golem = FakeGolemV2Client()
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120))
            testApplication {
                application { mountActions(store, golem) }
                val body =
                    client
                        .action(
                            "u1",
                            sid,
                            null,
                            "chip_invocation",
                            """{"prompt":"Kolik jsme prodali?","patternId":"revenue"}""",
                        ).bodyAsText()
                body shouldContain "event: envelope"
                body shouldContain "event: done"
                // It dispatched a real turn to the agent (golem thread opened).
                golem.createdThreads.size shouldBe 1
            }
        }

        "chip_invocation with a blank prompt is a 400" {
            val store = InMemorySessionStore()
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120))
            testApplication {
                application { mountActions(store, FakeGolemV2Client()) }
                client.action("u1", sid, null, "chip_invocation", """{"prompt":""}""").status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        "select_row drills down via the agent and streams a NEW bubble + persists a new turn" {
            val store = InMemorySessionStore()
            val golem = FakeGolemV2Client()
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120, "2026-02" to 98), withDrilldown = true)
            store.putV2Thread(sid, sid.toString())
            testApplication {
                application { mountActions(store, golem) }
                val body = client.action("u1", sid, "b-1", "select_row", """{"rowIndex":0}""").bodyAsText()
                body shouldContain "event: envelope"
                body shouldContain "b-drill"
                // The agent was re-issued the drilldown with the row's mapped args.
                golem.reissuedActions.size shouldBe 1
                golem.reissuedActions.single().kind shouldBe "select_row"
                golem.reissuedActions.single().payload_json shouldContain "revenue-detail"
                golem.reissuedActions.single().payload_json shouldContain "2026-01"
                // The drilldown opens a NEW view → a second turn is persisted.
                store.getTurns(sid).size shouldBe 2
            }
        }

        "select_row with no drilldown on the bubble streams a NO_DRILLDOWN error" {
            val store = InMemorySessionStore()
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120))
            testApplication {
                application { mountActions(store, FakeGolemV2Client()) }
                val body = client.action("u1", sid, "b-1", "select_row", """{"rowIndex":0}""").bodyAsText()
                body shouldContain "NO_DRILLDOWN"
                body shouldContain "event: done"
            }
        }

        "select_row with a bad payload is a 400" {
            val store = InMemorySessionStore()
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120), withDrilldown = true)
            testApplication {
                application { mountActions(store, FakeGolemV2Client()) }
                client.action("u1", sid, "b-1", "select_row", """{"nope":1}""").status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        "investigate escalates to pythia (routing_hint), audits, and returns NO_AGENT_CLIENT" {
            val store = InMemorySessionStore()
            val escAudit = InMemoryAuditStore(Ed25519Signer())
            val sid = seedTableTurn(store, "u1", "b-1", listOf("2026-01" to 120))
            val turnId = store.getTurns(sid).single().turnId
            testApplication {
                application { mountActions(store, FakeGolemV2Client(), escalationAudit = escAudit) }
                val body =
                    client
                        .action(
                            "u1",
                            sid,
                            null,
                            "investigate",
                            """{"turnId":"$turnId","proposedQuestion":"why did revenue drop?"}""",
                        ).bodyAsText()
                // pythia has no registered client at Phase 3 → a clean no-client error.
                body shouldContain "NO_AGENT_CLIENT"
                body shouldContain "event: done"
                escAudit.all().any { it.eventKind == "escalation" } shouldBe true
            }
        }
    })
