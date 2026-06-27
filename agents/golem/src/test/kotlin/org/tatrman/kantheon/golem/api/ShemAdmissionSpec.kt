package org.tatrman.kantheon.golem.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.tatrman.kantheon.golem.shem.assembledShemContext
import java.util.Base64

private fun jwt(claimsJson: String): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claimsJson.toByteArray())
    return "header.$payload.sig"
}

private val shem = assembledShemContext() // visibility_roles: [kantheon-area-erp]
private val openShem = assembledShemContext(visibilityRoles = emptyList())

class ShemAdmissionSpec :
    StringSpec({

        "admits a caller holding a visibility role, extracting id + roles" {
            val token = jwt("""{"sub":"u1","realm_access":{"roles":["kantheon-area-erp","other"]}}""")
            val result = ShemAdmission(shem).admit("Bearer $token")
            result.shouldBeInstanceOf<AdmissionResult.Admitted>()
            result.caller.userId shouldBe "u1"
            result.caller.roles shouldBe setOf("kantheon-area-erp", "other")
        }

        "denies (403) a valid caller lacking any visibility role" {
            val token = jwt("""{"sub":"u1","realm_access":{"roles":["unrelated"]}}""")
            val result = ShemAdmission(shem).admit("Bearer $token")
            result.shouldBeInstanceOf<AdmissionResult.Denied>()
            result.status shouldBe HttpStatusCode.Forbidden
        }

        "an open Shem (empty visibility_roles) admits any authenticated caller" {
            val token = jwt("""{"sub":"u1","realm_access":{"roles":[]}}""")
            ShemAdmission(openShem).admit("Bearer $token").shouldBeInstanceOf<AdmissionResult.Admitted>()
        }

        "denies (401) a missing or malformed bearer" {
            val a = ShemAdmission(shem)
            a.admit(null).shouldBeInstanceOf<AdmissionResult.Denied>().status shouldBe HttpStatusCode.Unauthorized
            a.admit("Basic abc").shouldBeInstanceOf<AdmissionResult.Denied>().status shouldBe
                HttpStatusCode.Unauthorized
            a.admit("Bearer not-a-jwt").shouldBeInstanceOf<AdmissionResult.Denied>().status shouldBe
                HttpStatusCode.Unauthorized
        }

        "denies (401) an expired token (fail-closed on exp)" {
            val token = jwt("""{"sub":"u1","exp":1000,"realm_access":{"roles":["kantheon-area-erp"]}}""")
            ShemAdmission(shem).admit("Bearer $token").shouldBeInstanceOf<AdmissionResult.Denied>().status shouldBe
                HttpStatusCode.Unauthorized
        }

        "the route plugin enforces admission on /v1 (deny carries a Rule-6 message)" {
            testApplication {
                application {
                    routing {
                        route("/v1") {
                            install(ShemAdmissionPlugin) { admission = ShemAdmission(shem) }
                            get("/ping") { call.respondText("pong") }
                        }
                    }
                }
                val ok = jwt("""{"sub":"u1","realm_access":{"roles":["kantheon-area-erp"]}}""")
                val nope = jwt("""{"sub":"u1","realm_access":{"roles":["unrelated"]}}""")

                client.get("/v1/ping") { header(HttpHeaders.Authorization, "Bearer $ok") }.also {
                    it.status shouldBe HttpStatusCode.OK
                    it.bodyAsText() shouldBe "pong"
                }
                client.get("/v1/ping") { header(HttpHeaders.Authorization, "Bearer $nope") }.also {
                    it.status shouldBe HttpStatusCode.Forbidden
                    it.bodyAsText() shouldContain "humanMessage"
                }
                client.get("/v1/ping").status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
