package org.tatrman.kantheon.iris.api

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.delete
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
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.envelope.v1.CurrentView
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.iris.artifact.ArtifactExecutor
import org.tatrman.kantheon.iris.artifact.ArtifactService
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.domain.ArtifactRecord
import org.tatrman.kantheon.iris.domain.InMemoryArtifactStore
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

private val printer = JsonFormat.printer().omittingInsignificantWhitespace()
private val json = Json { ignoreUnknownKeys = true }

/** Executor that returns a fresh single-row envelope (drives the refresh path). */
private class StubExecutor : ArtifactExecutor {
    override suspend fun reexecute(
        caller: CallerIdentity,
        artifact: ArtifactRecord,
    ): String =
        printer.print(
            FormatEnvelope
                .newBuilder()
                .setBubbleId("b-1")
                .setContentJson("""[{"m":"Refreshed","r":99}]""")
                .build(),
        )
}

/** Seed a DONE table turn (with current_view provenance) and return its turn id. */
private fun seedTurn(
    store: InMemorySessionStore,
    userId: String,
): UUID {
    val session = store.createSession(userId, "t1")
    val env =
        FormatEnvelope
            .newBuilder()
            .setBubbleId("b-1")
            .setContentJson("""[{"m":"Jan","r":10}]""")
            .setAgentId("golem-erp")
            .setCurrentView(CurrentView.newBuilder().setPatternId("rev").setBubbleId("b-1"))
            .build()
    return store
        .appendTurn(
            NewTurn(
                sessionId = session.sessionId,
                agentId = "golem-erp",
                question = "tržby",
                status = TurnStatus.DONE,
                envelopeJson = printer.print(env),
                displayedBlockIds = listOf("b-1"),
            ),
        ).turnId
}

private fun Application.mountArtifacts(store: InMemorySessionStore) {
    installKtorServerBase(KtorServerConfig(serviceName = "iris-bff-test", serverPort = 0))
    installErrorPages()
    val artifacts = InMemoryArtifactStore()
    val service = ArtifactService(artifacts, StubExecutor(), InMemoryAuditStore(Ed25519Signer()))
    routing {
        artifactRoutes(store, artifacts, service, BearerAuthenticator())
    }
}

private suspend fun io.ktor.client.HttpClient.pin(
    user: String,
    turnId: UUID,
) = post("/v1/artifacts") {
    header(HttpHeaders.Authorization, bearer(user))
    contentType(ContentType.Application.Json)
    setBody("""{"kind":"pin","turnId":"$turnId","bubbleId":"b-1","name":"Revenue"}""")
}

class ArtifactRoutesSpec :
    StringSpec({

        "POST pin captures the turn's bubble and returns 201 with provenance" {
            testApplication {
                val store = InMemorySessionStore()
                application { mountArtifacts(store) }
                val turnId = seedTurn(store, "u1")

                val res = client.pin("u1", turnId)
                res.status shouldBe HttpStatusCode.Created
                val dto = json.parseToJsonElement(res.bodyAsText()).jsonObject
                dto["kind"]!!.jsonPrimitive.content shouldBe "pin"
                dto["agentId"]!!.jsonPrimitive.content shouldBe "golem-erp"
                dto["provenance"]!!.jsonObject["patternId"]!!.jsonPrimitive.content shouldBe "rev"
            }
        }

        "pin of an unknown turn is 404; bad payload is 400" {
            testApplication {
                val store = InMemorySessionStore()
                application { mountArtifacts(store) }
                client.pin("u1", UUID.randomUUID()).status shouldBe HttpStatusCode.NotFound
                client
                    .post("/v1/artifacts") {
                        header(HttpHeaders.Authorization, bearer("u1"))
                        contentType(ContentType.Application.Json)
                        setBody("""{"kind":"pin","name":"x"}""")
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        "list, get, patch, delete round-trip; non-owner cannot see a pin" {
            testApplication {
                val store = InMemorySessionStore()
                application { mountArtifacts(store) }
                val turnId = seedTurn(store, "u1")
                val id =
                    json
                        .parseToJsonElement(client.pin("u1", turnId).bodyAsText())
                        .jsonObject["artifactId"]!!
                        .jsonPrimitive.content

                // list
                client
                    .get("/v1/artifacts") { header(HttpHeaders.Authorization, bearer("u1")) }
                    .bodyAsText() shouldContain id
                // non-owner 404 on get
                client
                    .get("/v1/artifacts/$id") { header(HttpHeaders.Authorization, bearer("intruder")) }
                    .status shouldBe HttpStatusCode.NotFound
                // patch rename
                val patched =
                    client.patch("/v1/artifacts/$id") {
                        header(HttpHeaders.Authorization, bearer("u1"))
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"Renamed"}""")
                    }
                json
                    .parseToJsonElement(patched.bodyAsText())
                    .jsonObject["name"]!!
                    .jsonPrimitive.content shouldBe "Renamed"
                // delete
                client
                    .delete("/v1/artifacts/$id") { header(HttpHeaders.Authorization, bearer("u1")) }
                    .status shouldBe HttpStatusCode.NoContent
                client
                    .get("/v1/artifacts/$id") { header(HttpHeaders.Authorization, bearer("u1")) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        "refresh re-executes and updates refreshed_at" {
            testApplication {
                val store = InMemorySessionStore()
                application { mountArtifacts(store) }
                val turnId = seedTurn(store, "u1")
                val id =
                    json
                        .parseToJsonElement(client.pin("u1", turnId).bodyAsText())
                        .jsonObject["artifactId"]!!
                        .jsonPrimitive.content

                val res =
                    client.post("/v1/artifacts/$id/refresh") { header(HttpHeaders.Authorization, bearer("u1")) }
                res.status shouldBe HttpStatusCode.OK
                val dto = json.parseToJsonElement(res.bodyAsText()).jsonObject
                dto["refreshedAt"]!!.jsonPrimitive.content.isNotBlank() shouldBe true
                dto["envelope"]!!.jsonObject["contentJson"]!!.jsonPrimitive.content shouldContain "Refreshed"
            }
        }

        "dashboard create + open streams a frame per member pin" {
            testApplication {
                val store = InMemorySessionStore()
                application { mountArtifacts(store) }
                val turnId = seedTurn(store, "u1")
                val pinId =
                    json
                        .parseToJsonElement(client.pin("u1", turnId).bodyAsText())
                        .jsonObject["artifactId"]!!
                        .jsonPrimitive.content

                val dash =
                    client.post("/v1/artifacts") {
                        header(HttpHeaders.Authorization, bearer("u1"))
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"kind":"dashboard","name":"Board","memberIds":["$pinId"],"refreshMode":"on_open"}""",
                        )
                    }
                dash.status shouldBe HttpStatusCode.Created
                val dashId =
                    json
                        .parseToJsonElement(dash.bodyAsText())
                        .jsonObject["artifactId"]!!
                        .jsonPrimitive.content

                val body =
                    client
                        .get("/v1/dashboards/$dashId/open") { header(HttpHeaders.Authorization, bearer("u1")) }
                        .bodyAsText()
                body shouldContain "event: pin"
                body shouldContain "event: done"
                body shouldContain "Refreshed" // on_open refreshed the member pin
            }
        }
    })
