package org.tatrman.kantheon.bffbase.health

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class HealthRoutesSpec :
    StringSpec({

        "/health is always UP; /ready reflects the readiness gate" {
            testApplication {
                application {
                    routing { healthRoutes(Readiness { true }) }
                }
                client.get("/health").status shouldBe HttpStatusCode.OK
                val ready = client.get("/ready")
                ready.status shouldBe HttpStatusCode.OK
                ready.bodyAsText() shouldContain "UP"
            }
        }

        "/ready returns 503 NOT_READY when the gate is closed" {
            testApplication {
                application {
                    routing { healthRoutes(Readiness { false }) }
                }
                val ready = client.get("/ready")
                ready.status shouldBe HttpStatusCode.ServiceUnavailable
                ready.bodyAsText() shouldContain "NOT_READY"
            }
        }
    })
