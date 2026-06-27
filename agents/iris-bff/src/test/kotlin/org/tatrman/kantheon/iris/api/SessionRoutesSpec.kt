package org.tatrman.kantheon.iris.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.iris.dispatch.golemv2.FakeGolemV2Client
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2Client
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.installErrorPages
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import java.util.Base64

/** Build a (validate-only) bearer token carrying the given claims. */
private fun bearer(
    sub: String,
    tenant: String? = null,
): String {
    val claims =
        buildString {
            append("{\"sub\":\"$sub\"")
            if (tenant != null) append(",\"tenant\":\"$tenant\"")
            append("}")
        }
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())
    return "Bearer header.$payload.sig"
}

private fun Application.testModule(store: SessionStore) {
    installKtorServerBase(KtorServerConfig(serviceName = "iris-bff-test", serverPort = 0))
    installErrorPages()
    routing {
        healthRoutes { true }
        sessionRoutes(store, BearerAuthenticator())
    }
}

private fun Application.testModuleWithGolem(
    store: SessionStore,
    golem: GolemV2Client,
) {
    installKtorServerBase(KtorServerConfig(serviceName = "iris-bff-test", serverPort = 0))
    installErrorPages()
    routing {
        healthRoutes { true }
        sessionRoutes(store, BearerAuthenticator(), golem)
    }
}

private val json = Json { ignoreUnknownKeys = true }

