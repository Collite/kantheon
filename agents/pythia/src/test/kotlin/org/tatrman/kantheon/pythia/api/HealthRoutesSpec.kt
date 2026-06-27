package org.tatrman.kantheon.pythia.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

/**
 * Stage 1.2 T1 — `/health` 200 + `/ready` gate smoke (testApplication). Boots only
 * the health routes (the full module bootstrap is exercised at deploy time).
 */
class HealthRoutesSpec :
    StringSpec({

        "/health returns UP" {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { healthRoutes(Readiness { true }) }
                }
                val resp = client.get("/health")
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain "UP"
            }
        }

        "/ready returns 503 until ready, 200 once ready" {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { healthRoutes(Readiness { false }) }
                }
                client.get("/ready").status shouldBe HttpStatusCode.ServiceUnavailable
            }
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { healthRoutes(Readiness { true }) }
                }
                client.get("/ready").status shouldBe HttpStatusCode.OK
            }
        }
    })
