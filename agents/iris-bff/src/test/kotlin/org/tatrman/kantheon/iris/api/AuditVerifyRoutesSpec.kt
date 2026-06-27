package org.tatrman.kantheon.iris.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.iris.audit.AuditStore
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.installErrorPages
import shared.ktor.KtorServerConfig
import shared.ktor.installKtorServerBase
import java.time.Instant
import java.util.Base64

private fun bearer(
    sub: String,
    roles: List<String> = emptyList(),
): String {
    val rolesJson = roles.joinToString(",") { "\"$it\"" }
    val claims = """{"sub":"$sub","realm_access":{"roles":[$rolesJson]}}"""
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())
    return "Bearer h.$payload.s"
}

class AuditVerifyRoutesSpec :
    StringSpec({

        fun Application.mount(
            audit: AuditStore,
            signer: Ed25519Signer,
        ) {
            installKtorServerBase(KtorServerConfig(serviceName = "iris-bff-test", serverPort = 0))
            installErrorPages()
            routing { auditRoutes(audit, signer, BearerAuthenticator()) }
        }

        "verifies an intact segment for an admin" {
            testApplication {
                val signer = Ed25519Signer()
                val audit = InMemoryAuditStore(signer)
                val seg = audit.append("u1", "turn", """{"q":"a"}""", Instant.now()).segment
                audit.append("u1", "typed_action", """{"k":"sort"}""", Instant.now())
                application { mount(audit, signer) }

                val res =
                    client.get("/v1/audit/verify?segment=$seg") {
                        header(HttpHeaders.Authorization, bearer("admin", listOf("iris-admin")))
                    }
                res.status shouldBe HttpStatusCode.OK
                val body =
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(res.bodyAsText())
                body.jsonObject["ok"]!!.jsonPrimitive.boolean shouldBe true
                body.jsonObject["count"]!!.jsonPrimitive.int shouldBe 2
            }
        }

        "rejects a non-admin with 403" {
            testApplication {
                val signer = Ed25519Signer()
                val audit = InMemoryAuditStore(signer)
                application { mount(audit, signer) }
                client
                    .get("/v1/audit/verify?segment=2026-06") {
                        header(HttpHeaders.Authorization, bearer("u1"))
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        "requires the segment parameter" {
            testApplication {
                val signer = Ed25519Signer()
                val audit = InMemoryAuditStore(signer)
                application { mount(audit, signer) }
                client
                    .get("/v1/audit/verify") {
                        header(HttpHeaders.Authorization, bearer("admin", listOf("iris-admin")))
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }
    })