class SessionRoutesSpec :
    StringSpec({

        "health and ready are UP" {
            testApplication {
                application { testModule(InMemorySessionStore()) }
                client.get("/health").status shouldBe HttpStatusCode.OK
                val ready = client.get("/ready")
                ready.status shouldBe HttpStatusCode.OK
                ready.bodyAsText() shouldContain "UP"
            }
        }

        "POST /v1/session requires a bearer" {
            testApplication {
                application { testModule(InMemorySessionStore()) }
                client.post("/v1/session").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "POST /v1/session creates a session owned by the caller" {
            testApplication {
                application { testModule(InMemorySessionStore()) }
                val res = client.post("/v1/session") { header(HttpHeaders.Authorization, bearer("u1", "acme")) }
                res.status shouldBe HttpStatusCode.Created
                val body = json.parseToJsonElement(res.bodyAsText()).jsonObject
                body["userId"]!!.jsonPrimitive.content shouldBe "u1"
                body["tenantId"]!!.jsonPrimitive.content shouldBe "acme"
                (body["turns"] as JsonArray).size shouldBe 0
            }
        }

        "a session is visible to its owner and 404 to everyone else" {
            testApplication {
                application { testModule(InMemorySessionStore()) }
                val created = client.post("/v1/session") { header(HttpHeaders.Authorization, bearer("u1")) }
                val id =
                    json
                        .parseToJsonElement(created.bodyAsText())
                        .jsonObject["sessionId"]!!
                        .jsonPrimitive.content

                client
                    .get("/v1/session/$id") { header(HttpHeaders.Authorization, bearer("u1")) }
                    .status shouldBe HttpStatusCode.OK
                client
                    .get("/v1/session/$id") { header(HttpHeaders.Authorization, bearer("intruder")) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        "GET /v1/sessions lists only the caller's sessions" {
            testApplication {
                application { testModule(InMemorySessionStore()) }
                client.post("/v1/session") { header(HttpHeaders.Authorization, bearer("u1")) }
                client.post("/v1/session") { header(HttpHeaders.Authorization, bearer("u1")) }
                client.post("/v1/session") { header(HttpHeaders.Authorization, bearer("u2")) }

                val list = client.get("/v1/sessions") { header(HttpHeaders.Authorization, bearer("u1")) }
                list.status shouldBe HttpStatusCode.OK
                (json.parseToJsonElement(list.bodyAsText()) as JsonArray).size shouldBe 2
            }
        }

        "a malformed session id is a 400" {
            testApplication {
                application { testModule(InMemorySessionStore()) }
                client
                    .get("/v1/session/not-a-uuid") { header(HttpHeaders.Authorization, bearer("u1")) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        "POST /v1/session/{id}/reset clears the session" {
            testApplication {
                application { testModule(InMemorySessionStore()) }
                val created = client.post("/v1/session") { header(HttpHeaders.Authorization, bearer("u1")) }
                val id =
                    json
                        .parseToJsonElement(created.bodyAsText())
                        .jsonObject["sessionId"]!!
                        .jsonPrimitive.content
                client
                    .post("/v1/session/$id/reset") { header(HttpHeaders.Authorization, bearer("u1")) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        "POST /v1/session/{id}/undo restores the latest snapshot; 409 when nothing to undo" {
            testApplication {
                val store = InMemorySessionStore()
                application { testModule(store) }
                val created = client.post("/v1/session") { header(HttpHeaders.Authorization, bearer("u1")) }
                val id =
                    json
                        .parseToJsonElement(created.bodyAsText())
                        .jsonObject["sessionId"]!!
                        .jsonPrimitive.content
                val sid = java.util.UUID.fromString(id)
                store.appendTurn(
                    org.tatrman.kantheon.iris.domain.NewTurn(
                        sessionId = sid,
                        agentId = "golem-v2",
                        question = "a",
                        status = org.tatrman.kantheon.iris.domain.TurnStatus.DONE,
                    ),
                )

                // Nothing snapshotted yet → 409.
                client
                    .post("/v1/session/$id/undo") { header(HttpHeaders.Authorization, bearer("u1")) }
                    .status shouldBe HttpStatusCode.Conflict

                client.post("/v1/session/$id/reset") { header(HttpHeaders.Authorization, bearer("u1")) }
                val undo = client.post("/v1/session/$id/undo") { header(HttpHeaders.Authorization, bearer("u1")) }
                undo.status shouldBe HttpStatusCode.OK
                (json.parseToJsonElement(undo.bodyAsText()).jsonObject["turns"] as JsonArray).size shouldBe 1
            }
        }

        "POST /v1/session/{id}/undo is 404 for a non-owner" {
            testApplication {
                application { testModule(InMemorySessionStore()) }
                val created = client.post("/v1/session") { header(HttpHeaders.Authorization, bearer("u1")) }
                val id =
                    json
                        .parseToJsonElement(created.bodyAsText())
                        .jsonObject["sessionId"]!!
                        .jsonPrimitive.content
                client
                    .post("/v1/session/$id/undo") { header(HttpHeaders.Authorization, bearer("intruder")) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        // --- Stage 2.2 BFF-grow: discovery mirrored from golem + /v1/refresh ---

        "POST /v1/session mirrors golem discovery (chips / example questions / version)" {
            testApplication {
                application { testModuleWithGolem(InMemorySessionStore(), FakeGolemV2Client()) }
                val res = client.post("/v1/session") { header(HttpHeaders.Authorization, bearer("u1", "acme")) }
                res.status shouldBe HttpStatusCode.Created
                val body = json.parseToJsonElement(res.bodyAsText()).jsonObject
                body["agentVersion"]!!.jsonPrimitive.content shouldBe "golem-v2@fake"
                val chips = body["staticChips"] as JsonArray
                chips.size shouldBe 1
                chips[0].jsonObject["display"]!!.jsonPrimitive.content shouldBe "Zobraz faktury"
                (body["exampleQuestions"] as JsonArray).size shouldBe 1
                (body["packages"] as JsonArray).size shouldBe 2
            }
        }

        "POST /v1/session still succeeds (thin) when no golem backend is wired" {
            testApplication {
                application { testModule(InMemorySessionStore()) }
                val res = client.post("/v1/session") { header(HttpHeaders.Authorization, bearer("u1")) }
                res.status shouldBe HttpStatusCode.Created
                val body = json.parseToJsonElement(res.bodyAsText()).jsonObject
                (body["staticChips"] as JsonArray).size shouldBe 0
                body["agentVersion"]!!.jsonPrimitive.content shouldBe ""
            }
        }

        "POST /v1/refresh proxies golem refresh when a backend is wired" {
            testApplication {
                application { testModuleWithGolem(InMemorySessionStore(), FakeGolemV2Client()) }
                val res = client.post("/v1/refresh?service=erp") { header(HttpHeaders.Authorization, bearer("u1")) }
                res.status shouldBe HttpStatusCode.OK
                val results = json.parseToJsonElement(res.bodyAsText()).jsonObject["results"] as JsonArray
                results[0].jsonObject["status"]!!.jsonPrimitive.content shouldBe "ok"
                results[0].jsonObject["service"]!!.jsonPrimitive.content shouldBe "erp"
            }
        }

        "POST /v1/refresh is 503 when no backend is wired" {
            testApplication {
                application { testModule(InMemorySessionStore()) }
                client
                    .post("/v1/refresh") { header(HttpHeaders.Authorization, bearer("u1")) }
                    .status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }
    })
