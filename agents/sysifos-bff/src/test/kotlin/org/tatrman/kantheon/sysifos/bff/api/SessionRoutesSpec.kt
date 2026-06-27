package org.tatrman.kantheon.sysifos.bff.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.sysifos.bff.bearer
import org.tatrman.kantheon.sysifos.bff.module
import org.tatrman.kantheon.sysifos.bff.testDeps

class SessionRoutesSpec :
    StringSpec({

        "GET /sessions/current without a JWT → 401" {
            testApplication {
                application { module(testDeps()) }
                client.get("/sessions/current").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "GET /sessions/current with a valid JWT → SysifosSession carrying the claim tenant" {
            testApplication {
                application { module(testDeps()) }
                val res =
                    client.get("/sessions/current") {
                        header(HttpHeaders.Authorization, bearer("""{"sub":"u1","tenant":"acme"}"""))
                    }
                res.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
                body["userId"]?.jsonPrimitive?.content shouldBe "u1"
                body["tenantId"]?.jsonPrimitive?.content shouldBe "acme"
                body["sessionId"]?.jsonPrimitive?.content!!.isNotBlank() shouldBe true
            }
        }

        "POST /sessions → 201 with a fresh session_id" {
            testApplication {
                application { module(testDeps()) }
                val res =
                    client.post("/sessions") {
                        header(HttpHeaders.Authorization, bearer("""{"sub":"u1","tenant":"acme"}"""))
                    }
                res.status shouldBe HttpStatusCode.Created
                res.bodyAsText() shouldContain "session_id"
            }
        }
    })
