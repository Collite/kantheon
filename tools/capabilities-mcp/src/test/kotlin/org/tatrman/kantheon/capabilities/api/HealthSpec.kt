package org.tatrman.kantheon.capabilities.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import org.tatrman.kantheon.capabilities.ReadinessGate

private fun testModule(gate: ReadinessGate): io.ktor.server.application.Application.() -> Unit =
    {
        install(ContentNegotiation) { json() }
        routing { healthRoutes(gate) }
    }

class HealthSpec :
    StringSpec({

        "GET /health returns 200 ok" {
            val gate = ReadinessGate()
            testApplication {
                application(testModule(gate))
                val resp = client.get("/health")
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain "\"status\":\"ok\""
            }
        }

        "GET /ready returns 503 when not ready" {
            val gate = ReadinessGate()
            testApplication {
                application(testModule(gate))
                val resp = client.get("/ready")
                resp.status shouldBe HttpStatusCode.ServiceUnavailable
                resp.bodyAsText() shouldContain "\"status\":\"not-ready\""
            }
        }

        "GET /ready returns 200 once readiness toggled" {
            val gate = ReadinessGate().apply { ready = true }
            testApplication {
                application(testModule(gate))
                val resp = client.get("/ready")
                resp.status shouldBe HttpStatusCode.OK
                resp.bodyAsText() shouldContain "\"status\":\"ready\""
            }
        }
    })
