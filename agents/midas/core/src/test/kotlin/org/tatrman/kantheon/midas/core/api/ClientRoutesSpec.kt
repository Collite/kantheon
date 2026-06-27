package org.tatrman.kantheon.midas.core.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import org.tatrman.kantheon.midas.core.auth.BearerAuthenticator
import org.tatrman.kantheon.midas.core.repository.ClientRepository
import org.tatrman.kantheon.midas.v1.Client
import java.util.Base64
import java.util.UUID

/**
 * Stage 1.3 T6 — Clients route behaviour at the unit level (mocked repository).
 * Asserts the HTTP/auth/tenant contract (contracts §2.1 + §12): 401 without a
 * bearer, 403 on tenant mismatch, 400 on a missing tenant header, 201/200/404 on
 * the happy/missing paths. Real DB + RLS is proven by `RlsLeakageComponentSpec`
 * and the deploy smoke.
 */
class ClientRoutesSpec :
    StringSpec({

        val tenant = "11111111-1111-1111-1111-111111111111"

        fun bearer(
            sub: String = "u1",
            tenantClaim: String = tenant,
        ): String {
            val payload =
                Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("""{"sub":"$sub","tenant":"$tenantClaim","exp":9999999999}""".toByteArray())
            return "Bearer h.$payload.s"
        }

        fun Application.mount(repo: ClientRepository) {
            installMidasErrorPages()
            routing { route("/api/v1") { clientRoutes(repo, BearerAuthenticator()) } }
        }

        "401 when no bearer is present" {
            testApplication {
                application { mount(mockk()) }
                client.get("/api/v1/clients") { }.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "403 when X-Tenant-Id does not match the token tenant claim" {
            testApplication {
                application { mount(mockk()) }
                val res =
                    client.get("/api/v1/clients") {
                        header("Authorization", bearer())
                        header("X-Tenant-Id", "22222222-2222-2222-2222-222222222222")
                    }
                res.status shouldBe HttpStatusCode.Forbidden
                res.bodyAsText() shouldContain "TENANT_HEADER_JWT_MISMATCH"
            }
        }

        "400 when X-Tenant-Id header is missing" {
            testApplication {
                application { mount(mockk()) }
                val res = client.get("/api/v1/clients") { header("Authorization", bearer()) }
                res.status shouldBe HttpStatusCode.BadRequest
                res.bodyAsText() shouldContain "TENANT_HEADER_MISSING"
            }
        }

        "201 on create, echoing the persisted client" {
            val repo = mockk<ClientRepository>()
            val saved =
                Client
                    .newBuilder()
                    .setClientId(UUID.randomUUID().toString())
                    .setTenantId(tenant)
                    .setName("Acme")
                    .build()
            every { repo.create(eq(tenant), any(), any()) } returns saved

            testApplication {
                application { mount(repo) }
                val res =
                    client.post("/api/v1/clients") {
                        header("Authorization", bearer())
                        header("X-Tenant-Id", tenant)
                        setBody("""{"client":{"name":"Acme"}}""")
                    }
                res.status shouldBe HttpStatusCode.Created
                res.bodyAsText() shouldContain "Acme"
            }
        }

        "404 when getting a client that does not exist in tenant scope" {
            val repo = mockk<ClientRepository>()
            every { repo.get(eq(tenant), any()) } returns null

            testApplication {
                application { mount(repo) }
                val res =
                    client.get("/api/v1/clients/${UUID.randomUUID()}") {
                        header("Authorization", bearer())
                        header("X-Tenant-Id", tenant)
                    }
                res.status shouldBe HttpStatusCode.NotFound
                res.bodyAsText() shouldContain "CLIENT_NOT_FOUND"
            }
        }
    })
