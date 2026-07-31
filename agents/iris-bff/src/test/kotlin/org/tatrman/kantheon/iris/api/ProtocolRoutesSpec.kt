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
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.NewTurn
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.installErrorPages
import org.tatrman.kantheon.iris.protocol.FixtureLoader
import org.tatrman.kantheon.iris.protocol.assemble.ProtocolAssembler
import org.tatrman.kantheon.iris.protocol.record.InMemoryProtocolRecordStore
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import java.time.Instant
import java.util.Base64
import java.util.UUID

private fun bearer(sub: String): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$sub"}""".toByteArray())
    return "Bearer header.$payload.sig"
}

/**
 * `POST /v1/session/{id}/protocol` (contracts §3.1) through the wire.
 *
 * The status ladder is contractual and is asserted in order: **404 before 403
 * before 400**. A non-member must not be able to distinguish "your scope was
 * malformed" from "that session exists" — so authorization resolves before the
 * body is even looked at.
 *
 * The other load-bearing case is that **a degraded document is still a 200**
 * (P-4): degradation lives inside the document, not in the status line.
 */
class ProtocolRoutesSpec :
    StringSpec({

        val owner = "maya"
        val json = Json { ignoreUnknownKeys = true }

        class Harness {
            val store = InMemorySessionStore()
            val records = InMemoryProtocolRecordStore()
            val session = store.createSession(owner, "hartland")

            init {
                // Three turns, so lastN has something to slice.
                listOf("first?", "second?", "third?").forEach { q ->
                    store.appendTurn(
                        NewTurn(
                            sessionId = session.sessionId,
                            agentId = "golem-finance",
                            question = q,
                            status = TurnStatus.DONE,
                        ),
                    )
                }
            }

            /** No source clients at all — every source is skipped-by-config. */
            fun assembler() =
                ProtocolAssembler(
                    records = records,
                    config = FixtureLoader.config("H1-full"),
                    gateway = null,
                    loki = null,
                    tempo = null,
                    explain = null,
                    clock = { Instant.parse("2026-07-30T07:05:00Z") },
                    ids = { UUID.fromString("00000000-0000-4000-8000-0000000000ff") },
                )
        }

        fun Application.testModule(h: Harness) {
            installKtorServerBase(KtorServerConfig(serviceName = "iris-bff-test", serverPort = 0))
            installErrorPages()
            routing { protocolRoutes(h.store, BearerAuthenticator(), h.assembler()) }
        }

        suspend fun io.ktor.client.HttpClient.protocol(
            sessionId: UUID,
            body: String,
            who: String = owner,
        ) = post("/v1/session/$sessionId/protocol") {
            header(HttpHeaders.Authorization, bearer(who))
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        "last scope returns 200 with protocolId and a markdown envelope" {
            val h = Harness()
            testApplication {
                application { testModule(h) }
                val res = client.protocol(h.session.sessionId, """{"scope":"last"}""")

                res.status shouldBe HttpStatusCode.OK
                val body = json.parseToJsonElement(res.bodyAsText()).jsonObject
                body["protocolId"]!!.jsonPrimitive.content shouldBe "00000000-0000-4000-8000-0000000000ff"

                val envelope = body["envelope"]!!.jsonObject
                envelope["format"]!!.jsonObject["kind"]!!.jsonPrimitive.content shouldBe "MARKDOWN"
                // The rendered document, not a stub.
                envelope["text"]!!.jsonPrimitive.content shouldContain "## Receipts"
                // S-8 title.
                body["title"]!!.jsonPrimitive.content shouldContain "Protocol —"
            }
        }

        "session scope returns 200 and covers every turn" {
            val h = Harness()
            testApplication {
                application { testModule(h) }
                val res = client.protocol(h.session.sessionId, """{"scope":"session"}""")

                res.status shouldBe HttpStatusCode.OK
                val text =
                    json
                        .parseToJsonElement(res.bodyAsText())
                        .jsonObject["envelope"]!!
                        .jsonObject["text"]!!
                        .jsonPrimitive.content
                text shouldContain "Turn 1"
                text shouldContain "Turn 3"
            }
        }

        "lastN=2 returns 200 and narrows the document to the last two turns" {
            val h = Harness()
            testApplication {
                application { testModule(h) }
                val res = client.protocol(h.session.sessionId, """{"scope":{"lastN":2}}""")

                res.status shouldBe HttpStatusCode.OK
                val text =
                    json
                        .parseToJsonElement(res.bodyAsText())
                        .jsonObject["envelope"]!!
                        .jsonObject["text"]!!
                        .jsonPrimitive.content
                text shouldContain "Turn 2"
                text shouldContain "Turn 3"
                // The scope really narrowed — turn 1 is not in the document.
                (text.contains("## Turn 1 ")) shouldBe false
            }
        }

        listOf(
            """{"scope":{"lastN":0}}""" to "lastN=0",
            """{"scope":{"lastN":-1}}""" to "lastN=-1",
            """{"scope":"everything"}""" to "unknown string scope",
            """{"scope":42}""" to "numeric scope",
            """{"nope":"last"}""" to "missing scope key",
            """not json at all""" to "unparseable body",
        ).forEach { (body, label) ->
            "$label returns 400" {
                val h = Harness()
                testApplication {
                    application { testModule(h) }
                    val res = client.protocol(h.session.sessionId, body)

                    res.status shouldBe HttpStatusCode.BadRequest
                    res.bodyAsText() shouldContain "invalid_scope"
                }
            }
        }

        "unknown session returns 404" {
            val h = Harness()
            testApplication {
                application { testModule(h) }
                client.protocol(UUID.randomUUID(), """{"scope":"last"}""").status shouldBe HttpStatusCode.NotFound
            }
        }

        "someone else's session is 404, indistinguishable from one that does not exist" {
            // A-6 / review-079 R11. A 403 would say "this exists and is not yours",
            // which turns the endpoint into an existence oracle for any session id a
            // caller can guess or overhear. The two answers must be byte-identical.
            val h = Harness()
            testApplication {
                application { testModule(h) }
                val theirs = client.protocol(h.session.sessionId, """{"scope":"last"}""", who = "someone-else")
                val nothing = client.protocol(UUID.randomUUID(), """{"scope":"last"}""", who = "someone-else")

                theirs.status shouldBe HttpStatusCode.NotFound
                nothing.status shouldBe HttpStatusCode.NotFound
                theirs.bodyAsText() shouldBe nothing.bodyAsText()
            }
        }

        "an outsider gets 404 even with a malformed scope — authorization resolves first" {
            // Otherwise the status line becomes an oracle the other way round: a 400
            // would tell an outsider the session exists before they ever named a scope.
            val h = Harness()
            testApplication {
                application { testModule(h) }
                client
                    .protocol(h.session.sessionId, """{"scope":"garbage"}""", who = "someone-else")
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        "missing bearer returns 401" {
            val h = Harness()
            testApplication {
                application { testModule(h) }
                val res =
                    client.post("/v1/session/${h.session.sessionId}/protocol") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"scope":"last"}""")
                    }
                res.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "assembler degrade still returns 200 — degradation lives inside the document (P-4)" {
            val h = Harness()
            testApplication {
                application { testModule(h) }
                // No sources are configured at all, so every one is skipped/degraded.
                val res = client.protocol(h.session.sessionId, """{"scope":"last"}""")

                res.status shouldBe HttpStatusCode.OK
                val text =
                    json
                        .parseToJsonElement(res.bodyAsText())
                        .jsonObject["envelope"]!!
                        .jsonObject["text"]!!
                        .jsonPrimitive.content
                // ...and the reader is told, in the receipts, rather than left guessing.
                text shouldContain "## Receipts"
                text shouldContain "records"
            }
        }
    })
